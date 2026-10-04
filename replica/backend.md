# Backend: Lumen

Lumen has no server by design (see architecture.md). Its whole backend is an on-device SQLite database.

## Database
- [x] Schema checked in: `src/main/assets/schema.sql`. The app runs this exact file on first launch (`Db.onCreate`).
- [x] Migrations: none yet. Schema v2 → bump the `Db` version and add `assets/migrate_2.sql`.
- [x] Constraints: rating 0–5, flag −1/0/1, label enum, foreign keys on with cascade deletes.
- [x] Edits autosave per photo (300 ms debounce). They survive the app being killed, which was tested on the emulator.
- [x] Self-test: `adb shell am start -n dev.lumen/.MainActivity --ez selftest true`, then `adb logcat -s Lumen` shows `PASS db=true`.
- [x] Backups: Android Auto Backup is on by default (`allowBackup`), so the DB goes to the user's own Google Drive backup.
- [n/a] Seed data: single user, real photos.

## Not applicable (no server, single user, offline)
Auth, sessions, roles, account deletion, Stripe, webhooks, email, jobs, integrations, OAuth review, rate limits.

## Security checklist
- [x] no secrets: there are none
- [x] input validated: SQL uses bound parameters only. Edit JSON is parsed with `optDouble` defaults, so bad keys are ignored.
- [n/a] authorisation, second-user test: single user, app-private DB
- [n/a] rate limits, webhooks, uploads
- [x] no user data in URLs. The only log line is a failed-open warning with the content URI. Drop it if logs ever leave the device.
- [x] dependencies audited: zero third-party dependencies
- [n/a] privacy policy processors: nothing leaves the device
