import { useCallback, useEffect, useRef, useState } from "react";
import { localDataClient } from "../recorder/LocalDataClient";
import { RecorderClientError } from "../recorder/RecorderClient";
import type { Preferences } from "../../modules/veymur-recorder";
import {
  defaultSettings,
  editableSettings,
  type EditableSettings,
  type UserRange,
} from "./sessionMath";

export function deviceTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  } catch {
    return "UTC";
  }
}
function conflictMessage(error: unknown): Error {
  if (error instanceof RecorderClientError && error.code === "INVALID_STATE")
    return new Error(
      "Settings changed in another place. They were reloaded; try again.",
    );
  return error instanceof Error ? error : new Error(String(error));
}
export type PreferencesState = {
  preferences: Preferences | null;
  loaded: boolean;
  error: string | null;
  reload: () => Promise<void>;
  saveSettings: (
    change: (current: EditableSettings) => EditableSettings,
  ) => Promise<Preferences>;
  saveRangesAndSettings: (
    ranges: UserRange[],
    change: (current: EditableSettings, rangesId: string) => EditableSettings,
  ) => Promise<Preferences>;
};
export function usePreferences(ready: boolean): PreferencesState {
  const [preferences, setPreferences] = useState<Preferences | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const latest = useRef<Preferences | null>(null);
  const apply = useCallback((value: Preferences) => {
    latest.current = value;
    setPreferences(value);
    setLoaded(true);
    setError(null);
  }, []);
  const reload = useCallback(async () => {
    try {
      apply(await localDataClient.getPreferences());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }, [apply]);
  useEffect(() => {
    if (ready) void reload();
  }, [ready, reload]);
  const saveSettings = useCallback(
    async (change: (current: EditableSettings) => EditableSettings) => {
      const current = latest.current?.settings ?? null;
      const base = current
        ? editableSettings(current)
        : defaultSettings(deviceTimeZone());
      try {
        const result = await localDataClient.saveSettings({
          expected_revision: current?.revision ?? 0,
          value: change(base),
        });
        apply(result);
        return result;
      } catch (cause) {
        await reload();
        throw conflictMessage(cause);
      }
    },
    [apply, reload],
  );
  const saveRangesAndSettings = useCallback(
    async (
      ranges: UserRange[],
      change: (current: EditableSettings, rangesId: string) => EditableSettings,
    ) => {
      try {
        const saved = await localDataClient.saveRanges({
          expected_revision: latest.current?.hr_ranges?.revision ?? 0,
          value: { ranges },
        });
        apply(saved);
        const rangesId = saved.hr_ranges?.id;
        if (!rangesId) throw new Error("Ranges were not saved; try again.");
        return await saveSettings((current) => change(current, rangesId));
      } catch (cause) {
        await reload();
        throw conflictMessage(cause);
      }
    },
    [apply, reload, saveSettings],
  );
  return {
    preferences,
    loaded,
    error,
    reload,
    saveSettings,
    saveRangesAndSettings,
  };
}
