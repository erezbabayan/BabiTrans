# STT trials for v0.3.8 (box x86, 8 cores, whisper.cpp 231638a, threads=6, lang=he, app params)

Audio: real Hebrew speech `test/he_test.mp3` (7.6 s); long = same clip looped 12x with 0.4 s gaps = 96.1 s.
Short path uses audio_ctx + single_segment (as in jni.cpp). Times = whisper_full only (model load 0.2-0.6 s extra).

| Clip | Model | Trial 1 | Trial 2 | Trial 3 | RTF |
|---|---|---|---|---|---|
| 7.6 s | q4_0 (fast) | 0.85 s | 0.88 s | 0.93 s | ~0.12 |
| 7.6 s | q5_0 (HQ) | 3.34 s | 3.19 s | 3.28 s | ~0.43 |
| 96.1 s | q4_0 (fast) | 14.06 s | 13.32 s | 13.20 s | ~0.14 |
| 96.1 s | q5_0 (HQ) | 43.14 s | 44.80 s | 43.09 s | ~0.45 |

Transcript (all runs): "שלום, זוהי בדיקה של מערכת התמלול. היום יום ראשון ומזג האוויר נעים מאוד."
Concurrency (2 jobs, STT serialized): job1 forced to abort at 4 s -> lock released at 4.0 s, job2 waited 3.8 s with "ממתין למנוע" then ran to completion.
