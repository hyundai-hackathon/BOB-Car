import request from "supertest";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createApp } from "./app";
import { COL, db } from "./db";
import { koreaDay, SEED_RESERVED_DRIVER_ID, SEED_VEHICLE_ID } from "./seed";
import { anonymousIdToken, resetData } from "./testing";

const app = createApp({ dev: true });
const VEHICLE = SEED_VEHICLE_ID;
const MIN = 60_000;
const HOLDER = SEED_RESERVED_DRIVER_ID; // drv-001 holds today's reservation
let vehicleToken = "";
let queueId = 0;
/** Inside today's seed reservation. */
let T = 0;

async function upload(...items: object[]) {
  const res = await request(app).post("/v1/uploads").set({ Authorization: `Bearer ${vehicleToken}` }).send({ vehicleId: VEHICLE, items });
  expect(res.status).toBe(200);
  expect(res.body.skipped).toEqual([]);
}

function trip(driverId: string, tripId: number, startedAt: number, endedAt: number, extra: Record<string, unknown> = {}) {
  queueId += 1;
  return {
    queueId,
    kind: "trip",
    createdAt: startedAt,
    driverId,
    payload: {
      tripId,
      status: "ENDED",
      startedAt,
      endedAt,
      startOdometerKm: 12_340,
      endOdometerKm: 12_358.4,
      endSocPercent: 70,
      driverId,
      driverProvisional: false,
      purpose: "BUSINESS",
      purposeProvisional: false,
      destinationName: "판교 테크노밸리",
      expectedTollFare: 2_400,
      endReason: "COUNTDOWN",
      ...extra,
    },
  };
}

function event(driverId: string, type: string, occurredAt: number, detail: string) {
  queueId += 1;
  return { queueId, kind: "vehicle_event", createdAt: occurredAt, driverId, payload: { eventId: queueId, type, occurredAt, detail } };
}

/** A phone: its own anonymous sign-in, linked to [driverId]. */
async function phoneOf(driverId: string, agree = true) {
  const token = await anonymousIdToken();
  const call = (method: "get" | "post", path: string, body?: object) => {
    const r = request(app)[method](path).set({ Authorization: `Bearer ${token}` });
    return body ? r.send(body) : r;
  };
  expect((await call("post", "/v1/phone/link", { driverId })).status).toBe(200);
  if (agree) expect((await call("post", "/v1/phone/consent", { noticeVersion: 1 })).status).toBe(200);
  return call;
}

const correct = (phone: Awaited<ReturnType<typeof phoneOf>>, tripDocId: string, body: object) =>
  phone("post", `/v1/phone/trips/${tripDocId}/correction`, body);

beforeAll(async () => {
  vehicleToken = await anonymousIdToken();
});

beforeEach(async () => {
  const now = Date.now();
  await resetData(now);
  T = koreaDay(now).startAt + 3 * 60 * MIN;
  queueId = 0;
  for (const id of ["drv-001", "drv-002", "drv-003"]) {
    await db().collection(COL.drivers).doc(id).set({ consent: { status: "AGREED", agreedAt: 1 } }, { merge: true });
  }
});

describe("link and consent", () => {
  it("acts as the chosen driver; consent on the phone is stored as AGREED", async () => {
    const phone = await phoneOf("drv-004", false);
    const me = await phone("get", "/v1/phone/me");
    expect(me.body).toMatchObject({ driverId: "drv-004", name: "최유나", department: "총무팀", agreed: false });
    // No records before consent
    expect((await phone("get", "/v1/phone/trips")).status).toBe(403);

    expect((await phone("post", "/v1/phone/consent", { noticeVersion: 2 })).body).toMatchObject({ agreed: true });
    const driver = await db().collection(COL.drivers).doc("drv-004").get();
    expect(driver.get("consent")).toMatchObject({ status: "AGREED", noticeVersion: 2, source: "PHONE" });
    expect((await phone("get", "/v1/phone/trips")).status).toBe(200);
  });

  it("an unlinked phone or an unknown driver is refused", async () => {
    const token = await anonymousIdToken();
    expect((await request(app).get("/v1/phone/me").set({ Authorization: `Bearer ${token}` })).status).toBe(404);
    expect((await request(app).post("/v1/phone/link").set({ Authorization: `Bearer ${token}` }).send({ driverId: "drv-999" })).status).toBe(404);
  });
});

