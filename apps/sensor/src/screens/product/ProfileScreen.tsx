import { useEffect, useState } from "react";
import {
  Action,
  Card,
  Choice,
  Heading,
  Label,
  Status,
  TextField,
  Warning,
} from "../../ui/components";
import { useTheme } from "../../ui/Theme";
import { useProduct } from "../../product/ProductContext";
import { deviceTimeZone } from "../../product/usePreferences";
import {
  isValidTimeZone,
  type EditableSettings,
  type ThemeChoice,
  type Units,
} from "../../product/sessionMath";
import {
  Muted,
  rangesSummary,
  SignInDialog,
  StorageDialog,
  Subheading,
} from "./shared";

const themes: { value: ThemeChoice; title: string }[] = [
  { value: "system", title: "Follow my phone" },
  { value: "light", title: "Light" },
  { value: "dark", title: "Dark" },
];
const unitChoices: { value: Units; title: string }[] = [
  { value: "metric", title: "Kilometres" },
  { value: "imperial", title: "Miles" },
];

export function ProfileScreen({
  onEditRanges,
  onOpenDiagnostics,
}: {
  onEditRanges: () => void;
  onOpenDiagnostics: () => void;
}) {
  const { colors } = useTheme();
  const { preferences } = useProduct();
  const settings = preferences.preferences?.settings ?? null;
  const [storageVisible, setStorageVisible] = useState(false);
  const [signInVisible, setSignInVisible] = useState(false);
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
    <>
      <Heading>Profile</Heading>
      <Card>
        <Status
          title="Guest"
          detail="No account. Everything stays on this phone."
        />
        <Action
          title="Sign in"
          secondary
          onPress={() => setSignInVisible(true)}
        />
        <Label>Cloud sync: Off · history stays on this phone</Label>
      </Card>
      <Card>
        <Subheading>Range alerts</Subheading>
        <Label>{rangesSummary(preferences.preferences)}</Label>
        <Action title="Set up range alerts" secondary onPress={onEditRanges} />
      </Card>
      <Card>
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
      <Card>
        <Subheading>Distance units</Subheading>
        {unitChoices.map((choice) => (
          <Choice
            key={choice.value}
            title={choice.title}
            selected={(settings?.units ?? "metric") === choice.value}
            disabled={saving}
            onPress={() => {
              void save((current) => ({ ...current, units: choice.value }));
            }}
          />
        ))}
      </Card>
      <Card>
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
      {error && <Warning title={error} />}
      <Card>
        <Subheading>Your data</Subheading>
        <Action
          title="How your data is stored"
          secondary
          onPress={() => setStorageVisible(true)}
        />
        <Muted>To delete a session, open it in History.</Muted>
        <Action
          title="Recorder diagnostics"
          secondary
          onPress={onOpenDiagnostics}
        />
      </Card>
      <Label style={{ color: colors["ink-2"] }}>
        For adults. veymur/sensor is a wellness log, not a medical device.
      </Label>
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
