import ExcelJS from "exceljs";
import type { DocumentSnapshot } from "firebase-admin/firestore";
import { COL, db } from "./db";
import { FLAG_OPEN } from "./reconcile";
import { SEED_COMPANY } from "./seed";
import { FLAG_KIND_OUT_OF_RESERVATION, FLAG_KIND_UNRECORDED, TRIP_STATUS_UNRECORDED } from "./usage";

/*
 * Views for the admin web (F7, Figma Admin-Web A1–A2). Read only: the server joins and computes here,
 * the page only shows. Wording on screen lives in the page; values and IDs come from here.
 *
 * A1 "확인이 필요한 항목": open flags of the vehicle (payments, unrecorded drives, use outside the
 *    reservation) with names and the trips they point at, and the drivers whose consent is pending.
 * A2 운행기록부: the month's ended trips and unrecorded drives (Korea time), with the 국세청
 *    업무용승용차 운행기록부 figures: commute and business distance, business-use ratio. Also as .xlsx.
 */

const KST_OFFSET_MS = 9 * 60 * 60_000;
const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

export interface VehicleView {
  vehicleId: string;
  name: string | null;
  model: string | null;
  plate: string | null;
}

export interface Driver {
  name: string;
  department: string | null;
  agreed: boolean;
}

// ---------------------------------------------------------------- A1 attention list

export async function attentionView(vehicleId: string, now: number) {
  const [vehicle, drivers, flags] = await Promise.all([
    vehicleOf(vehicleId),
    driversById(),
    db().collection(COL.flags).where("vehicleId", "==", vehicleId).where("status", "==", FLAG_OPEN).get(),
  ]);
  if (!vehicle) return null;
  const nameOf = (id: unknown) => (typeof id === "string" ? drivers.get(id)?.name ?? id : null);

  const items = [];
  for (const flag of flags.docs) {
    const kind = flag.get("kind") as string;
    if (kind === "CHARGE_PAYMENT" || kind === "FUEL_PAYMENT") {
      const tx = await db().collection(COL.transactions).doc(flag.get("transactionId") as string).get();
      items.push({
        flagId: flag.id,
        type: "payment",
        category: kind === "FUEL_PAYMENT" ? "FUEL" : "CHARGE",
        reason: flag.get("reason") ?? null,
        note: flag.get("note") ?? null,
        at: flag.get("paidAt") as number,
        paidAmount: flag.get("paidAmount") ?? null,
        merchant: tx.exists ? tx.get("merchant") : null,
        expectedAmount: flag.get("expectedAmount") ?? null,
        ratio: flag.get("ratio") ?? null,
        // Who had the car when the card was used (the card holder is not known)
        driverName: nameOf(flag.get("driverId")),
      });
    } else if (kind === FLAG_KIND_UNRECORDED) {
      const trip = await db().collection(COL.trips).doc(flag.get("tripDocId") as string).get();
      items.push({
        flagId: flag.id,
        type: "unrecorded",
        at: (flag.get("since") as number | null) ?? (flag.get("foundAt") as number),
        since: flag.get("since") ?? null,
        foundAt: flag.get("foundAt") ?? null,
        km: flag.get("km") ?? null,
        startKm: trip.exists ? trip.get("startOdometerKm") ?? null : null,
        endKm: trip.exists ? trip.get("endOdometerKm") ?? null : null,
        lastDriverName: nameOf(flag.get("lastDriverId")),
        foundByName: nameOf(flag.get("foundByDriverId")),
        tripDocId: flag.get("tripDocId") ?? null,
        // Named on the phone but not yet taken by that driver (provisional), and their note
        assignedDriverName: trip.exists ? nameOf(trip.get("driverId")) : null,
        memo: trip.exists ? trip.get("memo") ?? null : null,
      });
    } else if (kind === FLAG_KIND_OUT_OF_RESERVATION) {
      const occurrences = (flag.get("occurrences") as Array<{ type: string; ref: string; at: number }>) ?? [];
      const trips = (
        await Promise.all(
          occurrences.filter((o) => o.type === "trip").map((o) => db().collection(COL.trips).doc(o.ref).get()),
        )
      )
        .filter((t) => t.exists)
        .map(tripSummary)
        .sort((a, b) => a.startedAt - b.startedAt);
      items.push({
        flagId: flag.id,
        type: "usage",
        reason: flag.get("reason") ?? null,
        at: trips[0]?.startedAt ?? (flag.get("firstAt") as number),
        driverName: nameOf(flag.get("driverId")),
        reservedDriverName: nameOf(flag.get("reservedDriverId")),
        reservationStartAt: flag.get("reservationStartAt") ?? null,
        reservationEndAt: flag.get("reservationEndAt") ?? null,
        trips,
      });
    }
  }
  items.sort((a, b) => b.at - a.at);

  // Consent: the server only knows who agreed (answers other than [동의] stay in the car)
  const consentPending = [...drivers.entries()]
    .filter(([, d]) => !d.agreed)
    .map(([driverId, d]) => ({ driverId, name: d.name, department: d.department }));

  return { asOf: now, vehicle, items, consentPending };
}

