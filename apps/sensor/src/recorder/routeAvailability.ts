import type { RecorderSnapshot } from "./types";

export const routeUnavailableLabel = "Unavailable for now";

export function locationOffForRequestedRoute(
  routeRequested: boolean,
  locationEnabled: boolean | null,
): boolean {
  return routeRequested && locationEnabled === false;
}

export function sessionRouteUnavailable(
  snapshot: Pick<RecorderSnapshot, "routeEnabled" | "gpsStatus">,
): boolean {
  return (
    snapshot.routeEnabled &&
    /no recent|gps disabled|permission denied|route unavailable/i.test(
      snapshot.gpsStatus,
    )
  );
}

export function sessionRouteLabel(
  snapshot: Pick<RecorderSnapshot, "routeEnabled" | "gpsStatus">,
  paused: boolean,
): string {
  if (!snapshot.routeEnabled) return "Off";
  if (sessionRouteUnavailable(snapshot)) return routeUnavailableLabel;
  return paused ? "Paused" : "Recording";
}
