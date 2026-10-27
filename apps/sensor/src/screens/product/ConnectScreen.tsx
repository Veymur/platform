import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Linking, StyleSheet, View } from "react-native";
import {
  Action,
  Card,
  Chip,
  KeyValue,
  Label,
  Status,
  Warning,
} from "../../ui/components";
import { ContourArt } from "../../ui/Contours";
import { Icon, type IconName } from "../../ui/Icon";
import { AppBar, BackButton, Screen } from "../../ui/Screen";
import { useTheme } from "../../ui/Theme";
import { Body, Caption, H2 } from "../../ui/Typography";
import { fonts } from "../../ui/tokens";
import { useProduct } from "../../product/ProductContext";
import { productPermissions } from "../../recorder/permissions";
import { withoutErrorCode } from "../../product/sessionMath";
import { Muted } from "./shared";

function Radar({
  seed,
  icon,
  height = 176,
  wobble = 0.16,
}: {
  seed: number;
  icon: IconName;
  height?: number;
  wobble?: number;
}) {
  const { colors } = useTheme();
  return (
    <View style={[styles.radar, { height }]}>
      <View pointerEvents="none" style={styles.radarArt}>
        <ContourArt
          rings={6}
          baseR={40}
          gap={12}
          size={220}
          seed={seed}
          wobble={wobble}
        />
      </View>
      <View style={[styles.radarCore, { backgroundColor: colors.solid }]}>
        <Icon name={icon} size={28} color={colors["on-solid"]} />
      </View>
    </View>
  );
}

function SensorOption({
  name,
  id,
  disabled,
  onConnect,
}: {
  name: string;
  id: string;
  disabled: boolean;
  onConnect: () => void;
}) {
  const { colors } = useTheme();
  return (
    <View
      style={[
        styles.sensorOption,
        { borderColor: colors.rule, backgroundColor: colors.raised },
      ]}
    >
      <View style={[styles.sensorLead, { backgroundColor: colors.sunk }]}>
        <Icon name="pulse" size={20} color={colors["ink-2"]} />
      </View>
      <View style={styles.sensorBody}>
        <Label style={{ fontFamily: fonts.uiMedium }}>{name}</Label>
        <Label
          style={{
            color: colors["ink-3"],
            fontFamily: fonts.mono,
            fontSize: 14,
            lineHeight: 20.3,
          }}
        >
          ID {id}
        </Label>
      </View>
      <Action
        title="Connect"
        size="sm"
        block={false}
        disabled={disabled}
        onPress={onConnect}
      />
    </View>
  );
}