function tripSummary(trip: DocumentSnapshot) {
  return {
    tripDocId: trip.id,
    startedAt: trip.get("startedAt") as number,
    endedAt: (trip.get("endedAt") as number | null) ?? null,
    status: trip.get("status") as string,
    distanceKm: distanceOf(trip),
    purpose: (trip.get("purpose") as string | null) ?? null,
    purposeProvisional: trip.get("purposeProvisional") !== false,
    memo: (trip.get("memo") as string | null | undefined) ?? null,
  };
}

// ---------------------------------------------------------------- A2 logbook

export interface LogbookRow {
  tripDocId: string;
  startedAt: number;
  endedAt: number | null;
  driverName: string | null;
  department: string | null;
  driverProvisional: boolean;
  startKm: number | null;
  endKm: number | null;
  distanceKm: number | null;
  purpose: string | null;
  purposeProvisional: boolean;
  /** 업무용 사용거리: 출·퇴근용 / 일반 업무용 (국세청 양식) */
  commuteKm: number;
  businessKm: number;
  /** null for a personal trip (never shown) */
  destination: string | null;
  expectedTollFare: number | null;
  unrecorded: boolean;
  closedByServer: boolean;
  /** The driver's note from the phone (visit purpose, passengers; also an explanation of a flag). */
  memo: string | null;
  /** Driver or purpose was set on the phone (audit trail in tripEdits). */
  editedOnPhone: boolean;
  /** An unrecorded drive that a driver later took as theirs on the phone. */
  claimedUnrecorded: boolean;
}

export interface LogbookView {
  month: string;
  from: number;
  to: number;
  vehicle: VehicleView;
  rows: LogbookRow[];
  summary: { trips: number; totalKm: number; businessKm: number; commuteKm: number; generalKm: number; ratioPercent: number; tollFare: number };
}

/** "2026-11" (Korea time) for [now]. */
export function koreaMonth(now: number): string {
  return new Date(now + KST_OFFSET_MS).toISOString().slice(0, 7);
}

function monthRange(month: string): { from: number; to: number } | null {
  const m = /^(\d{4})-(\d{2})$/.exec(month);
  if (!m) return null;
  const y = Number(m[1]);
  const mo = Number(m[2]);
  if (mo < 1 || mo > 12) return null;
  return { from: Date.UTC(y, mo - 1, 1) - KST_OFFSET_MS, to: Date.UTC(y, mo, 1) - KST_OFFSET_MS };
}

