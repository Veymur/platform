import type { PropsWithChildren, ReactNode } from "react";
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
  type StyleProp,
  type TextStyle,
  type ViewStyle,
} from "react-native";
import { Grain } from "./Grain";
import { Icon, type IconName } from "./Icon";
import { useInsets } from "./Insets";
import { useTheme } from "./Theme";
import { fonts, spacing } from "./tokens";

type ScreenProps = PropsWithChildren<{
  appBar?: ReactNode;
  actions?: ReactNode;
  scroll?: boolean;
  contentContainerStyle?: StyleProp<ViewStyle>;
  style?: StyleProp<ViewStyle>;
}>;

/**
 * Fixed screen chrome: app bar, scrolling body and bottom actions.
 * The tab bar is rendered by the application shell below each screen.
 */
export function Screen({
  appBar,
  actions,
  scroll = true,
  contentContainerStyle,
  style,
  children,
}: ScreenProps) {
  const { colors } = useTheme();
  return (
    <View style={[styles.screen, { backgroundColor: colors.bg }, style]}>
      <Grain />
      {appBar}
      {scroll ? (
        <ScrollView
          keyboardShouldPersistTaps="handled"
          showsVerticalScrollIndicator={false}
          contentContainerStyle={[styles.content, contentContainerStyle]}
        >
          {children}
        </ScrollView>
      ) : (
        <View
          style={[styles.content, styles.staticContent, contentContainerStyle]}
        >
          {children}
        </View>
      )}
      {actions && <ScreenActions>{actions}</ScreenActions>}
    </View>
  );
}

export function AppBar({
  title,
  left,
  right,
  titleStyle,
}: {
  title: ReactNode;
  left?: ReactNode;
  right?: ReactNode;
  titleStyle?: StyleProp<TextStyle>;
}) {
  const { colors } = useTheme();
  return (
    <View style={styles.appBar}>
      {left}
      {typeof title === "string" ? (
        <Text
          accessibilityRole="header"
          numberOfLines={1}
          style={[styles.appBarTitle, { color: colors.ink }, titleStyle]}
        >
          {title}
        </Text>
      ) : (
        <View style={styles.appBarTitleNode}>{title}</View>
      )}
      {right}
    </View>
  );
}

export function BackButton({
  onPress,
  label = "Back",
}: {
  onPress: () => void;
  label?: string;
}) {
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      style={({ pressed }) => [
        styles.backButton,
        {
          borderColor: colors.rule,
          backgroundColor: pressed ? colors.sunk : colors.raised,
        },
      ]}
    >
      <Icon name="chevron-left" size={22} color={colors.ink} />
    </Pressable>
  );
}

export function IconButton({
  icon,
  label,
  onPress,
}: {
  icon: IconName;
  label: string;
  onPress: () => void;
}) {
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      style={({ pressed }) => [
        styles.backButton,
        {
          borderColor: colors.rule,
          backgroundColor: pressed ? colors.sunk : colors.raised,
        },
      ]}
    >
      <Icon name={icon} size={20} color={colors.ink} />
    </Pressable>
  );
}

export function ScreenActions({ children }: PropsWithChildren) {
  const { colors } = useTheme();
  const insets = useInsets();
  return (
    <View
      style={[
        styles.actions,
        {
          backgroundColor: colors.bg,
          paddingBottom: insets.bottom + spacing.gutter,
        },
      ]}
    >
      {children}
    </View>
  );
}

export type TabBarItem = {
  key: string;
  label: string;
  active: boolean;
  onPress: () => void;
  icon?: ReactNode;
  accessibilityLabel?: string;
};

export function TabBar({ items }: { items: readonly TabBarItem[] }) {
  const { colors } = useTheme();
  const insets = useInsets();
  return (
    <View
      accessibilityRole="tablist"
      style={[
        styles.tabBar,
        {
          backgroundColor: colors.raised,
          borderTopColor: colors.rule,
          paddingBottom: insets.bottom + spacing.unit,
        },
      ]}
    >
      {items.map((item) => (
        <Pressable
          key={item.key}
          accessibilityRole="tab"
          accessibilityState={{ selected: item.active }}
          accessibilityLabel={item.accessibilityLabel ?? item.label}
          onPress={item.onPress}
          style={styles.tabItem}
        >
          {item.icon && (
            <View
              style={[
                styles.tabIcon,
                item.active && { backgroundColor: colors["tide-wash"] },
              ]}
            >
              {item.icon}
            </View>
          )}
          <Text
            style={[
              styles.tabLabel,
              {
                color: item.active ? colors.tide : colors["ink-3"],
                fontFamily: item.active ? fonts.uiSemibold : fonts.uiMedium,
              },
            ]}
          >
            {item.label}
          </Text>
        </Pressable>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  appBar: {
    minHeight: 56,
    paddingVertical: spacing.unit,
    paddingHorizontal: spacing.gutter,
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.gap + spacing.unit,
  },
  appBarTitle: {
    flex: 1,
    fontFamily: fonts.displayMedium,
    fontSize: 26,
    lineHeight: 29,
    letterSpacing: -0.4,
  },
  appBarTitleNode: { flex: 1 },
  backButton: {
    width: spacing.tap,
    height: spacing.tap,
    borderRadius: spacing.tap / 2,
    borderWidth: 1.5,
    alignItems: "center",
    justifyContent: "center",
  },
  content: {
    paddingTop: spacing.gap,
    paddingHorizontal: spacing.gutter,
    paddingBottom: spacing.section + spacing.gap,
    gap: spacing.gutter,
  },
  staticContent: { flex: 1 },
  actions: {
    paddingTop: spacing.gap + spacing.unit,
    paddingHorizontal: spacing.gutter,
    gap: spacing.gap,
  },
  tabBar: {
    flexDirection: "row",
    borderTopWidth: 1,
    paddingTop: spacing.unit,
    paddingHorizontal: spacing.gap,
  },
  tabItem: {
    flex: 1,
    minHeight: 60,
    alignItems: "center",
    justifyContent: "center",
    gap: 2,
  },
  tabIcon: {
    width: 56,
    height: 30,
    borderRadius: 15,
    alignItems: "center",
    justifyContent: "center",
  },
  tabLabel: {
    fontSize: 12.5,
    lineHeight: 17,
    textAlign: "center",
  },
});
