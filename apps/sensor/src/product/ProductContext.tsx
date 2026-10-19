import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  type PropsWithChildren,
} from "react";
import { productPermissions } from "../recorder/permissions";
import { useRecorder } from "../recorder/useRecorder";
import { appendLiveSample, type LiveSample, type Units } from "./sessionMath";
import {
  deviceTimeZone,
  usePreferences,
  type PreferencesState,
} from "./usePreferences";

type Recorder = ReturnType<typeof useRecorder>;
export type ProductState = {
  recorder: Recorder;
  preferences: PreferencesState;
  liveSamples: readonly LiveSample[];
  units: Units;
  timeZone: string;
  nowMs: number;
};
const ProductContext = createContext<ProductState | null>(null);
export function ProductProvider({ children }: PropsWithChildren) {
  const recorder = useRecorder(productPermissions);
  const preferences = usePreferences(recorder.snapshot?.ready === true);
  const [liveSamples, setLiveSamples] = useState<readonly LiveSample[]>([]);
  const [nowMs, setNowMs] = useState(() => Date.now());
  const lastRevision = useRef(-1);
  const snapshot = recorder.snapshot;
  useEffect(() => {
    if (!snapshot || snapshot.revision === lastRevision.current) return;
    lastRevision.current = snapshot.revision;
    const now = Date.now();
    setNowMs(now);
    if (snapshot.sessionState === "paused") return;
    const sample =
      snapshot.hrStatus === "fresh" &&
      snapshot.hr !== null &&
      snapshot.hrAgeMs !== null &&
      snapshot.sessionState === "recording"
        ? { atMs: now - snapshot.hrAgeMs, bpm: snapshot.hr }
        : { atMs: now, bpm: null };
    setLiveSamples((samples) => appendLiveSample(samples, sample));
  }, [snapshot]);
  const settings = preferences.preferences?.settings ?? null;
  const value: ProductState = {
    recorder,
    preferences,
    liveSamples,
    nowMs,
    units: settings?.units ?? "metric",
    timeZone: settings?.timezone_id ?? deviceTimeZone(),
  };
  return (
    <ProductContext.Provider value={value}>{children}</ProductContext.Provider>
  );
}
export function useProduct(): ProductState {
  const value = useContext(ProductContext);
  if (!value) throw new Error("ProductProvider is required");
  return value;
}
