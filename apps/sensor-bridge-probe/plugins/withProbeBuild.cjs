const {
  withDangerousMod,
  withGradleProperties,
  withProjectBuildGradle,
} = require("expo/config-plugins");
const fs = require("node:fs/promises");
const path = require("node:path");

module.exports = (config) => {
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
      const file = path.join(
        config.modRequest.platformProjectRoot,
        "gradle/wrapper/gradle-wrapper.properties",
      );
      let contents = await fs.readFile(file, "utf8");
      if (!contents.includes("gradle-9.3.1-bin.zip")) {
        throw new Error(
          "Revalidate Gradle version/checksum before updating the probe template.",
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
