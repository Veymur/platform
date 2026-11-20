export type HeartRateScale = {
  min: number;
  max: number;
  ticks: number[];
};

export type HeartRateBand = { min: number; max: number };

export type HeartRateScaleOptions = {
  paddingBpm: number;
  minimumSpanBpm: number;
  maximumIntervals: number;
};

export const heartRateScaleDefaults: HeartRateScaleOptions = {
  paddingBpm: 5,
  minimumSpanBpm: 40,
  maximumIntervals: 4,
};

const preferredTickSteps = [10, 20, 25, 50, 100];

function scaledValues(
  values: readonly (number | null)[],
  band: HeartRateBand | null,
): number[] {
  const usable = values.filter(
    (value): value is number => value !== null && Number.isFinite(value),
  );
  if (band && Number.isFinite(band.min) && Number.isFinite(band.max))
    usable.push(band.min, band.max);
  return usable;
}

function intervalCount(lower: number, upper: number, step: number): number {
  return Math.ceil(upper / step) - Math.floor(lower / step);
}

function tickStepFor(
  lower: number,
  upper: number,
  maximumIntervals: number,
): number {
  const preferred = preferredTickSteps.find(
    (step) => intervalCount(lower, upper, step) <= maximumIntervals,
  );
  if (preferred !== undefined) return preferred;
  const largestPreferred = preferredTickSteps[preferredTickSteps.length - 1];
  let step = largestPreferred;
  while (intervalCount(lower, upper, step) > maximumIntervals)
    step += largestPreferred;
  return step;
}

export function heartRateScale(
  values: readonly (number | null)[],
  band: HeartRateBand | null = null,
  options: HeartRateScaleOptions = heartRateScaleDefaults,
): HeartRateScale | null {
  const usable = scaledValues(values, band);
  if (usable.length === 0) return null;
  const lowest = usable.reduce((low, value) => Math.min(low, value));
  const highest = usable.reduce((high, value) => Math.max(high, value));
  const center = (lowest + highest) / 2;
  const halfSpan = Math.max(
    (highest - lowest) / 2 + options.paddingBpm,
    options.minimumSpanBpm / 2,
  );
  let lower = center - halfSpan;
  let upper = center + halfSpan;
  if (lower < 0) {
    upper -= lower;
    lower = 0;
  }
  const step = tickStepFor(lower, upper, options.maximumIntervals);
  const min = Math.floor(lower / step) * step;
  const max = Math.ceil(upper / step) * step;
  const ticks = Array.from(
    { length: Math.round((max - min) / step) + 1 },
    (_, index) => min + index * step,
  );
  return { min, max, ticks };
}

export function stableHeartRateScale(
  previous: HeartRateScale | null,
  values: readonly (number | null)[],
  band: HeartRateBand | null = null,
  options: HeartRateScaleOptions = heartRateScaleDefaults,
): HeartRateScale | null {
  const next = heartRateScale(values, band, options);
  if (next === null) return previous;
  if (previous === null) return next;
  const usable = scaledValues(values, band);
  const fitsPrevious = usable.every(
    (value) => value >= previous.min && value <= previous.max,
  );
  const previousSpan = previous.max - previous.min;
  const nextSpan = next.max - next.min;
  return fitsPrevious && nextSpan * 2 > previousSpan ? previous : next;
}
