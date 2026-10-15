import { Alert, PermissionsAndroid } from 'react-native';
import type { RecorderPermissions } from './RecorderController';
const P = PermissionsAndroid.PERMISSIONS;
const granted = PermissionsAndroid.RESULTS.GRANTED;
function confirm(title: string, message: string, positive: string, negative: string, cancelable = true): Promise<boolean> {
  return new Promise(resolve => Alert.alert(title, message, [
    { text: negative, style: 'cancel', onPress: () => resolve(false) },
    { text: positive, onPress: () => resolve(true) },
  ], { cancelable, onDismiss: () => resolve(false) }));
}
export const recorderPermissions: RecorderPermissions = {
  async bluetoothGranted() {
    const results = await Promise.all([PermissionsAndroid.check(P.BLUETOOTH_SCAN), PermissionsAndroid.check(P.BLUETOOTH_CONNECT)]);
    return results.every(Boolean);
  },
  async requestBluetooth() {
    if (await this.bluetoothGranted()) return true;
    if (!await confirm('Connect your sensor', 'Bluetooth is required to find and connect to Polar Verity Sense.', 'Continue', 'Cancel')) return false;
    const result = await PermissionsAndroid.requestMultiple([P.BLUETOOTH_SCAN, P.BLUETOOTH_CONNECT]);
    return result[P.BLUETOOTH_SCAN] === granted && result[P.BLUETOOTH_CONNECT] === granted;
  },
  async requestRoute() {
    if (await PermissionsAndroid.check(P.ACCESS_FINE_LOCATION)) return true;
    const result = await PermissionsAndroid.requestMultiple([P.ACCESS_FINE_LOCATION, P.ACCESS_COARSE_LOCATION]);
    return result[P.ACCESS_FINE_LOCATION] === granted;
  },
  async notifications() {
    if (await PermissionsAndroid.check(P.POST_NOTIFICATIONS)) return;
    if (await confirm('Sensor notification', 'Allow the ongoing notification for recording or diagnostics? Denying permission does not block either action.', 'Allow', 'Continue without notifications', false)) {
      await PermissionsAndroid.request(P.POST_NOTIFICATIONS);
    }
  },
  confirmDeleteDiagnostic: id => confirm('Delete diagnostic capture?', `Delete ${id} and its temporary ZIP copies from this phone? Files already saved or shared elsewhere remain there.`, 'Delete capture', 'Keep capture'),
  confirmFinish: () => confirm('Finish recording?', 'Save the available data on this phone?', 'Save recording', 'Continue recording'),
};
