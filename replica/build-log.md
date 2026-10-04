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
