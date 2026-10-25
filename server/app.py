"""מתמלל - local Hebrew ASR server (faster-whisper + ivrit.ai). Hosts PWA + API."""
import os, tempfile, logging, subprocess, json, threading
from pathlib import Path
from fastapi import FastAPI, UploadFile, File, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

log = logging.getLogger("matamel")
MODEL = os.getenv("MATAMEL_MODEL", "ivrit-ai/whisper-large-v3-turbo-ct2")
FALLBACK = os.getenv("MATAMEL_FALLBACK_MODEL", "small")
DEVICE = os.getenv("MATAMEL_DEVICE", "cpu")
COMPUTE = os.getenv("MATAMEL_COMPUTE", "int8")
TOKEN = os.getenv("MATAMEL_TOKEN", "")
MAX_SEC = float(os.getenv("MATAMEL_MAX_SEC", "300"))  # MVP: 5 min
DELETE_AUDIO = os.getenv("MATAMEL_DELETE_AUDIO", "1") == "1"
UPLOAD_DIR = Path(os.getenv("MATAMEL_UPLOAD_DIR", tempfile.gettempdir())) / "matamel"
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
PWA_DIR = Path(__file__).resolve().parent.parent / "pwa"

state = {"model": None, "name": None, "error": None}
lock = threading.Lock()

def load_model():
    from faster_whisper import WhisperModel
    for name in (MODEL, FALLBACK):
        try:
            state["model"] = WhisperModel(name, device=DEVICE, compute_type=COMPUTE,
                                          cpu_threads=int(os.getenv("MATAMEL_THREADS", "0")))
            state["name"] = name; state["error"] = None
            log.warning("model loaded: %s", name); return
        except Exception as e:
            state["error"] = f"{name}: {e}"; log.exception("load failed %s", name)

app = FastAPI(title="matamel")
app.add_middleware(CORSMiddleware, allow_origin_regex=".*", allow_credentials=False,
                   allow_methods=["*"], allow_headers=["*"])

@app.on_event("startup")
def _startup():
    threading.Thread(target=load_model, daemon=True).start()

def check_token(tok):
    if TOKEN and tok != TOKEN:
        raise HTTPException(401, "bad token")

def probe_duration(path):
    try:
        out = subprocess.run(["ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", str(path)],
                             capture_output=True, text=True, timeout=30).stdout
        return float(json.loads(out)["format"]["duration"])
    except Exception:
        return None

@app.get("/health")
def health():
    return {"ok": True, "model_loaded": state["model"] is not None, "model": state["name"],
            "device": f"{DEVICE}/{COMPUTE}", "error": state["error"], "max_sec": MAX_SEC,
            "token_required": bool(TOKEN)}

@app.post("/transcribe")
async def transcribe(audio: UploadFile = File(...), x_matamel_token: str = Header(default="")):
    check_token(x_matamel_token)
    if state["model"] is None:
        raise HTTPException(503, "model still loading")
    suffix = Path(audio.filename or "a.ogg").suffix or ".ogg"
    fd, tmp = tempfile.mkstemp(suffix=suffix, dir=UPLOAD_DIR); os.close(fd)
    tmp = Path(tmp)
    try:
        with open(tmp, "wb") as f:
            while chunk := await audio.read(1 << 20):
                f.write(chunk)
        dur = probe_duration(tmp)
        if dur is not None and dur > MAX_SEC:
            raise HTTPException(413, f"audio too long ({dur:.0f}s > {MAX_SEC:.0f}s) - MVP limit")
        with lock:  # one job at a time on CPU; long-job queue can be added later
            segs, info = state["model"].transcribe(str(tmp), language="he", beam_size=5, vad_filter=True)
            text = " ".join(s.text.strip() for s in segs).strip()
        return {"text": text, "duration_sec": round(info.duration, 2), "language": info.language}
    finally:
        if DELETE_AUDIO and tmp.exists():
            tmp.unlink()

if PWA_DIR.exists():
    app.mount("/", StaticFiles(directory=PWA_DIR, html=True), name="pwa")

if __name__ == "__main__":
    import uvicorn
    kw = {}
    if os.getenv("MATAMEL_SSL_CERT"):
        kw = {"ssl_certfile": os.getenv("MATAMEL_SSL_CERT"), "ssl_keyfile": os.getenv("MATAMEL_SSL_KEY")}
    uvicorn.run(app, host=os.getenv("MATAMEL_HOST", "0.0.0.0"), port=int(os.getenv("MATAMEL_PORT", "8000")), **kw)
