import { useEffect, useRef, useState } from "react";
import {
  BackHandler,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StatusBar,
  View,
} from "react-native";
import { DiagnosticRecorderScreen } from "./screens/DiagnosticRecorderScreen";
import { ConnectScreen } from "./screens/product/ConnectScreen";
import { HistoryScreen } from "./screens/product/HistoryScreen";
import { ProfileScreen } from "./screens/product/ProfileScreen";
import { RangeEditor } from "./screens/product/RangeEditor";
import { ReadyScreen } from "./screens/product/ReadyScreen";
import { SessionScreen } from "./screens/product/SessionScreen";
import { SummaryScreen } from "./screens/product/SummaryScreen";
import { WelcomeScreen } from "./screens/product/WelcomeScreen";
import { ProductProvider, useProduct } from "./product/ProductContext";
import { withoutErrorCode } from "./product/sessionMath";
import { routePermissionGranted } from "./recorder/permissions";
import { ThemeProvider, useTheme } from "./ui/Theme";
import { Action, Label, Status, Warning } from "./ui/components";
import { spacing } from "./ui/tokens";

const tabs = ["Record", "History", "Profile"] as const;
type Tab = (typeof tabs)[number];
type Overlay =
  | { kind: "summary"; sessionId: string; afterFinish: boolean }
  | { kind: "connect" }
  | null;

function useBack(handler: (() => void) | null) {
  useEffect(() => {
    if (!handler) return;
    const subscription = BackHandler.addEventListener(
      "hardwareBackPress",
      () => {
        handler();
        return true;
      },
    );
    return () => subscription.remove();
  }, [handler]);
}

