import {
  useEffect,
  useRef,
  type PropsWithChildren,
  type ReactNode,
} from "react";
import {
  Animated,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  View,
  type StyleProp,
  type ViewStyle,
} from "react-native";
import { useInsets } from "./Insets";
import { useTheme } from "./Theme";
import { H2 } from "./Typography";
import { useReducedMotion } from "./useReducedMotion";
import { spacing } from "./tokens";

export function Sheet({
  visible,
  title,
  children,
  onClose,
  footer,
  contentStyle,
}: PropsWithChildren<{
  visible: boolean;
  title: string;
  onClose: () => void;
  footer?: ReactNode;
  contentStyle?: StyleProp<ViewStyle>;
}>) {
  const { colors } = useTheme();
  const insets = useInsets();
  const reduceMotion = useReducedMotion();
  const progress = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    if (!visible) return;
    if (reduceMotion) {
      progress.setValue(1);
      return;
    }
    progress.setValue(0);
    Animated.timing(progress, {
      toValue: 1,
      duration: 260,
      useNativeDriver: true,
    }).start();
  }, [progress, reduceMotion, visible]);
  return (
    <Modal
      visible={visible}
      transparent
      animationType="none"
      statusBarTranslucent
      onRequestClose={onClose}
    >
      <View style={styles.layer}>
        <Animated.View
          pointerEvents="none"
          style={[
            styles.scrim,
            {
              backgroundColor: colors.scrim,
              opacity: progress,
            },
          ]}
        />
        <Pressable
          accessible={false}
          onPress={onClose}
          style={styles.dismissArea}
        />
        <Animated.View
          accessibilityViewIsModal
          style={[
            styles.sheet,
            {
              backgroundColor: colors.raised,
              paddingBottom: insets.bottom + spacing.section,
              opacity: progress,
              transform: [
                {
                  translateY: progress.interpolate({
                    inputRange: [0, 1],
                    outputRange: [24, 0],
                  }),
                },
              ],
            },
          ]}
        >
          <View style={[styles.grip, { backgroundColor: colors.rule }]} />
          <ScrollView
            keyboardShouldPersistTaps="handled"
            showsVerticalScrollIndicator={false}
            style={styles.scroll}
            contentContainerStyle={[styles.content, contentStyle]}
          >
            <H2
              accessibilityRole="header"
              style={{ fontSize: 24, lineHeight: 27 }}
            >
              {title}
            </H2>
            {children}
          </ScrollView>
          {footer && <View style={styles.footer}>{footer}</View>}
        </Animated.View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  layer: {
    flex: 1,
    justifyContent: "flex-end",
  },
  scrim: {
    position: "absolute",
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
  },
  dismissArea: {
    position: "absolute",
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
  },
  sheet: {
    maxHeight: "90%",
    borderTopLeftRadius: 28,
    borderTopRightRadius: 28,
    paddingTop: spacing.unit * 3,
    paddingHorizontal: spacing.gutter,
    shadowColor: "#16211f",
    shadowOffset: { width: 0, height: 22 },
    shadowOpacity: 0.28,
    shadowRadius: 24,
    elevation: 16,
  },
  grip: {
    width: 40,
    height: 4,
    borderRadius: 2,
    alignSelf: "center",
    marginBottom: spacing.section - spacing.unit,
  },
  scroll: { flexShrink: 1 },
  content: {
    gap: spacing.gutter,
    paddingBottom: spacing.gap,
  },
  footer: {
    paddingTop: spacing.gap,
    gap: spacing.gap,
  },
});
