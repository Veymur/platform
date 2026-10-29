import { useMemo } from "react";
import type { StyleProp, ViewStyle } from "react-native";
import Svg, { Path } from "react-native-svg";
import { useTheme } from "./Theme";

type Point = readonly [number, number];

function closedPath(points: readonly Point[]): string {
  const n = points.length;
  let d = `M${points[0][0].toFixed(2)} ${points[0][1].toFixed(2)}`;
  for (let i = 0; i < n; i++) {
    const p0 = points[(i - 1 + n) % n];
    const p1 = points[i];
    const p2 = points[(i + 1) % n];
    const p3 = points[(i + 2) % n];
    const c1x = p1[0] + (p2[0] - p0[0]) / 6;
    const c1y = p1[1] + (p2[1] - p0[1]) / 6;
    const c2x = p2[0] - (p3[0] - p1[0]) / 6;
    const c2y = p2[1] - (p3[1] - p1[1]) / 6;
    d += `C${c1x.toFixed(2)} ${c1y.toFixed(2)} ${c2x.toFixed(2)} ${c2y.toFixed(2)} ${p2[0].toFixed(2)} ${p2[1].toFixed(2)}`;
  }
  return `${d}Z`;
}

function makeContours({
  rings,
  baseR,
  gap,
  size,
  seed,
  wobble,
  energy,
  phase,
}: {
  rings: number;
  baseR: number;
  gap: number;
  size: number;
  seed: number;
  wobble: number;
  energy: number;
  phase: number;
}): string[] {
  const center = size / 2;
  const noise = (angle: number, ring: number) =>
    Math.sin(angle * 2 + ring * 0.7 + phase + seed) * 0.5 +
    Math.sin(angle * 3 - ring * 0.4 - phase * 1.3 + seed * 2) * 0.3 +
    Math.sin(angle * 5 + ring * 0.2 + phase * 0.6 + seed * 3) * 0.2;
  return Array.from({ length: rings }, (_, ring) => {
    const radius = baseR + ring * gap;
    const amplitude = wobble * (1 + ring * 0.04) * energy;
    const points: Point[] = [];
    for (let k = 0; k < 56; k++) {
      const angle = (k / 56) * Math.PI * 2;
      const r = radius * (1 + amplitude * noise(angle, ring));
      points.push([center + r * Math.cos(angle), center + r * Math.sin(angle)]);
    }
    return closedPath(points);
  });
}

export function ContourArt({
  rings = 9,
  baseR = 30,
  gap = 15,
  size = 360,
  seed = 3,
  wobble = 0.14,
  energy = 1,
  phase = 0,
  style,
}: {
  rings?: number;
  baseR?: number;
  gap?: number;
  size?: number;
  seed?: number;
  wobble?: number;
  energy?: number;
  phase?: number;
  style?: StyleProp<ViewStyle>;
}) {
  const { colors } = useTheme();
  const paths = useMemo(
    () =>
      makeContours({
        rings,
        baseR,
        gap,
        size,
        seed,
        wobble,
        energy,
        phase,
      }),
    [rings, baseR, gap, size, seed, wobble, energy, phase],
  );
  return (
    <Svg
      accessible={false}
      width="100%"
      height="100%"
      viewBox={`0 0 ${size} ${size}`}
      style={style}
    >
      {paths.map((d, index) => (
        <Path
          key={index}
          d={d}
          fill={index === 0 ? colors["tide-mark"] : "none"}
          fillOpacity={index === 0 ? 0.08 : undefined}
          stroke={index === 0 ? colors["tide-mark"] : colors.ink}
          strokeOpacity={index === 0 ? 0.5 : index % 4 === 0 ? 0.3 : 0.16}
          strokeWidth={1}
        />
      ))}
    </Svg>
  );
}
