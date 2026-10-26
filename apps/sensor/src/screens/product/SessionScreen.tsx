import { useEffect, useRef, useState } from "react";
import { View } from "react-native";
import {
  Action,
  Card,
  Detail,
  Dialog,
  Heading,
  Label,
  Status,
  Warning,
} from "../../ui/components";
import { HrColumns } from "../../ui/HrColumns";
import { SessionShape } from "../../ui/SessionShape";
import { useTheme } from "../../ui/Theme";
import { useProduct } from "../../product/ProductContext";
import { localDataClient } from "../../recorder/LocalDataClient";
import {
  displayActiveMs,
  formatAge,
  formatClock,
  formatTime,
  frozenLiveNow,
  liveChartColumns,
  monotonicNowMs,
  rangeContaining,
  withoutErrorCode,
  type UserRange,
} from "../../product/sessionMath";
import type { RecorderSnapshot } from "../../recorder/types";
import { Muted, OpenSettingsAction } from "./shared";

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

export function SessionScreen() {
  const { colors } = useTheme();
  const { recorder, liveSamples, nowMs, timeZone, network, reduceMotion } =
    useProduct();
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
  const position = fresh ? rangeContaining(snapshot.hr!, ranges) : null;
  const chartNow = paused ? (liveSamples.at(-1)?.atMs ?? nowMs) : nowMs;
  const alertsBlocked =
    snapshot.alertsStatus === "notification_permission_denied" ||
    snapshot.alertsStatus === "notifications_disabled";
  const title =
    state in titles ? titles[state as keyof typeof titles] : "Recording";
  return (
    <>
      <Heading>{title}</Heading>
      <View
        accessible
        accessibilityLabel={`Active time ${formatClock(shownActiveMs)}`}
      >
        <Label
          style={{
            fontSize: 40,
            fontVariant: ["tabular-nums"],
            fontWeight: "600",
          }}
        >
          {formatClock(shownActiveMs)}
        </Label>
        <Muted>active time{paused ? " · paused, not counting" : ""}</Muted>
      </View>
      <Card>
        {fresh ? (
          <View
            accessible
            accessibilityLabel={`Pulse ${snapshot.hr} beats per minute${position ? `, ${position.label}` : ""}`}
          >
            <Label style={{ fontSize: 56, fontWeight: "700" }}>
              {snapshot.hr} <Label style={{ fontSize: 20 }}>bpm</Label>
            </Label>
            {position && (
              <Label style={{ color: colors.tide, fontWeight: "600" }}>
                Your range: {position.label}
              </Label>
            )}
            <Muted>{formatAge(snapshot.hrAgeMs)}</Muted>
          </View>
        ) : (
          <Detail
            title="Pulse"
            value={
              !connected
                ? "Sensor lost"
                : snapshot.hrStatus === "stale"
                  ? "Pulse not current"
                  : "Waiting for pulse"
            }
            note={
              !connected
                ? "Recording continues. Time without pulse is saved as a gap."
                : snapshot.hrStatus === "stale"
                  ? `The last reading is too old to show. ${formatAge(snapshot.hrAgeMs)}`
                  : "The sensor has not sent a reading yet."
            }
          />
        )}
        <HrColumns
          title={paused ? "Last 90 seconds · paused" : "Last 90 seconds"}
          columns={liveChartColumns(liveSamples, chartNow)}
          height={80}
        />
        {!reduceMotion && (
          <View style={{ alignItems: "center" }}>
            <SessionShape
              label={
                fresh && !paused
                  ? "Live shape of the last 90 seconds"
                  : "Live shape, stopped until a fresh reading arrives"
              }
              size={128}
              columns={liveChartColumns(
                liveSamples,
                frozenLiveNow(liveSamples, nowMs, fresh && !paused),
                90000,
                36,
              )}
            />
          </View>
        )}
      </Card>
      <Card>
        <Detail
          title="Route"
          value={snapshot.routeEnabled ? snapshot.gpsStatus : "Off"}
          note={
            snapshot.routeEnabled
              ? `${snapshot.gpsCount} points saved. Distance is shown in the summary.`
              : undefined
          }
        />
        <Status
          live
          title={connected ? "● Sensor connected" : "○ Sensor lost"}
          detail={
            connected
              ? undefined
              : "Keep the sensor close. Pulse recording resumes after reconnecting."
          }
        />
        {!connected && snapshot.selectedDeviceId && !interrupted && (
          <Action
            title="Reconnect sensor"
            disabled={busy}
            onPress={() => {
              void controller.connect(snapshot.selectedDeviceId!);
            }}
          />
        )}
        <Status
          title={interrupted ? "Not saving" : "● Saving on this phone"}
          detail={
            snapshot.lastCommitUtcMs
              ? `Last saved at ${formatTime(snapshot.lastCommitUtcMs, timeZone)}.`
              : "Waiting for the first save."
          }
        />
        <Status
          live
          title={
            network === null
              ? "Network status unknown"
              : network.validated
                ? "● Online"
                : "○ No network"
          }
          detail="Recording and saving work without a network."
        />
      </Card>
      {interrupted && (
        <Warning
          title="Saving stopped"
          detail="The phone could not save new data, so recording stopped. Save the part recorded before this happened."
        />
      )}
      {snapshot.storageWarning === "low_space" && !interrupted && (
        <Warning
          title="Low storage space"
          detail="Finish soon to keep the recording. If space runs out, the saved part is kept."
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
      {interrupted ? (
        <Action
          title={pending === "Finish" ? "Saving…" : "Save available part"}
          disabled={busy}
          onPress={() => {
            void controller.finish();
          }}
        />
      ) : (
        <>
          <Action
            title={paused ? "Resume" : "Pause"}
            disabled={busy || !(paused || state === "recording")}
            onPress={() => {
              void (paused ? controller.resume() : controller.pause());
            }}
          />
          <Action
            title={pending === "Finish" ? "Saving…" : "Finish"}
            secondary
            disabled={busy || state === "finishing"}
            onPress={() => setConfirmFinish(true)}
          />
        </>
      )}
      {error && <Warning title={withoutErrorCode(error)} />}
      <Dialog
        visible={confirmFinish}
        title="Finish this session?"
        onClose={() => setConfirmFinish(false)}
        closeTitle="Keep recording"
      >
        <Label>The session is saved on this phone.</Label>
        <Action
          title="Finish and save"
          onPress={() => {
            setConfirmFinish(false);
            void controller.finish();
          }}
        />
      </Dialog>
    </>
  );
}
