import { createContext, useContext } from "react";
import { StatusBar } from "react-native";
import { spacing } from "./tokens";

export type ScreenInsets = { top: number; bottom: number; known: boolean };
export const fallbackInsets: ScreenInsets = {
  top: StatusBar.currentHeight ?? 0,
  bottom: spacing.navigationBar,
  known: false,
};
const InsetsContext = createContext<ScreenInsets>(fallbackInsets);
export const InsetsProvider = InsetsContext.Provider;
export function useInsets(): ScreenInsets {
  return useContext(InsetsContext);
}