/** The month's logbook, newest first. null when the vehicle or the month is unknown. */
export async function logbookView(vehicleId: string, month: string): Promise<LogbookView | null> {
  const range = monthRange(month);
  const vehicle = await vehicleOf(vehicleId);
  if (!range || !vehicle) return null;
  const [drivers, trips] = await Promise.all([driversById(), db().collection(COL.trips).where("vehicleId", "==", vehicleId).get()]);

  const rows: LogbookRow[] = trips.docs
    .filter((t) => {
      const status = t.get("status");
      const startedAt = t.get("startedAt");
      return (status === "ENDED" || status === TRIP_STATUS_UNRECORDED) && typeof startedAt === "number" && startedAt >= range.from && startedAt < range.to;
    })
    .map((t) => {
      const unrecorded = t.get("status") === TRIP_STATUS_UNRECORDED;
      const distance = distanceOf(t);
      const purpose = unrecorded ? null : ((t.get("purpose") as string | null) ?? null);
      const driverId = t.get("driverId") as string | null;
      const driver = driverId ? drivers.get(driverId) : undefined;
      return {
        tripDocId: t.id,
        startedAt: t.get("startedAt") as number,
        endedAt: (t.get("endedAt") as number | null) ?? null,
        driverName: driver?.name ?? driverId ?? null,
        department: driver?.department ?? null,
        driverProvisional: !unrecorded && driverId !== null && t.get("driverProvisional") !== false,
        startKm: num(t.get("startOdometerKm")),
        endKm: num(t.get("endOdometerKm")),
        distanceKm: distance,
        purpose,
        purposeProvisional: !unrecorded && t.get("purposeProvisional") !== false,
        commuteKm: purpose === "COMMUTE" ? distance ?? 0 : 0,
        businessKm: purpose === "BUSINESS" ? distance ?? 0 : 0,
        destination: purpose === "PERSONAL" ? null : ((t.get("destinationName") as string | null) ?? null),
        expectedTollFare: num(t.get("expectedTollFare")),
        unrecorded,
        closedByServer: t.get("closedByServer") === true,
        memo: (t.get("memo") as string | null | undefined) ?? null,
        editedOnPhone: t.get("correction") !== undefined,
        claimedUnrecorded: t.get("recordedFrom") === TRIP_STATUS_UNRECORDED,
      };
    })
    .sort((a, b) => b.startedAt - a.startedAt);

  const sum = (f: (r: LogbookRow) => number) => round1(rows.reduce((s, r) => s + f(r), 0));
  const totalKm = sum((r) => r.distanceKm ?? 0);
  const commuteKm = sum((r) => r.commuteKm);
  const generalKm = sum((r) => r.businessKm);
  const businessKm = round1(commuteKm + generalKm);
  return {
    month,
    ...range,
    vehicle,
    rows,
    summary: {
      trips: rows.length,
      totalKm,
      businessKm,
      commuteKm,
      generalKm,
      ratioPercent: totalKm > 0 ? round1((businessKm / totalKm) * 100) : 0,
      tollFare: rows.reduce((s, r) => s + (r.expectedTollFare ?? 0), 0),
    },
  };
}

// ---------------------------------------------------------------- 국세청 양식 xlsx

/**
 * 업무용승용차 운행기록부 (법인세법 시행규칙 별지 제52호의2 서식의 항목) as an .xlsx workbook, oldest first.
 * Destination, expected toll and BOB-Car remarks (provisional, unrecorded, personal) go in 비고.
 */
