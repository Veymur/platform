export const provisionalAggregateParameters = {
  hr_stale_after_ms: 5000,
  gps_max_accuracy_m: 30,
  gps_max_gap_ms: 5000,
  gps_max_speed_m_s: 20,
  map_hr_window_ms: 2000,
} as const;
export const provisionalAlertTiming = {
  debounce_ms: 10000,
  hysteresis_bpm: 3,
  repeat_ms: null,
} as const;
export const rangeBoundLimits = { min: 30, max: 230 } as const;

export type UserRange = {
  id: string;
  label: string;
  lower_bpm_inclusive: number | null;
  upper_bpm_exclusive: number | null;
};
export type RangeBounds = { from: number; to: number };
export type AlertMode = "vibration" | "sound" | "silent";
export type Units = "metric" | "imperial";
export type ThemeChoice = "system" | "light" | "dark";
export type EditableSettings = {
  units: Units;
  timezone_id: string;
  theme: ThemeChoice;
  route_enabled: boolean;
  hr_ranges_id: string | null;
  alerts: {
    enabled: boolean;
    sound: boolean;
    vibration: boolean;
    debounce_ms: number;
    hysteresis_bpm: number;
    repeat_ms: number | null;
  };
};

const boundsMessage = `“From” must be lower than “To”, between ${rangeBoundLimits.min} and ${rangeBoundLimits.max} bpm.`;
export function parseRangeBounds(
  fromText: string,
  toText: string,
): { bounds: RangeBounds } | { error: string } {
  const fromTrimmed = fromText.trim();
  const toTrimmed = toText.trim();
  if (!/^\d+$/.test(fromTrimmed) || !/^\d+$/.test(toTrimmed))
    return { error: `Enter whole numbers. ${boundsMessage}` };
  const from = Number(fromTrimmed);
  const to = Number(toTrimmed);
  if (from < rangeBoundLimits.min || to > rangeBoundLimits.max || from >= to)
    return { error: boundsMessage };
  return { bounds: { from, to } };
}
export function rangesFromBounds({ from, to }: RangeBounds): UserRange[] {
  return [
    {
      id: "below",
      label: `Below ${from} bpm`,
      lower_bpm_inclusive: null,
      upper_bpm_exclusive: from,
    },
    {
      id: "within",
      label: `${from}–${to} bpm`,
      lower_bpm_inclusive: from,
      upper_bpm_exclusive: to + 1,
    },
    {
      id: "above",
      label: `Above ${to} bpm`,
      lower_bpm_inclusive: to + 1,
      upper_bpm_exclusive: null,
    },
  ];
}
export function boundsFromRanges(
  ranges: readonly UserRange[] | null | undefined,
): RangeBounds | null {
  const within = ranges?.find((range) => range.id === "within");
  if (
    !within ||
    within.lower_bpm_inclusive === null ||
    within.upper_bpm_exclusive === null
  )
    return null;
  return {
    from: within.lower_bpm_inclusive,
    to: within.upper_bpm_exclusive - 1,
  };
}
export function rangeContaining(
  bpm: number,
  ranges: readonly UserRange[] | null | undefined,
): UserRange | null {
  return (
    ranges?.find(
      (range) =>
        bpm >= (range.lower_bpm_inclusive ?? 0) &&
        (range.upper_bpm_exclusive === null || bpm < range.upper_bpm_exclusive),
    ) ?? null
  );
}
export function alertModeOf(alerts: {
  sound: boolean;
  vibration: boolean;
}): AlertMode {
  return alerts.sound ? "sound" : alerts.vibration ? "vibration" : "silent";
}
export function alertDelivery(mode: AlertMode) {
  return { sound: mode === "sound", vibration: mode === "vibration" };
}
export const alertModeLabels: Record<AlertMode, string> = {
  vibration: "Vibration",
  sound: "Sound",
  silent: "Silent",
};

export function defaultSettings(timezoneId: string): EditableSettings {
  return {
    units: "metric",
    timezone_id: timezoneId,
    theme: "system",
    route_enabled: false,
    hr_ranges_id: null,
    alerts: {
      enabled: false,
      sound: false,
      vibration: false,
      ...provisionalAlertTiming,
    },
  };
}
export function editableSettings(settings: EditableSettings): EditableSettings {
  return {
    units: settings.units,
    timezone_id: settings.timezone_id,
    theme: settings.theme,
    route_enabled: settings.route_enabled,
    hr_ranges_id: settings.hr_ranges_id,
    alerts: { ...settings.alerts },
  };
}

/**
 * Chart-only projection. `payload.active_ms` is the worker processing time;
 * native summary/export use receipt elapsed time and session timeline.
 */
