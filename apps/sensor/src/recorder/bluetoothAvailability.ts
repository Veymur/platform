export type BluetoothAvailability =
  | "checking"
  | "off"
  | "permission_denied"
  | "on"
  | "unknown";

export function bluetoothAvailability(
  adapterEnabled: boolean | null | undefined,
  permissionGranted: boolean | null,
): BluetoothAvailability {
  if (adapterEnabled === false) return "off";
  if (permissionGranted === null) return "checking";
  if (!permissionGranted) return "permission_denied";
  return adapterEnabled === true ? "on" : "unknown";
}

export type AutomaticSearchState = {
  availability: BluetoothAvailability;
  recorderReady: boolean;
  alreadySearched: boolean;
  sensorConnected: boolean;
  sensorSelected: boolean;
  discoveryActive: boolean;
};

export function automaticSensorSearchAllowed(
  state: AutomaticSearchState,
): boolean {
  return (
    (state.availability === "on" || state.availability === "unknown") &&
    state.recorderReady &&
    !state.alreadySearched &&
    !state.sensorConnected &&
    !state.sensorSelected &&
    !state.discoveryActive
  );
}
