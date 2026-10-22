import { execFileSync } from "node:child_process";
import { readFile, writeFile } from "node:fs/promises";
import * as prettier from "prettier";

const [mode = "--check", ...args] = process.argv.slice(2);
if (
  !["--write", "--check"].includes(mode) ||
  (args.length !== 0 && (args.length !== 2 || args[0] !== "--base"))
) {
  throw new Error(
    "Usage: format-changed.mjs --write|--check [--base REVISION]",
  );
}
const gitFiles = (...gitArgs) =>
  execFileSync("git", gitArgs, { encoding: "utf8" })
    .split("\0")
    .filter(Boolean);
const base = args.length
  ? execFileSync(
      "git",
      ["rev-parse", "--verify", "--end-of-options", args[1]],
      {
        encoding: "utf8",
      },
    ).trim()
  : null;
const files = base
  ? gitFiles("diff", "--name-only", "--diff-filter=ACMR", "-z", base, "HEAD")
  : [
      ...gitFiles("diff", "HEAD", "--name-only", "--diff-filter=ACMR", "-z"),
      ...gitFiles("ls-files", "--others", "--exclude-standard", "-z"),
    ];
let failed = false;
let count = 0;
for (const file of [...new Set(files)].sort()) {
  const info = await prettier.getFileInfo(file, {
    ignorePath: ".prettierignore",
    resolveConfig: false,
  });
  if (info.ignored || !info.inferredParser) continue;
  const input = await readFile(file, "utf8");
  const options = {
    ...(await prettier.resolveConfig(file, { editorconfig: true })),
    filepath: file,
  };
  count++;
  if (mode === "--write") {
    const output = await prettier.format(input, options);
    if (output !== input) {
      await writeFile(file, output);
      console.log(`Formatted: ${file}`);
    }
  } else if (!(await prettier.check(input, options))) {
    console.error(`Needs formatting: ${file}`);
    failed = true;
  }
}
console.log(
  `Checked ${count} changed files with Prettier ${prettier.version}.`,
);
if (failed) process.exitCode = 1;