export function ConnectScreen({
  onDone,
  onBack,
}: {
  onDone: () => void;
  onBack?: () => void;
}) {
  const { recorder } = useProduct();
  const { snapshot, controller, pending, error, message } = recorder;
  const [granted, setGranted] = useState<boolean | null>(null);
  const [settingsError, setSettingsError] = useState<string | null>(null);
  const [lastTarget, setLastTarget] = useState<string | null>(null);
  const autoSearched = useRef(false);
  const refreshPermission = useCallback(async () => {
    const value = await productPermissions.bluetoothGranted();
    setGranted(value);
    return value;
  }, []);
  const search = useCallback(async () => {
    setSettingsError(null);
    setLastTarget(null);
    await controller.search();
    await refreshPermission();
  }, [controller, refreshPermission]);
  const connect = (id: string) => {
    setLastTarget(id);
    void controller.connect(id);
  };
  const openSettings = () => {
    setSettingsError(null);
    void Linking.openSettings().catch(() =>
      setSettingsError(
        "Could not open system settings. Open them from your phone settings.",
      ),
    );
  };
  useEffect(() => {
    void refreshPermission();
  }, [refreshPermission]);
  useEffect(() => {
    if (
      !granted ||
      autoSearched.current ||
      !snapshot?.ready ||
      snapshot.connectedDeviceId ||
      snapshot.selectedDeviceId ||
      snapshot.discoveryActive
    )
      return;
    autoSearched.current = true;
    void search();
  }, [granted, snapshot, search]);
  const appBar = (
    <AppBar
      title="Connect a sensor"
      left={onBack ? <BackButton onPress={onBack} /> : undefined}
    />
  );
  const frame = (content: ReactNode, actions?: ReactNode) => (
    <Screen appBar={appBar} actions={actions}>
      {content}
    </Screen>
  );
  if (granted === null || !snapshot)
    return frame(<Status live title="Checking Bluetooth permission…" />);
  const busy = pending !== null;
  const connected = snapshot.connectedDeviceId;
  const selected = snapshot.selectedDeviceId;
  const failedTarget =
    !connected &&
    lastTarget !== null &&
    pending === null &&
    (snapshot.errorCode === "CONNECTION_ERROR" || error !== null)
      ? lastTarget
      : null;
  const connecting =
    !connected &&
    lastTarget !== null &&
    !failedTarget &&
    !snapshot.discoveryActive;
  if (!granted)
    return frame(
      <>
        <Radar seed={2} icon="bluetooth" height={144} wobble={0.12} />
        <H2>Why Bluetooth?</H2>
        <Body>
          veymur/sensor reads your heart-rate sensor over Bluetooth. That is the
          only permission we ask for now.
        </Body>
        <Muted>
          Location and notifications are requested later, and only if you turn
          those features on.
        </Muted>
        {message && message.startsWith("Bluetooth permission denied") && (
          <Warning
            title="Bluetooth is not allowed"
            detail="Without Bluetooth the app can't find your sensor. Nothing was recorded. Try again or allow Bluetooth in system settings."
          />
        )}
        {settingsError && <Warning title={settingsError} />}
      </>,
      <>
        <Action
          title={busy ? "Waiting…" : "Allow Bluetooth"}
          size="lg"
          disabled={busy}
          onPress={() => {
            void search();
          }}
        />
        {onBack && <Action title="Not now" variant="quiet" onPress={onBack} />}
      </>,
    );
  if (connected) {
    const device = snapshot.devices.find((item) => item.id === connected);
    return frame(
      <>
        <Chip label="Connected" tone="tide" icon="check" />
        <H2>{device?.name ?? "Polar Verity Sense"} is connected</H2>
        <Card variant="flat">
          <KeyValue
            label="Battery"
            value={
              snapshot.batteryPercent === null
                ? "Not reported yet"
                : `${snapshot.batteryPercent}%`
            }
          />
          <KeyValue
            label="Pulse"
            value={
              snapshot.hrStatus === "fresh" && snapshot.hr !== null
                ? `${snapshot.hr} bpm`
                : "Waiting for the first reading…"
            }
          />
        </Card>
        <Caption>
          Pulse appears as soon as the sensor sends its first reading.
        </Caption>
      </>,
      <Action title="Continue" size="lg" onPress={onDone} />,
    );
  }
  if (failedTarget)
    return frame(
      <>
        <Chip label="Not connected" tone="ember" icon="attention" />
        <H2>Couldn't connect to the sensor</H2>
        <Body>
          Check that the sensor is on and worn snugly, then try again. Nothing
          was recorded.
        </Body>
        {error && <Muted>{withoutErrorCode(error)}</Muted>}
        {settingsError && <Warning title={settingsError} />}
      </>,
      <>
        <Action
          title="Try again"
          disabled={busy}
          onPress={() => connect(failedTarget)}
        />
        <Action
          title="Choose another sensor"
          variant="secondary"
          disabled={busy}
          onPress={() => {
            void search();
          }}
        />
        <Action
          title="Open system settings"
          variant="quiet"
          onPress={openSettings}
        />
      </>,
    );
  if (connecting)
    return frame(
      <>
        <Radar seed={4} icon="pulse" wobble={0.1} />
        <H2>Connecting…</H2>
        <Muted>Polar Verity Sense · ID {selected ?? lastTarget}</Muted>
      </>,
      <Action
        title="Choose another sensor"
        variant="secondary"
        disabled={busy}
        onPress={() => {
          void search();
        }}
      />,
    );
  const searching = snapshot.discoveryActive || pending === "Search";
  return frame(
    <>
      <Radar
        seed={searching ? 9 : 2}
        icon="bluetooth"
        wobble={searching ? 0.16 : 0.12}
      />
      <View style={{ gap: 8 }}>
        <H2>{searching ? "Looking for sensors" : "Select your sensor"}</H2>
        <Muted>
          {searching
            ? "Turn your sensor on and keep it close to the phone."
            : snapshot.devices.length
              ? "Choose the sensor you are wearing."
              : "No sensors found. Turn the sensor on, keep it close and search again."}
        </Muted>
      </View>
      {snapshot.devices.map((device) => (
        <SensorOption
          key={device.id}
          name={device.name || "Polar Verity Sense"}
          id={device.id}
          disabled={busy}
          onConnect={() => connect(device.id)}
        />
      ))}
      <Caption>
        When several sensors are nearby you choose one yourself. We never
        connect to the nearest one for you.
      </Caption>
      {snapshot.errorCode === "DISCOVERY_ERROR" && (
        <Warning
          title="Sensor search failed"
          detail="Check that Bluetooth is on, then search again."
        />
      )}
      {error && !failedTarget && <Warning title={withoutErrorCode(error)} />}
    </>,
    searching ? (
      <Action
        title="Cancel search"
        variant="secondary"
        size="lg"
        disabled={busy}
        onPress={() => {
          void controller.cancelSearch();
        }}
      />
    ) : (
      <Action
        title="Search again"
        variant="secondary"
        size="lg"
        disabled={busy}
        onPress={() => {
          void search();
        }}
      />
    ),
  );
}

const styles = StyleSheet.create({
  radar: {
    alignItems: "center",
    justifyContent: "center",
  },
  radarArt: {
    position: "absolute",
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
  },
  radarCore: {
    width: 64,
    height: 64,
    borderRadius: 32,
    alignItems: "center",
    justifyContent: "center",
  },
  sensorOption: {
    flexDirection: "row",
    alignItems: "center",
    gap: 16,
    padding: 16,
    borderWidth: 1.5,
    borderRadius: 16,
  },
  sensorLead: {
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: "center",
    justifyContent: "center",
  },
  sensorBody: { flex: 1, minWidth: 0, gap: 2 },
});