describe("my trips", () => {
  it("shows only my trips; a personal trip shows no destination", async () => {
    await upload(
      trip(HOLDER, 1, T, T + 30 * MIN),
      trip("drv-002", 2, T + 60 * MIN, T + 90 * MIN),
      trip(HOLDER, 3, T + 120 * MIN, T + 150 * MIN, { purpose: "PERSONAL" }),
    );
    const phone = await phoneOf(HOLDER);

    const res = await phone("get", "/v1/phone/trips");

    expect(res.body.items.map((t: { tripDocId: string }) => t.tripDocId)).toEqual([`${VEHICLE}_3`, `${VEHICLE}_1`]);
    expect(res.body.items[0]).toMatchObject({ purpose: "PERSONAL", destination: null, check: null });
    expect(res.body.items[1]).toMatchObject({ destination: "판교 테크노밸리", distanceKm: 18.4, expectedTollFare: 2_400, driverName: "김민준" });
  });

  it("a provisional trip asks to be checked; [맞습니다] confirms it and keeps a trail", async () => {
    await upload(trip(HOLDER, 1, T, T + 30 * MIN, { driverProvisional: true, purposeProvisional: true }));
    const phone = await phoneOf(HOLDER);
    expect((await phone("get", "/v1/phone/trips")).body.items[0].check).toBe("DRIVER");

    const res = await correct(phone, `${VEHICLE}_1`, { confirm: true });

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ driverProvisional: false, purposeProvisional: false, check: null });
    const edits = await db().collection(COL.tripEdits).where("tripDocId", "==", `${VEHICLE}_1`).get();
    expect(edits.docs[0].data()).toMatchObject({
      byDriverId: HOLDER,
      changes: { driverProvisional: { from: true, to: false }, purposeProvisional: { from: true, to: false } },
    });
  });

  it("purpose and memo set on the phone stay when the car sends the trip again", async () => {
    await upload(trip(HOLDER, 1, T, T + 30 * MIN));
    const phone = await phoneOf(HOLDER);
    await correct(phone, `${VEHICLE}_1`, { purpose: "PERSONAL", memo: "  병원 방문  " });

    // The car resends a newer row of the same trip (e.g. its queue retried)
    await upload(trip(HOLDER, 1, T, T + 31 * MIN));

    const stored = await db().collection(COL.trips).doc(`${VEHICLE}_1`).get();
    expect(stored.get("purpose")).toBe("PERSONAL");
    expect(stored.get("destinationName")).toBeNull();
    expect(stored.get("memo")).toBe("병원 방문");
    expect(stored.get("endedAt")).toBe(T + 31 * MIN);
    const logbook = await request(app).get(`/v1/admin/logbook?vehicleId=${VEHICLE}`).set({ Authorization: `Bearer ${vehicleToken}` });
    expect(logbook.body.rows[0]).toMatchObject({ purpose: "PERSONAL", destination: null, memo: "병원 방문", editedOnPhone: true });
  });

  it("someone else's trip cannot be changed", async () => {
    await upload(trip("drv-002", 1, T, T + 30 * MIN));
    const phone = await phoneOf(HOLDER);

    expect((await correct(phone, `${VEHICLE}_1`, { purpose: "PERSONAL" })).status).toBe(403);
    expect((await correct(phone, `${VEHICLE}_1`, {})).status).toBe(400);
  });

  it("handing my trip to another driver leaves it provisional for them and re-judges the reservation", async () => {
    await upload(trip(HOLDER, 1, T, T + 30 * MIN));
    const phone = await phoneOf(HOLDER);

    const res = await correct(phone, `${VEHICLE}_1`, { driverId: "drv-002" });

    expect(res.body).toMatchObject({ driverId: "drv-002", driverProvisional: true, purposeProvisional: true });
    // drv-002 is not the holder: use outside the reservation
    const flag = await db().collection(COL.flags).doc(`usage_${VEHICLE}_${(await reservationId())}_drv-002`).get();
    expect(flag.get("status")).toBe("OPEN");
    // drv-002 sees it to confirm, with who named them
    const other = await phoneOf("drv-002");
    expect((await other("get", "/v1/phone/trips")).body.items[0]).toMatchObject({ check: "DRIVER", assignedByName: "김민준" });
    // ...and fixing it back to the holder withdraws the flag
    await correct(other, `${VEHICLE}_1`, { driverId: HOLDER });
    expect((await flag.ref.get()).get("status")).toBe("RESOLVED");
  });
});