export type HrSample = {
  activeMs: number;
  bpm: number | null;
  segmentId: string | null;
};
const excludedQualityFlags = new Set([
  "suspect_signal",
  "missing",
  "stale",
  "estimated",
]);
export function hrSampleFromRecord(record: {
  payload_json: string;
  segment_id: string | null;
}): HrSample | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(record.payload_json);
  } catch {
    return null;
  }
  if (parsed === null || typeof parsed !== "object") return null;
  const payload = parsed as Record<string, unknown>;
  if (
    typeof payload.active_ms !== "number" ||
    !Number.isFinite(payload.active_ms) ||
    payload.active_ms < 0
  )
    return null;
  const metadata = payload.observation_metadata as
    | { quality_flags?: unknown }
    | undefined;
  const flags = Array.isArray(metadata?.quality_flags)
    ? metadata.quality_flags
    : [];
  const usable =
    typeof payload.bpm === "number" &&
    Number.isInteger(payload.bpm) &&
    payload.bpm > 0 &&
    !(payload.contact_supported === true && payload.contact === false) &&
    flags.includes("valid_format") &&
    !flags.some((flag) => excludedQualityFlags.has(String(flag)));
  return {
    activeMs: payload.active_ms,
    bpm: usable ? (payload.bpm as number) : null,
    segmentId: record.segment_id,
  };
}

export type ChartColumn = { bpm: number | null; pauseBefore: boolean };
export function sessionChartColumns(
  samples: readonly HrSample[],
  activeDurationMs: number,
  staleAfterMs: number,
  maxColumns = 60,
): ChartColumn[] {
  if (!(activeDurationMs > 0) || !(staleAfterMs > 0)) return [];
  const count = Math.max(
    1,
    Math.min(maxColumns, Math.floor(activeDurationMs / staleAfterMs)),
  );
  const width = activeDurationMs / count;
  const weighted = new Array<number>(count).fill(0);
  const covered = new Array<number>(count).fill(0);
  const pauseBefore = new Array<boolean>(count).fill(false);
  const ordered = [...samples].sort((a, b) => a.activeMs - b.activeMs);
  const columnAt = (ms: number) =>
    Math.min(count - 1, Math.max(0, Math.floor(ms / width)));
  ordered.forEach((sample, index) => {
    const next = ordered[index + 1];
    if (next && next.segmentId !== sample.segmentId)
      pauseBefore[columnAt(next.activeMs)] = true;
    if (sample.bpm === null) return;
    const end = Math.min(
      activeDurationMs,
      sample.activeMs + staleAfterMs,
      next ? next.activeMs : Infinity,
    );
    for (
      let column = columnAt(sample.activeMs);
      column < count && column * width < end;
      column++
    ) {
      const overlap =
        Math.min(end, (column + 1) * width) -
        Math.max(sample.activeMs, column * width);
      if (overlap <= 0) continue;
      weighted[column] += sample.bpm * overlap;
      covered[column] += overlap;
    }
  });
  return weighted.map((sum, column) => ({
    bpm: covered[column] > 0 ? Math.round(sum / covered[column]) : null,
    pauseBefore: pauseBefore[column],
  }));
}

export type LiveSample = { atMs: number; bpm: number | null };

/**
 * Display-only interpolation for the S04 counter. The native snapshot remains
 * authoritative: this value is never persisted or used for aggregates.
 */
export function displayActiveMs(
  activeMs: number,
  recording: boolean,
  anchorAtMs: number,
  nowMs: number,
  maxExtrapolationMs = 1500,
): number {
  if (
    !recording ||
    !Number.isFinite(activeMs) ||
    !Number.isFinite(anchorAtMs) ||
    !Number.isFinite(nowMs)
  )
    return activeMs;
  const elapsed = nowMs - anchorAtMs;
  if (elapsed <= 0) return activeMs;
  return activeMs + Math.min(elapsed, Math.max(0, maxExtrapolationMs));
}

/** Monotonic clock for display interpolation; wall-clock changes do not affect it. */
export function monotonicNowMs(): number {
  const clock = (
    globalThis as typeof globalThis & {
      performance?: { now?: () => number };
    }
  ).performance;
  return typeof clock?.now === "function" ? clock.now() : Date.now();
}

export function appendLiveSample(
  samples: readonly LiveSample[],
  sample: LiveSample,
  keepMs = 120000,
): LiveSample[] {
  const last = samples.at(-1);
  if (last && sample.atMs <= last.atMs) return [...samples];
  return [
    ...samples.filter((item) => item.atMs >= sample.atMs - keepMs),
    sample,
  ];
}
export function liveChartColumns(
  samples: readonly LiveSample[],
  nowMs: number,
  windowMs = 90000,
  count = 45,
): ChartColumn[] {
  const width = windowMs / count;
  const start = nowMs - windowMs;
  return Array.from({ length: count }, (_, column) => {
    const from = start + column * width;
    const to = from + width;
    const inside = samples.filter(
      (sample) => sample.atMs >= from && sample.atMs < to,
    );
    const latest = inside.at(-1);
    return {
      bpm: latest && latest.bpm !== null ? latest.bpm : null,
      pauseBefore: false,
    };
  });
}

