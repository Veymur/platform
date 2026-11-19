const fs = require("node:fs");
const path = require("node:path");
const zlib = require("node:zlib");

const replaySessionsDirectory = path.join(
  __dirname,
  "..",
  "src",
  "dev",
  "hrReplay",
  "sessions",
);
const endOfCentralDirectorySignature = 0x06054b50;
const centralDirectoryEntrySignature = 0x02014b50;
const localFileHeaderSignature = 0x04034b50;

function readZipEntry(zipBuffer, entryName) {
  const searchStart = Math.max(0, zipBuffer.length - 65557);
  let endRecordOffset = -1;
  for (let offset = zipBuffer.length - 22; offset >= searchStart; offset--) {
    if (zipBuffer.readUInt32LE(offset) === endOfCentralDirectorySignature) {
      endRecordOffset = offset;
      break;
    }
  }
  if (endRecordOffset < 0) throw new Error("Not a ZIP archive.");
  const entryCount = zipBuffer.readUInt16LE(endRecordOffset + 10);
  let entryOffset = zipBuffer.readUInt32LE(endRecordOffset + 16);
  for (let index = 0; index < entryCount; index++) {
    if (zipBuffer.readUInt32LE(entryOffset) !== centralDirectoryEntrySignature)
      throw new Error("Broken ZIP central directory.");
    const compressionMethod = zipBuffer.readUInt16LE(entryOffset + 10);
    const compressedSize = zipBuffer.readUInt32LE(entryOffset + 20);
    const nameLength = zipBuffer.readUInt16LE(entryOffset + 28);
    const extraLength = zipBuffer.readUInt16LE(entryOffset + 30);
    const commentLength = zipBuffer.readUInt16LE(entryOffset + 32);
    const localHeaderOffset = zipBuffer.readUInt32LE(entryOffset + 42);
    const name = zipBuffer.toString(
      "utf8",
      entryOffset + 46,
      entryOffset + 46 + nameLength,
    );
    if (name === entryName) {
      if (zipBuffer.readUInt32LE(localHeaderOffset) !== localFileHeaderSignature)
        throw new Error("Broken ZIP local header.");
      const dataStart =
        localHeaderOffset +
        30 +
        zipBuffer.readUInt16LE(localHeaderOffset + 26) +
        zipBuffer.readUInt16LE(localHeaderOffset + 28);
      const data = zipBuffer.subarray(dataStart, dataStart + compressedSize);
      if (compressionMethod === 0) return data.toString("utf8");
      if (compressionMethod === 8)
        return zlib.inflateRawSync(data).toString("utf8");
      throw new Error(`Unsupported ZIP compression method ${compressionMethod}.`);
    }
    entryOffset += 46 + nameLength + extraLength + commentLength;
  }
  throw new Error(`${entryName} is missing. Use a veymur-<session>.zip export.`);
}

function parseCsvRows(text) {
  const rows = [];
  let row = [];
  let field = "";
  let quoted = false;
  for (let index = 0; index < text.length; index++) {
    const character = text[index];
    if (quoted) {
      if (character === '"' && text[index + 1] === '"') {
        field += '"';
        index++;
      } else if (character === '"') quoted = false;
      else field += character;
    } else if (character === '"') quoted = true;
    else if (character === ",") {
      row.push(field);
      field = "";
    } else if (character === "\n" || character === "\r") {
      if (character === "\r" && text[index + 1] === "\n") index++;
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else field += character;
  }
  if (field.length > 0 || row.length > 0) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

function replayEventFromRecord(kind, payload, atMs) {
  if (kind === "hr" && Number.isSafeInteger(payload.bpm) && payload.bpm > 0)
    return { atMs, kind: "hr", bpm: payload.bpm };
  if (kind === "pause" || kind === "resume") return { atMs, kind };
  if (kind === "disconnected" || kind === "connected") return { atMs, kind };
  if (
    kind === "sensor_battery" &&
    Number.isSafeInteger(payload.percent) &&
    payload.percent >= 0 &&
    payload.percent <= 100
  )
    return { atMs, kind: "battery", percent: payload.percent };
  if (kind === "finish") return { atMs, kind: "end" };
  return null;
}

function convertSessionExport(zipPath) {
  const rows = parseCsvRows(readZipEntry(fs.readFileSync(zipPath), "records.csv"));
  const [header, ...records] = rows;
  const column = (name) => {
    const index = header.indexOf(name);
    if (index < 0) throw new Error(`records.csv has no ${name} column.`);
    return index;
  };
  const elapsedColumn = column("elapsed_realtime_ms");
  const kindColumn = column("kind");
  const payloadColumn = column("payload_json");
  const startRecord = records.find((record) => record[kindColumn] === "start");
  if (!startRecord) throw new Error("records.csv has no start record.");
  const startElapsedMs = Number(startRecord[elapsedColumn]);
  const events = [];
  for (const record of records) {
    if (record.length < header.length) continue;
    let payload;
    try {
      payload = JSON.parse(record[payloadColumn] || "{}");
    } catch {
      continue;
    }
    const atMs = Number(record[elapsedColumn]) - startElapsedMs;
    if (!Number.isFinite(atMs) || atMs < 0) continue;
    const event = replayEventFromRecord(record[kindColumn], payload, atMs);
    if (event) events.push(event);
  }
  events.sort((left, right) => left.atMs - right.atMs);
  const endEvent = events.find((event) => event.kind === "end");
  const durationMs = endEvent ? endEvent.atMs : (events.at(-1)?.atMs ?? 0);
  return {
    schema: "veymur-hr-replay-v1",
    durationMs,
    events: events.filter((event) => event.kind !== "end"),
  };
}

function printError(message) {
  process.stderr.write(`${message}\n`);
}

function printOutput(message) {
  process.stdout.write(`${message}\n`);
}

function printUsage() {
  printError(
    "Usage: node scripts/prepare-hr-replay.cjs <veymur-session.zip> <replay-name>",
  );
}

function main() {
  const [zipPath, replayName] = process.argv.slice(2);
  if (!zipPath || !replayName) {
    printUsage();
    process.exit(2);
  }
  if (!/^[a-z0-9][a-z0-9-]{0,47}$/.test(replayName)) {
    printError(
      "Replay name must be a short lowercase slug, for example run-intervals.",
    );
    process.exit(2);
  }
  const outputPath = path.join(replaySessionsDirectory, `${replayName}.json`);
  if (fs.existsSync(outputPath)) {
    printError(`${outputPath} already exists; choose another name.`);
    process.exit(1);
  }
  const replay = convertSessionExport(zipPath);
  const heartRateCount = replay.events.filter(
    (event) => event.kind === "hr",
  ).length;
  if (heartRateCount === 0) {
    printError("The session contains no heart rate samples.");
    process.exit(1);
  }
  fs.mkdirSync(replaySessionsDirectory, { recursive: true });
  fs.writeFileSync(outputPath, `${JSON.stringify(replay)}\n`, { flag: "wx" });
  printOutput(
    JSON.stringify({
      output: path.relative(process.cwd(), outputPath),
      durationSeconds: Math.round(replay.durationMs / 1000),
      heartRateSamples: heartRateCount,
      otherEvents: replay.events.length - heartRateCount,
    }),
  );
}

main();
