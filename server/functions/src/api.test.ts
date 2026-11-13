import request from "supertest";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createApp } from "./app";
import { COL, db } from "./db";
import { SEED_RESERVED_DRIVER_ID, SEED_VEHICLE_ID } from "./seed";
import { anonymousIdToken, resetData } from "./testing";

const app = createApp({ dev: false });
let token = "";

const VEHICLE = SEED_VEHICLE_ID;

function event(queueId: number, driverId: string | null, type = "Boarded", occurredAt = 1_000 + queueId) {
  return {
    queueId,
    kind: "vehicle_event",
    createdAt: occurredAt,
    driverId,
    payload: { eventId: queueId, type, occurredAt, detail: null },
  };
}

function consent(queueId: number, driverId: string, version = 1) {
  return {
    queueId,
    kind: "consent",
    createdAt: 5_000 + queueId,
    driverId,
    payload: { eventId: queueId, type: "ConsentAgreed", occurredAt: 5_000 + queueId, detail: `driver=${driverId} version=${version}` },
  };
}

async function agree(driverId: string) {
  await db().collection(COL.drivers).doc(driverId).set({ consent: { status: "AGREED", agreedAt: 1, noticeVersion: 1 } }, { merge: true });
}

const upload = (body: unknown) => request(app).post("/v1/uploads").set("Authorization", `Bearer ${token}`).send(body as object);

beforeAll(async () => {
  token = await anonymousIdToken();
});

beforeEach(async () => {
  await resetData(Date.now());
});

describe("authentication", () => {
  const routes: Array<["get" | "post", string]> = [
    ["post", "/v1/uploads"],
    ["get", `/v1/vehicles/${VEHICLE}`],
    ["get", "/v1/drivers"],
    ["get", `/v1/reservations?vehicleId=${VEHICLE}`],
    ["get", `/v1/trips?vehicleId=${VEHICLE}`],
    ["get", `/v1/events?vehicleId=${VEHICLE}`],
    ["get", `/v1/flags?vehicleId=${VEHICLE}`],
    ["get", `/v1/transactions?vehicleId=${VEHICLE}`],
    ["post", "/v1/transactions"],
  ];

  it.each(routes)("%s %s without a token is 401", async (method, path) => {
    const res = await request(app)[method](path);
    expect(res.status).toBe(401);
  });

  it.each(routes)("%s %s with a bad token is 401", async (method, path) => {
    const res = await request(app)[method](path).set("Authorization", "Bearer not-a-token");
    expect(res.status).toBe(401);
  });

  it("a token in the query string is not accepted", async () => {
    const res = await request(app).get(`/v1/drivers?token=${token}`);
    expect(res.status).toBe(401);
  });
});

describe("uploads", () => {
  it("stores the items of a driver who agreed, and only once", async () => {
    await agree("drv-001");
    const body = { vehicleId: VEHICLE, items: [event(1, "drv-001"), event(2, "drv-001", "TripStarted")] };

    const first = await upload(body);
    expect(first.status).toBe(200);
    expect(first.body).toEqual({ stored: [1, 2], duplicates: [], skipped: [] });

    const again = await upload(body);
    expect(again.body).toEqual({ stored: [], duplicates: [1, 2], skipped: [] });

    const events = await db().collection(COL.events).where("vehicleId", "==", VEHICLE).get();
    expect(events.size).toBe(2);
    const vehicle = await db().collection(COL.vehicles).doc(VEHICLE).get();
    expect(typeof vehicle.get("lastUploadAt")).toBe("number");
  });

  it("applies consent first, so earlier records in the same batch go through", async () => {
    const res = await upload({ vehicleId: VEHICLE, items: [event(1, "drv-002"), event(2, "drv-002"), consent(3, "drv-002", 2)] });

    expect(res.status).toBe(200);
    expect(res.body.stored.sort()).toEqual([1, 2, 3]);
    const driver = await db().collection(COL.drivers).doc("drv-002").get();
    expect(driver.get("consent.status")).toBe("AGREED");
    expect(driver.get("consent.noticeVersion")).toBe(2);
    expect(driver.get("consent.agreedAt")).toBe(5_003);
  });

  it("skips records of drivers without consent and keeps the rest of the batch", async () => {
    await agree("drv-001");
    const res = await upload({ vehicleId: VEHICLE, items: [event(1, "drv-001"), event(2, "drv-003"), event(3, null), event(4, "drv-999")] });

    expect(res.status).toBe(200);
    expect(res.body.stored).toEqual([1]);
    expect(res.body.skipped).toEqual([
      { queueId: 2, reason: "no-consent" },
      { queueId: 3, reason: "no-driver" },
      { queueId: 4, reason: "unknown-driver" },
    ]);
    const events = await db().collection(COL.events).get();
    expect(events.docs.map((d) => d.get("driverId"))).toEqual(["drv-001"]);
  });

  it("skips malformed items and unknown kinds", async () => {
    await agree("drv-001");
    const res = await upload({
      vehicleId: VEHICLE,
      items: [{ queueId: 1, kind: "vehicle_event" }, "x", { ...event(3, "drv-001"), kind: "photo" }, event(4, "drv-001")],
    });

    expect(res.status).toBe(200);
    expect(res.body.stored).toEqual([4]);
    expect(res.body.skipped).toEqual([
      { queueId: 1, reason: "invalid" },
      { queueId: null, reason: "invalid" },
      { queueId: 3, reason: "unsupported-kind" },
    ]);
  });

  it("refuses a body that is not a batch (400) and an unregistered vehicle (403)", async () => {
    expect((await upload({ items: "nope" })).status).toBe(400);
    expect((await upload({ items: [] })).status).toBe(400);
    expect((await upload({ vehicleId: "veh-999", items: [event(1, "drv-001")] })).status).toBe(403);
  });

  it("does not touch the vehicle when nothing was stored", async () => {
    await upload({ vehicleId: VEHICLE, items: [event(1, "drv-003")] });

    const vehicle = await db().collection(COL.vehicles).doc(VEHICLE).get();
    expect(vehicle.get("lastUploadAt")).toBeUndefined();
  });
});

