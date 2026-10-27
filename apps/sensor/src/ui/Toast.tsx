import { StyleSheet, Text, View } from "react-native";
import { Icon, type IconName } from "./Icon";
import { useInsets } from "./Insets";
import { useTheme } from "./Theme";
import { fonts, spacing } from "./tokens";

/** Transient confirmation above the tab bar. Callers own its lifetime. */
export function Toast({
  message,
  icon = "check",
  visible = true,
}: {
  message: string;
  icon?: IconName;
  visible?: boolean;
}) {
  const { colors } = useTheme();
  const insets = useInsets();
  if (!visible) return null;
  return (
    <View
      accessibilityLiveRegion="polite"
      style={[
        styles.toast,
        {
          bottom: insets.bottom + 88,
          backgroundColor: colors.ink,
          shadowColor: colors.ink,
        },
      ]}
    >
      <Icon name={icon} size={20} color={colors.bg} />
      <Text style={[styles.text, { color: colors.bg }]}>{message}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  toast: {
    position: "absolute",
    left: spacing.gutter,
    right: spacing.gutter,
    minHeight: 48,
    paddingVertical: spacing.section / 2,
    paddingHorizontal: spacing.gutter * 0.8,
    borderRadius: 16,
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.section / 2,
    shadowOffset: { width: 0, height: 22 },
    shadowOpacity: 0.28,
    shadowRadius: 24,
    elevation: 12,
  },
  text: {
    flex: 1,
    fontFamily: fonts.ui,
    fontSize: 14,
    lineHeight: 20.3,
  },
});
