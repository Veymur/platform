import { View } from "react-native";
import {
  describeColumns,
  shapeInnerRadius,
  shapePoints,
  type ChartColumn,
} from "../product/sessionMath";
import { useTheme } from "./Theme";

export function SessionShape({
  columns,
  size,
  label,
}: {
  columns: readonly ChartColumn[];
  size: number;
  label: string;
}) {
  const { colors } = useTheme();
  const { gaps } = describeColumns(columns);
  const dot = Math.max(3, Math.round(size / 28));
  const inner = size * shapeInnerRadius;
  const description =
    columns.length === 0
      ? `${label}: no pulse data.`
      : `${label}: time runs clockwise, distance from the centre is pulse${gaps > 0 ? `; ${gaps} of ${columns.length} parts without pulse data, shown as a dotted inner ring` : ""}.`;
  return (
    <View
      accessible
      accessibilityRole="image"
      accessibilityLabel={description}
      style={{ width: size, height: size }}
    >
      <View
        style={{
          position: "absolute",
          left: (size - inner) / 2,
          top: (size - inner) / 2,
          width: inner,
          height: inner,
          borderRadius: inner / 2,
          borderWidth: 1,
          borderColor: colors.rule,
        }}
      />
      {shapePoints(columns).map((point, index) => (
        <View
          key={index}
          style={{
            position: "absolute",
            left: point.x * size - dot / 2,
            top: point.y * size - dot / 2,
            width: dot,
            height: dot,
            borderRadius: dot / 2,
            ...(point.hasPulse
              ? { backgroundColor: colors["tide-mark"] }
              : { borderWidth: 1, borderColor: colors["ink-3"] }),
          }}
        />
      ))}
    </View>
  );
}
