import { useEffect, useState } from "react";
import {
  Pressable,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import {
  Action,
  Banner,
  Card,
  Chip,
  Dialog,
  Label,
  Mark,
  Toggle,
  Warning,
} from "../../ui/components";
import { ContourArt } from "../../ui/Contours";
import { Icon } from "../../ui/Icon";
import { AppBar, Screen } from "../../ui/Screen";
import { useTheme } from "../../ui/Theme";
import { Eyebrow, Unit } from "../../ui/Typography";
import { fonts } from "../../ui/tokens";
import { useProduct } from "../../product/ProductContext";
import { routePermissionGranted } from "../../recorder/permissions";
import { formatAge, withoutErrorCode } from "../../product/sessionMath";
import { Muted, OpenSettingsAction, rangesSummary } from "./shared";

function RangeRow({
  summary,
  onPress,
}: {
  summary: string;
  onPress: () => void;
}) {
  const { colors } = useTheme();
  return (
    <View style={styles.rangeRow}>
      <View style={[styles.rowLead, { backgroundColor: colors.sunk }]}>
        <Icon name="bell" size={20} color={colors["ink-2"]} />
      </View>
      <View style={styles.rowBody}>
        <Label style={{ fontFamily: fonts.uiMedium }}>Range alerts</Label>
        <Label
          style={{ color: colors["ink-3"], fontSize: 14, lineHeight: 20.3 }}
        >
          {summary}
        </Label>
      </View>
      <Action
        title="Set up"
        size="sm"
        variant="secondary"
        block={false}
        onPress={onPress}
      />
    </View>
  );
}

export function ReadyScreen({
  onChangeSensor,
  onOpenHistory,
  onEditRanges,
}: {
  onChangeSensor: () => void;
  onOpenHistory: () => void;
  onEditRanges: () => void;
}) {
  const { colors } = useTheme();
  const { width } = useWindowDimensions();
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
  const fresh = snapshot.hrStatus === "fresh" && snapshot.hr !== null;
  const starting = pending === "Start";
  const canStart = snapshot.ready && !busy && connected;
  const stageWidth = Math.max(0, width - 40);
  const discSize = Math.min(240, stageWidth * 0.62);
  const stageHeight = discSize + 64;
  const artSize = discSize * 1.35;
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
    <Screen
      appBar={
        <AppBar
          title="Record"
          right={
            <Action
              title="Device"
              size="sm"
              variant="secondary"
              block={false}
              icon={<Icon name="pulse" size={16} color={colors.ink} />}
              onPress={onChangeSensor}
            />
          }
        />
      }
    >
      {snapshot.recoveredCount > 0 && !recoveredDismissed && (
        <Banner
          tone="dusk"
          icon="info"
          title="Found an unfinished recording"
          detail={`The part saved before the app closed is kept in History. ${snapshot.recoveredCount} recording${snapshot.recoveredCount === 1 ? "" : "s"} recovered.`}
          action={
            <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8 }}>
              <Action
                title="Open history"
                size="sm"
                block={false}
                onPress={onOpenHistory}
              />
              <Action
                title="Dismiss"
                size="sm"
                variant="quiet"
                block={false}
                onPress={() => setRecoveredDismissed(true)}
              />
            </View>
          }
        />
      )}
      <View style={styles.split}>
        <Chip
          label={
            connected
              ? "Polar Verity Sense · connected"
              : "Sensor not connected"
          }
          tone={connected ? "tide" : "neutral"}
          icon={connected ? "check" : "bluetooth"}
        />
        {connected && (
          <Mark
            icon="battery"
            label={
              snapshot.batteryPercent === null
                ? "Battery unavailable"
                : `${snapshot.batteryPercent}%`
            }
          />
        )}
      </View>
      {!connected && snapshot.selectedDeviceId && (
        <Action
          title="Reconnect selected sensor"
          size="sm"
          variant="secondary"
          block={false}
          disabled={busy}
          onPress={() => {
            void controller.connect(snapshot.selectedDeviceId!);
          }}
        />
      )}
      <View style={[styles.readyStage, { height: stageHeight }]}>
        <View
          pointerEvents="none"
          style={{
            position: "absolute",
            width: artSize,
            height: artSize,
            left: (stageWidth - artSize) / 2,
            top: (stageHeight - artSize) / 2,
          }}
        >
          <ContourArt
            rings={9}
            baseR={110}
            gap={15}
            size={560}
            seed={14}
            wobble={0.07}
            energy={0.7}
          />
        </View>
        <View style={{ width: discSize, height: discSize }}>
          {canStart && (
            <>
              <View
                style={[
                  styles.startHaloOuter,
                  {
                    width: discSize + 48,
                    height: discSize + 48,
                    borderRadius: (discSize + 48) / 2,
                    backgroundColor: colors["tide-wash"],
                  },
                ]}
              />
              <View
                style={[
                  styles.startHaloInner,
                  {
                    width: discSize + 24,
                    height: discSize + 24,
                    borderRadius: (discSize + 24) / 2,
                    backgroundColor: colors["tide-wash"],
                  },
                ]}
              />
            </>
          )}
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Start recording"
            accessibilityState={{ disabled: !canStart }}
            disabled={!canStart}
            onPress={() => {
              void controller.start();
            }}
            style={({ pressed }) => [
              styles.startDisc,
              {
                width: discSize,
                height: discSize,
                borderRadius: discSize / 2,
                backgroundColor: canStart
                  ? pressed
                    ? colors["solid-press"]
                    : colors.solid
                  : colors.sunk,
                borderWidth: canStart ? 0 : 2,
                borderColor: colors.rule,
                shadowOpacity: canStart ? 0.28 : 0,
                elevation: canStart ? 12 : 0,
                transform: pressed && canStart ? [{ scale: 0.97 }] : undefined,
              },
            ]}
          >
            <Text
              style={[
                styles.startTitle,
                { color: canStart ? colors["on-solid"] : colors["ink-3"] },
              ]}
            >
              {starting ? "Starting…" : "Start\nrecording"}
            </Text>
            <Text
              style={[
                styles.startHint,
                { color: canStart ? colors["on-solid"] : colors["ink-3"] },
              ]}
            >
              {starting ? "WAIT" : "TAP ONCE"}
            </Text>
          </Pressable>
        </View>
      </View>
      <Card variant="flat" style={styles.pulseCard}>
        <View style={styles.pulseSplit}>
          <View style={{ gap: 4 }}>
            <Eyebrow>Pulse now</Eyebrow>
            {fresh ? (
              <View style={styles.bigReading}>
                <Text style={[styles.bigReadingText, { color: colors.ink }]}>
                  {snapshot.hr}
                </Text>
                <Unit size={15}>bpm</Unit>
              </View>
            ) : (
              <Text style={[styles.readingState, { color: colors["ink-2"] }]}>
                {snapshot.hrStatus === "stale"
                  ? "Not current"
                  : "No current pulse"}
              </Text>
            )}
          </View>
          <View style={{ alignItems: "flex-end", gap: 4 }}>
            <Mark icon="from-sensor" label="From sensor" />
            <Mark label={formatAge(snapshot.hrAgeMs)} />
          </View>
        </View>
      </Card>
      <Card variant="flat" style={styles.listCard}>
        <Toggle
          title="Route"
          meta="Save this phone’s coordinates"
          lead={<Icon name="route" size={20} color={colors["ink-2"]} />}
          value={routeRequested}
          disabled={busy}
          divider
          onChange={(value) => {
            if (value) setRouteDialog(true);
            else void disableRoute();
          }}
        />
        <RangeRow
          summary={rangesSummary(preferences.preferences)}
          onPress={onEditRanges}
        />
      </Card>
      {routeDenied && (
        <Banner
          tone="dusk"
          icon="info"
          title="You can continue without a route"
          detail="Location is off for veymur/sensor. Pulse and history work as usual."
          action={<OpenSettingsAction />}
        />
      )}
      {connected && !fresh && (
        <Muted>
          You can start now. Time without a current pulse is marked as a gap.
        </Muted>
      )}
      {error && <Warning title={withoutErrorCode(error)} />}
      {saveError && <Warning title={saveError} />}
      <Mark
        icon="on-phone"
        label="Only on this phone"
        style={{ alignSelf: "center" }}
      />
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

