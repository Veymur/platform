import { useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { AppMark } from "../../ui/AppMark";
import { Action, Warning } from "../../ui/components";
import { ContourArt } from "../../ui/Contours";
import { Screen } from "../../ui/Screen";
import { useTheme } from "../../ui/Theme";
import { Caption, Eyebrow, H1, Lede } from "../../ui/Typography";
import { useProduct } from "../../product/ProductContext";
import { SignInDialog, StorageDialog } from "./shared";

export function WelcomeScreen({ onContinue }: { onContinue?: () => void }) {
  const { colors } = useTheme();
  const { preferences } = useProduct();
  const [storageVisible, setStorageVisible] = useState(false);
  const [signInVisible, setSignInVisible] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const continueAsGuest = async () => {
    setSaving(true);
    setError(null);
    try {
      await preferences.saveSettings((current) => current);
      onContinue?.();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSaving(false);
    }
  };
  return (
    <Screen
      scroll
      contentContainerStyle={styles.heroContent}
      actions={
        <>
          {error && <Warning title="Couldn't continue" detail={error} />}
          <Action
            title={saving ? "Saving…" : "Continue without an account"}
            size="lg"
            disabled={saving}
            onPress={() => {
              void continueAsGuest();
            }}
          />
          <Action
            title="Sign in"
            variant="quiet"
            onPress={() => setSignInVisible(true)}
          />
          <Caption style={styles.caption}>
            For adults. A wellness log, not a medical device.{"\n"}
            <Text
              accessibilityRole="link"
              onPress={() => setStorageVisible(true)}
              style={{ color: colors.tide, textDecorationLine: "underline" }}
            >
              How your data is stored
            </Text>
          </Caption>
        </>
      }
    >
      <View style={styles.hero}>
        <View pointerEvents="none" style={styles.heroArt}>
          <ContourArt
            rings={12}
            baseR={28}
            gap={17}
            size={420}
            seed={5}
            wobble={0.17}
          />
        </View>
        <View style={styles.heroText}>
          <AppMark size={52} />
          <Eyebrow>veymur/sensor</Eyebrow>
          <H1>Pulse and activity. Nothing extra.</H1>
          <Lede>
            Record sessions on this phone. An account is only needed for the
            cloud.
          </Lede>
        </View>
      </View>
      <StorageDialog
        visible={storageVisible}
        onClose={() => setStorageVisible(false)}
      />
      <SignInDialog
        visible={signInVisible}
        onClose={() => setSignInVisible(false)}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  heroContent: {
    flexGrow: 1,
    paddingTop: 0,
    paddingBottom: 8,
  },
  hero: {
    flex: 1,
    justifyContent: "flex-end",
    minHeight: 420,
    paddingBottom: 8,
  },
  heroArt: {
    position: "absolute",
    top: -32,
    left: -48,
    right: -48,
    height: 384,
  },
  heroText: {
    gap: 20,
  },
  caption: {
    textAlign: "center",
  },
});
