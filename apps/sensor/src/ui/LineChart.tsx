import { useId, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import Svg, {
  Circle,
  Defs,
  Line,
  Path,
  Pattern,
  Rect,
  Text as SvgText,
} from "react-native-svg";
import { useTheme } from "./Theme";
import { fonts } from "./tokens";

type XTick = { index: number; label: string };

export function LineChart({
  values,
  height = 168,
  yMin,
  yMax,
  yTicks,
  xTicks = [],
  band,
  label,
  gapLabel = "No pulse",
  endDot = false,
  emptyLabel = "No pulse data",
}: {
  values: readonly (number | null)[];
  height?: number;
  yMin?: number;
  yMax?: number;
  yTicks?: readonly number[];
  xTicks?: readonly XTick[];
  band?: { min: number; max: number } | null;
  label: string;
  gapLabel?: string;
  endDot?: boolean;
  emptyLabel?: string;
}) {
  const { colors } = useTheme();
  const [width, setWidth] = useState(320);
  const rawId = useId();
  const patternId = `hatch-${rawId.replace(/[^a-zA-Z0-9_-]/g, "")}`;
  const valid = values.filter((value): value is number => value !== null);
  const pad = { top: 14, right: 12, bottom: 26, left: 36 };
  const w = Math.max(width, 200);
  const plotWidth = Math.max(1, w - pad.left - pad.right);
  const plotHeight = Math.max(1, height - pad.top - pad.bottom);
  const lo =
    yMin ?? (valid.length ? Math.floor((Math.min(...valid) - 6) / 10) * 10 : 0);
  const hi =
    yMax ?? (valid.length ? Math.ceil((Math.max(...valid) + 6) / 10) * 10 : 1);
  const span = Math.max(1, hi - lo);
  const x = (index: number) =>
    pad.left + (index / Math.max(values.length - 1, 1)) * plotWidth;
  const y = (value: number) => pad.top + (1 - (value - lo) / span) * plotHeight;
  const ticks =
    yTicks ??
    [lo, Math.round((lo + hi) / 2), hi].filter(
      (value, index, all) => all.indexOf(value) === index,
    );
  const gapRuns: { from: number; to: number }[] = [];
  let gapStart: number | null = null;
  values.forEach((value, index) => {
    if (value === null && gapStart === null) gapStart = index;
    if (value !== null && gapStart !== null) {
      gapRuns.push({ from: gapStart, to: index - 1 });
      gapStart = null;
    }
  });
  if (gapStart !== null)
    gapRuns.push({ from: gapStart, to: values.length - 1 });
  let line = "";
  let runStart: number | null = null;
  values.forEach((value, index) => {
    if (value !== null) {
      if (runStart === null) {
        line += `M${x(index).toFixed(1)} ${y(value).toFixed(1)}`;
        runStart = index;
      } else {
        line += `L${x(index).toFixed(1)} ${y(value).toFixed(1)}`;
      }
    } else {
      runStart = null;
    }
  });
  const lastValid = (() => {
    for (let index = values.length - 1; index >= 0; index--)
      if (values[index] !== null) return index;
    return -1;
  })();
  const isolated = values.flatMap((value, index) =>
    value !== null &&
    (index === 0 || values[index - 1] === null) &&
    (index === values.length - 1 || values[index + 1] === null)
      ? [index]
      : [],
  );
  if (valid.length === 0)
    return (
      <View style={[styles.container, { height }]}>
        <Text style={[styles.empty, { color: colors["ink-3"] }]}>
          {emptyLabel}
        </Text>
      </View>
    );
  return (
    <View
      onLayout={(event) => setWidth(event.nativeEvent.layout.width)}
      accessible
      accessibilityRole="image"
      accessibilityLabel={label}
      style={[styles.container, { height }]}
    >
      <Svg width="100%" height={height} viewBox={`0 0 ${w} ${height}`}>
        <Defs>
          <Pattern
            id={patternId}
            width={6}
            height={6}
            patternUnits="userSpaceOnUse"
            patternTransform="rotate(45)"
          >
            <Line
              x1={0}
              y1={0}
              x2={0}
              y2={6}
              stroke={colors["ink-3"]}
              strokeWidth={1}
              opacity={0.55}
            />
          </Pattern>
        </Defs>
        {ticks.map((tick) => (
          <Line
            key={`grid-${tick}`}
            x1={pad.left}
            x2={w - pad.right}
            y1={y(tick)}
            y2={y(tick)}
            stroke={colors.rule}
            strokeWidth={1}
          />
        ))}
        {ticks.map((tick) => (
          <SvgText
            key={`tick-${tick}`}
            x={pad.left - 8}
            y={y(tick) + 4}
            textAnchor="end"
            fill={colors["ink-3"]}
            fontFamily={fonts.mono}
            fontSize={11}
          >
            {tick}
          </SvgText>
        ))}
        {band && (
          <>
            <Rect
              x={pad.left}
              y={y(band.max)}
              width={plotWidth}
              height={Math.max(1, y(band.min) - y(band.max))}
              fill={colors["tide-mark"]}
              opacity={0.12}
            />
            {[band.max, band.min].map((value) => (
              <Line
                key={`band-${value}`}
                x1={pad.left}
                x2={w - pad.right}
                y1={y(value)}
                y2={y(value)}
                stroke={colors["tide-mark"]}
                strokeWidth={1}
                strokeDasharray="2 3"
                opacity={0.7}
              />
            ))}
          </>
        )}
        {gapRuns.map((run) => {
          const x0 = run.from === 0 ? x(0) : x(run.from - 1);
          const x1 =
            run.to >= values.length - 1 ? x(values.length - 1) : x(run.to);
          const gapWidth = x1 - x0;
          return (
            <Rect
              key={`gap-${run.from}-${run.to}`}
              x={x0}
              y={pad.top}
              width={Math.max(1, gapWidth)}
              height={plotHeight}
              fill={`url(#${patternId})`}
              opacity={0.5}
            />
          );
        })}
        {gapRuns.map((run) => {
          const x0 = run.from === 0 ? x(0) : x(run.from - 1);
          return (
            <Line
              key={`gap-edge-${run.from}-${run.to}`}
              x1={x0}
              x2={x0}
              y1={pad.top}
              y2={height - pad.bottom}
              stroke={colors["ink-3"]}
              strokeWidth={1}
              strokeDasharray="3 3"
            />
          );
        })}
        {gapRuns.map((run) => {
          const x1 =
            run.to >= values.length - 1 ? x(values.length - 1) : x(run.to);
          return (
            <Line
              key={`gap-right-${run.from}-${run.to}`}
              x1={x1}
              x2={x1}
              y1={pad.top}
              y2={height - pad.bottom}
              stroke={colors["ink-3"]}
              strokeWidth={1}
              strokeDasharray="3 3"
            />
          );
        })}
        {gapRuns.map((run) => {
          const x0 = run.from === 0 ? x(0) : x(run.from - 1);
          const x1 =
            run.to >= values.length - 1 ? x(values.length - 1) : x(run.to);
          if (x1 - x0 <= 70 || !gapLabel) return null;
          return (
            <SvgText
              key={`gap-label-${run.from}-${run.to}`}
              x={(x0 + x1) / 2}
              y={pad.top + 16}
              textAnchor="middle"
              fill={colors["ink-3"]}
              fontFamily={fonts.ui}
              fontSize={12}
            >
              {gapLabel}
            </SvgText>
          );
        })}
        <Path
          d={line}
          fill="none"
          stroke={colors["tide-mark"]}
          strokeWidth={2}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        {isolated.map((index) => (
          <Circle
            key={`isolated-${index}`}
            cx={x(index)}
            cy={y(values[index] as number)}
            r={4}
            fill={colors["tide-mark"]}
          />
        ))}
        {endDot && lastValid >= 0 && (
          <Circle
            cx={x(lastValid)}
            cy={y(values[lastValid] as number)}
            r={4.5}
            fill={colors["tide-mark"]}
            stroke={colors.raised}
            strokeWidth={2}
          />
        )}
        {xTicks.map((tick) => (
          <SvgText
            key={`x-${tick.index}`}
            x={x(tick.index)}
            y={height - 6}
            textAnchor={
              tick.index === 0
                ? "start"
                : tick.index >= values.length - 1
                  ? "end"
                  : "middle"
            }
            fill={colors["ink-3"]}
            fontFamily={fonts.mono}
            fontSize={11}
          >
            {tick.label}
          </SvgText>
        ))}
      </Svg>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { width: "100%", justifyContent: "center" },
  empty: {
    fontFamily: fonts.mono,
    fontSize: 12,
    textAlign: "center",
  },
});
