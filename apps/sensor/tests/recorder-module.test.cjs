const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");

function wrapper(platform, nativeInfo) {
  let nativeLoads = 0;
  const source = fs.readFileSync(
    path.join(__dirname, "../modules/veymur-recorder/src/index.ts"),
    "utf8",
  );
  const code = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
    },
  }).outputText;
  const exports = {};
  vm.runInNewContext(code, {
    exports,
    require(name) {
      if (name === "react-native") return { Platform: { OS: platform } };
      if (name === "expo")
        return {
          requireNativeModule(moduleName) {
            nativeLoads += 1;
            assert.equal(moduleName, "VeymurRecorder");
            return { getModuleInfo: async () => nativeInfo };
          },
        };
      throw new Error(`Unexpected test import: ${name}`);
    },
  });
  return { client: exports, nativeLoads: () => nativeLoads };
}

const info = {
  moduleName: "VeymurRecorder",
  platform: "android",
  contractVersion: 1,
  recorderImplemented: false,
};

test("iOS rejects before loading an Android-only native module", async () => {
  const { client, nativeLoads } = wrapper("ios", info);
  await assert.rejects(
    client.getModuleInfo(),
    /iOS recorder is not implemented/,
  );
  assert.equal(nativeLoads(), 0);
});

test("Android returns a valid typed handshake, with recording unavailable", async () => {
  const { client } = wrapper("android", info);
  const result = await client.getModuleInfo();
  assert.deepEqual(JSON.parse(JSON.stringify(result)), info);
});

test("an incompatible installed native binary requires rebuilding", async () => {
  const { client } = wrapper("android", { ...info, contractVersion: 2 });
  await assert.rejects(client.getModuleInfo(), /Rebuild and install/);
});

test("a binary advertising unimplemented recording is not accepted", async () => {
  const { client } = wrapper("android", { ...info, recorderImplemented: true });
  await assert.rejects(client.getModuleInfo(), /Incompatible VeymurRecorder/);
});
