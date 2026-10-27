# Sensor UI foundation — stage 1, first increment

Palette, spacing and radii come from `design/markup-design/assets/css/tokens.css`.
`ThemeProvider` supports system/light/dark; since stage 4 the choice is persisted in native local settings.
Text uses bundled Bricolage Grotesque (200/300/340/440/460), Instrument Sans and
DM Mono static TTF faces with default font scaling. If loading fails, the system
font remains the fallback. `Typography.tsx` carries the design type scale
(`H1`–`H4`, `Eyebrow`, `Lede`, `Body`, `Small`, `Caption`, `Mono`, `Unit`,
`PulseNumber`). The sheet entrance and button press response are short animations;
the sheet becomes static when the system asks to remove animations. The live session
shape keeps its own reduced-motion handling.
Controls have minimum 48 dp targets; text has no fixed line limit.
Selection includes a visual indicator and accessibility state; status always includes text.

`Screen.tsx` provides the fixed screen shell: `Screen`, `AppBar`, `ScreenActions` and
`TabBar`. The app bar and bottom actions stay outside the scrolling body; the application
shell owns the tab bar so it can be shown only on screens that have one.
`components.tsx` provides Label, Heading, Card with variants, Action variants and sizes,
Toggle, Status, Dialog (a bottom `Sheet`), Choice, Detail, Banner/Warning, Chip, Mark,
LiveDot, KeyValue, Row, Segmented and text/number fields. `Sheet.tsx` owns the modal sheet, scrim and
focus boundary. `Icon.tsx` renders the design SVG symbols from the generated `icons.ts`.
`Meter.tsx` provides the segmented meter and legend. `Toast.tsx` is the transient
confirmation above the tab bar. `Grain.tsx` draws the repeating paper texture behind the
screen body. `HrColumns.tsx` draws a gap-aware column chart from plain Views. `SensorApp.tsx`
hosts the stage 4 product screens (see the app README); the diagnostic screen stays
reachable from Profile.
No sample HR, GPS, cloud connection or fabricated recordings are shown.
The recorder remains native; opening or closing UI does not issue stop commands.
The existing diagnostic screen retains its own appearance and English text.

Verification on 2026-10-19: `npm run typecheck`, `npm run lint`, `npm test`
(47 passed), and `npx expo export --platform android --output-dir /tmp/veymur-stage1-bundle`
passed. Native build result is recorded in the application README.

P1 foundation verification on 2026-10-27: `npm run typecheck`, `npm run lint`,
`npm test` (70 passed), Prettier, and `npx expo export --platform android` passed.
The bundle contains 11 font assets. `react-native-svg` was added for the icon set,
so a native prebuild/reinstall is required; device rendering and screenshot parity
were NOT RUN. CI runs the same JS checks from the existing lockfile. It does not claim
native or hardware validation. The fonts and icons are bundled locally; no remote assets
are used.

Remaining stage 1 work:

- Device checks of font rendering at font scale 2.0, TalkBack/VoiceOver, contrast
  and modal focus. The token contrast test now enforces the dark pressed primary
  button at ≥4.5:1.
- Gap-aware line chart with range band, gap hatching and tooltip; component preview and
  visual regression screenshots. Icons are transferred as generated SVG symbols.
- Persistent theme choice using existing native settings, after contract review.
- iOS native module and build on macOS; Android hardware validation remains open.
- Cloud/API scaffolding and shared package extraction in a separate increment.
  Contracts currently live in recorder/types and the native module; domain rules
  belong to pure calculation code, ui-tokens to platform-neutral visual constants.
  Do not duplicate native persistence ownership in those packages.

The stage is in progress. These static components do not close stage 0 or the
product-screen milestones. Use a custom development build (`npm run android`),
not Expo Go. No environment secrets or backend are needed for this increment.
