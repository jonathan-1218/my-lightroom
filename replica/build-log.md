# Build log

| screen | date | status | missing | harder than expected |
| --- | --- | --- | --- | --- |
| S01 Library | 2026-10-04 | done | edited badge, ratings/flags overlay (milestone 2), skeleton cells while thumbnails load | a focusable grid cell swallows GridView item clicks; Android 14 partial access needs its own "Choose more" path |
| S02 Editor | 2026-10-04 | partial | detail/optics/crop panels (milestone 2) | undo had to be one step per drag, not per progress tick, so the checkpoint hooks onStartTrackingTouch |
| S08 Export | 2026-10-04 | partial | format/size/quality sheet, full-res tiled export, GPS | MediaStore redacts location without ACCESS_MEDIA_LOCATION; framework ExifInterface lacks the lens tag constants |

States checked on the Pixel 8 emulator (API 37): no permission, permission prompt, grid (11 photos), tap → editor,
undo/redo, export with EXIF (Make, Model, three dates, Software=Lumen), "Photos" from an externally opened editor.
Not exercised: empty library, query error, Android 14 limited access, a permanently denied permission.
Screenshots: `replica/clone-screens/`.
| S02 Editor (milestone 2) | 2026-10-04 | done | colour grading, grain, noise (should-haves) | the curve LUT was being scaled like the blurs and turned every pixel white; the self-test caught it. Curve inside a scrolling panel stole scroll gestures, so it got its own tab |
| S03 Crop | 2026-10-04 | done | perspective, Upright | rotate while mirrored has to turn the source the other way; handles at the screen edge were ungrabbable until crop mode got an inset |
| S06 Presets | 2026-10-04 | partial | import of other apps' presets, preset thumbnails | — |
| S08 Export (milestone 2) | 2026-10-04 | partial | format/size/quality sheet, GPS | tiled render verified seam-free on a 6000×4000 source |

Milestone 2 checked on the emulator: presets (apply, undo), crop (straighten, 1:1 / 4:5 lock, corner drag, rotate), curve, colour mix,
clarity/dehaze, rating/flag/label → library badges, full-size tiled export. Self-test: 12 checks, PASS.
The library's empty state was seen for real after an emulator cold boot (`S01-empty.png`).
Not exercised: DNG (no sample), preset delete dialog, flip, Ctrl/letter shortcuts on a hardware keyboard.
