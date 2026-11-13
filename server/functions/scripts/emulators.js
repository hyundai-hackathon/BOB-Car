// Starts the Firebase emulators and keeps their data between runs in server/emulator-data (not in git).
// Saved data is loaded only when an earlier run exported it. The folder is not created beforehand:
// on Windows the export fails (EPERM on rename) when the target folder already exists.
// Stop with Ctrl+C so the data is written on exit.
const fs = require("fs");
const path = require("path");
const { spawnSync } = require("child_process");

const dataDir = path.resolve(__dirname, "..", "..", "emulator-data");
const args = ["emulators:start", "--project", "demo-bobcar", "--export-on-exit", dataDir];
if (fs.existsSync(path.join(dataDir, "firebase-export-metadata.json"))) args.push("--import", dataDir);

const result = spawnSync("firebase", args.map((a) => (a.includes(" ") ? `"${a}"` : a)), {
  stdio: "inherit",
  shell: true,
  cwd: path.resolve(__dirname, "..", ".."),
});
process.exit(result.status ?? 1);
