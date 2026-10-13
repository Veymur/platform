import { requireNativeModule } from 'expo';
import { Platform } from 'react-native';
import type { RecorderModuleInfo } from './types';

export type { RecorderModuleInfo } from './types';

type NativeRecorderModule = {
  getModuleInfo(): Promise<unknown>;
};

export async function getModuleInfo(): Promise<RecorderModuleInfo> {
  if (Platform.OS !== 'android') {
    throw new Error('Veymur recorder supports Android only. The iOS recorder is not implemented.');
  }
  const result = await requireNativeModule<NativeRecorderModule>('VeymurRecorder').getModuleInfo();
  if (result === null || typeof result !== 'object' ||
      !('moduleName' in result) || result.moduleName !== 'VeymurRecorder' ||
      !('platform' in result) || result.platform !== 'android' ||
      !('contractVersion' in result) || result.contractVersion !== 1 ||
      !('recorderImplemented' in result) || result.recorderImplemented !== false) {
    throw new Error('Incompatible VeymurRecorder native binary. Rebuild and install the development build.');
  }
  return {
    moduleName: result.moduleName,
    platform: result.platform,
    contractVersion: result.contractVersion,
    recorderImplemented: result.recorderImplemented,
  };
}
