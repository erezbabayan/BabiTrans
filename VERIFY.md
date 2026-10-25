# VERIFY — 2026-10-25 (box, Linux, 8 vCPU, no GPU)

## Install
```
python3 -m venv .venv && . .venv/bin/activate
pip install -r server/requirements.txt        # also re-verified in a clean venv: OK
```
Note: PyAV 16+ breaks faster-whisper 1.2.1 (`open() got an unexpected keyword argument 'metadata_errors'`) → pinned `av>=14,<16`.

## Model
`ivrit-ai/whisper-large-v3-turbo-ct2` downloaded fully from Hugging Face (~1.6 GB), loaded on `cpu/int8` in ~15–40 s. No fallback needed.

## Health
```
$ curl -s localhost:8000/health
{"ok":true,"model_loaded":true,"model":"ivrit-ai/whisper-large-v3-turbo-ct2","device":"cpu/int8","error":null,"max_sec":300.0,"token_required":false}
```

## Test audio
Hebrew TTS (gTTS, test-only, not part of the app) → `test/he_test.mp3`, converted to WhatsApp-like Opus: `ffmpeg -i he_test.mp3 -c:a libopus -b:a 24k he_test.ogg`.
Source sentence: "שלום, זוהי בדיקה של מערכת התמלול. היום יום ראשון ומזג האוויר נעים מאוד."

## Transcription
```
$ curl -s -F audio=@test/he_test.ogg localhost:8000/transcribe      # 4.3 s wall for 7.6 s audio
{"text":"שלום, זוהי בדיקה של מערכת התמלול. היום יום ראשון ומזג האוויר נעים מאוד.","duration_sec":7.61,"language":"he"}
$ curl -s -F audio=@test/he_test.mp3 localhost:8000/transcribe      # 3.8 s
(same text — exact match)
```
Upload dir `/tmp/matamel` empty afterwards → audio deleted ✓

## Limits & auth
```
$ curl -F audio=@test/long.ogg (310 s)   → 413 {"detail":"audio too long (310s > 300s) - MVP limit"}
MATAMEL_TOKEN=s3cret: no header → 401 {"detail":"bad token"}; with X-Matamel-Token → 200 transcript
```

## PWA
`/`, `/manifest.webmanifest`, `/sw.js` → 200 from the same process. Manifest JSON valid; sw.js/app.js parse OK.
Not tested on a real phone (box can't; requires HTTPS + install — see README).
