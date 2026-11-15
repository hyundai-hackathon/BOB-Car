// Copies the admin web (repository root admin-web/) into server/hosting/ for Firebase Hosting.
// Hosting only deploys files inside the Firebase project folder (server/), and the repository keeps the admin web
// at the root, so the copy is made right before a deploy (firebase.json hosting predeploy) and before the
// emulators start (scripts/emulators.js). server/hosting/ is not in git. Restart the emulators to see admin-web edits.
const fs = require("fs");
const path = require("path");

const source = path.resolve(__dirname, "..", "..", "..", "admin-web");
const target = path.resolve(__dirname, "..", "..", "hosting");

fs.rmSync(target, { recursive: true, force: true });
fs.cpSync(source, target, { recursive: true });
console.log(`admin web copied to ${path.relative(process.cwd(), target) || target}`);
