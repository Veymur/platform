import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { View } from "react-native";
import { Action, Card, Label, Status, Warning } from "../../ui/components";
import { AppBar, BackButton, Screen } from "../../ui/Screen";
import { useProduct } from "../../product/ProductContext";
import { productPermissions } from "../../recorder/permissions";
import { withoutErrorCode } from "../../product/sessionMath";
import { Muted, OpenSettingsAction, Subheading } from "./shared";

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
  const frame = (content: ReactNode) => (
    <Screen
      appBar={
        <AppBar
          title="Connect a sensor"
          left={onBack ? <BackButton onPress={onBack} /> : undefined}
        />
      }
    >
      {content}
    </Screen>
  );
  if (granted === null || !snapshot)
    return frame(<Status live title="Checking Bluetooth permission…" />);
  const busy = pending !== null;
  const connected = snapshot.connectedDeviceId;
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
        <Card>
          <Subheading>Why Bluetooth?</Subheading>
          <Label>
            veymur/sensor reads your heart-rate sensor over Bluetooth. That is
            the only permission we ask for now.
          </Label>
          <Muted>
            Location and notifications are requested later, and only if you turn
            those features on.
          </Muted>
          <Action
            title={busy ? "Waiting…" : "Continue"}
            disabled={busy}
            onPress={() => {
              void search();
            }}
          />
        </Card>
        {message && message.startsWith("Bluetooth permission denied") && (
          <Warning
            title="Bluetooth is not allowed"
            detail="Without Bluetooth the app can't find your sensor. Nothing was recorded. Try again or allow Bluetooth in system settings."
          />
        )}
        <OpenSettingsAction onError={setSettingsError} />
        {settingsError && <Warning title={settingsError} />}
      </>,
    );
  if (connected) {
    const device = snapshot.devices.find((item) => item.id === connected);
    return frame(
      <>
        <Card>
          <Status
            live
            title="✓ Connected"
            detail={`${device?.name ?? "Polar Verity Sense"} · ID ${connected}`}
          />
          <Label>
            Battery:{" "}
            {snapshot.batteryPercent === null
              ? "not reported yet"
              : `${snapshot.batteryPercent}%`}
          </Label>
          <Label>
            Pulse:{" "}
            {snapshot.hrStatus === "fresh" && snapshot.hr !== null
              ? `${snapshot.hr} bpm`
              : "Waiting for the first reading…"}
          </Label>
          <Muted>
            Pulse appears as soon as the sensor sends its first reading.
          </Muted>
          <Action title="Continue" onPress={onDone} />
          <Action
            title="Choose another sensor"
            secondary
            disabled={busy}
            onPress={() => {
              void search();
            }}
          />
        </Card>
      </>,
    );
  }
  if (failedTarget)
    return frame(
      <>
        <Card>
          <Status
            live
            title="Not connected"
            detail="Couldn't connect to the sensor"
          />
          <Label>
            Check that the sensor is on and worn snugly, then try again. Nothing
            was recorded.
          </Label>
          {error && <Muted>{withoutErrorCode(error)}</Muted>}
          <Action
            title="Try again"
            disabled={busy}
            onPress={() => connect(failedTarget)}
          />
          <Action
            title="Choose another sensor"
            secondary
            disabled={busy}
            onPress={() => {
              void search();
            }}
          />
          <OpenSettingsAction onError={setSettingsError} />
        </Card>
        {settingsError && <Warning title={settingsError} />}
      </>,
    );
  if (connecting)
    return frame(
      <>
        <Card>
          <Status
            live
            title="Connecting…"
            detail={`Polar Verity Sense · ID ${snapshot.selectedDeviceId}`}
          />
          <Muted>{snapshot.status}</Muted>
          <Action
            title="Choose another sensor"
            secondary
            disabled={busy}
            onPress={() => {
              void search();
            }}
          />
        </Card>
      </>,
    );
  return frame(
    <>
      <Card>
        {snapshot.discoveryActive || pending === "Search" ? (
          <Status
            live
            title="Looking for sensors"
            detail="Turn your sensor on and keep it close to the phone."
          />
        ) : (
          <Status
            live
            title={
              snapshot.devices.length
                ? "Select your sensor"
                : "No sensors found"
            }
            detail={
              snapshot.devices.length
                ? "Choose the sensor you are wearing."
                : "Turn your sensor on, keep it close and search again."
            }
          />
        )}
        {snapshot.devices.map((device) => (
          <View key={device.id} style={{ gap: 8 }}>
            <Label>
              {device.name} · ID {device.id}
            </Label>
            <Action
              title={`Connect ${device.id}`}
              disabled={busy}
              onPress={() => connect(device.id)}
            />
          </View>
        ))}
        <Muted>
          When several sensors are nearby you choose one yourself. We never
          connect to the nearest one for you.
        </Muted>
        {snapshot.discoveryActive ? (
          <Action
            title="Cancel search"
            secondary
            disabled={busy}
            onPress={() => {
              void controller.cancelSearch();
            }}
          />
        ) : (
          <Action
            title="Search again"
            secondary
            disabled={busy}
            onPress={() => {
              void search();
            }}
          />
        )}
      </Card>
      {snapshot.errorCode === "DISCOVERY_ERROR" && (
        <Warning
          title="Sensor search failed"
          detail="Check that Bluetooth is on, then search again."
        />
      )}
      {error && !failedTarget && <Warning title={withoutErrorCode(error)} />}
    </>,
  );
}
