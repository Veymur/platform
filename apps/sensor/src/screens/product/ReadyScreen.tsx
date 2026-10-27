import { useEffect, useState } from "react";
import { View } from "react-native";
import {
  Action,
  Card,
  Detail,
  Dialog,
  Label,
  Status,
  Toggle,
  Warning,
} from "../../ui/components";
import { AppBar, Screen } from "../../ui/Screen";
import { useProduct } from "../../product/ProductContext";
import { routePermissionGranted } from "../../recorder/permissions";
import { formatAge, withoutErrorCode } from "../../product/sessionMath";
import { Muted, OpenSettingsAction, rangesSummary, Subheading } from "./shared";

export function ReadyScreen({
  onChangeSensor,
  onOpenHistory,
  onEditRanges,
}: {
  onChangeSensor: () => void;
  onOpenHistory: () => void;
  onEditRanges: () => void;
}) {
  const { recorder, preferences } = useProduct();
  const { snapshot, controller, pending, routeRequested, error } = recorder;
  const [routeDialog, setRouteDialog] = useState(false);
  const [routeDenied, setRouteDenied] = useState(false);
  const [recoveredDismissed, setRecoveredDismissed] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  useEffect(() => {
    if (routeRequested) setRouteDenied(false);
  }, [routeRequested]);
  if (!snapshot) return null;
  const busy = pending !== null;
  const connected = snapshot.connectedDeviceId !== null;
  const rememberRoute = async (enabled: boolean) => {
    try {
      await preferences.saveSettings((settings) => ({
        ...settings,
        route_enabled: enabled,
      }));
    } catch (cause) {
      setSaveError(cause instanceof Error ? cause.message : String(cause));
    }
  };
  const enableRoute = async () => {
    setRouteDialog(false);
    await controller.setRoute(true);
    const granted = await routePermissionGranted();
    setRouteDenied(!granted);
    await rememberRoute(granted);
  };
  const disableRoute = async () => {
    await controller.setRoute(false);
    setRouteDenied(false);
    await rememberRoute(false);
  };
  return (
    <Screen appBar={<AppBar title="Record" />}>
      {snapshot.recoveredCount > 0 && !recoveredDismissed && (
        <Card>
          <Subheading>Found an unfinished recording</Subheading>
          <Label>
            The app was closed during a recording. The part saved before that is
            kept in History, marked “Interrupted”. You can review or delete it
            there.
          </Label>
          <Action title="Open history" onPress={onOpenHistory} />
          <Action
            title="Dismiss"
            secondary
            onPress={() => setRecoveredDismissed(true)}
          />
        </Card>
      )}
      <Card>
        <View
          style={{
            flexDirection: "row",
            flexWrap: "wrap",
            gap: 12,
            alignItems: "center",
            justifyContent: "space-between",
          }}
        >
          <Status
            title={
              connected
                ? "● Polar Verity Sense · connected"
                : "○ Sensor not connected"
            }
            detail={
              connected
                ? `Battery ${snapshot.batteryPercent === null ? "not reported" : `${snapshot.batteryPercent}%`}`
                : "Keep the sensor close and reconnect it."
            }
          />
        </View>
        {!connected && snapshot.selectedDeviceId && (
          <Action
            title="Reconnect selected sensor"
            disabled={busy}
            onPress={() => {
              void controller.connect(snapshot.selectedDeviceId!);
            }}
          />
        )}
        <Action
          title="Change sensor"
          secondary
          disabled={busy}
          onPress={onChangeSensor}
        />
      </Card>
      <Card>
        {snapshot.hrStatus === "fresh" && snapshot.hr !== null ? (
          <Detail
            title="Pulse now"
            value={`${snapshot.hr} bpm`}
            note={`From sensor · ${formatAge(snapshot.hrAgeMs)}`}
          />
        ) : (
          <Detail
            title="Pulse now"
            value={
              snapshot.hrStatus === "stale" ? "Not current" : "No current pulse"
            }
            note={
              snapshot.hrStatus === "stale"
                ? `Last reading is old. ${formatAge(snapshot.hrAgeMs)}`
                : "Waiting for the sensor to send a reading."
            }
          />
        )}
      </Card>
      <Card>
        <Toggle
          title="Route · save this phone’s coordinates"
          value={routeRequested}
          disabled={busy}
          onChange={(value) => {
            if (value) setRouteDialog(true);
            else void disableRoute();
          }}
        />
        {routeDenied && (
          <>
            <Warning
              title="You can continue without a route"
              detail="Location is off for veymur/sensor. Pulse and history work as usual."
            />
            <OpenSettingsAction />
          </>
        )}
        <View style={{ gap: 8 }}>
          <Label>Range alerts: {rangesSummary(preferences.preferences)}</Label>
          <Action
            title="Set up range alerts"
            secondary
            onPress={onEditRanges}
          />
        </View>
      </Card>
      <Action
        title={pending === "Start" ? "Starting…" : "Start recording"}
        disabled={!snapshot.ready || busy || !connected}
        onPress={() => {
          void controller.start();
        }}
      />
      {connected && snapshot.hrStatus !== "fresh" && (
        <Muted>
          You can start now. Time without a current pulse is marked as a gap.
        </Muted>
      )}
      {error && <Warning title={withoutErrorCode(error)} />}
      {saveError && <Warning title={saveError} />}
      <Muted>Only on this phone. No account or network is needed.</Muted>
      <Dialog
        visible={routeDialog}
        title="Record your route?"
        onClose={() => setRouteDialog(false)}
        closeTitle="Continue without a route"
      >
        <Label>
          Coordinates come from your phone, not from the sensor. They stay on
          this phone unless you choose to sync them.
        </Label>
        <Muted>If you say no, pulse and history still work.</Muted>
        <Action
          title="Allow location"
          onPress={() => {
            void enableRoute();
          }}
        />
      </Dialog>
    </Screen>
  );
}