function Shell() {
  const { colors, isDark, setPreference } = useTheme();
  const { recorder, preferences } = useProduct();
  const { snapshot, controller } = recorder;
  const settings = preferences.preferences?.settings ?? null;
  const [tab, setTab] = useState<Tab>("Record");
  const [overlay, setOverlay] = useState<Overlay>(null);
  const [rangeEditor, setRangeEditor] = useState(false);
  const [diagnostics, setDiagnostics] = useState(false);
  const [historyKey, setHistoryKey] = useState(0);
  const acknowledgedSave = useRef<string | null>(null);
  const routeRestored = useRef(false);
  useEffect(() => {
    if (settings) setPreference(settings.theme);
  }, [settings, setPreference]);
  useEffect(() => {
    const saved = recorder.saved;
    if (!saved || acknowledgedSave.current === saved.sessionId) return;
    acknowledgedSave.current = saved.sessionId;
    setTab("Record");
    setOverlay({
      kind: "summary",
      sessionId: saved.sessionId,
      afterFinish: true,
    });
  }, [recorder.saved]);
  useEffect(() => {
    if (
      routeRestored.current ||
      !settings ||
      !snapshot?.ready ||
      snapshot.sessionId
    )
      return;
    routeRestored.current = true;
    if (!settings.route_enabled) return;
    void routePermissionGranted().then((granted) => {
      if (granted) void controller.setRoute(true);
    });
  }, [settings, snapshot, controller]);
  useEffect(() => {
    if (
      settings &&
      snapshot?.ready &&
      !snapshot.sessionId &&
      !snapshot.connectedDeviceId &&
      !snapshot.selectedDeviceId &&
      overlay === null
    )
      setOverlay({ kind: "connect" });
  }, [settings, snapshot, overlay]);
  useBack(
    overlay
      ? () => setOverlay(null)
      : tab !== "Record"
        ? () => setTab("Record")
        : null,
  );
  const sessionActive = snapshot?.sessionId != null;
  const closeSummary = () => {
    setOverlay(null);
    setHistoryKey((key) => key + 1);
  };
  const body = () => {
    if (Platform.OS !== "android")
      return (
        <Warning
          title="This phone is not supported yet"
          detail="veymur/sensor currently records on Android only."
        />
      );
    if (!snapshot)
      return recorder.error ? (
        <>
          <Warning
            title="The recorder is not available"
            detail={withoutErrorCode(recorder.error)}
          />
          <Action
            title="Try again"
            onPress={() => {
              void controller.retry();
            }}
          />
        </>
      ) : (
        <Status title="Starting the recorder…" />
      );
    if (!snapshot.ready)
      return <Status title="Preparing storage on this phone…" />;
    if (!preferences.loaded)
      return preferences.error ? (
        <>
          <Warning
            title="Couldn't read your settings"
            detail={withoutErrorCode(preferences.error)}
          />
          <Action
            title="Try again"
            onPress={() => {
              void preferences.reload();
            }}
          />
        </>
      ) : (
        <Status title="Loading your settings…" />
      );
    if (!settings) return <WelcomeScreen />;
    if (overlay?.kind === "summary")
      return (
        <SummaryScreen
          key={overlay.sessionId}
          sessionId={overlay.sessionId}
          afterFinish={overlay.afterFinish}
          onClose={() => {
            const backToHistory = !overlay.afterFinish;
            closeSummary();
            if (backToHistory) setTab("History");
          }}
          onDeleted={() => {
            closeSummary();
            setTab("History");
          }}
        />
      );
    if (tab === "History")
      return (
        <HistoryScreen
          key={historyKey}
          onOpen={(id) =>
            setOverlay({ kind: "summary", sessionId: id, afterFinish: false })
          }
          onRecord={() => setTab("Record")}
        />
      );
    if (tab === "Profile")
      return (
        <ProfileScreen
          onEditRanges={() => setRangeEditor(true)}
          onOpenDiagnostics={() => setDiagnostics(true)}
        />
      );
    if (sessionActive) return <SessionScreen />;
    if (
      overlay?.kind === "connect" ||
      (!snapshot.connectedDeviceId && !snapshot.selectedDeviceId)
    )
      return (
        <ConnectScreen
          onDone={() => setOverlay(null)}
          onBack={
            snapshot.selectedDeviceId ? () => setOverlay(null) : undefined
          }
        />
      );
    return (
      <ReadyScreen
        onChangeSensor={() => setOverlay({ kind: "connect" })}
        onOpenHistory={() => setTab("History")}
        onEditRanges={() => setRangeEditor(true)}
      />
    );
  };
  const showTabs = settings !== null && overlay?.kind !== "summary";
  return (
    <View
      style={{
        flex: 1,
        paddingTop: StatusBar.currentHeight ?? 0,
        backgroundColor: colors.bg,
      }}
    >
      <StatusBar
        barStyle={isDark ? "light-content" : "dark-content"}
        backgroundColor={colors.bg}
      />
      <ScrollView
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={{ padding: 20, paddingBottom: 40, gap: 20 }}
      >
        {body()}
      </ScrollView>
      {showTabs && (
        <View
          accessibilityRole="tablist"
          style={{
            flexDirection: "row",
            flexWrap: "wrap",
            padding: 8,
            paddingBottom: spacing.navigationBar + 8,
            gap: 8,
            backgroundColor: colors.raised,
            borderTopWidth: 1,
            borderColor: colors.rule,
          }}
        >
          {tabs.map((value) => (
            <Pressable
              key={value}
              accessibilityRole="tab"
              accessibilityState={{ selected: tab === value }}
              accessibilityLabel={
                value === "Record" && sessionActive
                  ? "Record, recording in progress"
                  : value
              }
              onPress={() => {
                setOverlay((current) =>
                  current?.kind === "connect" ? current : null,
                );
                setTab(value);
              }}
              style={{
                flexGrow: 1,
                flexBasis: 88,
                minHeight: 48,
                padding: 8,
                justifyContent: "center",
                borderRadius: 10,
                backgroundColor:
                  tab === value ? colors["tide-wash"] : colors.raised,
              }}
            >
              <Label
                style={{
                  textAlign: "center",
                  fontWeight: tab === value ? "600" : "400",
                }}
              >
                {tab === value ? "• " : ""}
                {value}
                {value === "Record" && sessionActive ? " ●" : ""}
              </Label>
            </Pressable>
          ))}
        </View>
      )}
      <RangeEditor
        visible={rangeEditor}
        onClose={() => setRangeEditor(false)}
      />
      <Modal
        visible={diagnostics}
        animationType="none"
        onRequestClose={() => setDiagnostics(false)}
      >
        <View
          style={{
            flex: 1,
            paddingTop: StatusBar.currentHeight ?? 0,
            backgroundColor: colors.bg,
          }}
        >
          <Action
            title="Back to the app"
            secondary
            onPress={() => setDiagnostics(false)}
          />
          <DiagnosticRecorderScreen />
        </View>
      </Modal>
    </View>
  );
}
export function SensorApp() {
  return (
    <ThemeProvider>
      <ProductProvider>
        <Shell />
      </ProductProvider>
    </ThemeProvider>
  );
}
