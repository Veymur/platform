import type { ExpoConfig } from 'expo/config';

const config: ExpoConfig = {
  name: 'Veymur Sensor',
  slug: 'veymur-sensor',
  version: '0.0.1',
  platforms: ['android'],
  android: { package: 'com.veymur.sensor', allowBackup: false },
  plugins: [
    'expo-dev-client',
    ['expo-build-properties', {
      android: {
        minSdkVersion: 33,
        compileSdkVersion: 36,
        targetSdkVersion: 36,
        buildToolsVersion: '36.0.0',
        kotlinVersion: '2.3.20',
        extraMavenRepos: ['https://jitpack.io'],
      },
    }],
    './modules/veymur-recorder/plugin/withVeymurRecorder.cjs',
  ],
};
export default config;
