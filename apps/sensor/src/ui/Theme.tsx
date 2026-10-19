import { createContext, useContext, useState, type PropsWithChildren } from 'react';
import { useColorScheme } from 'react-native';
import { dark, light, type Palette } from './tokens';
export type ThemePreference = 'system' | 'light' | 'dark';
const ThemeContext = createContext<{ colors: Palette; isDark: boolean; preference: ThemePreference; setPreference: (value: ThemePreference) => void } | null>(null);
export function ThemeProvider({ children }: PropsWithChildren) {
  const system = useColorScheme();
  const [preference, setPreference] = useState<ThemePreference>('system');
  const isDark = preference === 'dark' || (preference === 'system' && system === 'dark');
  return <ThemeContext.Provider value={{ colors: isDark ? dark : light, isDark, preference, setPreference }}>{children}</ThemeContext.Provider>;
}
export function useTheme() {
  const theme = useContext(ThemeContext);
  if (!theme) throw new Error('ThemeProvider is required');
  return theme;
}
