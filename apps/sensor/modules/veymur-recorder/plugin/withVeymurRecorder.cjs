const {
  withDangerousMod,
  withGradleProperties,
  withProjectBuildGradle,
  withAppBuildGradle,
  withAndroidManifest,
} = require("expo/config-plugins");
const fs = require("node:fs/promises");
const path = require("node:path");

module.exports = (config) => {
  config = withAndroidManifest(config, (config) => {
    const application = config.modResults.manifest.application[0];
    application.$['android:allowBackup'] = 'false';
    application.$['android:dataExtractionRules'] = '@xml/veymur_recorder_data_extraction_rules';
    return config;
  });
  config = withAppBuildGradle(config, (config) => {
    const marker = '// @veymur-recorder instrumentation';
    if (!config.modResults.contents.includes(marker)) {
      config.modResults.contents += `\n${marker}\nandroid {
  defaultConfig { testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner" }
}
dependencies {
  androidTestImplementation 'androidx.test:runner:1.6.2'
  androidTestImplementation 'androidx.test.ext:junit:1.2.1'
  androidTestImplementation 'net.zetetic:sqlcipher-android:4.6.1@aar'
  androidTestImplementation 'androidx.sqlite:sqlite:2.4.0'
}\n`;
    }
    const polarTestDependency = "androidTestImplementation 'com.github.polarofficial:polar-ble-sdk:8.4.0'";
    if (!config.modResults.contents.includes(polarTestDependency)) {
      config.modResults.contents += `\ndependencies { ${polarTestDependency} }\n`;
    }
    return config;
  });
  config = withProjectBuildGradle(config, (config) => {
    const unversioned =
      "classpath('org.jetbrains.kotlin:kotlin-gradle-plugin')";
    const pinned =
      "classpath('org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20')";
    if (
      !config.modResults.contents.includes(unversioned) &&
      !config.modResults.contents.includes(pinned)
    ) {
      throw new Error(
        "Revalidate Kotlin classpath after changing the Android template.",
      );
    }
    config.modResults.contents = config.modResults.contents.replace(
      unversioned,
      pinned,
    );
    return config;
  });
  config = withGradleProperties(config, (config) => {
    const values = {
      reactNativeArchitectures: "arm64-v8a,x86_64",
      "org.gradle.workers.max": "4",
    };
    for (const [key, value] of Object.entries(values)) {
      config.modResults = config.modResults.filter((item) => item.key !== key);
      config.modResults.push({ type: "property", key, value });
    }
    return config;
  });
  return withDangerousMod(config, [
    "android",
    async (config) => {
      // Host instrumentation, not the standalone library test APK: use the actual sensor sandbox.
      const tests = path.resolve(__dirname, '../tests/android');
      const target = path.join(config.modRequest.platformProjectRoot, 'app/src/androidTest/java');
      await fs.mkdir(target, { recursive: true });
      await fs.cp(tests, target, { recursive: true });
      const storageAssets = path.join(config.modRequest.platformProjectRoot, 'app/src/androidTest/assets/local-data');
      await fs.mkdir(storageAssets, { recursive: true });
      await fs.cp(path.resolve(__dirname, '../tests/fixtures'), storageAssets, { recursive: true });
      const replayAssets = path.join(config.modRequest.platformProjectRoot, 'app/src/androidTest/assets/polar');
      await fs.mkdir(replayAssets, { recursive: true });
      await fs.cp(path.resolve(__dirname, '../../../diagnostics/fixtures/synthetic'), replayAssets, { recursive: true });
      const file = path.join(
        config.modRequest.platformProjectRoot,
        "gradle/wrapper/gradle-wrapper.properties",
      );
      let contents = await fs.readFile(file, "utf8");
      if (!contents.includes("gradle-9.3.1-bin.zip")) {
        throw new Error(
          "Revalidate Gradle version/checksum before updating the sensor template.",
        );
      }
      contents = contents.replace(/^distributionSha256Sum=.*\n?/gm, "");
      contents +=
        "\ndistributionSha256Sum=b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06\n";
      await fs.writeFile(file, contents);
      return config;
    },
  ]);
};