const styles = StyleSheet.create({
  split: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: 12,
  },
  readyStage: {
    alignItems: "center",
    justifyContent: "center",
  },
  startHaloOuter: {
    position: "absolute",
    left: -24,
    top: -24,
    opacity: 0.55,
  },
  startHaloInner: {
    position: "absolute",
    left: -12,
    top: -12,
  },
  startDisc: {
    width: "100%",
    height: "100%",
    alignItems: "center",
    justifyContent: "center",
    shadowColor: "#16211f",
    shadowOffset: { width: 0, height: 22 },
    shadowOpacity: 0.28,
    shadowRadius: 24,
    elevation: 12,
  },
  startTitle: {
    fontFamily: fonts.display,
    fontSize: 28,
    lineHeight: 30,
    letterSpacing: -0.28,
    textAlign: "center",
  },
  startHint: {
    fontFamily: fonts.mono,
    fontSize: 12,
    lineHeight: 16.8,
    letterSpacing: 1.44,
    opacity: 0.85,
    marginTop: 4,
  },
  pulseCard: {
    paddingVertical: 16,
    paddingHorizontal: 20,
  },
  pulseSplit: {
    flexDirection: "row",
    alignItems: "flex-start",
    justifyContent: "space-between",
    gap: 12,
  },
  bigReading: {
    flexDirection: "row",
    alignItems: "baseline",
    gap: 8,
  },
  bigReadingText: {
    fontFamily: fonts.uiSemibold,
    fontSize: 40,
    lineHeight: 40,
    letterSpacing: -1.2,
  },
  readingState: {
    fontFamily: fonts.uiSemibold,
    fontSize: 24,
    lineHeight: 28,
  },
  listCard: {
    paddingVertical: 0,
    paddingHorizontal: 20,
    gap: 0,
  },
  rangeRow: {
    minHeight: 56,
    paddingVertical: 12,
    flexDirection: "row",
    alignItems: "center",
    gap: 16,
  },
  rowLead: {
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: "center",
    justifyContent: "center",
  },
  rowBody: { flex: 1, minWidth: 0, gap: 2 },
});
