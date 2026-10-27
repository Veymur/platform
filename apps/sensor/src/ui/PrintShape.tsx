import { View } from "react-native";
import Svg, { Circle, Path } from "react-native-svg";
import type { ChartColumn } from "../product/sessionMath";
import { useTheme } from "./Theme";

type Point = readonly [number, number];

function closedPath(points: readonly Point[]): string {
  const n = points.length;
  if (n === 0) return "";
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

/** Filled radial session print from the prototype, used in S05/H01. */
export function PrintShape({
  columns,
  size,
  label,
}: {
  columns: readonly ChartColumn[];
  size: number;
  label: string;
}) {
  const { colors } = useTheme();
  if (columns.length === 0)
    return <View style={{ width: size, height: size }} />;
  const values = columns.flatMap((column) =>
    column.bpm === null ? [] : [column.bpm],
  );
  const min = values.length ? Math.min(...values) : 0;
  const max = values.length ? Math.max(...values) : 1;
  const span = max - min || 1;
  const center = size / 2;
  const outer = size * 0.421875;
  const inner = size * 0.15625;
  let last = 0.5;
  const radii = columns.map((column) => {
    if (column.bpm !== null) last = (column.bpm - min) / span;
    return inner + (outer - inner) * (0.18 + 0.82 * last);
  });
  const points: Point[] = radii.map((radius, index) => {
    const angle = (index / columns.length) * Math.PI * 2 - Math.PI / 2;
    return [
      center + radius * Math.cos(angle),
      center + radius * Math.sin(angle),
    ];
  });
  const gapRuns: { from: number; to: number }[] = [];
  let start: number | null = null;
  columns.forEach((column, index) => {
    if (column.bpm === null && start === null) start = index;
    if (column.bpm !== null && start !== null) {
      gapRuns.push({ from: start, to: index - 1 });
      start = null;
    }
  });
  if (start !== null) gapRuns.push({ from: start, to: columns.length - 1 });
  const ring = outer + (size / 64) * 3;
  return (
    <View
      accessible
      accessibilityRole="image"
      accessibilityLabel={label}
      style={{ width: size, height: size }}
    >
      <Svg
        accessible={false}
        width={size}
        height={size}
        viewBox={`0 0 ${size} ${size}`}
      >
        <Circle
          cx={center}
          cy={center}
          r={ring}
          fill="none"
          stroke={colors.rule}
          strokeWidth={1}
        />
        <Path
          d={closedPath(points)}
          fill={colors["tide-wash"]}
          stroke={colors["tide-mark"]}
          strokeWidth={1.5}
          strokeLinejoin="round"
        />
        {gapRuns.map((run) => {
          const a0 = (run.from / columns.length) * Math.PI * 2 - Math.PI / 2;
          const a1 =
            ((run.to + 1) / columns.length) * Math.PI * 2 - Math.PI / 2;
          const large = a1 - a0 > Math.PI ? 1 : 0;
          const x0 = center + ring * Math.cos(a0);
          const y0 = center + ring * Math.sin(a0);
          const x1 = center + ring * Math.cos(a1);
          const y1 = center + ring * Math.sin(a1);
          return (
            <Path
              key={`${run.from}-${run.to}`}
              d={`M${x0.toFixed(2)} ${y0.toFixed(2)}A${ring} ${ring} 0 ${large} 1 ${x1.toFixed(2)} ${y1.toFixed(2)}`}
              fill="none"
              stroke={colors["ink-3"]}
              strokeWidth={1.5}
              strokeDasharray="2 3"
            />
          );
        })}
      </Svg>
    </View>
  );
}
