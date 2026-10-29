import type { PropsWithChildren, ReactNode } from "react";
import {
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
  type StyleProp,
  type TextProps,
  type TextStyle,
  type ViewStyle,
} from "react-native";
import { Icon, type IconName } from "./Icon";
import { Sheet } from "./Sheet";
import { useTheme } from "./Theme";
import { H1 } from "./Typography";
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
      style={[
        {
          color: colors.ink,
          fontFamily: fonts.ui,
          fontSize: 16,
          lineHeight: 24.8,
        },
        labelStyle(style),
      ]}
    />
  );
}

export function Heading({ children }: PropsWithChildren) {
  return <H1 accessibilityRole="header">{children}</H1>;
}

export type CardVariant = "default" | "flat" | "sunk" | "tide" | "pebble";

export function Card({
  children,
  variant = "default",
  style,
}: PropsWithChildren<{
  variant?: CardVariant;
  style?: StyleProp<ViewStyle>;
}>) {
  const { colors } = useTheme();
  const background =
    variant === "sunk"
      ? colors.sunk
      : variant === "tide"
        ? colors["tide-wash"]
        : colors.raised;
  const borderColor =
    variant === "sunk" || variant === "tide" ? "transparent" : colors.rule;
  return (
    <View
      style={[
        styles.card,
        variant !== "flat" &&
          variant !== "sunk" &&
          variant !== "tide" &&
          styles.cardShadow,
        variant === "pebble" && styles.cardPebble,
        { backgroundColor: background, borderColor },
        style,
      ]}
    >
      {children}
    </View>
  );
}

export type ActionVariant =
  | "primary"
  | "secondary"
  | "quiet"
  | "ink"
  | "danger";

export function Action({
  title,
  onPress,
  disabled = false,
  secondary = false,
  variant,
  size = "md",
  block = true,
  icon,
  style,
  accessibilityLabel,
}: {
  title: string;
  onPress: () => void;
  disabled?: boolean;
  /** Kept for callers from the first product screens. */
  secondary?: boolean;
  variant?: ActionVariant;
  size?: "sm" | "md" | "lg";
  block?: boolean;
  icon?: ReactNode;
  style?: StyleProp<ViewStyle>;
  accessibilityLabel?: string;
}) {
  const { colors } = useTheme();
  const kind = variant ?? (secondary ? "secondary" : "primary");
  const height = size === "lg" ? 56 : size === "sm" ? 40 : 48;
  const background =
    kind === "primary"
      ? colors.solid
      : kind === "ink"
        ? colors.ink
        : "transparent";
  const textColor =
    kind === "primary"
      ? colors["on-solid"]
      : kind === "ink"
        ? colors.bg
        : kind === "quiet"
          ? colors.tide
          : kind === "danger"
            ? colors.ember
            : colors.ink;
  const borderColor =
    kind === "secondary"
      ? colors.edge
      : kind === "danger"
        ? colors.ember
        : "transparent";
  const pressedBackground =
    kind === "primary"
      ? colors["solid-press"]
      : kind === "secondary"
        ? colors.sunk
        : kind === "quiet"
          ? colors["tide-wash"]
          : kind === "ink"
            ? colors["ink-2"]
            : colors["ember-wash"];
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel ?? title}
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.action,
        {
          minHeight: height,
          backgroundColor: pressed ? pressedBackground : background,
          borderColor,
          borderWidth: kind === "quiet" ? 0 : 1.5,
          paddingHorizontal:
            kind === "quiet" ? spacing.section / 2 : size === "sm" ? 16 : 24,
          opacity: disabled ? 0.45 : 1,
          transform: pressed ? [{ scale: 0.98 }] : undefined,
        },
        block ? styles.actionBlock : styles.actionInline,
        style,
      ]}
    >
      {icon}
      <Text
        style={{
          color: textColor,
          fontFamily: fonts.uiSemibold,
          fontSize: size === "lg" ? 17 : size === "sm" ? 14 : 16,
          lineHeight: size === "lg" ? 18.7 : size === "sm" ? 15.4 : 17.6,
          textAlign: "center",
        }}
      >
        {title}
      </Text>
    </Pressable>
  );
}

