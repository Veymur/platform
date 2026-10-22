import { View } from "react-native";
import { describeColumns, type ChartColumn } from "../product/sessionMath";
import { Label } from "./components";
import { useTheme } from "./Theme";

export function HrColumns({
  columns,
  title,
  height = 120,
}: {
  columns: readonly ChartColumn[];
  title: string;
  height?: number;
}) {
  const { colors } = useTheme();
  const { min, max, gaps } = describeColumns(columns);
  const floor = min === null ? 0 : Math.max(0, min - 10);
  const span = max === null ? 1 : Math.max(1, max + 5 - floor);
  const summary =
    min === null
      ? `${title}: no pulse data.`
      : `${title}: pulse from ${min} to ${max} bpm${gaps > 0 ? `, ${gaps} of ${columns.length} parts without pulse data` : ""}.`;
  return (
    <View
      accessible
      accessibilityRole="image"
      accessibilityLabel={summary}
      style={{ gap: 8 }}
    >
      <Label style={{ color: colors["ink-3"] }}>{title}</Label>
      <View
        style={{
          height,
          flexDirection: "row",
          alignItems: "flex-end",
          gap: 2,
          borderBottomWidth: 1,
          borderColor: colors.rule,
        }}
      >
        {columns.map((column, index) => (
          <View
            key={index}
            style={{
              flex: 1,
              height: "100%",
              justifyContent: "flex-end",
              flexDirection: "row",
            }}
          >
            {column.pauseBefore && (
              <View
                style={{
                  width: 2,
                  height: "100%",
                  backgroundColor: colors["ink-3"],
                }}
              />
            )}
            <View
              style={{ flex: 1, justifyContent: "flex-end", height: "100%" }}
            >
              {column.bpm === null ? (
                <View
                  style={{
                    height: 10,
                    borderWidth: 1,
                    borderStyle: "dashed",
                    borderColor: colors["ink-3"],
                  }}
                />
              ) : (
                <View
                  style={{
                    height: `${Math.max(4, ((column.bpm - floor) / span) * 100)}%`,
                    backgroundColor: colors["tide-mark"],
                    borderTopLeftRadius: 2,
                    borderTopRightRadius: 2,
                  }}
                />
              )}
            </View>
          </View>
        ))}
      </View>
      <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 16 }}>
        <Label style={{ color: colors["ink-2"] }}>▮ Pulse from sensor</Label>
        <Label style={{ color: colors["ink-2"] }}>┄ No pulse data</Label>
        {columns.some((column) => column.pauseBefore) && (
          <Label style={{ color: colors["ink-2"] }}>│ Pause</Label>
        )}
      </View>
    </View>
  );
}
