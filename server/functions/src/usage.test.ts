import request from "supertest";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createApp } from "./app";
import { COL, db } from "./db";
import { koreaDay, SEED_RESERVED_DRIVER_ID, SEED_VEHICLE_ID } from "./seed";
import { anonymousIdToken, resetData } from "./testing";

const app = createApp({ dev: true });
const VEHICLE = SEED_VEHICLE_ID;
const MIN = 60_000;
const DAY = 24 * 60 * MIN;
const HOLDER = SEED_RESERVED_DRIVER_ID; // drv-001 holds today's reservation
let token = "";
let queueId = 0;
/** Inside today's seed reservation. */
let T = 0;

async function upload(...items: object[]) {
  const res = await request(app).post("/v1/uploads").set({ Authorization: `Bearer ${token}` }).send({ vehicleId: VEHICLE, items });
  expect(res.status).toBe(200);
  return res.body as { stored: number[]; skipped: Array<{ queueId: number; reason: string }> };
}

function event(driverId: string, type: string, occurredAt: number, detail: string | null) {
  queueId += 1;
  return { queueId, kind: "vehicle_event", createdAt: occurredAt, driverId, payload: { eventId: queueId, type, occurredAt, detail } };
}

const confirmed = (driverId: string, at: number) => event(driverId, "DriverConfirmed", at, `driver=${driverId}`);

function trip(driverId: string, tripId: number, startedAt: number, ended: { at: number; km?: number } | null = null) {
  queueId += 1;
  return {
    queueId,
    kind: "trip",
    createdAt: startedAt,
    driverId,
    payload: {
      tripId,
      status: ended ? "ENDED" : "ACTIVE",
      startedAt,
      endedAt: ended?.at ?? null,
      startOdometerKm: 12_340,
      endOdometerKm: ended ? 12_340 + (ended.km ?? 10) : null,
      driverId,
      driverProvisional: false,
      purpose: ended ? "BUSINESS" : null,
      endReason: ended ? "COUNTDOWN" : null,
    },
  };
}

async function usageFlags() {
  const snaps = await db().collection(COL.flags).where("kind", "==", "OUT_OF_RESERVATION").get();
  return snaps.docs.map((d) => ({ id: d.id, ...d.data() })) as Array<Record<string, unknown>>;
}

beforeAll(async () => {
  token = await anonymousIdToken();
});

beforeEach(async () => {
  const now = Date.now();
  await resetData(now);
  // 03:00 Korea time today: inside today's seed reservation, with room before and after
  T = koreaDay(now).startAt + 3 * 60 * MIN;
  queueId = 0;
  for (const id of ["drv-001", "drv-002", "drv-003"]) {
    await db().collection(COL.drivers).doc(id).set({ consent: { status: "AGREED", agreedAt: 1 } }, { merge: true });
  }
});

describe("③ use outside the reservation", () => {
  it("the reservation holder raises no flag", async () => {
    await upload(confirmed(HOLDER, T), trip(HOLDER, 1, T + MIN), trip(HOLDER, 1, T + MIN, { at: T + 30 * MIN }));

    expect(await usageFlags()).toEqual([]);
  });

  it("[다른 사람] shows the flag right away, before any trip", async () => {
    await upload(confirmed("drv-002", T));

    const [flag] = await usageFlags();
    expect(flag).toMatchObject({
      status: "OPEN",
      reason: "OTHER_DRIVER",
      note: "예약 밖 사용 (예약자와 다른 운전자)",
      driverId: "drv-002",
      reservedDriverId: HOLDER,
      tripCount: 0,
    });
  });

  it("the same driver in the same reservation is one flag, counting the trips", async () => {
    await upload(confirmed("drv-002", T), trip("drv-002", 1, T + MIN), trip("drv-002", 1, T + MIN, { at: T + 20 * MIN }));
    await upload(trip("drv-002", 2, T + 40 * MIN, { at: T + 60 * MIN }));

    const flags = await usageFlags();
    expect(flags).toHaveLength(1);
    expect(flags[0]).toMatchObject({ driverId: "drv-002", tripCount: 2, status: "OPEN" });
  });

  it("a time with no reservation is flagged as such", async () => {
    const twoDaysAgo = T - 2 * DAY;
    await upload(trip(HOLDER, 1, twoDaysAgo, { at: twoDaysAgo + 30 * MIN }));

    const [flag] = await usageFlags();
    expect(flag).toMatchObject({ reason: "NO_RESERVATION", note: "예약 없는 시간 사용", driverId: HOLDER, reservedDriverId: null });
  });

  it("fixing the driver to the holder on the trip-end card withdraws the flag", async () => {
    // Chosen someone else at check-in, drove, then [변경] to the holder on the trip-end card
    await upload(confirmed("drv-002", T), trip("drv-002", 1, T + MIN));
    expect((await usageFlags())[0]).toMatchObject({ status: "OPEN" });

    await upload(confirmed(HOLDER, T + 20 * MIN), trip(HOLDER, 1, T + MIN, { at: T + 21 * MIN }));

    const [flag] = await usageFlags();
    expect(flag).toMatchObject({ status: "RESOLVED", note: "예약자 운행으로 정정", tripCount: 0 });
    expect(flag.resolvedAt).toEqual(expect.any(Number));
  });

  it("a later trip by the holder does not withdraw an earlier trip's flag", async () => {
    await upload(confirmed("drv-002", T), trip("drv-002", 1, T + MIN, { at: T + 10 * MIN }));
    await upload(confirmed(HOLDER, T + 2 * 60 * MIN), trip(HOLDER, 2, T + 2 * 60 * MIN + MIN, { at: T + 3 * 60 * MIN }));

    expect((await usageFlags())[0]).toMatchObject({ driverId: "drv-002", status: "OPEN", tripCount: 1 });
  });

  it("a driver who has not agreed to collection is not judged (nothing of theirs reaches the server)", async () => {
    const res = await upload(confirmed("drv-004", T));

    expect(res.skipped).toEqual([{ queueId: 1, reason: "no-consent" }]);
    expect(await usageFlags()).toEqual([]);
  });
});