export function Toggle({
  title,
  meta,
  lead,
  value,
  onChange,
  disabled = false,
  showState = true,
  divider = false,
}: {
  title: string;
  meta?: string;
  lead?: ReactNode;
  value: boolean;
  onChange: (value: boolean) => void;
  disabled?: boolean;
  showState?: boolean;
  divider?: boolean;
}) {
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="switch"
      accessibilityLabel={meta ? `${title}. ${meta}` : title}
      accessibilityState={{ checked: value, disabled }}
      disabled={disabled}
      onPress={() => onChange(!value)}
      style={[
        styles.toggle,
        {
          borderBottomColor: colors.rule,
          borderBottomWidth: divider ? 1 : 0,
        },
      ]}
    >
      {lead && (
        <View style={[styles.rowLead, { backgroundColor: colors.sunk }]}>
          {lead}
        </View>
      )}
      <View style={styles.toggleBody}>
        <Label style={{ fontFamily: fonts.uiMedium }}>{title}</Label>
        {meta && (
          <Label
            style={{ color: colors["ink-3"], fontSize: 14, lineHeight: 20.3 }}
          >
            {meta}
          </Label>
        )}
      </View>
      {showState && (
        <Label
          style={{
            color: colors["ink-2"],
            fontFamily: fonts.uiMedium,
            fontSize: 14,
            lineHeight: 17,
          }}
        >
          {value ? "On" : "Off"}
        </Label>
      )}
      <View
        style={[
          styles.switchTrack,
          {
            backgroundColor: value ? colors.solid : colors.sunk,
            borderColor: colors.edge,
            borderWidth: value ? 0 : 1.5,
          },
        ]}
      >
        <View
          style={[
            styles.switchThumb,
            {
              backgroundColor: value ? colors["on-solid"] : colors["ink-3"],
              transform: [{ translateX: value ? 22 : 0 }],
            },
          ]}
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
      <Label style={{ color: colors.tide, fontFamily: fonts.uiSemibold }}>
        {title}
      </Label>
      {detail && <Label style={{ color: colors["ink-2"] }}>{detail}</Label>}
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
  return (
    <Sheet
      visible={visible}
      title={title}
      onClose={onClose}
      footer={
        <Action title={closeTitle} variant="secondary" onPress={onClose} />
      }
    >
      {children}
    </Sheet>
  );
}

export function Choice({
  title,
  detail,
  selected,
  onPress,
  disabled = false,
  kind = "radio",
}: {
  title: string;
  detail?: string;
  selected: boolean;
  onPress: () => void;
  disabled?: boolean;
  kind?: "radio" | "checkbox";
}) {
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole={kind}
      accessibilityLabel={detail ? `${title}. ${detail}` : title}
      accessibilityState={{ checked: selected, disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.choice,
        { opacity: disabled ? 0.45 : pressed ? 0.8 : 1 },
      ]}
    >
      <View
        style={[
          styles.choiceIndicator,
          {
            borderRadius: kind === "radio" ? 12 : 7,
            borderColor: selected ? colors.solid : colors.edge,
            backgroundColor: selected ? colors.solid : colors.raised,
          },
        ]}
      >
        {selected &&
          (kind === "radio" ? (
            <View
              style={{
                width: 10,
                height: 10,
                borderRadius: 5,
                backgroundColor: colors["on-solid"],
              }}
            />
          ) : (
            <Icon name="check" size={16} color={colors["on-solid"]} />
          ))}
      </View>
      <View style={{ flex: 1, gap: 2 }}>
        <Label style={{ fontFamily: fonts.uiMedium }}>{title}</Label>
        {detail && (
          <Label
            style={{
              color: colors["ink-3"],
              fontSize: 14,
              lineHeight: 20.3,
            }}
          >
            {detail}
          </Label>
        )}
      </View>
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
      style={{ gap: spacing.unit / 2 }}
    >
      <Label
        style={{
          color: colors["ink-3"],
          fontSize: 14,
          lineHeight: 20.3,
        }}
      >
        {title}
      </Label>
      <Text
        style={{
          color: colors.ink,
          fontFamily: fonts.uiSemibold,
          fontSize: 30,
          lineHeight: 33,
          letterSpacing: -0.6,
        }}
      >
        {value}
      </Text>
      {note && (
        <Label
          style={{ color: colors["ink-3"], fontSize: 14, lineHeight: 20.3 }}
        >
          {note}
        </Label>
      )}
    </View>
  );
}

