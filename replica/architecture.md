# Architecture: Lumen (a rebuild of Lightroom mobile's editing, on-device, for one person)

Scope: everything in `features.csv` marked must/should/could. Skipped on purpose: cloud sync,
generative/cloud AI, community, video, in-app camera. Those need Adobe's servers or are
separate products. Constraint: **light**. No dependencies, release APK under 1 MB, works offline.

## Stack

| layer | choice | why |
| --- | --- | --- |
| app | Kotlin, plain Android Views, single module, minSdk 33 | already built that way; no AndroidX = tiny APK |
| image engine | one AGSL `RuntimeShader`, GPU | live preview and export run the same code |
| database | SQLite via `android.database.sqlite` | built in; Room would add a dependency + codegen |
| photo access | MediaStore + `READ_MEDIA_IMAGES`, photo picker as fallback | the "Device" grid needs the whole camera roll |
| metadata | `android.media.ExifInterface` | built in, can copy EXIF to exports |
| auth, payments, email, jobs, hosting | none | personal, offline, single device |
| build | Gradle, AGP 9.2.1, R8 on release | already set up |

## Schema

Tables: 5 (`photos`, `versions`, `presets`, `albums`, `album_photos`), in `src/main/assets/schema.sql` (the app runs this exact file on first launch).
Access rules: none needed. The DB is app-private and has a single user.

The edit document (stored in `photos.edit`, `versions.edit`, `presets.edit`):

```json
{ "v": 1,
  "light":  { "exposure": 0, "contrast": 0, "highlights": 0, "shadows": 0, "whites": 0, "blacks": 0 },
  "curve":  { "rgb": [[0,0],[1,1]], "r": [], "g": [], "b": [] },
  "color":  { "temp": 0, "tint": 0, "vibrance": 0, "saturation": 0,
              "grading": { "shadows": [0,0,0], "mid": [0,0,0], "high": [0,0,0], "blend": 0.5 } },
  "mixer":  { "redHue": 0, "redSat": 0, "redLum": 0, "...": "8 hues × Hue/Sat/Lum, flat keys" },
  "effects":{ "texture": 0, "clarity": 0, "dehaze": 0, "vignette": 0, "grain": 0 },
  "detail": { "sharpen": 0, "noise": 0 },
  "optics": { "distortion": 0, "ca": 0, "lensVignette": 0 },
  "geometry": { "crop": [0,0,1,1], "straighten": 0, "rot90": 0, "flipH": false },
  "profile": "color",
  "masks":  [ { "type": "linear|radial|brush|color|luma", "g": [4 numbers, meaning by type], "invert": false,
                "feather": 0.5, "range": 0.3, "strokes": [ { "size": 0.05, "erase": false, "p": [x,y, x,y, …] } ],
                "adjust": { "exposure","contrast","highlights","shadows","temp","tint","saturation","clarity" } } ],
  "retouch":[ [dx, dy, sx, sy, r, heal 1|0] ] }
```

Coordinates and radii are normalised (0..1 of the image), so preview and export match at any resolution.
Masks and spots use source coordinates, so they stay on the content when the crop changes. Up to 4 masks (shader slots).

## Screens and actions (replaces the API table; there is no server)

| screen | action | does | reads/writes |
| --- | --- | --- | --- |
| S01 Library | open app | grid of device photos, newest first | MediaStore, `photos` |
| S01 Library | filter | by rating, flag, label, album, date | `photos`, `album_photos` |
| S01 Library | multi-select → paste / preset / rate / add to album | batch apply | `photos`, `album_photos` |
| S02 Editor | open photo | decode, build preview + blur children, load edit | `photos.edit` |
| S02 Editor | move slider | update uniform, redraw, push undo, debounce-save 300 ms | `photos.edit` |
| S02 Editor | undo / redo, before/after, histogram, auto | in memory; auto writes `light` | `photos.edit` |
| S03 Crop | crop, straighten, rotate, flip, perspective | edits `geometry` | `photos.edit` |
| S04 Masks | add / paint / invert / feather | edits `masks` | `photos.edit` |
| S05 Retouch | tap spot, drag source | edits `retouch` | `photos.edit` |
| S06 Presets & profiles | apply, save current as preset, import .cube | merge partial edit | `presets` |
| S07 Versions | save / restore snapshot | copy edit | `versions` |
| S08 Export | format, size, quality, EXIF on/off, share | tiled full-res render → MediaStore / share sheet | none |

Webhooks: none. Jobs: none.

## Image pipeline

One shader pass per frame. It samples source coordinates through geometry, then develops:

1. **Load** (once per photo): decode in sRGB → `retouched` bitmap (heal/clone spots baked in) →
   `blurS` (texture) and `blurL` (clarity/dehaze) copies at preview size → curve LUT (256×1) →
   profile LUT (3D LUT as a 2D strip) → brush masks (up to 4 per RGBA texture).
2. **Shader** (`img`, `blurS`, `blurL`, `curve`, `lut`, `maskTex` children): geometry warp → white balance
   and exposure in linear → tone (whites/blacks/highlights/shadows) → curve → HSL mixer → grading →
   texture/clarity/dehaze (detail = img − blur) → sharpen and noise (few-tap) → masks (N analytic + brush,
   each with its own adjust block) → vignette, grain.
3. **Export**: same shader over 2048 px tiles with a blur-radius margin, source cropped per tile,
   stitched into one bitmap → JPEG/PNG → EXIF copied.

## The parts that bite

- **Memory**: a 50 MP photo is 200 MB as ARGB. Tiles for rendering, `largeHeap`, and export capped at the source size.
- **GPU limits**: the maximum texture size (8192–16384) and the uniform count. Masks are capped (8 analytic + 4 brush) so the shader stays compilable on mid-range GPUs.
- **Preview ≠ export**: blur radii and vignettes must be normalised, or the export looks different. A parity self-check renders the same edit at two sizes.
- **Colour**: phones shoot Display P3. Decode to sRGB on purpose (ponytail), wide gamut later.
- **Process death**: Android kills apps in the background. Edits debounce-save every 300 ms.
- **Permissions**: Android 14 "selected photos only" access. The library shows what was granted, and the picker covers the rest.
- **Edit JSON versioning**: the `"v"` field plus a migrate function. Presets saved today must still load in a year.
- **RAW**: DNG goes through ImageDecoder (slow, ~1–2 s). CR3/NEF/ARW need LibRaw, a native library that breaks "light".
- **Not applicable**: time zones, webhooks, races, rate limits, auth, GDPR, multi-tenancy.

## Build order

1. **Vertical slice**: S01 grid → S02 editor (current sliders) → edit persisted in `photos` → undo → S08 export with EXIF.
   Proves the library + non-destructive loop.
2. **Must-haves**: tone curve, HSL mixer, texture/clarity/dehaze, sharpening, S03 crop/straighten/rotate/flip,
   presets + copy/paste, ratings/flags, full-res tiled export, DNG check.
3. **Should-haves**: S04 masks (linear, radial, brush, colour/luma range, feather), S05 heal/clone, colour grading,
   grain, noise reduction, perspective, manual lens fixes, auto, histogram, profiles/.cube LUTs, S07 versions,
   albums, filters, batch, share, PNG.
4. **Could-haves**: AI subject/sky masks (ML Kit, adds ~5–10 MB: ask first), content-aware remove, Upright,
   lens profiles, Ultra HDR, watermark, proprietary RAW.
5. **Fixes from replica-entrepreneur**: not run. The angle so far is "Lightroom's editor, offline, free, no account, under 1 MB".
