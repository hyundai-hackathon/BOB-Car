import ExcelJS from "exceljs";
import request from "supertest";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { koreaMonth } from "./admin";
import { createApp } from "./app";
import { COL, db } from "./db";
import { koreaDay, SEED_VEHICLE_ID } from "./seed";
import { anonymousIdToken, resetData } from "./testing";

const app = createApp({ dev: true });
const VEHICLE = SEED_VEHICLE_ID;
const MIN = 60_000;
const DAY = 24 * 60 * MIN;
let token = "";
let queueId = 0;
let T = 0;

const auth = () => ({ Authorization: `Bearer ${token}` });
const get = (path: string) => request(app).get(path).set(auth());

async function upload(...items: object[]) {
  const res = await request(app).post("/v1/uploads").set(auth()).send({ vehicleId: VEHICLE, items });
  expect(res.status).toBe(200);
}

function trip(driverId: string, tripId: number, startedAt: number, fields: Record<string, unknown>) {
  queueId += 1;
  return {
    queueId,
    kind: "trip",
    createdAt: startedAt,
    driverId,
    payload: { tripId, status: "ENDED", startedAt, endedAt: startedAt + 30 * MIN, driverId, driverProvisional: false, purposeProvisional: false, ...fields },
  };
}

function event(driverId: string, type: string, at: number, detail: string) {
  queueId += 1;
  return { queueId, kind: "vehicle_event", createdAt: at, driverId, payload: { eventId: queueId, type, occurredAt: at, detail } };
}

beforeAll(async () => {
  token = await anonymousIdToken();
});

beforeEach(async () => {
  const now = Date.now();
  await resetData(now);
  queueId = 0;
  T = koreaDay(now).startAt + 3 * 60 * MIN; // inside today's reservation (김민준)
  for (const id of ["drv-001", "drv-002", "drv-003"]) {
    await db().collection(COL.drivers).doc(id).set({ consent: { status: "AGREED", agreedAt: 1 } }, { merge: true });
  }
  // A month of trips: business with toll, commute, a provisional personal trip by someone else, and an unrecorded drive
  await upload(
    trip("drv-001", 1, T, { startOdometerKm: 12_406, endOdometerKm: 12_433.6, purpose: "BUSINESS", destinationName: "강남 고객사", expectedTollFare: 1_900 }),
    trip("drv-001", 2, T + 60 * MIN, { startOdometerKm: 12_439.8, endOdometerKm: 12_460.6, purpose: "PERSONAL", destinationName: "동네 마트", driverProvisional: true, purposeProvisional: true }),
    trip("drv-001", 3, T + 2 * 60 * MIN, { startOdometerKm: 12_460.6, endOdometerKm: 12_481.6, purpose: "COMMUTE", destinationName: "본사 주차장", expectedTollFare: 0 }),
    event("drv-001", "UnrecordedDrive", T + 50 * MIN, `km=6.20 from=12433.6 to=12439.8 foundAt=BOARDING sinceAt=${T + 30 * MIN}`),
  );
});

describe("A1 attention list", () => {
  it("lists open flags with names and the consent still pending", async () => {
    // Use outside the reservation by 이서연, and a charge payment with no charge
    await upload(event("drv-002", "DriverConfirmed", T + 3 * 60 * MIN, "driver=drv-002"));
    await upload(trip("drv-002", 4, T + 3 * 60 * MIN + MIN, { startOdometerKm: 12_481.6, endOdometerKm: 12_500, purpose: "PERSONAL", purposeProvisional: true }));
    await request(app).post("/v1/transactions").set(auth()).send({ vehicleId: VEHICLE, amount: 32_000, merchant: "충전소", category: "CHARGE", paidAt: T + 4 * 60 * MIN });

    const res = await get(`/v1/admin/attention?vehicleId=${VEHICLE}`);

    expect(res.status).toBe(200);
    expect(res.body.vehicle).toMatchObject({ name: "업무차 1호", model: "아이오닉 5", plate: "12가 3456" });
    const byType = Object.fromEntries(res.body.items.map((i: { type: string }) => [i.type, i]));
    expect(byType.payment).toMatchObject({ category: "CHARGE", reason: "NO_CHARGE", paidAmount: 32_000, merchant: "충전소", driverName: "이서연" });
    expect(byType.unrecorded).toMatchObject({ km: 6.2, startKm: 12_433.6, endKm: 12_439.8, lastDriverName: "김민준", foundByName: "김민준" });
    expect(byType.usage).toMatchObject({ reason: "OTHER_DRIVER", driverName: "이서연", reservedDriverName: "김민준" });
    expect(byType.usage.trips).toEqual([expect.objectContaining({ distanceKm: 18.4, purpose: "PERSONAL", purposeProvisional: true })]);
    // Newest first
    expect(res.body.items.map((i: { type: string }) => i.type)).toEqual(["payment", "usage", "unrecorded"]);
    expect(res.body.consentPending.map((d: { name: string }) => d.name).sort()).toEqual(["정현우", "최유나", "한소희"]);
    expect(res.body.consentPending[0]).toHaveProperty("department");
  });

  it("resolved flags are not listed", async () => {
    await db().collection(COL.flags).doc("x").set({ vehicleId: VEHICLE, kind: "CHARGE_PAYMENT", status: "RESOLVED", transactionId: "t", paidAt: T });
    const res = await get(`/v1/admin/attention?vehicleId=${VEHICLE}`);

    expect(res.body.items.map((i: { flagId: string }) => i.flagId)).not.toContain("x");
  });

  it("an unknown vehicle is 404 and no token is 401", async () => {
    expect((await get("/v1/admin/attention?vehicleId=veh-999")).status).toBe(404);
    expect((await request(app).get(`/v1/admin/attention?vehicleId=${VEHICLE}`)).status).toBe(401);
  });
});