export type BannerTone = "neutral" | "tide" | "ember" | "dusk";

export function Banner({
  title,
  detail,
  tone = "neutral",
  icon = "info",
  action,
  live = false,
}: {
  title: string;
  detail?: string;
  tone?: BannerTone;
  icon?: IconName;
  action?: ReactNode;
  live?: boolean;
}) {
  const { colors } = useTheme();
  const background =
    tone === "tide"
      ? colors["tide-wash"]
      : tone === "ember"
        ? colors["ember-wash"]
        : tone === "dusk"
          ? colors["dusk-wash"]
          : colors.sunk;
  const iconColor =
    tone === "tide"
      ? colors.tide
      : tone === "ember"
        ? colors.ember
        : tone === "dusk"
          ? colors.dusk
          : colors["ink-2"];
  return (
    <View
      accessibilityLiveRegion={live ? "polite" : "none"}
      style={[styles.banner, { backgroundColor: background }]}
    >
      <Icon name={icon} size={20} color={iconColor} />
      <View style={{ flex: 1, gap: spacing.unit }}>
        <Label style={{ fontFamily: fonts.uiSemibold }}>{title}</Label>
        {detail && (
          <Label
            style={{ color: colors["ink-2"], fontSize: 14, lineHeight: 20.3 }}
          >
            {detail}
          </Label>
        )}
        {action}
      </View>
    </View>
  );
}

export function Warning({ title, detail }: { title: string; detail?: string }) {
  return (
    <View accessibilityRole="alert">
      <Banner title={title} detail={detail} tone="ember" icon="attention" />
    </View>
  );
}

export function Chip({
  label,
  tone = "neutral",
  icon,
  style,
}: {
  label: string;
  tone?: "neutral" | "tide" | "ember" | "dusk" | "outline";
  icon?: IconName;
  style?: StyleProp<ViewStyle>;
}) {
  const { colors } = useTheme();
  const background =
    tone === "tide"
      ? colors["tide-wash"]
      : tone === "ember"
        ? colors["ember-wash"]
        : tone === "dusk"
          ? colors["dusk-wash"]
          : tone === "outline"
            ? "transparent"
            : colors.sunk;
  const color =
    tone === "tide"
      ? colors.tide
      : tone === "ember"
        ? colors.ember
        : tone === "dusk"
          ? colors.dusk
          : colors["ink-2"];
  return (
    <View
      style={[
        styles.chip,
        {
          backgroundColor: background,
          borderWidth: tone === "outline" ? 1 : 0,
          borderColor: colors.edge,
        },
        style,
      ]}
    >
      {icon && <Icon name={icon} size={16} color={color} />}
      <Label
        style={{
          color,
          fontFamily: fonts.uiMedium,
          fontSize: 14,
          lineHeight: 17,
        }}
      >
        {label}
      </Label>
    </View>
  );
}

export function Mark({
  label,
  icon,
  color,
  style,
}: {
  label: string;
  icon?: IconName;
  color?: string;
  style?: StyleProp<ViewStyle>;
}) {
  const { colors } = useTheme();
  const tint = color ?? colors["ink-3"];
  return (
    <View style={[styles.mark, style]}>
      {icon && <Icon name={icon} size={15} color={tint} />}
      <Label
        style={{
          color: tint,
          fontFamily: fonts.mono,
          fontSize: 12.5,
          lineHeight: 16.3,
        }}
      >
        {label}
      </Label>
    </View>
  );
}

export function LiveDot({ active = true }: { active?: boolean }) {
  const { colors } = useTheme();
  return (
    <View
      style={{
        width: 16,
        height: 16,
        borderRadius: 8,
        alignItems: "center",
        justifyContent: "center",
        backgroundColor: active ? colors["tide-wash"] : "transparent",
      }}
    >
      <View
        style={{
          width: 10,
          height: 10,
          borderRadius: 5,
          backgroundColor: active ? colors["tide-mark"] : "transparent",
          borderWidth: active ? 0 : 2,
          borderColor: colors["ink-3"],
        }}
      />
    </View>
  );
}