describe("lookups", () => {
  const get = (path: string) => request(app).get(path).set("Authorization", `Bearer ${token}`);

  it("lists drivers with their consent state", async () => {
    await agree("drv-001");
    const res = await get("/v1/drivers");

    expect(res.status).toBe(200);
    expect(res.body.items).toHaveLength(6);
    const byId = Object.fromEntries(res.body.items.map((d: { driverId: string; consentStatus: string }) => [d.driverId, d.consentStatus]));
    expect(byId["drv-001"]).toBe("AGREED");
    expect(byId["drv-002"]).toBe("PENDING");
  });

  it("returns today's seed reservation", async () => {
    const now = Date.now();
    const res = await get(`/v1/reservations?vehicleId=${VEHICLE}&from=${now}&to=${now + 1}`);

    expect(res.status).toBe(200);
    expect(res.body.items).toHaveLength(1);
    expect(res.body.items[0].driverId).toBe(SEED_RESERVED_DRIVER_ID);
    expect(res.body.items[0].startAt).toBeLessThanOrEqual(now);
    expect(res.body.items[0].endAt).toBeGreaterThan(now);
  });

  it("returns the vehicle, and 404 for an unknown one", async () => {
    expect((await get(`/v1/vehicles/${VEHICLE}`)).body.vehicleId).toBe(VEHICLE);
    expect((await get("/v1/vehicles/veh-999")).status).toBe(404);
  });

  it("lists events newest first, and needs a vehicleId", async () => {
    await agree("drv-001");
    await upload({ vehicleId: VEHICLE, items: [event(1, "drv-001", "Boarded", 100), event(2, "drv-001", "TripStarted", 200)] });

    const res = await get(`/v1/events?vehicleId=${VEHICLE}`);
    expect(res.body.items.map((e: { type: string }) => e.type)).toEqual(["TripStarted", "Boarded"]);
    expect((await get("/v1/events")).status).toBe(400);
  });

  it("starts with no flags and no trips", async () => {
    expect((await get(`/v1/flags?vehicleId=${VEHICLE}`)).body.items).toEqual([]);
    expect((await get(`/v1/trips?vehicleId=${VEHICLE}`)).body.items).toEqual([]);
  });

  it("stores and lists mock card payments", async () => {
    const post = (body: object) => request(app).post("/v1/transactions").set("Authorization", `Bearer ${token}`).send(body);
    const created = await post({ vehicleId: VEHICLE, amount: 12_000, merchant: "충전소", category: "CHARGE", paidAt: 3_000 });
    expect(created.status).toBe(201);

    expect((await post({ vehicleId: VEHICLE, amount: -1, merchant: "x", category: "CHARGE", paidAt: 1 })).status).toBe(400);
    expect((await post({ vehicleId: VEHICLE, amount: 1, merchant: "x", category: "SNACK", paidAt: 1 })).status).toBe(400);
    expect((await post({ vehicleId: "veh-999", amount: 1, merchant: "x", category: "TOLL", paidAt: 1 })).status).toBe(403);

    const list = await get(`/v1/transactions?vehicleId=${VEHICLE}`);
    expect(list.body.items).toHaveLength(1);
    expect(list.body.items[0]).toMatchObject({ amount: 12_000, category: "CHARGE", paidAt: 3_000 });
  });

  it("answers 404 for unknown paths", async () => {
    expect((await get("/v1/nothing")).status).toBe(404);
  });
});
