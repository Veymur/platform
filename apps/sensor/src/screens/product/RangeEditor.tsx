import { useEffect, useState } from "react";
import { View } from "react-native";
import {
  Action,
  Choice,
  Dialog,
  Label,
  NumberField,
  Warning,
} from "../../ui/components";
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

type SignalChoice = AlertMode | "off";
const signalChoices: SignalChoice[] = ["vibration", "sound", "silent", "off"];
const signalLabels: Record<SignalChoice, string> = {
  ...alertModeLabels,
  off: "No signal",
};
const signalDetails: Record<SignalChoice, string> = {
  vibration: "A short vibration with a notification.",
  sound: "Your phone’s notification sound.",
  silent: "A notification only, no sound or vibration.",
  off: "Ranges are shown in the summary only.",
};

export function RangeEditor({
  visible,
  onClose,
}: {
  visible: boolean;
  onClose: () => void;
}) {
  const { preferences, recorder } = useProduct();
  const current = preferences.preferences;
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [signal, setSignal] = useState<SignalChoice>("off");
  const [error, setError] = useState<string | null>(null);
  const [notificationsBlocked, setNotificationsBlocked] = useState(false);
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    if (!visible) return;
    const bounds = boundsFromRanges(current?.hr_ranges?.ranges);
    setFrom(bounds ? String(bounds.from) : "");
    setTo(bounds ? String(bounds.to) : "");
    const alerts = current?.settings?.alerts;
    setSignal(alerts?.enabled ? alertModeOf(alerts) : "off");
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
      const allowed =
        signal === "off" || (await requestNotificationPermission());
      await preferences.saveRangesAndSettings(
        rangesFromBounds(parsed.bounds),
        (settings, rangesId) => ({
          ...settings,
          hr_ranges_id: rangesId,
          alerts: {
            ...settings.alerts,
            enabled: signal !== "off",
            ...(signal === "off" ? {} : alertDelivery(signal)),
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
      <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 12 }}>
        <NumberField label="From bpm" value={from} onChange={setFrom} />
        <NumberField label="To bpm" value={to} onChange={setTo} />
      </View>
      <Muted>
        Both numbers are included in your range, between 30 and 230 bpm.
      </Muted>
      <Subheading>Signal</Subheading>
      {signalChoices.map((choice) => (
        <Choice
          key={choice}
          title={signalLabels[choice]}
          detail={signalDetails[choice]}
          selected={signal === choice}
          onPress={() => setSignal(choice)}
        />
      ))}
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
      {error && <Warning title={error} />}
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
          secondary
          disabled={saving}
          onPress={() => {
            void turnOff();
          }}
        />
      )}
    </Dialog>
  );
}
