import type { PropsWithChildren } from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, Switch, Text, View, type TextProps } from 'react-native';
import { useTheme } from './Theme';
import { radius, spacing } from './tokens';
export function Label({ style, ...props }: TextProps) {
  const { colors } = useTheme();
  return <Text {...props} style={[{ color: colors.ink, fontSize: 16 }, style]} />;
}
export function Heading({ children }: PropsWithChildren) {
  return <Label accessibilityRole="header" style={{ fontSize: 28, fontWeight: '600' }}>{children}</Label>;
}
export function Card({ children }: PropsWithChildren) {
  const { colors } = useTheme();
  return <View style={[styles.card, { backgroundColor: colors.raised, borderColor: colors.rule }]}>{children}</View>;
}
export function Action({ title, onPress, disabled = false, secondary = false }: { title: string; onPress: () => void; disabled?: boolean; secondary?: boolean }) {
  const { colors } = useTheme();
  return <Pressable accessibilityRole="button" accessibilityState={{ disabled }} disabled={disabled} onPress={onPress}
    style={({ pressed }) => [styles.action, { borderColor: colors.edge, backgroundColor: secondary ? colors.raised : pressed ? colors['solid-press'] : colors.solid, opacity: disabled ? 0.5 : 1 }]}>
    <Label style={{ color: secondary ? colors.ink : colors['on-solid'], fontWeight: '600', textAlign: 'center' }}>{title}</Label>
  </Pressable>;
}
export function Toggle({ title, value, onChange, disabled = false }: { title: string; value: boolean; onChange: (value: boolean) => void; disabled?: boolean }) {
  const { colors } = useTheme();
  return <Pressable accessibilityRole="switch" accessibilityLabel={title} accessibilityState={{ checked: value, disabled }} disabled={disabled}
    onPress={() => onChange(!value)} style={styles.toggle}>
    <Label style={{ flex: 1 }}>{title}</Label>
    <View pointerEvents="none" accessible={false} importantForAccessibility="no-hide-descendants">
      <Switch accessible={false} value={value} disabled={disabled} trackColor={{ false: colors.edge, true: colors.solid }} />
    </View>
  </Pressable>;
}
export function Status({ title, detail }: { title: string; detail?: string }) {
  const { colors } = useTheme();
  return <View accessibilityLiveRegion="polite" style={{ gap: spacing.gap }}><Label style={{ color: colors.tide, fontWeight: '600' }}>{title}</Label>{detail && <Label>{detail}</Label>}</View>;
}
export function Dialog({ visible, title, children, onClose }: PropsWithChildren<{ visible: boolean; title: string; onClose: () => void }>) {
  const { colors } = useTheme();
  return <Modal visible={visible} transparent animationType="none" onRequestClose={onClose}>
    <View style={[styles.scrim, { backgroundColor: '#000000aa' }]}><ScrollView contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', padding: spacing.gutter }}>
      <View accessibilityViewIsModal style={[styles.card, { backgroundColor: colors.raised }]}><Heading>{title}</Heading>{children}<Action title="Close" onPress={onClose} /></View>
    </ScrollView></View>
  </Modal>;
}
const styles = StyleSheet.create({
  card: { padding: spacing.gutter, gap: 16, borderRadius: radius.large, borderWidth: 1 },
  action: { minHeight: spacing.tap, minWidth: spacing.tap, padding: 12, borderRadius: radius.medium, borderWidth: 1, justifyContent: 'center' },
  toggle: { minHeight: spacing.tap, minWidth: spacing.tap, flexDirection: 'row', alignItems: 'center', gap: spacing.gap },
  scrim: { flex: 1 },
});
