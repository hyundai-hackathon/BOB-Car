// Demo helper (expense check ①): enter a mock card payment, cancel one, or list flags.
// Signs in anonymously and sends UTF-8 JSON (avoids broken Korean text in shell curl).
//
//   npm run demo:payment -- 12000            charge payment of 12,000 KRW now (veh-001)
//   npm run demo:payment -- 62000 FUEL       refuel payment
//   npm run demo:payment -- cancel <id>      cancel a payment
//   npm run demo:payment -- flags            list the flags of veh-001 (payments, use outside the reservation, unrecorded drives)
//
// Local emulators by default. "npm run demo:payment:release -- ..." uses the deployed server set for release builds
// in ~/.gradle/gradle.properties (bobcar.release.serverUrl / firebaseApiKey; the key is never printed).
// Override with BOBCAR_API / BOBCAR_AUTH / BOBCAR_VEHICLE.
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const ARGS = process.argv.slice(2);
const RELEASE = ARGS[0] === "--release";
if (RELEASE) ARGS.shift();
const release = RELEASE ? releaseSettings() : null;
const API = process.env.BOBCAR_API ?? (release ? `${release.serverUrl}/api/v1` : "http://127.0.0.1:5001/demo-bobcar/asia-northeast3/api/v1");
const AUTH = process.env.BOBCAR_AUTH ?? (release ? "https://identitytoolkit.googleapis.com" : "http://127.0.0.1:9099");
const API_KEY = release ? release.apiKey : "emulator";
const VEHICLE = process.env.BOBCAR_VEHICLE ?? "veh-001";

/** bobcar.release.* from the user's Gradle properties (outside the repository). */
function releaseSettings() {
  const file = path.join(os.homedir(), ".gradle", "gradle.properties");
  const props = {};
  if (fs.existsSync(file)) {
    for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
      const m = /^\s*([^#=\s]+)\s*=\s*(.*?)\s*$/.exec(line);
      if (m) props[m[1]] = m[2];
    }
  }
  const serverUrl = (props["bobcar.release.serverUrl"] ?? "").replace(/\/+$/, "");
  const apiKey = props["bobcar.release.firebaseApiKey"] ?? "";
  if (!serverUrl.startsWith("https://") || !apiKey) {
    throw new Error(`set bobcar.release.serverUrl (https) and bobcar.release.firebaseApiKey in ${file}`);
  }
  return { serverUrl, apiKey };
}
const MERCHANT = { CHARGE: "충전소", FUEL: "주유소", TOLL: "고속도로", OTHER: "기타" };

async function token() {
  const res = await fetch(`${AUTH}/identitytoolkit.googleapis.com/v1/accounts:signUp`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Goog-Api-Key": API_KEY },
    body: JSON.stringify({ returnSecureToken: true }),
  });
  if (!res.ok) throw new Error(`sign-in failed: HTTP ${res.status} (${RELEASE ? "check the API key and anonymous sign-in" : "are the emulators running?"})`);
  return (await res.json()).idToken;
}

async function call(method, path, body) {
  const res = await fetch(API + path, {
    method,
    headers: { Authorization: `Bearer ${await token()}`, "Content-Type": "application/json; charset=utf-8" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`${method} ${path}: HTTP ${res.status} ${JSON.stringify(json)}`);
  return json;
}

function describeFlag(flag) {
  if (!flag) return "플래그 없음 (판정 제외이거나 충전 기록과 맞음)";
  const head = `[${flag.status}] ${flag.note}`;
  switch (flag.kind) {
    case "OUT_OF_RESERVATION":
      return `${head} — 운전자 ${flag.driverId}, 예약자 ${flag.reservedDriverId ?? "없음"}, 운행 ${flag.tripCount}건 (${flag.flagId})`;
    case "UNRECORDED_DRIVE":
      return `${head} — 마지막 운전자 ${flag.lastDriverId ?? "미상"}, 발견 ${flag.foundByDriverId ?? "미상"} (${flag.flagId})`;
    default:
      return `${head} — 결제 ${flag.paidAmount?.toLocaleString("ko-KR")}원, 운전자 ${flag.driverId ?? "미상"} (${flag.flagId})`;
  }
}

async function main() {
  const [first, second] = ARGS;
  if (first === "flags") {
    const { items } = await call("GET", `/flags?vehicleId=${VEHICLE}`);
    if (items.length === 0) console.log("플래그 없음");
    for (const flag of items) console.log(describeFlag({ ...flag, flagId: flag.id }));
    return;
  }
  if (first === "cancel") {
    if (!second) throw new Error("usage: demo:payment -- cancel <transactionId>");
    const { flag } = await call("POST", `/transactions/${second}/cancel`, {});
    console.log(`취소: ${second}`);
    console.log(describeFlag(flag));
    return;
  }
  const amount = Number(first);
  const category = (second ?? "CHARGE").toUpperCase();
  if (!Number.isInteger(amount) || amount <= 0 || !(category in MERCHANT)) {
    throw new Error("usage: demo:payment -- <amount KRW> [CHARGE|FUEL|TOLL|OTHER] | cancel <id> | flags");
  }
  const { transactionId, flag } = await call("POST", "/transactions", {
    vehicleId: VEHICLE,
    amount,
    merchant: MERCHANT[category],
    category,
    paidAt: Date.now(),
  });
  console.log(`결제 등록: ${amount.toLocaleString("ko-KR")}원 ${category} (${transactionId})`);
  console.log(describeFlag(flag));
}

main().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
