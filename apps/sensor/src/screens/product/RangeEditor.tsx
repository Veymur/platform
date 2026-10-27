import { useEffect, useState } from "react";
import { View } from "react-native";
import {
  Action,
  Dialog,
  Label,
  NumberField,
  Segmented,
  Warning,
} from "../../ui/components";
import { useTheme } from "../../ui/Theme";
import { useProduct } from "../../product/ProductContext";
import { requestNotificationPermission } from "../../recorder/permissions";
import {
  alertDelivery,
  alertModeLabels,
  alertModeOf,
  boundsFromRanges,
  parseRangeBounds,
  rangesFromBounds,
  type AlertMode,
} from "../../product/sessionMath";
import { Muted, OpenSettingsAction, Subheading } from "./shared";

const signalOptions = (["vibration", "sound", "silent"] as const).map(
  (value) => ({ value, label: alertModeLabels[value] }),
);

export function RangeEditor({
  visible,
  onClose,
}: {
  visible: boolean;
  onClose: () => void;
}) {
  const { colors } = useTheme();
  const { preferences, recorder } = useProduct();
  const current = preferences.preferences;
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [signal, setSignal] = useState<AlertMode>("vibration");
  const [error, setError] = useState<string | null>(null);
  const [notificationsBlocked, setNotificationsBlocked] = useState(false);
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    if (!visible) return;
    const bounds = boundsFromRanges(current?.hr_ranges?.ranges);
    setFrom(bounds ? String(bounds.from) : "");
    setTo(bounds ? String(bounds.to) : "");
    const alerts = current?.settings?.alerts;
    setSignal(alerts?.enabled ? alertModeOf(alerts) : "vibration");
    setError(null);
    setNotificationsBlocked(false);
  }, [visible]);
  const sessionActive = recorder.snapshot?.sessionId != null;
  const save = async () => {
    const parsed = parseRangeBounds(from, to);
    if ("error" in parsed) {
      setError(parsed.error);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const allowed = await requestNotificationPermission();
      await preferences.saveRangesAndSettings(
        rangesFromBounds(parsed.bounds),
        (settings, rangesId) => ({
          ...settings,
          hr_ranges_id: rangesId,
          alerts: {
            ...settings.alerts,
            enabled: true,
            ...alertDelivery(signal),
          },
        }),
      );
      if (!allowed) setNotificationsBlocked(true);
      else onClose();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSaving(false);
    }
  };
  const turnOff = async () => {
    setSaving(true);
    setError(null);
    try {
      await preferences.saveSettings((settings) => ({
        ...settings,
        alerts: { ...settings.alerts, enabled: false },
      }));
      onClose();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSaving(false);
    }
  };
  return (
    <Dialog
      visible={visible}
      title="Your pulse range"
      onClose={onClose}
      closeTitle="Cancel"
    >
      <Label>
        These are your own numbers. veymur doesn’t suggest a range and doesn’t
        call anything outside it dangerous.
      </Label>
      <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 16 }}>
        <NumberField label="From" unit="bpm" value={from} onChange={setFrom} />
        <NumberField label="To" unit="bpm" value={to} onChange={setTo} />
      </View>
      {error && (
        <Label style={{ color: colors.ember, fontSize: 14, lineHeight: 20.3 }}>
          {error}
        </Label>
      )}
      <Muted>
        Both numbers are included in your range, between 30 and 230 bpm.
      </Muted>
      <Subheading>Signal</Subheading>
      <Segmented
        options={signalOptions}
        value={signal}
        onChange={(value) => setSignal(value as AlertMode)}
        disabled={saving}
      />
      <Muted>
        A brief spike never triggers a signal. We wait for a steady change of
        about 10 seconds and don’t repeat the same signal.
      </Muted>
      {sessionActive && (
        <Muted>
          New range numbers apply from your next recording. Signal changes apply
          now.
        </Muted>
      )}
      {notificationsBlocked && (
        <>
          <Warning
            title="Range saved, but notifications are off"
            detail="Signals can’t be shown until you allow notifications for veymur/sensor."
          />
          <OpenSettingsAction />
        </>
      )}
      <Action
        title={saving ? "Saving…" : "Save range"}
        disabled={saving}
        onPress={() => {
          void save();
        }}
      />
      {current?.settings?.alerts.enabled && (
        <Action
          title="Turn alerts off"
          variant="quiet"
          disabled={saving}
          onPress={() => {
            void turnOff();
          }}
        />
      )}
    </Dialog>
  );
}