describe("unrecorded drives", () => {
  async function unrecordedDrive() {
    // drv-002 had the car last; drv-003 boards and the car finds 6 km nobody recorded
    await upload(trip("drv-002", 1, T, T + 30 * MIN));
    await upload(event("drv-003", "UnrecordedDrive", T + 120 * MIN, `km=6.00 from=12358.4 to=12364.4 sinceAt=${T + 30 * MIN}`));
    const flags = await db().collection(COL.flags).where("kind", "==", "UNRECORDED_DRIVE").get();
    return { flagId: flags.docs[0].id, tripDocId: flags.docs[0].get("tripDocId") as string };
  }

  it("candidates see it; taking it as mine records it and resolves the flag", async () => {
    const { flagId, tripDocId } = await unrecordedDrive();
    const finder = await phoneOf("drv-003");
    const outsider = await phoneOf("drv-004");

    const listed = (await finder("get", "/v1/phone/trips")).body.items.find((t: { tripDocId: string }) => t.tripDocId === tripDocId);
    expect(listed).toMatchObject({ check: "UNRECORDED", distanceKm: 6, candidates: [{ driverId: "drv-002", name: "이서연" }, { driverId: "drv-003", name: "박지호" }] });
    expect((await outsider("get", "/v1/phone/trips")).body.items).toEqual([]);
    expect((await correct(outsider, tripDocId, { driverId: "drv-004" })).status).toBe(403);

    const res = await correct(finder, tripDocId, { driverId: "drv-003", purpose: "BUSINESS" });

    expect(res.body).toMatchObject({ status: "ENDED", driverId: "drv-003", driverProvisional: false, purpose: "BUSINESS", check: null });
    expect((await db().collection(COL.flags).doc(flagId).get()).get("status")).toBe("RESOLVED");
    const logbook = await request(app).get(`/v1/admin/logbook?vehicleId=${VEHICLE}`).set({ Authorization: `Bearer ${vehicleToken}` });
    expect(logbook.body.rows.find((r: { tripDocId: string }) => r.tripDocId === tripDocId)).toMatchObject({
      unrecorded: false,
      claimedUnrecorded: true,
      driverName: "박지호",
      businessKm: 6,
    });
  });

  it("naming the other candidate keeps the flag open until they confirm", async () => {
    const { flagId, tripDocId } = await unrecordedDrive();
    const finder = await phoneOf("drv-003");

    await correct(finder, tripDocId, { driverId: "drv-002", purpose: "PERSONAL" });

    expect((await db().collection(COL.flags).doc(flagId).get()).get("status")).toBe("OPEN");
    const lastDriver = await phoneOf("drv-002");
    const listed = (await lastDriver("get", "/v1/phone/trips")).body.items.find((t: { tripDocId: string }) => t.tripDocId === tripDocId);
    expect(listed).toMatchObject({ check: "DRIVER", assignedByName: "박지호", purpose: "PERSONAL" });

    await correct(lastDriver, tripDocId, { confirm: true });
    expect((await db().collection(COL.flags).doc(flagId).get()).get("status")).toBe("RESOLVED");
  });
});

describe("vehicle", () => {
  it("shows battery, last charge, odometer, month figures and the last driver", async () => {
    await upload(trip(HOLDER, 1, T, T + 30 * MIN));
    await upload(event("drv-002", "ChargeDetected", T + 200 * MIN, `gainedPercent=10 from=70 to=80 kwh=7.74 sinceAt=${T + 30 * MIN}`));
    const phone = await phoneOf(HOLDER);

    const res = await phone("get", "/v1/phone/vehicle");

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({
      vehicle: { vehicleId: VEHICLE, model: "아이오닉 5", plate: "12가 3456" },
      state: "PARKED",
      socPercent: 80,
      lastCharge: { at: T + 200 * MIN, fromPercent: 70, toPercent: 80 },
      odometerKm: 12_358.4,
      monthKm: 18.4,
      businessRatioPercent: 100,
      lastDriverName: "김민준",
    });
    expect(typeof res.body.lastUploadAt).toBe("number");
  });
});

describe("vehicle odometer", () => {
  it("shows the newest reading: an unrecorded drive found after the last trip end", async () => {
    await upload(trip(HOLDER, 1, T, T + 30 * MIN));
    // Boarding later: the car reads 13,040 km (e.g. the console value) and reports the gap
    await upload(event("drv-002", "UnrecordedDrive", T + 120 * MIN, `km=4852.00 from=8188.0 to=13040.0 sinceAt=${T + 30 * MIN}`));
    const phone = await phoneOf(HOLDER);

    expect((await phone("get", "/v1/phone/vehicle")).body.odometerKm).toBe(13_040);
    // The baseline the car compares against stays at the last trip end
    expect((await db().collection(COL.vehicles).doc(VEHICLE).get()).get("lastOdometerKm")).toBe(12_358.4);
  });

  it("a trip ending later wins again", async () => {
    await upload(event("drv-002", "UnrecordedDrive", T, `km=6.00 from=12334.0 to=12340.0 sinceAt=${T - 60 * MIN}`));
    await upload(trip(HOLDER, 1, T + 10 * MIN, T + 30 * MIN));
    const phone = await phoneOf(HOLDER);

    expect((await phone("get", "/v1/phone/vehicle")).body.odometerKm).toBe(12_358.4);
  });
});

async function reservationId(): Promise<string> {
  const snaps = await db().collection(COL.reservations).where("vehicleId", "==", VEHICLE).get();
  return snaps.docs.find((d) => (d.get("startAt") as number) <= T && (d.get("endAt") as number) > T)!.id;
}
