import { requireNativeModule } from 'expo';
import { Platform } from 'react-native';
import type { NativeRecorderModule, RecorderModuleInfo } from './types';
export type * from './types';

export function nativeRecorder(): NativeRecorderModule {
  if (Platform.OS !== 'android') {
    throw Object.assign(new Error('Veymur recorder supports Android only. The iOS recorder is not implemented.'), { code: 'UNSUPPORTED_PLATFORM' });
  }
  try { return requireNativeModule<NativeRecorderModule>('VeymurRecorder'); }
  catch (cause) { throw Object.assign(new Error('Recorder native module unavailable. Install the development build.', { cause }), { code: 'SERVICE_UNAVAILABLE' }); }
}
export async function getModuleInfo(): Promise<RecorderModuleInfo> {
  const result = await nativeRecorder().getModuleInfo();
  if (result === null || typeof result !== 'object' ||
      !('moduleName' in result) || result.moduleName !== 'VeymurRecorder' ||
      !('platform' in result) || result.platform !== 'android' ||
      !('contractVersion' in result) || result.contractVersion !== 5 ||
      !('recorderImplemented' in result) || result.recorderImplemented !== true) {
    throw Object.assign(new Error('Incompatible VeymurRecorder native binary. Rebuild and install the development build.'), { code: 'INCOMPATIBLE_BINARY' });
  }
  return { moduleName: result.moduleName, platform: result.platform, contractVersion: result.contractVersion, recorderImplemented: result.recorderImplemented };
}
