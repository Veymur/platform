import { Text, View } from "react-native";
import type { RecorderSnapshot } from "../recorder/types";
export function RecorderStatus({
  snapshot,
}: {
  snapshot: RecorderSnapshot | null;
}) {
  if (!snapshot)
    return (
      <Text accessibilityRole="alert">Connecting to the native recorder…</Text>
    );
  return (
    <View style={{ gap: 8 }}>
      <Text accessibilityRole="alert">{snapshot.status}</Text>
      <Text style={{ fontSize: 32, fontWeight: "600" }}>
        {snapshot.hrStatus === "fresh" && snapshot.hr !== null
          ? `${snapshot.hr} bpm`
          : "No current heart rate"}
      </Text>
      <Text>
        HR: {snapshot.hrStatus}
        {snapshot.hrAgeMs === null
          ? ""
          : ` · age ${(snapshot.hrAgeMs / 1000).toFixed(1)} s`}
      </Text>
      <Text selectable>
        Sensor: {snapshot.connectedDeviceId ?? "not connected"}
      </Text>
      <Text>
        Sensor battery:{" "}
        {snapshot.batteryPercent === null
          ? "unavailable"
          : `${snapshot.batteryPercent}%`}
      </Text>
      <Text>
        Session: {snapshot.sessionState} · Storage:{" "}
        {snapshot.ready ? "ready" : "not ready"}
      </Text>
      <Text>Active time: {Math.floor(snapshot.activeMs / 1000)} s</Text>
      <Text>
        Saved HR: {snapshot.hrCount} · GPS: {snapshot.gpsCount}
      </Text>
      <Text>GPS: {snapshot.gpsStatus}</Text>
      <Text>
        GPS distance:{" "}
        {snapshot.gpsDistanceM === null
          ? "unavailable"
          : `${snapshot.gpsDistanceM.toFixed(1)} m`}
      </Text>
      <Text>Range alerts: {snapshot.alertsStatus}</Text>
      {snapshot.storageWarning && (
        <Text accessibilityRole="alert">
          Low storage space. Finish and export the available recording.
        </Text>
      )}
      <Text>
        Last commit:{" "}
        {snapshot.lastCommitUtcMs === null
          ? "none"
          : new Date(snapshot.lastCommitUtcMs).toLocaleTimeString()}
      </Text>
      <Text>Recovered interrupted recordings: {snapshot.recoveredCount}</Text>
      {snapshot.errorCode && (
        <Text accessibilityRole="alert">
          {snapshot.errorCode}:{" "}
          {snapshot.errorCode === "STORAGE_ERROR"
            ? "Recording stopped. Export the available portion or retry saving."
            : "Check sensor status and retry the action."}
        </Text>
      )}
    </View>
  );
}
