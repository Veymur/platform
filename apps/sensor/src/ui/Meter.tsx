import { StyleSheet, Text, View } from "react-native";
import { useTheme } from "./Theme";
import { fonts, spacing } from "./tokens";

export type MeterTone = "tide" | "ember" | "dusk";

export function Meter({
  segments,
  label,
}: {
  segments: readonly { value: number; tone: MeterTone }[];
  label?: string;
}) {
  const { colors } = useTheme();
  return (
    <View
      style={[styles.meter, { backgroundColor: colors.raised }]}
      accessibilityRole="image"
      accessibilityLabel={label}
    >
      {segments
        .filter((segment) => segment.value > 0)
        .map((segment, index) => (
          <View
            key={`${segment.tone}-${index}`}
            style={{
              flex: Math.max(segment.value, 1),
              minWidth: 4,
              backgroundColor:
                segment.tone === "tide"
                  ? colors["tide-mark"]
                  : segment.tone === "ember"
                    ? colors["ember-mark"]
                    : colors["dusk-mark"],
            }}
          />
        ))}
    </View>
  );
}

export function Legend({
  items,
}: {
  items: readonly {
    label: string;
    value?: string;
    tone: MeterTone | "gap";
  }[];
}) {
  const { colors } = useTheme();
  return (
    <View style={styles.legend}>
      {items.map((item) => (
        <View key={item.label} style={styles.legendItem}>
          <View
            style={[
              styles.swatch,
              item.tone === "gap"
                ? { borderWidth: 1, borderColor: colors["ink-3"] }
                : {
                    backgroundColor:
                      item.tone === "tide"
                        ? colors["tide-mark"]
                        : item.tone === "ember"
                          ? colors["ember-mark"]
                          : colors["dusk-mark"],
                  },
            ]}
          />
          <Text style={[styles.legendText, { color: colors["ink-2"] }]}>
            {item.label}
            {item.value ? (
              <Text style={{ fontFamily: fonts.monoMedium }}>
                {" "}
                {item.value}
              </Text>
            ) : null}
          </Text>
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  meter: {
    height: 12,
    borderRadius: 999,
    overflow: "hidden",
    flexDirection: "row",
    gap: 2,
  },
  legend: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: spacing.gap,
    columnGap: spacing.section,
  },
  legendItem: {
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.unit + 2,
  },
  swatch: {
    width: 12,
    height: 12,
    borderRadius: 3,
  },
  legendText: {
    fontFamily: fonts.ui,
    fontSize: 14,
    lineHeight: 20.3,
  },
});