export function KeyValue({ label, value }: { label: string; value: string }) {
  const { colors } = useTheme();
  return (
    <View
      accessible
      accessibilityLabel={`${label}: ${value}`}
      style={{
        flexDirection: "row",
        alignItems: "baseline",
        gap: spacing.section,
      }}
    >
      <Label
        style={{
          flex: 1,
          color: colors["ink-3"],
          fontSize: 14,
          lineHeight: 20.3,
        }}
      >
        {label}
      </Label>
      <Label style={{ fontFamily: fonts.uiMedium, textAlign: "right" }}>
        {value}
      </Label>
    </View>
  );
}

export function Row({
  title,
  meta,
  lead,
  end,
  onPress,
  disabled = false,
  last = false,
  accessibilityLabel,
}: {
  title: string;
  meta?: string;
  lead?: ReactNode;
  end?: ReactNode;
  onPress?: () => void;
  disabled?: boolean;
  last?: boolean;
  accessibilityLabel?: string;
}) {
  const { colors } = useTheme();
  const content = (
    <>
      {lead && (
        <View style={[styles.rowLead, { backgroundColor: colors.sunk }]}>
          {lead}
        </View>
      )}
      <View style={styles.rowBody}>
        <Label style={{ fontFamily: fonts.uiMedium }}>{title}</Label>
        {meta && (
          <Label
            style={{ color: colors["ink-3"], fontSize: 14, lineHeight: 20.3 }}
          >
            {meta}
          </Label>
        )}
      </View>
      {end}
    </>
  );
  const rowStyle = [
    styles.row,
    {
      borderBottomColor: colors.rule,
      borderBottomWidth: last ? 0 : 1,
    },
  ];
  if (onPress)
    return (
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={accessibilityLabel ?? title}
        accessibilityState={{ disabled }}
        disabled={disabled}
        onPress={onPress}
        style={({ pressed }) => [
          ...rowStyle,
          {
            opacity: disabled ? 0.45 : 1,
            backgroundColor: pressed ? colors.sunk : "transparent",
          },
        ]}
      >
        {content}
      </Pressable>
    );
  return <View style={rowStyle}>{content}</View>;
}

