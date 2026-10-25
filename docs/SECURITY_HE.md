# אבטחה, וירוסים ותחזוקה – BabiTrans

מיושר עם מדריכי BabiSnap ו‑BabiTk (`docs/SECURITY_RUNBOOK.md` שם). מעודכן 2026‑10‑25. בלי שירותים בתשלום.

## עקרונות
- לא לשבור: שיתוף מוואטסאפ, תמלול במכשיר, הורדת מודל, עדכון מעל גרסה קיימת.
- שינוי אחד בכל פעם; build + בדיקה לפני Release; הגרסה הקודמת נשארת ב‑Releases כ‑rollback.
- לעולם לא להדביק סיסמאות/טוקנים בצ׳אט, בלוגים או בקוד.
- לשאול את Erez לפני: שחרור APK, שינוי keystore, סיבוב טוקן שרת, branch protection, טוקן GitHub עם scope `workflow`.

## אין אנטי‑וירוס באפליקציה
ההגנה היא hardening + סריקות + Play Protect אצל המשתמש:
- `usesCleartextTraffic=false` + `res/xml/network_security_config.xml`: HTTPS בלבד, CA של המערכת בלבד (לא CA שהמשתמש התקין). חריג יחיד: השרת הביתי `10.0.0.8` / localhost (matamel ב‑LAN). האפליקציה דוחה בהגדרות כתובת http אחרת.
- `allowBackup=false` + `data_extraction_rules.xml`: הטוקן וההגדרות לא יוצאים בגיבוי ענן / העברת מכשיר.
- הרשאות מינימליות: `INTERNET`, `POST_NOTIFICATIONS` בלבד.
- מודל התמלול מאומת ב‑**SHA‑256** אחרי הורדה (קובץ פגום נמחק). ערכים ב‑`MainActivity.FAST_SHA/HQ_SHA`:
  - q4_0: `e049b13550e5d67d44de3e30354710eaebd67569699d6b91ffe3103dbdc77975`
  - q5_0: `6c1da92e8e41dd64b8cc402eee7eb7a433d2152567e1a4d9cf181fefcc67a572`
- R8 (minify + shrinkResources) ב‑release, עם keep ל‑JNI (`proguard-rules.pro`).
- אין SDK צד ג׳, אין אנליטיקס, אין `Log` של תוכן/טוקן.
- CI: `gitleaks` בכל push/PR (Actions מוצמדים ל‑SHA, `permissions: contents: read`) + Dependabot חודשי ל‑Gradle ול‑Actions.

## הורדה בטוחה (APK מחוץ ל‑Play)
- מקור יחיד: <https://github.com/erezbabayan/BabiTrans/releases/latest/download/BabiTrans.apk>. לא מקבוצות / קישורים מועברים.
- כל הגרסאות חתומות באותו מפתח; Android לא יתקין עדכון בחתימה אחרת — זו הגנה, לא באג. אל תסירו גרסה כדי "לעקוף" שגיאת חתימה; דווחו.
- **Play Protect** עשוי להציג "אפליקציה לא מוכרת" — צפוי. אל תכבו את Play Protect. אם הוא אומר "מזיק" — לא להתקין ולדווח.
- אחרי התקנה אפשר לכבות שוב "התקנה ממקורות לא ידועים" לדפדפן.
- אימות אופציונלי: `apksigner verify --print-certs BabiTrans.apk` — SHA‑256 של תעודת החתימה: `e2a80123730764ab3541ee5204d2bbc074e4e8ed51521c96327300b7c10baa3e`.

## עדכונים
אין בדיקת עדכונים אוטומטית (אין קריאה לשרת ברקע — בחירת פרטיות). לעדכן: לפתוח את קישור releases/latest ולהתקין מעל. הגדרות ומודל נשמרים. versionCode עולה בכל Release.

## איפה הסודות
| סוד | איפה |
|-----|------|
| Keystore חתימה (`babitrans-release.jks` + `keystore.properties`) | מחוץ ל‑git: `/workspace/secrets/babitrans-android/` + גיבוי מוצפן אופליין. אובדן = אי אפשר לעדכן מעל גרסאות קיימות |
| טוקן שרת matamel (`X-Matamel-Token`) | SharedPreferences פרטי במכשיר (ללא גיבוי); בשרת — משתנה סביבה, לא בריפו |
| GitHub tokens | GitHub Secrets / Vault — לא בריפו |

## אם APK, מפתח או טוקן נחשפו
1. לעצור הפצה של הקישור הישן; לעדכן את Erez מיד.
2. טוקן שרת: להחליף בשרת ואז בהגדרות האפליקציה (חדש עובד → רק אז לבטל ישן).
3. Keystore: באישור Erez בלבד — מפתח חדש מחייב הסרה והתקנה מחדש אצל המשתמשים; לפרסם הסבר ב‑Release.
4. סוד שנכנס ל‑git נחשב שרוף גם אחרי מחיקה — לסובב קודם.

## תחזוקה
- **מודלים:** מתארחים כ‑Release assets (`model-ivrit-turbo-q4`, `model-ivrit-turbo-q5`). אסור להחליף קובץ באותו tag בלי לעדכן SIZE + SHA באפליקציה (אחרת ההורדה תיכשל באימות).
- **whisper.cpp:** מוצמד ב‑`CMakeLists.txt`; עדכון = build + בדיקת תמלול קצר וארוך.
- **Release:** bump `versionCode/versionName` → `./gradlew assembleRelease` (חתום) → Release עם `BabiTrans.apk` → בדיקת קישור latest.
- **שבועית:** שבת 22:00 שעון ישראל (כמו BabiSnap): התראות Dependabot/gitleaks, תקינות קישור latest והמודלים.

## דיווח
erezbabayan@gmail.com · נושא `BabiTrans – אבטחה`. לא לפתוח Issue ציבורי עם פרטי חולשה.
