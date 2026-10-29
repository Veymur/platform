import { useEffect, useState } from "react";
import {
  Action,
  Card,
  Choice,
  Label,
  Segmented,
  TextField,
  Warning,
} from "../../ui/components";
import { AppBar, BackButton, Screen } from "../../ui/Screen";
import { useProduct } from "../../product/ProductContext";
import { deviceTimeZone } from "../../product/usePreferences";
import {
  isValidTimeZone,
  type EditableSettings,
  type ThemeChoice,
  type Units,
} from "../../product/sessionMath";
import { Muted, Subheading } from "./shared";

const themes: { value: ThemeChoice; title: string }[] = [
  { value: "system", title: "Follow my phone" },
  { value: "light", title: "Light" },
  { value: "dark", title: "Dark" },
];
const units: { value: Units; label: string }[] = [
  { value: "metric", label: "Kilometres" },
  { value: "imperial", label: "Miles" },
];

export function ProfileAppearance({ onClose }: { onClose: () => void }) {
  const { preferences } = useProduct();
  const settings = preferences.preferences?.settings ?? null;
  const [timeZoneText, setTimeZoneText] = useState(settings?.timezone_id ?? "");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    setTimeZoneText(settings?.timezone_id ?? "");
  }, [settings?.timezone_id]);
  const save = async (
    change: (current: EditableSettings) => EditableSettings,
  ) => {
    setSaving(true);
    setError(null);
    try {
      await preferences.saveSettings(change);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSaving(false);
    }
  };
  const phoneZone = deviceTimeZone();
  const saveTimeZone = (value: string) => {
    if (!isValidTimeZone(value)) {
      setError("Enter a time zone name such as Europe/Kyiv.");
      return;
    }
    void save((current) => ({ ...current, timezone_id: value.trim() }));
  };
  return (
    <Screen
      appBar={
        <AppBar
          title="Appearance & display"
          left={<BackButton onPress={onClose} />}
        />
      }
    >
      <Card variant="flat">
        <Subheading>Appearance</Subheading>
        {themes.map((theme) => (
          <Choice
            key={theme.value}
            title={theme.title}
            selected={(settings?.theme ?? "system") === theme.value}
            disabled={saving}
            onPress={() => {
              void save((current) => ({ ...current, theme: theme.value }));
            }}
          />
        ))}
        <Muted>Text size follows your system setting.</Muted>
      </Card>
      <Card variant="flat">
        <Subheading>Distance units</Subheading>
        <Segmented
          options={units}
          value={settings?.units ?? "metric"}
          disabled={saving}
          onChange={(value) => {
            void save((current) => ({
              ...current,
              units: value as Units,
            }));
          }}
        />
      </Card>
      <Card variant="flat">
        <Subheading>Time zone</Subheading>
        <Label>Dates are shown in {settings?.timezone_id ?? phoneZone}.</Label>
        {settings?.timezone_id !== phoneZone && (
          <Action
            title={`Use phone time zone (${phoneZone})`}
            secondary
            disabled={saving}
            onPress={() => saveTimeZone(phoneZone)}
          />
        )}
        <TextField
          label="Time zone name"
          value={timeZoneText}
          onChange={setTimeZoneText}
        />
        <Action
          title="Save time zone"
          secondary
          disabled={saving || timeZoneText.trim() === settings?.timezone_id}
          onPress={() => saveTimeZone(timeZoneText)}
        />
      </Card>
      {error && (
        <Warning title="Couldn't save display settings" detail={error} />
      )}
    </Screen>
  );
}
