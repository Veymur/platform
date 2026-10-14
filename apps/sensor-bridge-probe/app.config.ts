import type { ExpoConfig } from 'expo/config';

const config: ExpoConfig = {
  name: 'Veymur Bridge Probe',
  slug: 'veymur-sensor-bridge-probe',
  version: '0.0.1',
  platforms: ['android'],
  android: { package: 'com.veymur.sensor.bridgeprobe' },
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
    './plugins/withProbeBuild.cjs',
  ],
};
export default config;
