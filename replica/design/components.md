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