describe("A2 logbook", () => {
  it("has the month's trips and the unrecorded drive, newest first, with the form's figures", async () => {
    const res = await get(`/v1/admin/logbook?vehicleId=${VEHICLE}&month=${koreaMonth(T)}`);

    expect(res.status).toBe(200);
    const rows = res.body.rows as Array<Record<string, unknown>>;
    expect(rows.map((r) => r.purpose ?? (r.unrecorded ? "UNRECORDED" : null))).toEqual(["COMMUTE", "PERSONAL", "UNRECORDED", "BUSINESS"]);
    expect(rows[0]).toMatchObject({ driverName: "김민준", department: "영업팀", distanceKm: 21, commuteKm: 21, businessKm: 0, destination: "본사 주차장" });
    // A personal trip never shows its destination; provisional values are marked
    expect(rows[1]).toMatchObject({ destination: null, driverProvisional: true, purposeProvisional: true, commuteKm: 0, businessKm: 0 });
    expect(rows[2]).toMatchObject({ unrecorded: true, driverName: null, distanceKm: 6.2, startKm: 12_433.6, endKm: 12_439.8 });
    expect(rows[3]).toMatchObject({ businessKm: 27.6, expectedTollFare: 1_900, destination: "강남 고객사" });
    // 27.6 + 20.8 + 6.2 + 21.0 = 75.6 km; business use = 27.6 + 21.0 = 48.6 km (64.3 %)
    expect(res.body.summary).toEqual({ trips: 4, totalKm: 75.6, businessKm: 48.6, commuteKm: 21, generalKm: 27.6, ratioPercent: 64.3, tollFare: 1_900 });
  });

  it("other months and running trips are left out", async () => {
    await upload(trip("drv-001", 9, T - 40 * DAY, { startOdometerKm: 1, endOdometerKm: 2, purpose: "BUSINESS" }));
    await upload({ ...trip("drv-001", 10, T + 5 * 60 * MIN, { purpose: null }), payload: { tripId: 10, status: "ACTIVE", startedAt: T + 5 * 60 * MIN, driverId: "drv-001" } });

    const res = await get(`/v1/admin/logbook?vehicleId=${VEHICLE}&month=${koreaMonth(T)}`);
    expect(res.body.rows).toHaveLength(4);
  });

  it("a bad month is 404", async () => {
    expect((await get(`/v1/admin/logbook?vehicleId=${VEHICLE}&month=2026-13`)).status).toBe(404);
  });
});

describe("국세청 양식 엑셀", () => {
  it("downloads the form with the trips oldest first and the period totals", async () => {
    const res = await get(`/v1/admin/logbook.xlsx?vehicleId=${VEHICLE}&month=${koreaMonth(T)}`).buffer(true).parse((r, cb) => {
      const chunks: Buffer[] = [];
      r.on("data", (c: Buffer) => chunks.push(c));
      r.on("end", () => cb(null, Buffer.concat(chunks)));
    });

    expect(res.status).toBe(200);
    expect(res.headers["content-type"]).toContain("spreadsheetml");
    expect(res.headers["content-disposition"]).toContain("filename*=UTF-8''");
    const wb = new ExcelJS.Workbook();
    await wb.xlsx.load(res.body as Buffer);
    const ws = wb.getWorksheet("운행기록부")!;
    expect(ws.getCell("A1").value).toBe("업무용승용차 운행기록부");
    expect(ws.getCell("E5").value).toBe("12가 3456");
    expect(ws.getCell("G8").value).toBe("업무용 사용거리(㎞)\n출·퇴근용");
    // First data row: the business trip
    expect(ws.getCell("B9").value).toBe("영업팀");
    expect(ws.getCell("C9").value).toBe("김민준");
    expect(ws.getCell("F9").value).toBe(27.6);
    expect(ws.getCell("H9").value).toBe(27.6);
    expect(String(ws.getCell("I9").value)).toContain("예상 통행료 1,900원");
    // The unrecorded drive and the personal trip
    expect(ws.getCell("C10").value).toBe("운전자 미상");
    expect(ws.getCell("I10").value).toBe("미기록 운행(운전자 미상)");
    expect(String(ws.getCell("I11").value)).toMatch(/^개인, 운전자·용도 잠정$/);
    // Totals
    const labels: Record<string, unknown> = {};
    ws.eachRow((row) => {
      const label = row.getCell(1).value;
      if (typeof label === "string" && label.startsWith("사업연도") || label === "업무사용비율(%)") labels[label as string] = row.getCell(4).value;
    });
    expect(labels).toMatchObject({ "사업연도 총주행 거리(㎞)": 75.6, "사업연도 업무용 사용거리(㎞)": 48.6, "업무사용비율(%)": 64.3 });
  });
});
