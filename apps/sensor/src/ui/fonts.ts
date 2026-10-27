/* eslint-disable @typescript-eslint/no-require-imports -- Metro asset loading uses require(). */
import { useFonts } from "expo-font";
import { fonts } from "./tokens";

const assets = {
  [fonts.display]: require("../../assets/fonts/BricolageGrotesque_460.ttf"),
  [fonts.displayMedium]: require("../../assets/fonts/BricolageGrotesque_440.ttf"),
  [fonts.displayPulse]: require("../../assets/fonts/BricolageGrotesque_340.ttf"),
  [fonts.displayLight]: require("../../assets/fonts/BricolageGrotesque_300.ttf"),
  [fonts.displayThin]: require("../../assets/fonts/BricolageGrotesque_200.ttf"),
  [fonts.ui]: require("../../assets/fonts/InstrumentSans_400.ttf"),
  [fonts.uiMedium]: require("../../assets/fonts/InstrumentSans_500.ttf"),
  [fonts.uiSemibold]: require("../../assets/fonts/InstrumentSans_600.ttf"),
  [fonts.uiBold]: require("../../assets/fonts/InstrumentSans_700.ttf"),
  [fonts.mono]: require("../../assets/fonts/DMMono_400.ttf"),
  [fonts.monoMedium]: require("../../assets/fonts/DMMono_500.ttf"),
};

/** Load bundled UI fonts; on failure the app keeps the system-font fallback. */
export function useVeymurFonts() {
  return useFonts(assets);
}
