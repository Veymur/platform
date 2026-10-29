import { SvgXml } from "react-native-svg";
import { icons, type IconName } from "./icons";
import { useTheme } from "./Theme";

export type { IconName };

/** Draw a design-system icon with the current theme colour. */
export function Icon({
  name,
  size = 20,
  color,
  strokeWidth = 1.75,
}: {
  name: IconName;
  size?: number;
  color?: string;
  strokeWidth?: number;
}) {
  const { colors } = useTheme();
  const tint = color ?? colors.ink;
  const icon = icons[name];
  const xml = `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="${icon.viewBox}" fill="none" stroke="${tint}" stroke-width="${strokeWidth}" stroke-linecap="round" stroke-linejoin="round">${icon.body.replaceAll("currentColor", tint)}</svg>`;
  return <SvgXml xml={xml} width={size} height={size} />;
}
