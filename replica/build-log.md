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
| S04 Masks | 2026-10-04 | done | AI subject / sky (needs ML Kit), add/subtract/intersect of components, more than 4 masks | a local fun named `select` silently won over `Tabs.select` inside `.apply { }` and re-selected mask 1; Tabs now take `initial`, which also fixed export settings not being remembered |
| S05 Retouch | 2026-10-04 | partial | freeform heal strokes, auto source search, content-aware remove | re-baking the spot + two GPU blurs on every move stalled the UI ~2 s per event on the emulator, so drags move the circles and apply on release |
| S08 Export (milestone 3) | 2026-10-04 | done | TIFF, export original | — |

Milestone 3 checked on the emulator: gradient (handles, overlay, darkened sky), radial (handles), brush (soft stroke), colour range (pick red → only reds darken),
masks saved and restored, heal spot + clone with dragged source, export dialog (WebP 2048 → 2048×1638 with masks, crop and spot baked in), settings remembered,
Share opens the system sheet. Self-test: 17 checks, PASS.
Known slow spot: adding/moving a spot re-blurs the preview (~2 s on the emulator GPU); fine on a phone, worth caching if it isn't.
Not exercised on device: luminance mask, mask invert (self-test only), brush erase.
| Milestone 4: everything else | 2026-10-04 | done | AI masks, proprietary RAW, HDR, lens profiles (each needs a big dependency or a database; skipped on purpose) | auto level by gradient-angle histogram was 4° off on clean lines, replaced by a projection search; noise taps of 1.5 × (2048/size) pixels sampled the pixel itself on small images, floored at 1 px; Auto aimed the median at 45% and blew out a mostly-black photo, now aims the mean and caps at ±1.2 EV |

Milestone 4 checked on the emulator: library filters (3★, album), long-press multi-select, batch preset (thumbnails re-render edited), batch album,
Auto + histogram, profile chips, .cube import → applied, .xmp import → preset with exactly its 4 settings, keystone, grain, distortion.
Release build (R8): 224 KB APK, self-test 27/27 PASS, library + editor open without crashes.
Self-test only (not driven by hand): colour grading, noise reduction, auto level, find spots, perspective fit, versions DB, watermark.