export async function logbookWorkbook(view: LogbookView): Promise<Buffer> {
  const wb = new ExcelJS.Workbook();
  wb.creator = "BOB-Car";
  const ws = wb.addWorksheet("운행기록부", { pageSetup: { orientation: "landscape", fitToPage: true, fitToWidth: 1 } });
  ws.columns = [
    { width: 18 }, // A 사용일자(요일)
    { width: 10 }, // B 부서
    { width: 10 }, // C 성명
    { width: 14 }, // D 주행 전
    { width: 14 }, // E 주행 후
    { width: 11 }, // F 주행거리
    { width: 12 }, // G 출·퇴근용
    { width: 12 }, // H 일반 업무용
    { width: 44 }, // I 비고
  ];
  const thin = { style: "thin" as const };
  const box = { top: thin, left: thin, bottom: thin, right: thin };
  const center = { vertical: "middle" as const, horizontal: "center" as const, wrapText: true };

  ws.mergeCells("A1:I1");
  ws.getCell("A1").value = "업무용승용차 운행기록부";
  ws.getCell("A1").font = { size: 16, bold: true };
  ws.getCell("A1").alignment = center;
  ws.getRow(1).height = 30;

  const lastDay = new Date(view.to - 1 + KST_OFFSET_MS).toISOString().slice(0, 10);
  const info: Array<[string, string, string, string]> = [
    ["사업연도", `${view.month}-01 ~ ${lastDay}`, "법인명", SEED_COMPANY.name],
    ["사업자등록번호", SEED_COMPANY.registrationNumber, "", ""],
    ["차종", view.vehicle.model ?? view.vehicle.name ?? "", "자동차등록번호", view.vehicle.plate ?? ""],
  ];
  info.forEach(([k1, v1, k2, v2], i) => {
    const r = 3 + i;
    ws.mergeCells(`B${r}:C${r}`);
    ws.mergeCells(`E${r}:F${r}`);
    ws.mergeCells(`G${r}:I${r}`);
    ws.getCell(`A${r}`).value = k1;
    ws.getCell(`B${r}`).value = v1;
    ws.getCell(`D${r}`).value = k2;
    ws.getCell(`E${r}`).value = v2;
    for (const c of ["A", "D"]) ws.getCell(`${c}${r}`).font = { bold: true };
  });

  // Two header rows
  const h1 = 7;
  const h2 = 8;
  const headers: Array<[string, string]> = [
    [`A${h1}:A${h2}`, "사용일자\n(요일)"],
    [`B${h1}:C${h1}`, "사용자"],
    [`D${h1}:H${h1}`, "운 행 내 역"],
    [`I${h1}:I${h2}`, "비고"],
  ];
  for (const [range, text] of headers) {
    ws.mergeCells(range);
    ws.getCell(range.split(":")[0]).value = text;
  }
  ws.getCell(`B${h2}`).value = "부서";
  ws.getCell(`C${h2}`).value = "성명";
  ws.getCell(`D${h2}`).value = "주행 전\n계기판의 거리(㎞)";
  ws.getCell(`E${h2}`).value = "주행 후\n계기판의 거리(㎞)";
  ws.getCell(`F${h2}`).value = "주행거리(㎞)";
  ws.getCell(`G${h2}`).value = "업무용 사용거리(㎞)\n출·퇴근용";
  ws.getCell(`H${h2}`).value = "업무용 사용거리(㎞)\n일반 업무용";
  for (const r of [h1, h2]) {
    ws.getRow(r).height = r === h2 ? 36 : 22;
    for (let c = 1; c <= 9; c++) {
      const cell = ws.getRow(r).getCell(c);
      cell.font = { bold: true };
      cell.alignment = center;
      cell.border = box;
      cell.fill = { type: "pattern", pattern: "solid", fgColor: { argb: "FFEDEDF3" } };
    }
  }

  const rows = [...view.rows].sort((a, b) => a.startedAt - b.startedAt);
  let r = h2 + 1;
  for (const row of rows) {
    const values = [
      koreaDateWithWeekday(row.startedAt),
      row.unrecorded ? "" : row.department ?? "",
      row.unrecorded ? "운전자 미상" : row.driverName ?? "",
      row.startKm,
      row.endKm,
      row.distanceKm,
      row.commuteKm || null,
      row.businessKm || null,
      remarks(row),
    ];
    const excelRow = ws.getRow(r);
    values.forEach((v, i) => {
      const cell = excelRow.getCell(i + 1);
      cell.value = v;
      cell.border = box;
      if (i >= 3 && i <= 7) {
        cell.numFmt = "#,##0.0";
        cell.alignment = { horizontal: "right" };
      }
    });
    r++;
  }

  // Totals for the period
  r++;
  const totals: Array<[string, number, string]> = [
    ["사업연도 총주행 거리(㎞)", view.summary.totalKm, "#,##0.0"],
    ["사업연도 업무용 사용거리(㎞)", view.summary.businessKm, "#,##0.0"],
    ["업무사용비율(%)", view.summary.ratioPercent, "0.0"],
  ];
  for (const [label, value, fmt] of totals) {
    ws.mergeCells(`A${r}:C${r}`);
    ws.getCell(`A${r}`).value = label;
    ws.getCell(`A${r}`).font = { bold: true };
    ws.getCell(`D${r}`).value = value;
    ws.getCell(`D${r}`).numFmt = fmt;
    for (const c of ["A", "D"]) ws.getCell(`${c}${r}`).border = box;
    r++;
  }
  r++;
  ws.mergeCells(`A${r}:I${r}`);
  ws.getCell(`A${r}`).value =
    "BOB-Car 차량 앱이 계기판 누적 거리로 자동 작성. 잠정 = 운전자가 확인하지 않은 자동 값, 미기록 운행 = 운행 기록 없이 늘어난 거리(운전자 미상). 개인 운행의 목적지는 기록하지 않음.";
  ws.getCell(`A${r}`).font = { size: 9, color: { argb: "FF666666" } };
  ws.getCell(`A${r}`).alignment = { wrapText: true };
  ws.getRow(r).height = 28;

  return Buffer.from(await wb.xlsx.writeBuffer());
}

