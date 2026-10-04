# Bugs: Lumen (Android + Mac)

From the 2026-10-04 test pass: hands-on hostile-file testing on both apps, a security review and a database cross-check.
Every fixed bug has a self-test check that fails without the fix (Android 35 checks, Mac 38 checks, both PASS).
Severity: S1 data loss / security hole, S2 broken without workaround, S3 broken with workaround, S4 cosmetic.

## Fixed

| id | sev | app | bug | fix |
| --- | --- | --- | --- | --- |
| B01 | S2 | Android | An .xmp with ~300k curve points made a preset over 2 MB; every editor open then crashed reading it (CursorWindow limit) and only Clear data recovered | curves capped at 64 points, preset JSON capped at 64 KB, every edit validated on load |
| B02 | S2 | both | Two photos with the same name and size share one record; the Mac also hid the second file | Mac lists by file path; key collision documented (see Open) |
| B03 | S2 | both | A photo whose size changed (metadata rewritten by another app) lost its edits | record follows the file path to the new key |
| B04 | S2 | Mac | Database write errors ignored: a failed save was treated as saved and lost on quit; a rejected rating still showed | errors reported; screen only updates when the write succeeds |
| B05 | S2 | Mac | Schema created without a transaction or version: a half-made database was never repaired; a missing schema crashed later | one transaction, `user_version` set, clear failure message |
| B06 | S3 | Android | Malformed .xmp number (`<rdf:li>., 5`) crashed the editor | numbers parsed safely, import wrapped |
| B07 | S3 | Mac | .xmp number with 309+ digits (= infinity) crashed on save | non-finite values dropped |
| B08 | S3 | Mac | Bad .cube (`LUT_3D_SIZE` alone, short `DOMAIN_MIN`) crashed | inputs checked, readable error |
| B09 | S3 | both | Crafted .xmp made the importer quadratic: app froze ("not responding") | linear-time parsing, one pass for all settings, import off the main thread, 8 MB read cap on Mac |
| B10 | S3 | Android | Another app could make Lumen open its own private files (`file://`, own provider), which Export would then copy to Pictures | only `content://` from other apps' providers |
| B11 | S3 | both | "Keep location" was remembered, so later batch exports silently included GPS | location is never remembered |
| B12 | S3 | Android | Two quick batch actions could overwrite each other's results | one batch at a time, each in a transaction |
| B13 | S3 | Mac | Batch actions on many photos froze the window | one transaction |
| B14 | S3 | both | Crafted edit JSON (negative rotation, inverted crop, odd stroke points, huge spot radius, `../` profile name, strings for numbers) crashed or misbehaved | `merge` validates everything (architecture.md, Validation) |
| B15 | S3 | both | Long brush sessions grew the edit without limit | points kept only on real movement, 20 000 per mask |
| B16 | S2 | Mac | Deleting a mask crashed (found earlier in the session) | guarded bindings |
| B17 | S4 | Android | Failed export left a broken file in Pictures/Lumen | deleted on failure (Mac too) |
| B18 | S4 | Android | Self-test / design switches reachable by other apps | debug builds only |
| B19 | S4 | both | Imports could hide or replace an existing preset / profile with the same name | "Name (2)" |
| B20 | S4 | both | Thumbnail cache keys could collide (32-bit hash, NSString.hash) | SHA-256 of file + edit; rating no longer re-renders |
| B21 | S4 | Android | Logs contained photo URIs | removed |
| B22 | S4 | Android | Broken thumbnails re-decoded on every scroll | failures remembered |
| B23 | S4 | Mac | No hardened runtime | `codesign --options runtime` |
| B24 | S4 | Mac | Full-size export of a huge photo could crash on a failed allocation | falls back to 4096 px like Android |
| B25 | S4 | both | Width/height stuck at 1 after a batch; version order unstable | upsert keeps real sizes; `order by created_at, id` |
| B26 | S4 | Mac | SQLite handle not serialized | `SQLITE_OPEN_FULLMUTEX` |
| B27 | S4 | Android | Two database connections in one process | one shared `Db.get()` |

## Open (accepted)

- Same name + same byte size = same record (B02). Realistic only for identically sized files with a reused name. A stronger key needs capture date or a content hash on every scan.
- `photos_taken` index unused; rating CHECK allows 2.5 (not reachable). Changing them needs schema v2 with a migration, so they wait for the next schema change.
- Android backup is on (default): the database goes to the user's own Google backup. Kept so edits survive a new phone.
- Truncated JPEG: Android refuses it, the Mac shows the part that's there.
- Mac app is unsandboxed (ad-hoc signed for personal use). Sandboxing needs security-scoped bookmarks for folders.

## Checked and safe

All SQL uses bound parameters (57 statements run against the schema, all as expected); edit JSON round-trips identically
Android ↔ Mac; no network permission or code; kernel and shader sources are constants; export filenames can't leave the folder;
decompression-bomb PNG (30000×30000), empty, random, text-as-image and odd-name files are all refused or opened safely on both.