export function Segmented({
  options,
  value,
  onChange,
  disabled = false,
}: {
  options: readonly { value: string; label: string }[];
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
}) {
  const { colors } = useTheme();
  return (
    <View
      accessibilityRole="radiogroup"
      style={[styles.segmented, { backgroundColor: colors.sunk }]}
    >
      {options.map((option) => {
        const selected = option.value === value;
        return (
          <Pressable
            key={option.value}
            accessibilityRole="radio"
            accessibilityState={{ selected, disabled }}
            disabled={disabled}
            onPress={() => onChange(option.value)}
            style={[
              styles.segment,
              selected && {
                backgroundColor: colors.raised,
                shadowColor: colors.ink,
                shadowOpacity: 0.08,
                shadowRadius: 10,
                shadowOffset: { width: 0, height: 6 },
                elevation: 1,
              },
            ]}
          >
            <Text
              style={{
                color: selected ? colors.ink : colors["ink-2"],
                fontFamily: fonts.uiMedium,
                fontSize: 14,
                lineHeight: 17,
              }}
            >
              {option.label}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

export function NumberField({
  label,
  value,
  onChange,
  unit,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  unit?: string;
}) {
  const { colors } = useTheme();
  return (
    <View style={styles.field}>
      <Label
        nativeID={label}
        style={{ fontFamily: fonts.uiSemibold, fontSize: 14, lineHeight: 20 }}
      >
        {label}
      </Label>
      <View
        style={[
          styles.inputUnit,
          {
            borderColor: colors.edge,
            backgroundColor: colors.raised,
          },
        ]}
      >
        <TextInput
          accessibilityLabel={unit ? `${label}, ${unit}` : label}
          accessibilityLabelledBy={label}
          keyboardType="number-pad"
          inputMode="numeric"
          maxLength={3}
          value={value}
          onChangeText={onChange}
          style={[styles.inputUnitField, { color: colors.ink }]}
        />
        {unit && (
          <Text style={[styles.inputUnitLabel, { color: colors["ink-3"] }]}>
            {unit}
          </Text>
        )}
      </View>
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
    <View style={styles.field}>
      <Label
        nativeID={label}
        style={{ fontFamily: fonts.uiSemibold, fontSize: 14, lineHeight: 20 }}
      >
        {label}
      </Label>
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
            backgroundColor: colors.raised,
          },
        ]}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    padding: spacing.gutter,
    gap: spacing.gutter * 0.8,
    borderRadius: radius.large,
    borderWidth: 1,
  },
  cardPebble: {
    borderBottomLeftRadius: radius.small,
  },
  cardShadow: {
    shadowColor: "#16211f",
    shadowOffset: { width: 0, height: 8 },
    shadowOpacity: 0.08,
    shadowRadius: 12,
    elevation: 2,
  },
  action: {
    minWidth: spacing.tap,
    borderRadius: 999,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: spacing.gap,
  },
  actionBlock: { width: "100%" },
  actionInline: { alignSelf: "flex-start" },
  toggle: {
    minHeight: 56,
    paddingVertical: spacing.section / 2,
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.section,
  },
  toggleBody: { flex: 1, minWidth: 0, gap: 2 },
  switchTrack: {
    width: 52,
    height: 30,
    borderRadius: 15,
    justifyContent: "center",
  },
  switchThumb: {
    width: 22,
    height: 22,
    borderRadius: 11,
    marginLeft: 3,
  },
  choice: {
    minHeight: spacing.tap,
    paddingVertical: spacing.section / 2,
    flexDirection: "row",
    alignItems: "flex-start",
    gap: spacing.section / 2,
  },
  choiceIndicator: {
    width: 24,
    height: 24,
    borderWidth: 1.5,
    alignItems: "center",
    justifyContent: "center",
    marginTop: 1,
    flexShrink: 0,
  },
  banner: {
    paddingVertical: spacing.section / 2,
    paddingHorizontal: spacing.gutter * 0.8,
    borderRadius: radius.medium,
    flexDirection: "row",
    alignItems: "flex-start",
    gap: spacing.section / 2,
  },
  chip: {
    minHeight: 30,
    paddingVertical: spacing.unit / 2,
    paddingHorizontal: spacing.section / 2,
    borderRadius: 999,
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.unit + 2,
    alignSelf: "flex-start",
  },
  mark: {
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.unit + 2,
  },
  row: {
    minHeight: 56,
    paddingVertical: spacing.section / 2,
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.section,
  },
  rowLead: {
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: "center",
    justifyContent: "center",
    flexShrink: 0,
  },
  rowBody: {
    flex: 1,
    minWidth: 0,
    gap: 2,
  },
  segmented: {
    padding: spacing.unit,
    borderRadius: 999,
    flexDirection: "row",
    alignSelf: "stretch",
  },
  segment: {
    flex: 1,
    minHeight: 40,
    paddingHorizontal: spacing.section,
    borderRadius: 999,
    alignItems: "center",
    justifyContent: "center",
  },
  field: {
    flex: 1,
    minWidth: 120,
    gap: spacing.gap,
  },
  input: {
    minHeight: 52,
    paddingHorizontal: spacing.section,
    borderWidth: 1.5,
    borderRadius: radius.medium,
    fontFamily: fonts.ui,
    fontSize: 17,
    lineHeight: 22,
  },
  inputUnit: {
    minHeight: 52,
    paddingHorizontal: spacing.section,
    borderWidth: 1.5,
    borderRadius: radius.medium,
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.gap,
  },
  inputUnitField: {
    flex: 1,
    paddingVertical: 0,
    fontFamily: fonts.ui,
    fontSize: 17,
    lineHeight: 22,
  },
  inputUnitLabel: {
    fontFamily: fonts.mono,
    fontSize: 14,
    lineHeight: 18.2,
  },
});
