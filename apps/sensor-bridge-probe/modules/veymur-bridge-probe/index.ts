import { requireNativeModule } from 'expo';
import { Platform } from 'react-native';

export type ProbeResult = {
  module: 'VeymurBridgeProbe';
  polarSdkVersion: string;
  sqlCipherVersion: string;
  encryptedRoundTrip: boolean;
  abi: string;
  androidApi: number;
};

export async function probe(): Promise<ProbeResult> {
  if (Platform.OS !== 'android') {
    throw new Error('This dependency probe supports Android only. iOS is unverified.');
  }
  return requireNativeModule<{ probe(): Promise<ProbeResult> }>('VeymurBridgeProbe').probe();
}
