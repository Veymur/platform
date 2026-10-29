import {
  Text,
  useWindowDimensions,
  type StyleProp,
  type TextProps,
  type TextStyle,
} from "react-native";
import { useTheme } from "./Theme";
import { fonts } from "./tokens";

type TextVariantProps = TextProps & { style?: StyleProp<TextStyle> };

/** Design h1: Bricolage 460, clamp(34px, 6cqw + 16px, 56px). */
export function H1({ style, ...props }: TextVariantProps) {
  const { colors } = useTheme();
  const { width } = useWindowDimensions();
  const size = Math.min(56, Math.max(34, width * 0.06 + 16));
  return (
    <Text
      {...props}
      style={[
        {
          color: colors.ink,
          fontFamily: fonts.display,
          fontSize: size,
          lineHeight: size * 1.04,
          letterSpacing: size * -0.022,
        },
        style,
      ]}
    />
  );
}

/** Design h2: Bricolage 440, clamp(24px, 3cqw + 16px, 32px). */
export function H2({ style, ...props }: TextVariantProps) {
  const { colors } = useTheme();
  const { width } = useWindowDimensions();
  const size = Math.min(32, Math.max(24, width * 0.03 + 16));
  return (
    <Text
      {...props}
      style={[
        {
          color: colors.ink,
          fontFamily: fonts.displayMedium,
          fontSize: size,
          lineHeight: size * 1.14,
          letterSpacing: size * -0.016,
        },
        style,
      ]}
    />
  );
}

export function H3({ style, ...props }: TextVariantProps) {
  const { colors } = useTheme();
  return (
    <Text
      {...props}
      style={[
        {
          color: colors.ink,
          fontFamily: fonts.uiSemibold,
          fontSize: 20,
          lineHeight: 26,
          letterSpacing: -0.16,
        },
        style,
      ]}
    />
  );
}

export function H4({ style, ...props }: TextVariantProps) {
  const { colors } = useTheme();
  return (
    <Text
      {...props}
      style={[
        {
          color: colors.ink,
          fontFamily: fonts.uiSemibold,
          fontSize: 16,
          lineHeight: 22.4,
        },
        style,
      ]}
    />
  );
}

export function Eyebrow({ style, ...props }: TextVariantProps) {
  const { colors } = useTheme();
  return (
    <Text
      {...props}
      style={[
        {
          color: colors["ink-3"],
          fontFamily: fonts.monoMedium,
          fontSize: 12,
          lineHeight: 16.8,
          letterSpacing: 1.44,
          textTransform: "uppercase",
        },
        style,
      ]}
    />
  );
}

export function Lede({ style, ...props }: TextVariantProps) {
  const { colors } = useTheme();
  return (
    <Text
      {...props}
      style={[
        {
          color: colors["ink-2"],
          fontFamily: fonts.ui,
          fontSize: 17,
          lineHeight: 25.5,
        },
        style,
      ]}
    />
  );
}

export function Body({ style, ...props }: TextVariantProps) {
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
        style,
      ]}
    />
  );
}

export function Small({ style, ...props }: TextVariantProps) {
  const { colors } = useTheme();
  return (
    <Text
      {...props}
      style={[
        {
          color: colors.ink,
          fontFamily: fonts.ui,
          fontSize: 14,
          lineHeight: 20.3,
        },
        style,
      ]}
    />
  );
}

export function Caption({ style, ...props }: TextVariantProps) {
  const { colors } = useTheme();
  return (
    <Text
      {...props}
      style={[
        {
          color: colors["ink-3"],
          fontFamily: fonts.ui,
          fontSize: 12.5,
          lineHeight: 17.5,
        },
        style,
      ]}
    />
  );
}

export function Mono({
  style,
  size = 14,
  ...props
}: TextVariantProps & { size?: number }) {
  const { colors } = useTheme();
  return (
    <Text
      {...props}
      style={[
        {
          color: colors.ink,
          fontFamily: fonts.mono,
          fontSize: size,
          lineHeight: size * 1.3,
          fontVariant: ["tabular-nums"],
        },
        style,
      ]}
    />
  );
}

export function Unit({
  style,
  size = 12,
  ...props
}: TextVariantProps & { size?: number }) {
  const { colors } = useTheme();
  return (
    <Text
      {...props}
      style={[
        {
          color: colors["ink-3"],
          fontFamily: fonts.mono,
          fontSize: size,
          lineHeight: size * 1.3,
          letterSpacing: size * 0.02,
        },
        style,
      ]}
    />
  );
}

/** Large live pulse numeral; the face changes with data freshness. */
export function PulseNumber({
  state = "fresh",
  style,
  ...props
}: TextVariantProps & { state?: "fresh" | "stale" }) {
  const { colors } = useTheme();
  const { width } = useWindowDimensions();
  const size = Math.min(144, Math.max(80, width * 0.3));
  return (
    <Text
      {...props}
      style={[
        {
          color: state === "stale" ? colors["ink-3"] : colors.ink,
          fontFamily:
            state === "fresh" ? fonts.displayPulse : fonts.displayThin,
          fontSize: size,
          lineHeight: size * 0.92,
          letterSpacing: size * -0.02,
          fontVariant: ["tabular-nums"],
        },
        style,
      ]}
    />
  );
}