/** Line-chart projection of the live window; null keeps gaps instead of zero. */
export function liveChartValues(
  samples: readonly LiveSample[],
  nowMs: number,
  windowMs = 90000,
  count = 90,
): (number | null)[] {
  const width = windowMs / count;
  const start = nowMs - windowMs;
  return Array.from({ length: count }, (_, column) => {
    const from = start + column * width;
    const to = from + width;
    const latest = samples
      .filter((sample) => sample.atMs >= from && sample.atMs < to)
      .at(-1);
    return latest?.bpm ?? null;
  });
}
export function describeColumns(columns: readonly ChartColumn[]): {
  min: number | null;
  max: number | null;
  gaps: number;
} {
  const values = columns.flatMap((column) =>
    column.bpm === null ? [] : [column.bpm],
  );
  return {
    min: values.length ? Math.min(...values) : null,
    max: values.length ? Math.max(...values) : null,
    gaps: columns.length - values.length,
  };
}

export const shapeInnerRadius = 0.35;
export type ShapePoint = { x: number; y: number; hasPulse: boolean };
export function shapePoints(columns: readonly ChartColumn[]): ShapePoint[] {
  const { min, max } = describeColumns(columns);
  return columns.map((column, index) => {
    const angle = -Math.PI / 2 + (2 * Math.PI * index) / columns.length;
    const spread =
      min === null || max === null || max === min ? null : max - min;
    const radius =
      column.bpm === null || min === null
        ? shapeInnerRadius
        : shapeInnerRadius +
          (1 - shapeInnerRadius) *
            (spread === null ? 0.5 : (column.bpm - min) / spread);
    return {
      x: 0.5 + 0.5 * radius * Math.cos(angle),
      y: 0.5 + 0.5 * radius * Math.sin(angle),
      hasPulse: column.bpm !== null,
    };
  });
}
export function frozenLiveNow(
  samples: readonly LiveSample[],
  nowMs: number,
  moving: boolean,
): number {
  if (moving) return nowMs;
  const lastFresh = [...samples]
    .reverse()
    .find((sample) => sample.bpm !== null);
  return lastFresh?.atMs ?? nowMs;
}

const pad = (value: number) => String(value).padStart(2, "0");
export function formatClock(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  return `${pad(Math.floor(total / 3600))}:${pad(Math.floor(total / 60) % 60)}:${pad(total % 60)}`;
}
export function formatDuration(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000));
  if (total < 60) return `${total} s`;
  const minutes = Math.floor(total / 60);
  if (minutes < 60) return `${minutes} min`;
  return `${Math.floor(minutes / 60)} h ${pad(minutes % 60)} min`;
}
export function formatDistance(meters: number, units: Units): string {
  return units === "imperial"
    ? `${(meters / 1609.344).toFixed(2)} mi`
    : `${(meters / 1000).toFixed(2)} km`;
}
export function formatPercent(fraction: number): string {
  return `${Math.round(fraction * 100)}%`;
}
export function formatAge(ageMs: number | null): string {
  if (ageMs === null) return "No reading yet";
  return ageMs < 2000
    ? "Updated just now"
    : `Updated ${Math.round(ageMs / 1000)} s ago`;
}
export function formatDateTime(utcMs: number, timeZone: string | null): string {
  const options: Intl.DateTimeFormatOptions = {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  };
  try {
    return new Intl.DateTimeFormat(
      "en",
      timeZone ? { ...options, timeZone } : options,
    ).format(new Date(utcMs));
  } catch {
    return (
      new Date(utcMs).toISOString().slice(0, 16).replace("T", " ") + " UTC"
    );
  }
}
export function formatTime(utcMs: number, timeZone: string | null): string {
  const options: Intl.DateTimeFormatOptions = {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  };
  try {
    return new Intl.DateTimeFormat(
      "en",
      timeZone ? { ...options, timeZone } : options,
    ).format(new Date(utcMs));
  } catch {
    return new Date(utcMs).toISOString().slice(11, 19) + " UTC";
  }
}
export function isValidTimeZone(value: string): boolean {
  if (value.trim().length === 0) return false;
  try {
    new Intl.DateTimeFormat("en", { timeZone: value.trim() });
    return true;
  } catch {
    return false;
  }
}
export function withoutErrorCode(message: string): string {
  return message.replace(/^[A-Z_]+: /, "");
}