describe("② unrecorded drive", () => {
  it("becomes a trip with no driver and a flag with the last and the finding driver", async () => {
    // drv-003 drove last and ended at 12,362 km; drv-001 boards later and the odometer shows 12,368 km
    await upload(trip("drv-003", 1, T - 60 * MIN, { at: T - 30 * MIN, km: 22 }));
    const before = (await db().collection(COL.vehicles).doc(VEHICLE).get()).data()!;

    await upload(event(HOLDER, "UnrecordedDrive", T, `km=6.00 from=12362.0 to=12368.0 foundAt=BOARDING since=TRIP_END sinceAt=${T - 30 * MIN}`));

    const flags = await db().collection(COL.flags).where("kind", "==", "UNRECORDED_DRIVE").get();
    expect(flags.size).toBe(1);
    expect(flags.docs[0].data()).toMatchObject({
      status: "OPEN",
      note: "미기록 6.0 km",
      km: 6,
      lastDriverId: "drv-003",
      foundByDriverId: HOLDER,
      foundAt: T,
      since: T - 30 * MIN,
    });
    const unrecorded = await db().collection(COL.trips).where("status", "==", "UNRECORDED").get();
    expect(unrecorded.docs[0].data()).toMatchObject({
      driverId: null,
      startedAt: T - 30 * MIN,
      endedAt: T,
      startOdometerKm: 12_362,
      endOdometerKm: 12_368,
      distanceKm: 6,
    });
    // The vehicle baseline and the "last driver" lookups still go by real trips only
    expect((await db().collection(COL.vehicles).doc(VEHICLE).get()).data()).toMatchObject({
      lastOdometerKm: before.lastOdometerKm,
      lastTripEndedAt: before.lastTripEndedAt,
    });
  });

  it("is listed with the vehicle's trips for the logbook", async () => {
    await upload(event(HOLDER, "UnrecordedDrive", T, `km=12.50 from=100.0 to=112.5 sinceAt=${T - 60 * MIN}`));

    const res = await request(app).get(`/v1/trips?vehicleId=${VEHICLE}`).set({ Authorization: `Bearer ${token}` });
    expect(res.body.items.map((t: { status: string }) => t.status)).toEqual(["UNRECORDED"]);
    const flag = (await db().collection(COL.flags).where("kind", "==", "UNRECORDED_DRIVE").get()).docs[0].data();
    expect(flag.note).toBe("미기록 13 km");
  });

  it("the same finding sent twice is one record", async () => {
    const finding = event(HOLDER, "UnrecordedDrive", T, `km=6.00 from=1.0 to=7.0 sinceAt=${T - MIN}`);
    await upload(finding);
    await upload(finding);

    expect((await db().collection(COL.flags).where("kind", "==", "UNRECORDED_DRIVE").get()).size).toBe(1);
  });
});

describe("seed reservations on an emulator left running", () => {
  it("a new Korea-time day gets its own reservation on its first call", async () => {
    const { ensureSeeded } = await import("./seed");
    const today = koreaDay(Date.now()).startAt;
    const tomorrow = today + DAY;

    await ensureSeeded(today + 60 * MIN);
    await ensureSeeded(tomorrow + 60 * MIN);

    const reservations = await db().collection(COL.reservations).where("vehicleId", "==", VEHICLE).get();
    const starts = reservations.docs.map((d) => d.get("startAt") as number).sort();
    expect(starts).toEqual([today, tomorrow]);
    expect(reservations.docs.every((d) => d.get("driverId") === HOLDER)).toBe(true);
  });
});
