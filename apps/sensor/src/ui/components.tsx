import type { PropsWithChildren } from "react";
import {
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
  type TextProps,
  type TextStyle,
} from "react-native";
import { useInsets } from "./Insets";
import { useTheme } from "./Theme";
import { fonts, radius, spacing } from "./tokens";

/** Map weight to a bundled static face; do not fake weights with a missing face. */
function labelStyle(style: TextProps["style"]): TextStyle {
  const flat = (StyleSheet.flatten(style) ?? {}) as TextStyle;
  const weight = flat.fontWeight;
  const family =
    flat.fontFamily ??
    (weight === "700" || weight === 700 || weight === "bold"
      ? fonts.uiBold
      : weight === "600" || weight === 600
        ? fonts.uiSemibold
        : weight === "500" || weight === 500
          ? fonts.uiMedium
          : fonts.ui);
  const rest: TextStyle = { ...flat };
  delete rest.fontWeight;
  return { ...rest, fontFamily: family };
}

export function Label({ style, ...props }: TextProps) {
  const { colors } = useTheme();
  return (
    <Text
      {...props}
      style={[{ color: colors.ink, fontSize: 16 }, labelStyle(style)]}
    />
  );
}
export function Heading({ children }: PropsWithChildren) {
  return (
    <Label
      accessibilityRole="header"
      style={{ fontFamily: fonts.display, fontSize: 28 }}
    >
      {children}
    </Label>
  );
}
export function Card({ children }: PropsWithChildren) {
  const { colors } = useTheme();
  return (
    <View
      style={[
        styles.card,
        { backgroundColor: colors.raised, borderColor: colors.rule },
      ]}
    >
      {children}
    </View>
  );
}
export function Action({
  title,
  onPress,
  disabled = false,
  secondary = false,
}: {
  title: string;
  onPress: () => void;
  disabled?: boolean;
  secondary?: boolean;
}) {
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.action,
        {
          borderColor: colors.edge,
          backgroundColor: secondary
            ? colors.raised
            : pressed
              ? colors["solid-press"]
              : colors.solid,
          opacity: disabled ? 0.5 : 1,
        },
      ]}
    >
      <Label
        style={{
          color: secondary ? colors.ink : colors["on-solid"],
          fontWeight: "600",
          textAlign: "center",
        }}
      >
        {title}
      </Label>
    </Pressable>
  );
}
export function Toggle({
  title,
  value,
  onChange,
  disabled = false,
}: {
  title: string;
  value: boolean;
  onChange: (value: boolean) => void;
  disabled?: boolean;
}) {
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="switch"
      accessibilityLabel={title}
      accessibilityState={{ checked: value, disabled }}
      disabled={disabled}
      onPress={() => onChange(!value)}
      style={styles.toggle}
    >
      <Label style={{ flex: 1 }}>{title}</Label>
      <View
        pointerEvents="none"
        accessible={false}
        importantForAccessibility="no-hide-descendants"
      >
        <Switch
          accessible={false}
          value={value}
          disabled={disabled}
          trackColor={{ false: colors.edge, true: colors.solid }}
        />
      </View>
    </Pressable>
  );
}
export function Status({
  title,
  detail,
  live = false,
}: {
  title: string;
  detail?: string;
  live?: boolean;
}) {
  const { colors } = useTheme();
  return (
    <View
      accessible
      accessibilityLiveRegion={live ? "polite" : "none"}
      style={{ gap: spacing.gap }}
    >
      <Label style={{ color: colors.tide, fontWeight: "600" }}>{title}</Label>
      {detail && <Label>{detail}</Label>}
    </View>
  );
}
export function Dialog({
  visible,
  title,
  children,
  onClose,
  closeTitle = "Close",
}: PropsWithChildren<{
  visible: boolean;
  title: string;
  onClose: () => void;
  closeTitle?: string;
}>) {
  const { colors } = useTheme();
  const insets = useInsets();
  return (
    <Modal
      visible={visible}
      transparent
      animationType="none"
      onRequestClose={onClose}
    >
      <View style={[styles.scrim, { backgroundColor: "#000000aa" }]}>
        <ScrollView
          keyboardShouldPersistTaps="handled"
          contentContainerStyle={{
            flexGrow: 1,
            justifyContent: "center",
            padding: spacing.gutter,
            paddingTop: insets.top + spacing.gutter,
            paddingBottom: insets.bottom + spacing.gutter,
          }}
        >
          <View
            accessibilityViewIsModal
            style={[styles.card, { backgroundColor: colors.raised }]}
          >
            <Heading>{title}</Heading>
            {children}
            <Action title={closeTitle} secondary onPress={onClose} />
          </View>
        </ScrollView>
      </View>
    </Modal>
  );
}
export function Choice({
  title,
  detail,
  selected,
  onPress,
  disabled = false,
}: {
  title: string;
  detail?: string;
  selected: boolean;
  onPress: () => void;
  disabled?: boolean;
}) {
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="radio"
      accessibilityLabel={detail ? `${title}. ${detail}` : title}
      accessibilityState={{ checked: selected, disabled }}
      disabled={disabled}
      onPress={onPress}
      style={[
        styles.choice,
        {
          borderColor: selected ? colors.tide : colors.edge,
          backgroundColor: selected ? colors["tide-wash"] : colors.raised,
          opacity: disabled ? 0.5 : 1,
        },
      ]}
    >
      <Label style={{ fontWeight: selected ? "600" : "400" }}>
        {selected ? "✓ " : ""}
        {title}
      </Label>
      {detail && <Label style={{ color: colors["ink-2"] }}>{detail}</Label>}
    </Pressable>
  );
}
export function Detail({
  title,
  value,
  note,
}: {
  title: string;
  value: string;
  note?: string;
}) {
  const { colors } = useTheme();
  return (
    <View
      accessible
      accessibilityLabel={`${title}: ${value}${note ? `. ${note}` : ""}`}
      style={{ gap: spacing.unit }}
    >
      <Label style={{ color: colors["ink-3"], fontFamily: fonts.mono }}>
        {title}
      </Label>
      <Label style={{ fontSize: 20, fontWeight: "600" }}>{value}</Label>
      {note && <Label style={{ color: colors["ink-2"] }}>{note}</Label>}
    </View>
  );
}
export function Warning({ title, detail }: { title: string; detail?: string }) {
  const { colors } = useTheme();
  return (
    <View
      accessibilityRole="alert"
      style={[
        styles.warning,
        { backgroundColor: colors["ember-wash"], borderColor: colors.ember },
      ]}
    >
      <Label style={{ fontWeight: "600" }}>⚠ {title}</Label>
      {detail && <Label>{detail}</Label>}
    </View>
  );
}
export function NumberField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const { colors } = useTheme();
  return (
    <View style={{ flex: 1, minWidth: 120, gap: spacing.unit }}>
      <Label nativeID={label}>{label}</Label>
      <TextInput
        accessibilityLabel={label}
        accessibilityLabelledBy={label}
        keyboardType="number-pad"
        inputMode="numeric"
        maxLength={3}
        value={value}
        onChangeText={onChange}
        style={[
          styles.input,
          {
            color: colors.ink,
            borderColor: colors.edge,
            backgroundColor: colors.bg,
          },
        ]}
      />
    </View>
  );
}
export function TextField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const { colors } = useTheme();
  return (
    <View style={{ gap: spacing.unit }}>
      <Label nativeID={label}>{label}</Label>
      <TextInput
        accessibilityLabel={label}
        accessibilityLabelledBy={label}
        autoCapitalize="none"
        autoCorrect={false}
        value={value}
        onChangeText={onChange}
        style={[
          styles.input,
          {
            color: colors.ink,
            borderColor: colors.edge,
            backgroundColor: colors.bg,
          },
        ]}
      />
    </View>
  );
}
const styles = StyleSheet.create({
  choice: {
    minHeight: spacing.tap,
    padding: 12,
    gap: spacing.unit,
    borderWidth: 1,
    borderRadius: radius.small,
    justifyContent: "center",
  },
  warning: {
    padding: 16,
    gap: spacing.gap,
    borderWidth: 1,
    borderRadius: radius.medium,
  },
  input: {
    minHeight: spacing.tap,
    paddingHorizontal: 12,
    borderWidth: 1,
    borderRadius: radius.small,
    fontFamily: fonts.ui,
    fontSize: 18,
  },
  card: {
    padding: spacing.gutter,
    gap: 16,
    borderRadius: radius.large,
    borderWidth: 1,
  },
  action: {
    minHeight: spacing.tap,
    minWidth: spacing.tap,
    padding: 12,
    borderRadius: radius.medium,
    borderWidth: 1,
    justifyContent: "center",
  },
  toggle: {
    minHeight: spacing.tap,
    minWidth: spacing.tap,
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.gap,
  },
  scrim: { flex: 1 },
});
