import { useState } from "react";
import { Alert, Modal, StyleSheet, View } from "react-native";
import { Action, Card, Chip, Row } from "../../ui/components";
import { Icon } from "../../ui/Icon";
import { AppBar, Screen } from "../../ui/Screen";
import { useInsets } from "../../ui/Insets";
import { useTheme } from "../../ui/Theme";
import { Caption, H3 } from "../../ui/Typography";
import { useProduct } from "../../product/ProductContext";
import { Muted, rangesSummary, SignInDialog, StorageDialog } from "./shared";
import { ProfileAppearance } from "./ProfileAppearance";

export function ProfileScreen({
  onEditRanges,
  onOpenDiagnostics,
}: {
  onEditRanges: () => void;
  onOpenDiagnostics: () => void;
}) {
  const { colors, isDark } = useTheme();
  const insets = useInsets();
  const { preferences } = useProduct();
  const settings = preferences.preferences?.settings ?? null;
  const [appearanceVisible, setAppearanceVisible] = useState(false);
  const [storageVisible, setStorageVisible] = useState(false);
  const [signInVisible, setSignInVisible] = useState(false);
  const effectiveDark =
    settings?.theme === "dark" || (settings?.theme !== "light" && isDark);
  const themeLabel = effectiveDark ? "Dark theme" : "Light theme";
  const themeIcon = effectiveDark ? "moon" : "sun";
  const openOverview = () =>
    Alert.alert(
      "Overview",
      "veymur/cloud is not available in this version yet.",
    );
  return (
    <Screen
      appBar={<AppBar title="Profile" />}
      contentContainerStyle={{ gap: 12 }}
    >
      <Card variant="flat">
        <View style={styles.guestRow}>
          <View style={{ flex: 1, gap: 4 }}>
            <H3>Guest</H3>
            <Muted>No account. Everything stays on this phone.</Muted>
          </View>
          <Action
            title="Sign in"
            size="sm"
            variant="secondary"
            block={false}
            onPress={() => setSignInVisible(true)}
          />
        </View>
      </Card>
      <Card variant="flat" style={styles.listCard}>
        <Row
          title="Cloud sync"
          meta="Off · history stays on this phone"
          lead={<Icon name="cloud" size={20} color={colors["ink-2"]} />}
          end={<Icon name="chevron-right" size={20} color={colors["ink-3"]} />}
          onPress={() => setSignInVisible(true)}
        />
        <Row
          title="Range alerts"
          meta={rangesSummary(preferences.preferences)}
          lead={<Icon name="bell" size={20} color={colors["ink-2"]} />}
          end={<Icon name="chevron-right" size={20} color={colors["ink-3"]} />}
          onPress={onEditRanges}
        />
        <Row
          title="Appearance"
          meta={
            settings?.theme === "system"
              ? "Follows your phone"
              : "Set in Appearance"
          }
          lead={<Icon name="sun" size={20} color={colors["ink-2"]} />}
          end={<Chip label={themeLabel} tone="outline" icon={themeIcon} />}
          onPress={() => setAppearanceVisible(true)}
        />
        <Row
          title="Text size"
          meta="Follows your system setting"
          lead={<Icon name="text-size" size={20} color={colors["ink-2"]} />}
        />
        <Row
          title="Overview"
          meta="Opens veymur/cloud in your browser"
          lead={<Icon name="overview" size={20} color={colors["ink-2"]} />}
          end={<Icon name="external" size={20} color={colors["ink-3"]} />}
          onPress={openOverview}
        />
        <Row
          title="How your data is stored"
          lead={<Icon name="lock" size={20} color={colors["ink-2"]} />}
          end={<Icon name="chevron-right" size={20} color={colors["ink-3"]} />}
          onPress={() => setStorageVisible(true)}
          last
        />
      </Card>
      <Caption style={{ marginTop: 8 }}>
        For adults. veymur/sensor is a wellness log, not a medical device.
      </Caption>
      <StorageDialog
        visible={storageVisible}
        onClose={() => setStorageVisible(false)}
        onOpenDiagnostics={() => {
          setStorageVisible(false);
          onOpenDiagnostics();
        }}
      />
      <SignInDialog
        visible={signInVisible}
        onClose={() => setSignInVisible(false)}
      />
      <Modal
        visible={appearanceVisible}
        animationType="slide"
        onRequestClose={() => setAppearanceVisible(false)}
      >
        <View
          style={{
            flex: 1,
            paddingTop: insets.top,
            backgroundColor: colors.bg,
          }}
        >
          <ProfileAppearance onClose={() => setAppearanceVisible(false)} />
        </View>
      </Modal>
    </Screen>
  );
}

const styles = StyleSheet.create({
  guestRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: 12,
  },
  listCard: {
    paddingVertical: 0,
    paddingHorizontal: 20,
    gap: 0,
  },
});
