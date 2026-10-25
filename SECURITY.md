# Security policy (BabiTrans)

Report vulnerabilities privately to **erezbabayan@gmail.com** (subject `BabiTrans – security`). Do not open a public issue.

Supported: latest GitHub Release only. Full Hebrew runbook (hardening, sideload/Play Protect guidance, secrets, incident steps, maintenance): [docs/SECURITY_HE.md](docs/SECURITY_HE.md). Privacy: [docs/PRIVACY_HE.md](docs/PRIVACY_HE.md).

Summary: on-device STT by default; HTTPS-only network config (LAN home-server exception); no backup of app data; INTERNET + POST_NOTIFICATIONS only; SHA-256 verified model download; R8 release builds; gitleaks + Dependabot in CI; release keystore kept out of git.
