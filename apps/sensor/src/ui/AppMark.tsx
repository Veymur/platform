import Svg, { G, Path, Rect } from "react-native-svg";

/** Brand mark from design/markup-design/assets/icons/app-sensor.svg. */
export function AppMark({ size = 52 }: { size?: number }) {
  return (
    <Svg
      accessible={false}
      width={size}
      height={size}
      viewBox="0 0 64 64"
      fill="none"
    >
      <Rect width={64} height={64} rx={15} fill="#0B6A63" />
      <G
        fill="none"
        stroke="#F5F0E6"
        strokeWidth={2}
        strokeLinejoin="round"
        strokeLinecap="round"
      >
        <G
          transform="translate(32 32) scale(.8) translate(-32 -32)"
          opacity={0.38}
        >
          <Path d="M32 7c12.500 0 24 8.700 24 23.500C56 46.500 45.500 57 31.500 57 18 57 8 46.500 8 33 8 17.500 19 7 32 7z" />
        </G>
        <G
          transform="translate(32 32) scale(.56) translate(-32 -32)"
          opacity={0.65}
        >
          <Path d="M32 7c12.500 0 24 8.700 24 23.500C56 46.500 45.500 57 31.500 57 18 57 8 46.500 8 33 8 17.500 19 7 32 7z" />
        </G>
        <Path d="M14 33h9l3.200-8 5.600 17 4-9H50" strokeWidth={2.6} />
      </G>
    </Svg>
  );
}
