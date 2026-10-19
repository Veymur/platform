import type { ReactNode } from "react";
import { Linking } from "react-native";
import { Action, Dialog, Label } from "../../ui/components";
import { useTheme } from "../../ui/Theme";
import type { Preferences } from "../../../modules/veymur-recorder";
import {
  alertModeLabels,
  alertModeOf,
  boundsFromRanges,
} from "../../product/sessionMath";

export function StorageDialog({
  visible,
  onClose,
}: {
  visible: boolean;
  onClose: () => void;
}) {
  return (
    <Dialog
      visible={visible}
      title="How your data is stored"
      onClose={onClose}
      closeTitle="Got it"
    >
      <Label>
        • Sessions are saved on this phone first. They work without an account
        or a network.
      </Label>
      <Label>
        • Nothing goes to the cloud unless you choose to sync, and you choose
        what.
      </Label>
      <Label>
        • Export and delete stay available even if you never sign in.
      </Label>
    </Dialog>
  );
}
export function SignInDialog({
  visible,
  onClose,
}: {
  visible: boolean;
  onClose: () => void;
}) {
  return (
    <Dialog
      visible={visible}
      title="Cloud account"
      onClose={onClose}
      closeTitle="OK"
    >
      <Label>
        Signing in to veymur/cloud is not available in this version yet.
      </Label>
      <Label>
        Recording, history and deleting sessions work fully without an account.
      </Label>
    </Dialog>
  );
}
export function Muted({ children }: { children: ReactNode }) {
  const { colors } = useTheme();
  return <Label style={{ color: colors["ink-2"] }}>{children}</Label>;
}
export function Subheading({ children }: { children: string }) {
  return (
    <Label
      accessibilityRole="header"
      style={{ fontSize: 20, fontWeight: "600" }}
    >
      {children}
    </Label>
  );
}
export function OpenSettingsAction({
  onError,
}: {
  onError?: (message: string) => void;
}) {
  return (
    <Action
      title="Open system settings"
      secondary
      onPress={() => {
        void Linking.openSettings().catch(() =>
          onError?.(
            "Could not open system settings. Open them from your phone settings.",
          ),
        );
      }}
    />
  );
}
export function rangesSummary(preferences: Preferences | null): string {
  const bounds = boundsFromRanges(preferences?.hr_ranges?.ranges);
  const settings = preferences?.settings;
  if (!bounds) return "Off";
  const range = `${bounds.from}–${bounds.to} bpm`;
  if (!settings || settings.hr_ranges_id !== preferences?.hr_ranges?.id)
    return "Off";
  return settings.alerts.enabled
    ? `${range} · ${alertModeLabels[alertModeOf(settings.alerts)]}`
    : `${range} · signal off`;
}
