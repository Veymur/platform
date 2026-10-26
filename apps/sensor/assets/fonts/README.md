# Bundled UI fonts

Static TTF instances were generated from the design package WOFF2 sources with
fontTools (`instantiateVariableFont` for the variable fonts; direct WOFF2
decompression for DM Mono). The app loads them through `expo-font` from
`src/ui/fonts.ts`.

| File                                                | Source                                                                                | Use                 |
| --------------------------------------------------- | ------------------------------------------------------------------------------------- | ------------------- |
| `BricolageGrotesque_460.ttf`                        | `design/markup-design/assets/fonts/bricolage-grotesque-var.woff2` (opsz 14, wght 460) | headings            |
| `BricolageGrotesque_300.ttf`                        | same source (opsz 14, wght 300)                                                       | large pulse numeral |
| `InstrumentSans_400.ttf` / `_500` / `_600` / `_700` | `instrument-sans-wght.woff2`                                                          | body and UI weights |
| `DMMono_400.ttf` / `_500`                           | `dm-mono-400.woff2`, `dm-mono-500.woff2`                                              | labels and units    |

The original OFL license texts are kept next to the fonts. The design source is
Latin-only; the app falls back to the system font for unsupported glyphs such as
Cyrillic. Fonts are local assets; no network request is made at runtime.
