import { useEffect, useRef, useState } from "react";
import { BackHandler, Modal, Platform, StatusBar, View } from "react-native";
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
import { Action, Status, Warning } from "./ui/components";
import { Icon } from "./ui/Icon";
import { Screen, TabBar } from "./ui/Screen";
import { useInsets } from "./ui/Insets";

const tabs = ["Record", "History", "Profile"] as const;
const tabIcons = {
  Record: "record",
  History: "history",
  Profile: "profile",
} as const;
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
  const insets = useInsets();
  const { recorder, preferences } = useProduct();
  const { snapshot, controller } = recorder;
  const settings = preferences.preferences?.settings ?? null;
  const [tab, setTab] = useState<Tab>("Record");
  const [overlay, setOverlay] = useState<Overlay>(null);
  const [rangeEditor, setRangeEditor] = useState(false);
  const [diagnostics, setDiagnostics] = useState(false);
  const [historyKey, setHistoryKey] = useState(0);
  const [showWelcome, setShowWelcome] = useState(false);
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
        <Screen>
          <Warning
            title="This phone is not supported yet"
            detail="veymur/sensor currently records on Android only."
          />
        </Screen>
      );
    if (!snapshot)
      return (
        <Screen>
          {recorder.error ? (
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
          )}
        </Screen>
      );
    if (!snapshot.ready)
      return (
        <Screen>
          <Status title="Preparing storage on this phone…" />
        </Screen>
      );
    if (!preferences.loaded)
      return (
        <Screen>
          {preferences.error ? (
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
          )}
        </Screen>
      );
    if (!settings) return <WelcomeScreen />;
    if (showWelcome)
      return <WelcomeScreen onContinue={() => setShowWelcome(false)} />;
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
            snapshot.selectedDeviceId
              ? () => setOverlay(null)
              : () => setShowWelcome(true)
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
  const connectScreen =
    settings !== null &&
    tab === "Record" &&
    !sessionActive &&
    overlay?.kind !== "summary" &&
    (overlay?.kind === "connect" ||
      (!snapshot?.connectedDeviceId && !snapshot?.selectedDeviceId));
  const showTabs =
    settings !== null &&
    !showWelcome &&
    overlay?.kind !== "summary" &&
    (!sessionActive || tab !== "Record") &&
    !connectScreen;
  return (
    <View
      style={{
        flex: 1,
        paddingTop: insets.top,
        backgroundColor: colors.bg,
      }}
    >
      <StatusBar
        barStyle={isDark ? "light-content" : "dark-content"}
        backgroundColor={colors.bg}
      />
      <View style={{ flex: 1 }}>{body()}</View>
      {showTabs && (
        <TabBar
          items={tabs.map((value) => ({
            key: value,
            label: value,
            active: tab === value,
            icon: (
              <Icon
                name={tabIcons[value]}
                size={20}
                color={tab === value ? colors.tide : colors["ink-3"]}
              />
            ),
            accessibilityLabel:
              value === "Record" && sessionActive
                ? "Record, recording in progress"
                : value,
            onPress: () => {
              setOverlay((current) =>
                current?.kind === "connect" ? current : null,
              );
              setTab(value);
            },
          }))}
        />
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
            paddingTop: insets.top,
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
