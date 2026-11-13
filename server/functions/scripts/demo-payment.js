// Demo helper for the local emulators (expense check ①): enter a mock card payment, cancel one, or list flags.
// Signs in anonymously on the Auth emulator and sends UTF-8 JSON (avoids broken Korean text in shell curl).
//
//   npm run demo:payment -- 12000            charge payment of 12,000 KRW now (veh-001)
//   npm run demo:payment -- 62000 FUEL       refuel payment
//   npm run demo:payment -- cancel <id>      cancel a payment
//   npm run demo:payment -- flags            list the flags of veh-001
//
// Emulators only (http://127.0.0.1). Override with BOBCAR_API / BOBCAR_AUTH / BOBCAR_VEHICLE.
const API = process.env.BOBCAR_API ?? "http://127.0.0.1:5001/demo-bobcar/asia-northeast3/api/v1";
const AUTH = process.env.BOBCAR_AUTH ?? "http://127.0.0.1:9099";
const VEHICLE = process.env.BOBCAR_VEHICLE ?? "veh-001";
const MERCHANT = { CHARGE: "충전소", FUEL: "주유소", TOLL: "고속도로", OTHER: "기타" };

async function token() {
  const res = await fetch(`${AUTH}/identitytoolkit.googleapis.com/v1/accounts:signUp`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Goog-Api-Key": "emulator" },
    body: JSON.stringify({ returnSecureToken: true }),
  });
  if (!res.ok) throw new Error(`sign-in failed: HTTP ${res.status} (are the emulators running?)`);
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
  return `[${flag.status}] ${flag.note} — 결제 ${flag.paidAmount?.toLocaleString("ko-KR")}원, 운전자 ${flag.driverId ?? "미상"} (${flag.flagId})`;
}

async function main() {
  const [first, second] = process.argv.slice(2);
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
