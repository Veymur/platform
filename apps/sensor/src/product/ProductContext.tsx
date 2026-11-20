import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  type PropsWithChildren,
} from "react";
import { AppState, Dimensions } from "react-native";
import { productPermissions } from "../recorder/permissions";
import { recorderClient, type NetworkStatus } from "../recorder/RecorderClient";
import {
  fallbackInsets,
  InsetsProvider,
  type ScreenInsets,
} from "../ui/Insets";
import { useRecorder } from "../recorder/useRecorder";
import {
  liveSamplesAfterSnapshot,
  type LiveSample,
  type Units,
} from "./sessionMath";
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
  network: NetworkStatus | null;
};
function useScreenInsets(): ScreenInsets {
  const [insets, setInsets] = useState<ScreenInsets>(fallbackInsets);
  useEffect(() => {
    let active = true;
    const refresh = () => {
      void recorderClient
        .getSystemInsets()
        .then((value) => {
          if (active)
            setInsets({ top: value.top, bottom: value.bottom, known: true });
        })
        .catch(() => undefined);
    };
    refresh();
    const retry = setTimeout(refresh, 500);
    const dimensions = Dimensions.addEventListener("change", refresh);
    const appState = AppState.addEventListener("change", (state) => {
      if (state === "active") refresh();
    });
    return () => {
      active = false;
      clearTimeout(retry);
      dimensions.remove();
      appState.remove();
    };
  }, []);
  return insets;
}
function useNetworkStatus(): NetworkStatus | null {
  const [network, setNetwork] = useState<NetworkStatus | null>(null);
  useEffect(() => {
    try {
      return recorderClient.subscribeNetwork(setNetwork, () =>
        setNetwork(null),
      );
    } catch {
      return undefined;
    }
  }, []);
  return network;
}
const ProductContext = createContext<ProductState | null>(null);
export function ProductProvider({ children }: PropsWithChildren) {
  const recorder = useRecorder(productPermissions);
  const preferences = usePreferences(recorder.snapshot?.ready === true);
  const [liveSamples, setLiveSamples] = useState<readonly LiveSample[]>([]);
  const [nowMs, setNowMs] = useState(() => Date.now());
  const lastRevision = useRef(-1);
  const liveSamplesSessionId = useRef<string | null>(null);
  const insets = useScreenInsets();
  const network = useNetworkStatus();
  const snapshot = recorder.snapshot;
  useEffect(() => {
    if (!snapshot || snapshot.revision === lastRevision.current) return;
    lastRevision.current = snapshot.revision;
    const now = Date.now();
    setNowMs(now);
    const samplesSessionId = liveSamplesSessionId.current;
    liveSamplesSessionId.current = snapshot.sessionId;
    setLiveSamples((samples) =>
      liveSamplesAfterSnapshot(samples, samplesSessionId, snapshot, now),
    );
  }, [snapshot]);
  const settings = preferences.preferences?.settings ?? null;
  const value: ProductState = {
    recorder,
    preferences,
    liveSamples,
    nowMs,
    network,
    units: settings?.units ?? "metric",
    timeZone: settings?.timezone_id ?? deviceTimeZone(),
  };
  return (
    <InsetsProvider value={insets}>
      <ProductContext.Provider value={value}>
        {children}
      </ProductContext.Provider>
    </InsetsProvider>
  );
}
export function useProduct(): ProductState {
  const value = useContext(ProductContext);
  if (!value) throw new Error("ProductProvider is required");
  return value;
}
