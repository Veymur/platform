import { useState } from "react";
import { Action, Heading, Label, Warning } from "../../ui/components";
import { useTheme } from "../../ui/Theme";
import { useProduct } from "../../product/ProductContext";
import { Muted, SignInDialog, StorageDialog } from "./shared";

export function WelcomeScreen() {
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
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSaving(false);
    }
  };
  return (
    <>
      <Label style={{ color: colors.tide }}>veymur/sensor</Label>
      <Heading>Pulse and activity. Nothing extra.</Heading>
      <Label>
        Record sessions on this phone. An account is only needed for the cloud.
      </Label>
      <Action
        title={saving ? "Saving…" : "Continue without an account"}
        disabled={saving}
        onPress={() => {
          void continueAsGuest();
        }}
      />
      <Action
        title="Sign in"
        secondary
        onPress={() => setSignInVisible(true)}
      />
      <Muted>For adults. A wellness log, not a medical device.</Muted>
      <Action
        title="How your data is stored"
        secondary
        onPress={() => setStorageVisible(true)}
      />
      {error && <Warning title="Couldn't continue" detail={error} />}
      <StorageDialog
        visible={storageVisible}
        onClose={() => setStorageVisible(false)}
      />
      <SignInDialog
        visible={signInVisible}
        onClose={() => setSignInVisible(false)}
      />
    </>
  );
}
