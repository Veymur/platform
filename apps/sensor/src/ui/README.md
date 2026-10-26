# Sensor UI foundation — stage 1, first increment

Palette, spacing and radii come from `design/markup-design/assets/css/tokens.css`.
`ThemeProvider` supports system/light/dark; since stage 4 the choice is persisted in native local settings.
Text uses bundled Bricolage Grotesque, Instrument Sans and DM Mono static TTF faces
with default font scaling. If loading fails, the system font remains the fallback.
No animation is used, including modal transitions, so the only reduced-motion
surface is the live session shape.
Controls have minimum 48 dp targets; tabs wrap and text has no fixed line limit.
Selection includes a text mark and accessibility state; status always includes text.

`components.tsx` provides Label, Heading, Card, Action, Toggle, Status, Dialog, Choice,
Detail, Warning, NumberField and TextField. `HrColumns.tsx` draws a gap-aware column chart
from plain Views. `SensorApp.tsx` hosts the stage 4 product screens (see the app README);
the diagnostic screen stays reachable from Profile.
No sample HR, GPS, cloud connection or fabricated recordings are shown.
The recorder remains native; opening or closing UI does not issue stop commands.
The existing diagnostic screen retains its own appearance and English text.

Verification on 2026-10-19: `npm run typecheck`, `npm run lint`, `npm test`
(47 passed), and `npx expo export --platform android --output-dir /tmp/veymur-stage1-bundle`
passed. Native build result is recorded in the application README.
CI runs the same JS checks from the existing lockfile. It does not claim native or
hardware validation. The fonts are bundled locally; no remote assets are used.

Remaining stage 1 work:

- Device checks of font rendering at font scale 2.0, TalkBack/VoiceOver, contrast
  and modal focus. The token contrast test now enforces the dark pressed primary
  button at ≥4.5:1.
- Licensed icon transfer, gap-aware chart component and component preview.
- Persistent theme choice using existing native settings, after contract review.
- iOS native module and build on macOS; Android hardware validation remains open.
- Cloud/API scaffolding and shared package extraction in a separate increment.
  Contracts currently live in recorder/types and the native module; domain rules
  belong to pure calculation code, ui-tokens to platform-neutral visual constants.
  Do not duplicate native persistence ownership in those packages.

The stage is in progress. These static components do not close stage 0 or the
product-screen milestones. Use a custom development build (`npm run android`),
not Expo Go. No environment secrets or backend are needed for this increment.
