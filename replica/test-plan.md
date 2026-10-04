# Test plan: Lumen (Android + Mac)

Run 2026-10-04. Android: Pixel 8 emulator, API 37, debug + release builds. Mac: macOS 26, arm64, `mac/build.sh`.
Automated: Android self-test (27 checks, `--ez selftest true`), Mac self-test (29 checks, `--selftest`), Mac `--check <folder>`.
No network, accounts, payments, email or timezone-dependent features exist, so those edge cases do not apply.

## Flows

| flow | what |
| --- | --- |
| F01 | Library: grant access / add folder, see photos, filters, albums |
| F02 | Open a photo and edit it (sliders, curve, colour, effects, optics, Auto) |
| F03 | Crop, straighten, perspective, rotate, flip, Level |
| F04 | Masks: gradient, radial, brush, colour, luminance, invert, delete |
| F05 | Retouch: heal / clone spots, Find spots |
| F06 | Presets, profiles, .cube / .xmp import, copy / paste, versions |
| F07 | Rate, flag, label, batch actions |
| F08 | Export (formats, sizes, metadata, watermark, share) |
| F09 | Persistence: edits survive closing the app; undo / redo |

## Cases

| id | case | Android | Mac |
| --- | --- | --- | --- |
| F01-H1 | photos appear newest first | pass | pass |
| F01-E1 | no permission → explanation + Allow | pass | n/a (folders) |
| F01-E2 | empty library message | pass | pass |
| F01-E3 | portrait + landscape thumbnails in an even grid | pass | pass (after fix) |
| F01-N1 | broken files in the library (empty, random bytes, text-as-PNG, truncated, 30000×30000 bomb) | pass: blank cells, no crash | pass |
| F01-E4 | filename with emoji, quotes, `%2F..` | pass (no quotes: Android storage refuses them) | pass |
| F02-H1 | Auto, sliders, histogram follow edits | pass | pass |
| F02-N1 | open each broken file directly | pass: "Can't open", no crash | pass: "Can't open", no crash |
| F02-E1 | 6000×4000 photo | pass | pass |
| F02-N2 | another app sends `file://` to Lumen's own database | refused (not an image) | n/a |
| F03-H1 | 1:1 crop, rotate, straighten keeps frame inside photo | pass | pass |
| F04-H1 | add / invert / delete masks; delete the selected one | pass | pass (crash fixed, see bugs) |
| F05-H1 | heal spot with automatic source | pass | pass |
| F06-H1 | .cube and .xmp import | pass | self-test only |
| F07-H1 | rating / flag / label show in the library | pass | pass |
| F07-H2 | batch preset + album on 2 photos | pass | not driven |
| F08-H1 | export with EXIF, size, format, watermark | pass (JPEG, WebP) | pass (HEIC + watermark) |
| F09-H1 | edits survive a force stop | pass | pass |

Not driven by hand on Mac (background automation can't move SwiftUI sliders or paint): slider drags, brush strokes,
curve points, batch actions, imports. Their logic is covered by the self-test; the controls themselves are untested there.
Security and database review: see `bugs.md` (27 bugs, all fixed or accepted; 0 open S1/S2).

## After fixes

| check | Android | Mac |
| --- | --- | --- |
| self-test | 35 / 35 PASS | 38 / 38 PASS |
| hostile files via intent / library | no crash, 5 refused, 1 opened | `--check`: 4 refused, 2 opened |
| own private file via `file://` intent | refused before reading | n/a |
| release build | R8, 239 KB | hardened runtime, 2.1 MB |
