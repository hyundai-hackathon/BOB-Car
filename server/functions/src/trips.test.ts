import request from "supertest";
import { afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createApp } from "./app";
import { COL, db } from "./db";
import { SEED_VEHICLE_ID } from "./seed";
import { anonymousIdToken, resetData } from "./testing";
import { closeStaleTrips, END_SERVER_TIMEOUT, parseTrip } from "./trips";

const app = createApp({ dev: true });
const VEHICLE = SEED_VEHICLE_ID;
const HOUR = 60 * 60_000;
let token = "";

function trip(queueId: number, fields: Record<string, unknown>) {
  return {
    queueId,
    kind: "trip",
    createdAt: queueId,
    driverId: "drv-001",
    payload: { tripId: 7, status: "ACTIVE", startedAt: 1_000, startOdometerKm: 12_340, driverId: "drv-001", ...fields },
  };
}

const upload = (...items: object[]) =>
  request(app).post("/v1/uploads").set("Authorization", `Bearer ${token}`).send({ vehicleId: VEHICLE, items });

const tripDoc = async () => (await db().collection(COL.trips).doc(`${VEHICLE}_7`).get()).data()!;
const vehicleDoc = async () => (await db().collection(COL.vehicles).doc(VEHICLE).get()).data()!;

beforeAll(async () => {
  token = await anonymousIdToken();
});

beforeEach(async () => {
  await resetData(Date.now());
  await db().collection(COL.drivers).doc("drv-001").set({ consent: { status: "AGREED", agreedAt: 1 } }, { merge: true });
});

afterEach(() => {
  delete process.env.BOBCAR_AUTO_CLOSE_MINUTES;
});

describe("trip rows and the vehicle baseline", () => {
  it("a started trip becomes the vehicle's running trip", async () => {
    const res = await upload(trip(1, {}));

    expect(res.body.stored).toEqual([1]);
    expect((await tripDoc()).status).toBe("ACTIVE");
    expect((await vehicleDoc()).activeTripId).toBe(7);
  });

  it("an ended trip from 12,340 to 12,362 km moves the baseline", async () => {
    await upload(trip(1, {}));
    await upload(trip(2, { status: "ENDED", endedAt: 9_000, endOdometerKm: 12_362, endSocPercent: 68, purpose: "BUSINESS", endReason: "COUNTDOWN" }));

    const stored = await tripDoc();
    expect(stored).toMatchObject({ status: "ENDED", startOdometerKm: 12_340, endOdometerKm: 12_362, purpose: "BUSINESS", lastQueueId: 2 });
    expect(await vehicleDoc()).toMatchObject({ activeTripId: null, lastOdometerKm: 12_362, lastSocPercent: 68, lastTripEndedAt: 9_000 });
  });

  it("a personal trip keeps no destination", async () => {
    await upload(trip(1, { status: "ENDED", endedAt: 9_000, purpose: "PERSONAL", destinationName: "동네 마트" }));

    expect((await tripDoc()).destinationName).toBeNull();
  });

  it("a business trip keeps its destination and expected toll", async () => {
    await upload(trip(1, { destinationName: "인천국제공항", expectedTollFare: 4_700 }));

    expect(await tripDoc()).toMatchObject({ destinationName: "인천국제공항", expectedTollFare: 4_700 });
  });

  it("an older row arriving late does not replace a newer one", async () => {
    await upload(trip(2, { status: "ENDED", endedAt: 9_000, endOdometerKm: 12_362 }));
    const res = await upload(trip(1, {}));

    expect(res.body.stored).toEqual([1]);
    expect((await tripDoc()).status).toBe("ENDED");
    // The late "running" row must not make the ended trip the vehicle's running trip again
    expect((await vehicleDoc()).activeTripId ?? null).toBeNull();
  });

  it("an older trip end does not move the baseline back", async () => {
    await upload({ ...trip(1, { status: "ENDED", endedAt: 9_000, endOdometerKm: 12_400, endSocPercent: 50 }) });
    await upload({ ...trip(2, { tripId: 6, status: "ENDED", endedAt: 5_000, endOdometerKm: 12_300, endSocPercent: 80 }) });

    expect(await vehicleDoc()).toMatchObject({ lastOdometerKm: 12_400, lastSocPercent: 50, lastTripEndedAt: 9_000 });
  });

  it("values out of range are dropped", async () => {
    await upload(trip(1, { status: "ENDED", endedAt: 9_000, endOdometerKm: -5, endSocPercent: 140 }));

    const stored = await tripDoc();
    expect(stored.endOdometerKm).toBeNull();
    expect(stored.endSocPercent).toBeNull();
    const vehicle = await vehicleDoc();
    expect(vehicle.lastOdometerKm).toBeUndefined();
    expect(vehicle.lastTripEndedAt).toBe(9_000);
  });

  it("a trip row that is not one is skipped as invalid", async () => {
    const res = await upload({ ...trip(1, {}), payload: { tripId: "x" } });

    expect(res.body.skipped).toEqual([{ queueId: 1, reason: "invalid" }]);
  });

  it("parseTrip keeps only known fields", () => {
    const parsed = parseTrip({ tripId: 3, status: "ENDED", startedAt: 1, purpose: "SNACK", vin: "KMH123", latitude: 37.5 });
    expect(parsed).not.toBeNull();
    expect(parsed!.purpose).toBeNull();
    expect(Object.keys(parsed!)).not.toContain("vin");
    expect(Object.keys(parsed!)).not.toContain("latitude");
  });
});

