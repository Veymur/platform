import { useEffect, useRef, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import {
  Action,
  Banner,
  Detail,
  Dialog,
  Label,
  LiveDot,
  Mark,
  Warning,
} from "../../ui/components";
import { ContourArt } from "../../ui/Contours";
import { Icon } from "../../ui/Icon";
import { LineChart } from "../../ui/LineChart";
import { AppBar, Screen } from "../../ui/Screen";
import { useTheme } from "../../ui/Theme";
import { Caption, Eyebrow, Mono, PulseNumber, Unit } from "../../ui/Typography";
import { fonts } from "../../ui/tokens";
import { useProduct } from "../../product/ProductContext";
import { localDataClient } from "../../recorder/LocalDataClient";
import {
  boundsFromRanges,
  displayActiveMs,
  formatAge,
  formatClock,
  formatDistance,
  liveChartValues,
  monotonicNowMs,
  rangeContaining,
  withoutErrorCode,
  type UserRange,
} from "../../product/sessionMath";
import type { RecorderSnapshot } from "../../recorder/types";
import { OpenSettingsAction } from "./shared";

function useSessionRanges(
  sessionId: string | null,
): readonly UserRange[] | null {
  const [ranges, setRanges] = useState<readonly UserRange[] | null>(null);
  useEffect(() => {
    if (!sessionId) {
      setRanges(null);
      return;
    }
    let active = true;
    void localDataClient
      .listSessions({ limit: 5, before: null })
      .then((page) => {
        if (active)
          setRanges(
            page.items.find((item) => item.id === sessionId)?.hr_ranges_snapshot
              ?.ranges ?? null,
          );
      })
      .catch(() => {
        if (active) setRanges(null);
      });
    return () => {
      active = false;
    };
  }, [sessionId]);
  return ranges;
}

/** Display-only smoothing between native snapshots; pause/finish use the native value. */
function useDisplayActiveMs(
  activeMs: number,
  sessionState: RecorderSnapshot["sessionState"],
): number {
  const anchor = useRef({ activeMs, at: monotonicNowMs() });
  const lastState = useRef(sessionState);
  const [, setTick] = useState(0);
  useEffect(() => {
    if (
      activeMs !== anchor.current.activeMs ||
      sessionState !== lastState.current
    ) {
      anchor.current = { activeMs, at: monotonicNowMs() };
      lastState.current = sessionState;
    }
    setTick((value) => value + 1);
    if (sessionState !== "recording") return;
    const timer = setInterval(() => setTick((value) => value + 1), 250);
    return () => clearInterval(timer);
  }, [activeMs, sessionState]);
  return displayActiveMs(
    anchor.current.activeMs,
    sessionState === "recording",
    anchor.current.at,
    monotonicNowMs(),
  );
}

const titles = {
  recording: "Recording",
  paused: "Paused",
  finishing: "Saving…",
  interrupted: "Saving stopped",
} as const;

function RangeReadout({
  paused,
  position,
  bounds,
}: {
  paused: boolean;
  position: UserRange | null;
  bounds: { from: number; to: number } | null;
}) {
  const { colors } = useTheme();
  const zone = paused
    ? "off"
    : position?.id === "within"
      ? "in"
      : position?.id === "above"
        ? "above"
        : position?.id === "below"
          ? "below"
          : "off";
  const background =
    zone === "in"
      ? colors["tide-wash"]
      : zone === "above"
        ? colors["ember-wash"]
        : zone === "below"
          ? colors["dusk-wash"]
          : colors.sunk;
  const foreground =
    zone === "in"
      ? colors.tide
      : zone === "above"
        ? colors.ember
        : zone === "below"
          ? colors.dusk
          : colors["ink-2"];
  const icon =
    zone === "in"
      ? "check"
      : zone === "above"
        ? "arrow-up"
        : zone === "below"
          ? "arrow-down"
          : "pause";
  const text = paused
    ? "Paused · not recording"
    : !bounds
      ? "Range alerts off"
      : position?.id === "within"
        ? `In your range · ${bounds.from}–${bounds.to} bpm`
        : position?.id === "above"
          ? `Above your range · ${bounds.to} bpm`
          : position?.id === "below"
            ? `Below your range · ${bounds.from} bpm`
            : "Outside your ranges";
  return (
    <View style={[styles.rangeReadout, { backgroundColor: background }]}>
      <Icon name={icon} size={16} color={foreground} />
      <Label
        style={{
          color: foreground,
          fontFamily: fonts.uiSemibold,
          fontSize: 14,
          lineHeight: 17,
        }}
      >
        {text}
      </Label>
    </View>
  );
}

export function SessionScreen() {
  const { colors } = useTheme();
  const { recorder, liveSamples, nowMs, network, units } = useProduct();
  const { snapshot, controller, pending, error } = recorder;
  const [confirmFinish, setConfirmFinish] = useState(false);
  const ranges = useSessionRanges(snapshot?.sessionId ?? null);
  const state = snapshot?.sessionState ?? "preparation";
  const shownActiveMs = useDisplayActiveMs(snapshot?.activeMs ?? 0, state);
  if (!snapshot) return null;
  const busy = pending !== null;
  const interrupted = state === "interrupted";
  const paused = state === "paused";
  const connected = snapshot.connectedDeviceId !== null;
  const fresh = snapshot.hrStatus === "fresh" && snapshot.hr !== null;
  const rangeActive = snapshot.alertsStatus === "enabled";
  const position =
    fresh && rangeActive ? rangeContaining(snapshot.hr!, ranges) : null;
  const bounds = boundsFromRanges(ranges);
  const activeBounds = rangeActive ? bounds : null;
  const chartNow = paused ? (liveSamples.at(-1)?.atMs ?? nowMs) : nowMs;
  const alertsBlocked =
    snapshot.alertsStatus === "notification_permission_denied" ||
    snapshot.alertsStatus === "notifications_disabled";
  const gpsUnavailable =
    snapshot.routeEnabled &&
    /no recent|gps disabled|permission denied/i.test(snapshot.gpsStatus);
  const lastPulseMark =
    snapshot.hrAgeMs === null
      ? "No pulse reading yet"
      : snapshot.hrAgeMs < 2000
        ? "Last pulse reading just now"
        : `Last pulse reading ${Math.round(snapshot.hrAgeMs / 1000)} s ago`;
  const title =
    state in titles ? titles[state as keyof typeof titles] : "Recording";
  const timer = (
    <Mono
      accessible
      accessibilityLabel={`Active time ${formatClock(shownActiveMs)}`}
      size={28}
      style={{
        lineHeight: 31,
        color: paused ? colors["ink-3"] : colors.ink,
      }}
    >
      {formatClock(shownActiveMs)}
    </Mono>
  );
  const actions = interrupted ? (
    <Action
      title={pending === "Finish" ? "Saving…" : "Save available part"}
      disabled={busy}
      onPress={() => {
        void controller.finish();
      }}
    />
  ) : (
    <View style={styles.actionPair}>
      <Action
        title={paused ? "Resume" : "Pause"}
        variant="secondary"
        size="lg"
        block={false}
        style={{ flex: 1, alignSelf: "stretch" }}
        icon={
          <Icon name={paused ? "play" : "pause"} size={18} color={colors.ink} />
        }
        disabled={busy || !(paused || state === "recording")}
        onPress={() => {
          void (paused ? controller.resume() : controller.pause());
        }}
      />
      <Action
        title={pending === "Finish" ? "Saving…" : "Finish"}
        variant="ink"
        size="lg"
        block={false}
        style={{ flex: 1.35, alignSelf: "stretch" }}
        icon={<Icon name="stop" size={18} color={colors.bg} />}
        disabled={busy || state === "finishing"}
        onPress={() => setConfirmFinish(true)}
      />
    </View>
  );
  return (
    <Screen
      appBar={
        <AppBar
          title={
            <View style={styles.appBarTitle}>
              <LiveDot active={!paused} />
              <Eyebrow
                style={{ color: paused ? colors["ink-3"] : colors["ink-2"] }}
              >
                {title}
              </Eyebrow>
            </View>
          }
          right={timer}
        />
      }
      actions={actions}
    >
      <View style={styles.pulseStage}>
        <View pointerEvents="none" style={styles.pulseArt}>
          <ContourArt
            rings={9}
            baseR={56}
            gap={13}
            size={360}
            seed={7}
            wobble={0.13}
            energy={0.8}
          />
        </View>
        {fresh ? (
          <>
            <PulseNumber state={paused ? "stale" : "fresh"}>
              {snapshot.hr}
            </PulseNumber>
            <Unit size={15}>{paused ? "bpm · not recording" : "bpm"}</Unit>
            {(paused || (rangeActive && activeBounds !== null)) && (
              <RangeReadout
                paused={paused}
                position={position}
                bounds={activeBounds}
              />
            )}
          </>
        ) : (
          <>
            <Text style={[styles.absentPulse, { color: colors["ink-2"] }]}>
              No current pulse
            </Text>
            <Caption>
              {snapshot.hr !== null
                ? `Last value ${snapshot.hr} bpm · ${formatAge(snapshot.hrAgeMs)}`
                : formatAge(snapshot.hrAgeMs)}
            </Caption>
            {paused && (
              <RangeReadout paused position={null} bounds={activeBounds} />
            )}
          </>
        )}
      </View>
      <View style={styles.chartBlock}>
        <LineChart
          values={liveChartValues(liveSamples, chartNow)}
          height={112}
          yMin={80}
          yMax={150}
          yTicks={[90, 120, 150]}
          xTicks={[
            { index: 0, label: "−90 s" },
            { index: 89, label: "now" },
          ]}
          band={
            activeBounds
              ? { min: activeBounds.from, max: activeBounds.to }
              : null
          }
          label="Pulse over the last 90 seconds"
          gapLabel="No pulse"
          endDot
        />
        <Caption>
          {activeBounds
            ? `Last 90 seconds · shaded band is your range, ${activeBounds.from}–${activeBounds.to} bpm`
            : "Last 90 seconds"}
        </Caption>
      </View>
      <View style={[styles.sessionGrid, { borderTopColor: colors.rule }]}>
        <View style={{ flex: 1 }}>
          <Detail
            title="Distance"
            value={
              snapshot.gpsDistanceM === null
                ? snapshot.routeEnabled
                  ? "Waiting"
                  : "—"
                : formatDistance(snapshot.gpsDistanceM, units)
            }
            note={
              snapshot.routeEnabled
                ? `${snapshot.gpsCount} points saved`
                : "Route off"
            }
          />
        </View>
        <View style={{ flex: 1 }}>
          <Detail
            title="Route"
            value={
              snapshot.routeEnabled
                ? gpsUnavailable
                  ? "Unavailable"
                  : paused
                    ? "Paused"
                    : "Recording"
                : "Off"
            }
            note={paused ? "Not counting" : undefined}
          />
        </View>
      </View>
      {paused && (
        <Banner
          icon="pause"
          title="Paused"
          detail="Timer and route recording are stopped. Resuming starts a new segment, without a line across the pause."
        />
      )}
      {!connected && !paused && (
        <Banner
          tone="dusk"
          icon="bluetooth"
          title="Connection to the sensor lost"
          detail="Trying to restore it. You can pause, finish, and keep the route recorded so far."
        />
      )}
      {connected && !paused && snapshot.hrStatus === "stale" && (
        <Banner
          tone="dusk"
          icon="clock"
          title="No current pulse"
          detail="The last value is shown only with its age. All session actions stay available."
        />
      )}
      {gpsUnavailable && !paused && (
        <Banner
          tone="dusk"
          icon="location"
          title="Route temporarily unavailable"
          detail="Pulse is still being recorded. The session continues without coordinates."
        />
      )}
      {snapshot.storageWarning === "low_space" && !interrupted && (
        <Banner
          tone="ember"
          icon="storage"
          title="Storage is almost full"
          detail="What you have recorded is safe and can be exported. Finish soon and free up some space."
          action={
            <Action
              title="Finish now"
              variant="secondary"
              size="sm"
              block={false}
              onPress={() => setConfirmFinish(true)}
            />
          }
        />
      )}
      <View style={[styles.statusLines, { borderTopColor: colors.rule }]}>
        <Mark
          icon={fresh ? "from-sensor" : "clock"}
          label={
            fresh
              ? paused
                ? "Sensor connected · not recording"
                : "Pulse updated just now"
              : lastPulseMark
          }
        />
        <Mark
          icon={interrupted ? "offline" : "on-phone"}
          label={
            interrupted
              ? "Not saving"
              : network?.validated === false
                ? "Offline · saving on this phone"
                : "Saving on this phone"
          }
        />
      </View>
      {interrupted && (
        <Warning
          title="Saving stopped"
          detail="The phone could not save new data, so recording stopped. Save the part recorded before this happened."
        />
      )}
      {alertsBlocked && (
        <>
          <Warning
            title="Range alerts can’t be shown"
            detail="Notifications are off for veymur/sensor. Recording is not affected."
          />
          <OpenSettingsAction />
        </>
      )}
      {error && <Warning title={withoutErrorCode(error)} />}
      <Dialog
        visible={confirmFinish}
        title="Finish this session?"
        onClose={() => setConfirmFinish(false)}
        closeTitle="Keep recording"
      >
        <Label>
          {`${formatClock(shownActiveMs)} · ${
            snapshot.gpsDistanceM === null
              ? "route distance unavailable"
              : formatDistance(snapshot.gpsDistanceM, units)
          }. The session is saved on this phone.`}
        </Label>
        <Action
          title="Finish and save"
          variant="ink"
          size="lg"
          onPress={() => {
            setConfirmFinish(false);
            void controller.finish();
          }}
        />
      </Dialog>
    </Screen>
  );
}

const styles = StyleSheet.create({
  appBarTitle: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
  },
  pulseStage: {
    minHeight: 264,
    alignItems: "center",
    justifyContent: "center",
    gap: 8,
  },
  pulseArt: {
    position: "absolute",
    top: -40,
    right: -24,
    bottom: -40,
    left: -24,
  },
  absentPulse: {
    maxWidth: 224,
    fontFamily: fonts.display,
    fontSize: 36,
    lineHeight: 40,
    textAlign: "center",
    paddingHorizontal: 16,
  },
  rangeReadout: {
    minHeight: 36,
    paddingHorizontal: 16,
    borderRadius: 999,
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    marginTop: 4,
  },
  chartBlock: { gap: 8 },
  sessionGrid: {
    flexDirection: "row",
    gap: 16,
    paddingTop: 16,
    borderTopWidth: 1,
  },
  statusLines: {
    gap: 4,
    paddingTop: 12,
    borderTopWidth: 1,
  },
  actionPair: {
    flexDirection: "row",
    gap: 12,
  },
});
