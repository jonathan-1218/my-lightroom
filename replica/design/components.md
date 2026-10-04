# Components: Lumen

Tokens: `src/main/assets/tokens.json`. The app reads this file at runtime and contrast.py checks it: 10 pairs, 0 AA failures.
Code: `src/main/kotlin/dev/lumen/Ui.kt`. Gallery: `adb shell am start -S -f 0x10008000 -n dev.lumen/.MainActivity --ez design true`.
No tokens.css or Tailwind: this is plain Android Views, not web.

TextButton
  states    default, pressed (ripple), disabled (text-muted, not just alpha)
  tokens    type sm/500, text, min 48×48dp
  a11y      real clickable TextView, label is the name. Icons later (Lucide, MIT) must keep a contentDescription.
  used on   editor top bar (Open, Reset, Export)

Tabs
  states    selected (accent pill, on-accent text), unselected (text-muted), pressed (ripple)
  tokens    type sm/500, radius pill, height 36dp in a 48dp row
  a11y      isSelected, so TalkBack says "selected"
  used on   editor panel switcher. One tab per edit-document section (Light, Color, Effects, then Detail, Optics…).

SliderRow
  parts     name (tap = reset), signed value (tabular figures, muted at 0), SeekBar −100…100
  track     CenterTrack: flat track line, accent fill from zero to the thumb (the Lightroom pattern)
  tokens    track, accent, type sm, 48dp touch height
  a11y      contentDescription = name, stateDescription = "+32", name says "tap to reset"
  used on   every adjustment panel

Photo preview (EditView)
  states    edited, original (while pressed), empty (bg)
  a11y      contentDescription explains touch-and-hold

Not built yet (they come with their screens): library grid cell, crop handles, mask pins, histogram, export sheet, icons.

CurveView
  parts     4×4 grid (border), curve (text / label-red / label-green / label-blue per channel), points (same colour)
  gestures  tap empty space = add point, drag = move, double-tap = remove; end points move vertically only
  a11y      contentDescription points to "Reset curve"; not adjustable by TalkBack (gap: no per-point controls)
  used on   Curve tab, one tab of its own so the panel never scrolls under a drag

Crop frame (EditView crop mode)
  parts     scrim outside the frame, 1dp frame + thirds (text), 7dp handles (accent), photo inset 24dp
  gestures  drag a corner (aspect-locked if a ratio chip is on), drag inside to move; always clamped inside the photo
  used on   Crop tab

Library cell marks
  parts     ★ × rating (text, shadow), ⚑ when picked, label dot (label-*), scrim over rejects
  a11y      contentDescription adds "4 stars, picked, blue"

Mask handles (EditView mask mode)
  linear    solid dot = full effect, ring = no effect, lines across the photo at both; drag the middle to move both
  radial    ellipse outline, solid centre (move), two rings (x / y radius)
  brush     finger paints strokes; Paint / Erase chips, Size, Softness
  color     tap to pick; swatch of the picked colour top-left
  overlay   selected mask's area tinted with `overlay` at 50%; "Hide overlay" / "Show overlay" toggles it

Retouch spots (EditView retouch mode)
  parts     solid ring = target (accent when selected), dashed ring + dashed line = source
  gestures  tap empty photo = new spot, tap a ring = select, drag either ring; the photo updates on release

Export dialog
  parts     Format chips, Long edge chips, Quality slider (50–100, off for PNG), metadata switch (accent when on), Share / Cancel / Export in accent

HistogramView
  parts     red / green / blue at 40% (label-* colours), ends ignored when scaling
  a11y      decorative, hidden from TalkBack

Library selection
  long press  selects; tap toggles while selecting; accent ring + dot on selected cells; title shows "N selected"
  bar         Paste · Preset · Rate · Album · Export · Done (replaces Pick)
  filters     chips under the title: All, Picks, 3★ +, Rejects, Edited, labels in use, albums
  cells       "±" bottom right when edited; edited thumbnails are rendered with the edit