describe("auto-close of trips nobody ended", () => {
  it("closes a running trip after 12 hours of silence, not before", async () => {
    const t0 = Date.now();
    await upload(trip(1, { destinationName: "판교" }));

    expect(await closeStaleTrips(t0 + 11 * HOUR)).toEqual([]);
    expect((await tripDoc()).status).toBe("ACTIVE");

    expect(await closeStaleTrips(t0 + 13 * HOUR)).toEqual([`${VEHICLE}_7`]);
    const closed = await tripDoc();
    expect(closed).toMatchObject({ status: "ENDED", endReason: END_SERVER_TIMEOUT, closedByServer: true, purpose: "BUSINESS", purposeProvisional: true });
    expect(closed.endedAt).toBeGreaterThanOrEqual(t0);
    expect(closed.endedAt).toBeLessThan(t0 + HOUR);
    expect((await vehicleDoc()).activeTripId).toBeNull();
  });

  it("any upload from the vehicle keeps its trip open", async () => {
    const t0 = Date.now();
    await upload(trip(1, {}));
    // Something else arrives from the vehicle later (simulated as a later lastUploadAt)
    await db().collection(COL.vehicles).doc(VEHICLE).set({ lastUploadAt: t0 + 10 * HOUR }, { merge: true });

    expect(await closeStaleTrips(t0 + 13 * HOUR)).toEqual([]);
  });

  it("a real trip end arriving after the server closed the trip replaces the provisional close", async () => {
    const t0 = Date.now();
    await upload(trip(1, {}));
    await closeStaleTrips(t0 + 13 * HOUR);

    await upload(trip(2, { status: "ENDED", endedAt: 8_000, endOdometerKm: 12_362, endSocPercent: 60, purpose: "COMMUTE", purposeProvisional: false, endReason: "COUNTDOWN" }));

    expect(await tripDoc()).toMatchObject({ status: "ENDED", endReason: "COUNTDOWN", closedByServer: false, purpose: "COMMUTE", purposeProvisional: false, endOdometerKm: 12_362 });
    expect(await vehicleDoc()).toMatchObject({ lastOdometerKm: 12_362, lastSocPercent: 60 });
  });

  it("the waiting time can be shortened for a demo", async () => {
    process.env.BOBCAR_AUTO_CLOSE_MINUTES = "1";
    const t0 = Date.now();
    await upload(trip(1, {}));

    expect(await closeStaleTrips(t0 + 2 * 60_000)).toEqual([`${VEHICLE}_7`]);
  });

  it("the dev route runs the check now", async () => {
    const t0 = Date.now();
    await upload(trip(1, {}));

    const res = await request(app).post("/v1/dev/auto-close").set("Authorization", `Bearer ${token}`).send({ now: t0 + 13 * HOUR });

    expect(res.status).toBe(200);
    expect(res.body.closed).toEqual([`${VEHICLE}_7`]);
  });

  it("the dev route does not exist outside the emulator", async () => {
    const prod = createApp({ dev: false });
    const res = await request(prod).post("/v1/dev/auto-close").set("Authorization", `Bearer ${token}`).send({});

    expect(res.status).toBe(404);
  });
});

describe("trip rows follow consent", () => {
  it("a trip of a driver without consent is not stored", async () => {
    const res = await upload({ ...trip(1, { driverId: "drv-002" }), driverId: "drv-002" });

    expect(res.body.skipped).toEqual([{ queueId: 1, reason: "no-consent" }]);
    expect((await db().collection(COL.trips).get()).size).toBe(0);
  });
});