function remarks(row: LogbookRow): string {
  if (row.unrecorded) return "미기록 운행(운전자 미상)";
  const parts: string[] = [];
  if (row.purpose === "PERSONAL") parts.push("개인");
  else if (row.destination) parts.push(`목적지 ${row.destination}`);
  if (row.expectedTollFare) parts.push(`예상 통행료 ${row.expectedTollFare.toLocaleString("ko-KR")}원`);
  const provisional = [row.driverProvisional ? "운전자" : null, row.purposeProvisional ? "용도" : null].filter(Boolean);
  if (provisional.length > 0) parts.push(`${provisional.join("·")} 잠정`);
  if (row.closedByServer) parts.push("서버 자동 종료");
  if (row.claimedUnrecorded) parts.push("미기록 운행을 운전자가 확인");
  if (row.memo && row.purpose !== "PERSONAL") parts.push(`메모: ${row.memo}`);
  return parts.join(", ");
}

// ---------------------------------------------------------------- helpers

export async function vehicleOf(vehicleId: string): Promise<VehicleView | null> {
  const v = await db().collection(COL.vehicles).doc(vehicleId).get();
  if (!v.exists) return null;
  return { vehicleId, name: v.get("name") ?? null, model: v.get("model") ?? null, plate: v.get("plate") ?? null };
}

export async function driversById(): Promise<Map<string, Driver>> {
  const snaps = await db().collection(COL.drivers).where("active", "==", true).get();
  return new Map(
    snaps.docs.map((d) => [d.id, { name: d.get("name") as string, department: (d.get("department") as string | undefined) ?? null, agreed: d.get("consent.status") === "AGREED" }]),
  );
}

/** Odometer end − start; the stored distance for an unrecorded drive; null when unknown or backwards. */
export function distanceOf(trip: DocumentSnapshot): number | null {
  const start = num(trip.get("startOdometerKm"));
  const end = num(trip.get("endOdometerKm"));
  if (start !== null && end !== null && end >= start) return round1(end - start);
  return num(trip.get("distanceKm"));
}

function koreaDateWithWeekday(at: number): string {
  const d = new Date(at + KST_OFFSET_MS);
  return `${d.toISOString().slice(0, 10)} (${WEEKDAYS[d.getUTCDay()]})`;
}

function num(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function round1(value: number): number {
  return Math.round(value * 10) / 10;
}
