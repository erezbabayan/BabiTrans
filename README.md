# מתמלל (matamel)

תמלול הודעות קוליות מוואטסאפ לעברית — מקומי, חינמי, בלי שירותי ענן בתשלום.
Local Hebrew voice-note transcription: WhatsApp → Share → מתמלל (PWA) → transcript in the same app.
Engine: faster-whisper + `ivrit-ai/whisper-large-v3-turbo-ct2` (CPU int8, ~1.6 GB download, ~3 GB RAM).

## הרצה על המחשב בבית / Run on your PC (Windows / Mac / Linux)
Requirements: Python 3.10+, ffmpeg (`winget install ffmpeg` / `brew install ffmpeg` / `apt install ffmpeg`).
```bash
cd matamel
python -m venv .venv
# Windows: .venv\Scripts\activate    Mac/Linux: source .venv/bin/activate
pip install -r server/requirements.txt
cd server && python app.py          # http://0.0.0.0:8000  (first start downloads the model)
```
Check: `curl http://localhost:8000/health` → `"model_loaded": true`. UI: http://localhost:8000/

### Env vars
| var | default | |
|---|---|---|
| MATAMEL_MODEL | ivrit-ai/whisper-large-v3-turbo-ct2 | fallback: MATAMEL_FALLBACK_MODEL=small |
| MATAMEL_DEVICE / MATAMEL_COMPUTE | cpu / int8 | NVIDIA: `cuda` / `float16` |
| MATAMEL_TOKEN | (empty) | if set, requests need header `X-Matamel-Token` |
| MATAMEL_MAX_SEC | 300 | MVP limit 5 min (413 above) |
| MATAMEL_DELETE_AUDIO | 1 | delete upload after transcription |
| MATAMEL_PORT / MATAMEL_HOST | 8000 / 0.0.0.0 | |
| MATAMEL_SSL_CERT / MATAMEL_SSL_KEY | — | enable HTTPS directly in uvicorn |

## מחשב + אנדרואיד / Desktop & Android — same app
- **מחשב (Desktop browser):** open http://localhost:8000 (or https://… if SSL is set) in Chrome/Edge/Firefox → **drag & drop** an audio file (ogg/opus/m4a/mp3/wav) onto the "מחשב" box, or click it to pick a file. Wide screens show both paths side by side.
- **אנדרואיד:** install the PWA over HTTPS (below), then WhatsApp → Share → **מתמלל**.

## גלקסי S23 באותה רשת Wi‑Fi / Galaxy S23 on the same Wi‑Fi
**חשוב:** Android מתקין PWA ומפעיל Share Target **רק ב-HTTPS** (או localhost). ב-HTTP רגיל דרך IP של הרשת (`http://192.168.x.x:8000`) אפשר לבחור קובץ ידנית, אבל **"מתמלל" לא יופיע בתפריט השיתוף**.
Android requires a secure origin to install the PWA and register the share target. Plain HTTP over LAN works only for the manual file picker.

Options (all free):
1. **mkcert (LAN, recommended)**
   ```bash
   mkcert -install
   mkcert 192.168.1.50 matamel.local localhost   # your PC's LAN IP
   MATAMEL_SSL_CERT=192.168.1.50+2.pem MATAMEL_SSL_KEY=192.168.1.50+2-key.pem python app.py
   ```
   Then install the CA on the phone: copy `$(mkcert -CAROOT)/rootCA.pem` to the S23 → Settings → Security → More security settings → Install from device storage → **CA certificate**. Open `https://192.168.1.50:8000` in Chrome.
   Windows firewall: allow inbound TCP 8000 (private network).
2. **Tailscale (free personal)**: `tailscale serve --bg 8000` gives a real HTTPS `https://<pc>.<tailnet>.ts.net` cert, works also off Wi‑Fi. Phone needs Tailscale app.
3. **Cloudflare quick tunnel** (`cloudflared tunnel --url http://localhost:8000`) — free, but exposes the server publicly; set `MATAMEL_TOKEN`.

### Install & use on the phone
Chrome → open the HTTPS URL → ⋮ → **Add to Home screen / Install app**. Only after install does "מתמלל" appear in Android's share sheet.
WhatsApp → long-press voice note → Share → **מתמלל** → transcript appears → העתק.
If the UI is hosted elsewhere, set the server URL + token in ⚙️ הגדרות.


## שימוש מכל מקום / Use from anywhere
המנוע רץ על המחשב בבית (חינמי, מקומי). כדי לפתוח מהטלפון גם מחוץ ל־Wi‑Fi הביתי:
1. **Tailscale (מומלץ, חינמי לשימוש אישי):** התקן בטלפון ובמחשב → `tailscale serve --bg 8000` → פתח את כתובת ה־HTTPS של ה־tailnet ב־Chrome בטלפון → התקן PWA.
2. **Cloudflare quick tunnel:** `cloudflared tunnel --url http://localhost:8000` — חינמי אבל חושף את השרת לאינטרנט; הגדר `MATAMEL_TOKEN`.

GitHub שומר את הקוד בלבד; התמלול עצמו נשאר על המחשב שלך.

## Architecture
- `server/app.py` — FastAPI: `GET /health`, `POST /transcribe` (multipart `audio`), serves `pwa/` statically on the same origin. One job at a time (CPU lock); long-job queue can be added later in place of the sync path.
- `pwa/sw.js` — intercepts `POST /share-target`, stores the file in Cache `matamel-share`, 303 → `/?share=1`; `app.js` picks it up, POSTs to `/transcribe`, shows result; "סיום" clears cached audio.
