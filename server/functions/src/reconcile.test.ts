import request from "supertest";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createApp } from "./app";
import { COL, db } from "./db";
import { SEED_VEHICLE_ID } from "./seed";
import { anonymousIdToken, resetData } from "./testing";

const app = createApp({ dev: true });
const VEHICLE = SEED_VEHICLE_ID;
const MIN = 60_000;
const HOUR = 60 * MIN;
/** Trip end (snapshot before the charge, A) and boarding (charge found, B). */
const A = 1_800_000_000_000;
const B = A + 2 * HOUR;
let token = "";
let queueId = 0;

const auth = () => ({ Authorization: `Bearer ${token}` });

/** The vehicle found a charge at B: SOC 23 -> 68 (+45%p, 34.83 kWh) since the trip end at A. */
async function chargeFound(opts: { gained?: number; kwh?: string; sinceAt?: number | null; foundAt?: number } = {}) {
  const gained = opts.gained ?? 45;
  const sinceAt = opts.sinceAt === undefined ? A : opts.sinceAt;
  const detail =
    `gainedPercent=${gained} from=23 to=${23 + gained} kwh=${opts.kwh ?? "34.83"} foundAt=BOARDING since=TRIP_END` +
    (sinceAt === null ? "" : ` sinceAt=${sinceAt}`);
  queueId += 1;
  const res = await request(app)
    .post("/v1/uploads")
    .set(auth())
    .send({
      vehicleId: VEHICLE,
      items: [{ queueId, kind: "vehicle_event", createdAt: opts.foundAt ?? B, driverId: "drv-001", payload: { eventId: queueId, type: "ChargeDetected", occurredAt: opts.foundAt ?? B, detail } }],
    });
  expect(res.body.stored).toEqual([queueId]);
}

async function pay(amount: number, paidAt: number, category = "CHARGE") {
  const res = await request(app).post("/v1/transactions").set(auth()).send({ vehicleId: VEHICLE, amount, merchant: "충전소", category, paidAt });
  expect(res.status).toBe(201);
  return res.body as { transactionId: string; flag: Record<string, unknown> | null };
}

const flag = async (transactionId: string) => (await db().collection(COL.flags).doc(`payment_${transactionId}`).get()).data();

beforeAll(async () => {
  token = await anonymousIdToken();
});

beforeEach(async () => {
  await resetData(Date.now());
  queueId = 0;
  await db().collection(COL.drivers).doc("drv-001").set({ consent: { status: "AGREED", agreedAt: 1 } }, { merge: true });
  // Trip that ended at A, driven by drv-002 (who had the car)
  await db().collection(COL.trips).doc(`${VEHICLE}_1`).set({ vehicleId: VEHICLE, tripId: 1, status: "ENDED", startedAt: A - HOUR, endedAt: A, driverId: "drv-002" });
});

describe("demo scene 5: charge payment, then the charge is found", () => {
  it("a 12,000 KRW charge payment is flagged until boarding with +45%p clears it", async () => {
    const paid = await pay(12_000, A + 30 * MIN);

    expect(paid.flag).toMatchObject({ status: "OPEN", reason: "NO_CHARGE", note: "충전 기록 없음", paidAmount: 12_000, driverId: "drv-002" });

    await chargeFound();

    expect(await flag(paid.transactionId)).toMatchObject({
      status: "RESOLVED",
      reason: null,
      note: "충전 확인 +45%p (34.8 kWh)",
      expectedAmount: 12_191,
      windowPaidTotal: 12_000,
      ratio: 1.02,
      gainedPercent: 45,
    });
    expect((await flag(paid.transactionId))!.resolvedAt).toEqual(expect.any(Number));
  });

  it("the flag shows in the flag list of the vehicle", async () => {
    await pay(12_000, A + 30 * MIN);

    const res = await request(app).get(`/v1/flags?vehicleId=${VEHICLE}`).set(auth());
    expect(res.body.items).toHaveLength(1);
    expect(res.body.items[0]).toMatchObject({ kind: "CHARGE_PAYMENT", status: "OPEN" });
  });
});

describe("matching rules", () => {
  it("a payment made after the charge was found and explained by it gets no flag", async () => {
    await chargeFound();

    const paid = await pay(12_000, B + 5 * MIN);

    expect(paid.flag).toBeNull();
    expect(await flag(paid.transactionId)).toBeUndefined();
  });

  it("paying much more than the charge is worth stays flagged", async () => {
    const paid = await pay(30_000, A + 30 * MIN);
    await chargeFound();

    expect(await flag(paid.transactionId)).toMatchObject({
      status: "OPEN",
      reason: "AMOUNT_MISMATCH",
      expectedAmount: 12_191,
      ratio: 0.41,
      note: "결제 금액이 충전량보다 큼 (예상 12,191원 / 결제 30,000원)",
    });
  });

  it("payments in the same window are added up", async () => {
    const first = await pay(10_000, A + 20 * MIN);
    const second = await pay(10_000, A + 40 * MIN);
    await chargeFound();

    // 12,191 / 20,000 = 0.61: both stay open
    expect(await flag(first.transactionId)).toMatchObject({ status: "OPEN", reason: "AMOUNT_MISMATCH", windowPaidTotal: 20_000 });
    expect(await flag(second.transactionId)).toMatchObject({ status: "OPEN", windowPaidTotal: 20_000 });
  });

  it("payments under 10,000 KRW and tolls are not checked", async () => {
    expect((await pay(9_900, A + 30 * MIN)).flag).toBeNull();
    expect((await pay(30_000, A + 30 * MIN, "TOLL")).flag).toBeNull();
    expect((await db().collection(COL.flags).get()).size).toBe(0);
  });

  // One payment per test inside the window: two would be added up (24,000 KRW is more than the charge)
  it("the window starts 10 minutes before the trip end", async () => {
    await chargeFound();

    expect((await pay(12_000, A - 11 * MIN)).flag).toMatchObject({ reason: "NO_CHARGE" });
    expect((await pay(12_000, A - 9 * MIN)).flag).toBeNull();
  });

  it("the window ends 10 minutes after the charge was found", async () => {
    await chargeFound();

    expect((await pay(12_000, B + 11 * MIN)).flag).toMatchObject({ reason: "NO_CHARGE" });
    expect((await pay(12_000, B + 9 * MIN)).flag).toBeNull();
  });

  it("without a snapshot time the last trip end is the start of the window", async () => {
    const paid = await pay(12_000, A + 30 * MIN);
    await chargeFound({ sinceAt: null });

    expect(await flag(paid.transactionId)).toMatchObject({ status: "RESOLVED" });
  });

  it("a charge without kWh is priced with the vehicle's battery capacity", async () => {
    const paid = await pay(12_000, A + 30 * MIN);
    await chargeFound({ kwh: "unknown" });

    // 45% of 77.4 kWh = 34.83 kWh
    expect(await flag(paid.transactionId)).toMatchObject({ status: "RESOLVED", expectedAmount: 12_191 });
  });

  it("a refuel payment with no refuel record is flagged (the emulator car is electric)", async () => {
    expect((await pay(62_000, A + 30 * MIN, "FUEL")).flag).toMatchObject({ kind: "FUEL_PAYMENT", status: "OPEN", note: "주유 기록 없음" });
  });
});
