import type { DocumentSnapshot } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { COL, db } from "./db";
import { driverBefore, FLAG_OPEN, FLAG_RESOLVED } from "./reconcile";
import { koreaDay } from "./seed";

/*
 * Expense check ② unrecorded drive and ③ use outside the reservation (design-review F11, F12, demo scene 4).
 *
 * ③ The driver who uses the car (confirmed on the driver card, or the provisional default) is compared with
 *   the reservation covering that moment. Someone else, or no reservation at all, is flagged. Judged when a
 *   driver is confirmed (the flag shows right away) and again whenever a trip row changes. One flag per
 *   (vehicle, reservation or day without one, driver): more trips just add to it. When a trip ends up with
 *   the reservation holder (e.g. fixed on the trip-end card), what that trip had flagged is withdrawn.
 * ② A drive nobody recorded (F4 "UnrecordedDrive": odometer up between two snapshots) becomes a trip row
 *   with no driver (status UNRECORDED) and a flag "미기록 n km" with the last and the finding driver.
 *
 * Logs carry app-made IDs and counts only.
 */

export const EVENT_DRIVER_CONFIRMED = "DriverConfirmed";
export const EVENT_UNRECORDED = "UnrecordedDrive";

export const FLAG_KIND_OUT_OF_RESERVATION = "OUT_OF_RESERVATION";
export const FLAG_KIND_UNRECORDED = "UNRECORDED_DRIVE";
export const TRIP_STATUS_UNRECORDED = "UNRECORDED";

/** A confirmation made this long before a trip started belongs to that trip's check-in. */
const CHECKIN_LOOKBACK_MS = 30 * 60_000;

/** Where a use of the car was seen: a driver confirmation event, or a trip row. */
export type UsageSource = { type: "confirm"; ref: string } | { type: "trip"; ref: string; startedAt: number; endedAt: number | null };

interface Occurrence {
  type: "confirm" | "trip";
  ref: string;
  at: number;
}

// ---------------------------------------------------------------- ③ use outside the reservation

/** A driver confirmed on the card ("driver=drv-002" at [occurredAt]). */
export async function onDriverConfirmed(event: DocumentSnapshot): Promise<void> {
  const driverId = /(?:^|\s)driver=(\S+)/.exec(String(event.get("detail") ?? ""))?.[1];
  const at = event.get("occurredAt");
  if (!driverId || typeof at !== "number") return;
  await judgeUsage(event.get("vehicleId") as string, driverId, at, { type: "confirm", ref: event.id });
}

/** A trip row changed: its driver (confirmed or provisional) is judged at the trip's start. */
export async function onTripChanged(vehicleId: string, tripId: number): Promise<void> {
  const trip = await db().collection(COL.trips).doc(`${vehicleId}_${tripId}`).get();
  const driverId = trip.get("driverId");
  const startedAt = trip.get("startedAt");
  if (typeof driverId !== "string" || typeof startedAt !== "number") return;
  const endedAt = typeof trip.get("endedAt") === "number" ? (trip.get("endedAt") as number) : null;
  await judgeUsage(vehicleId, driverId, startedAt, { type: "trip", ref: trip.id, startedAt, endedAt });
}

export async function judgeUsage(vehicleId: string, driverId: string, at: number, source: UsageSource): Promise<void> {
  const reservation = await reservationAt(vehicleId, at);
  const slot = reservation ? reservation.id : `none-${koreaDate(at)}`;
  if (reservation && reservation.get("driverId") === driverId) {
    // The holder: a trip that ends up with the holder withdraws what it had flagged for someone else
    if (source.type === "trip") await withdrawForTrip(vehicleId, slot, driverId, source);
    return;
  }
  const ref = db().collection(COL.flags).doc(`usage_${vehicleId}_${slot}_${driverId}`);
  const now = Date.now();
  await db().runTransaction(async (tx) => {
    const existing = await tx.get(ref);
    const occurrences: Occurrence[] = existing.exists ? ((existing.get("occurrences") as Occurrence[]) ?? []) : [];
    if (occurrences.some((o) => o.type === source.type && o.ref === source.ref)) {
      if (existing.get("status") === FLAG_OPEN) return;
    }
    const next = [...occurrences.filter((o) => !(o.type === source.type && o.ref === source.ref)), { type: source.type, ref: source.ref, at }];
    tx.set(ref, {
      vehicleId,
      kind: FLAG_KIND_OUT_OF_RESERVATION,
      status: FLAG_OPEN,
      reason: reservation ? "OTHER_DRIVER" : "NO_RESERVATION",
      note: reservation ? "예약 밖 사용 (예약자와 다른 운전자)" : "예약 없는 시간 사용",
      driverId,
      reservedDriverId: reservation ? reservation.get("driverId") : null,
      reservationId: reservation ? reservation.id : null,
      reservationStartAt: reservation ? reservation.get("startAt") : null,
      reservationEndAt: reservation ? reservation.get("endAt") : null,
      occurrences: next,
      tripCount: new Set(next.filter((o) => o.type === "trip").map((o) => o.ref)).size,
      firstAt: Math.min(...next.map((o) => o.at)),
      createdAt: existing.exists ? existing.get("createdAt") : now,
      updatedAt: now,
      resolvedAt: null,
    });
  });
  logger.info("usage flagged", { vehicleId, driverId, slot, source: source.type });
}

/**
 * Trip [source] ended up with the reservation holder: occurrences of that trip (its row, and driver
 * confirmations made during its check-in or the trip) are taken out of other drivers' flags in the same
 * slot; a flag left with none is resolved as corrected.
 */
async function withdrawForTrip(vehicleId: string, slot: string, holderId: string, source: Extract<UsageSource, { type: "trip" }>): Promise<void> {
  const open = await db()
    .collection(COL.flags)
    .where("vehicleId", "==", vehicleId)
    .where("kind", "==", FLAG_KIND_OUT_OF_RESERVATION)
    .where("status", "==", FLAG_OPEN)
    .get();
  const from = source.startedAt - CHECKIN_LOOKBACK_MS;
  const to = source.endedAt ?? Number.MAX_SAFE_INTEGER;
  for (const flag of open.docs.filter((d) => d.id.startsWith(`usage_${vehicleId}_${slot}_`) && d.get("driverId") !== holderId)) {
    const occurrences = (flag.get("occurrences") as Occurrence[]) ?? [];
    const rest = occurrences.filter((o) => !(o.type === "trip" ? o.ref === source.ref : o.at >= from && o.at <= to));
    if (rest.length === occurrences.length) continue;
    const now = Date.now();
    await flag.ref.set(
      rest.length === 0
        ? { occurrences: rest, tripCount: 0, status: FLAG_RESOLVED, note: "예약자 운행으로 정정", updatedAt: now, resolvedAt: now }
        : { occurrences: rest, tripCount: new Set(rest.filter((o) => o.type === "trip").map((o) => o.ref)).size, updatedAt: now },
      { merge: true },
    );
    logger.info("usage flag corrected", { vehicleId, flagId: flag.id, left: rest.length });
  }
}

/** The reservation of [vehicleId] covering [at] (start inclusive, end exclusive). */
async function reservationAt(vehicleId: string, at: number): Promise<DocumentSnapshot | null> {
  const snaps = await db()
    .collection(COL.reservations)
    .where("vehicleId", "==", vehicleId)
    .where("startAt", "<=", at)
    .orderBy("startAt", "desc")
    .limit(10)
    .get();
  return snaps.docs.find((d) => (d.get("endAt") as number) > at) ?? null;
}

function koreaDate(at: number): string {
  return new Date(koreaDay(at).startAt + 9 * 60 * 60_000).toISOString().slice(0, 10);
}

// ---------------------------------------------------------------- ② unrecorded drive

/** The vehicle found a drive nobody recorded ("km=6.00 from=12362.0 to=12368.0 ... sinceAt=..."). */
export async function onUnrecordedDrive(event: DocumentSnapshot): Promise<void> {
  const detail = String(event.get("detail") ?? "");
  const km = numberIn(detail, "km");
  const foundAt = event.get("occurredAt");
  if (km === null || km <= 0 || typeof foundAt !== "number") return;
  const vehicleId = event.get("vehicleId") as string;
  const since = numberIn(detail, "sinceAt");
  const lastDriverId = await driverBefore(vehicleId, since ?? foundAt);
  const now = Date.now();
  const batch = db().batch();
  batch.set(db().collection(COL.trips).doc(`${vehicleId}_unrecorded_${event.id}`), {
    vehicleId,
    tripId: null,
    status: TRIP_STATUS_UNRECORDED,
    startedAt: since ?? foundAt,
    endedAt: foundAt,
    startOdometerKm: numberIn(detail, "from"),
    endOdometerKm: numberIn(detail, "to"),
    distanceKm: km,
    driverId: null,
    purpose: null,
    destinationName: null,
    sourceEventId: event.id,
    receivedAt: now,
  });
  batch.set(db().collection(COL.flags).doc(`unrecorded_${event.id}`), {
    vehicleId,
    kind: FLAG_KIND_UNRECORDED,
    status: FLAG_OPEN,
    reason: "UNRECORDED_DRIVE",
    note: `미기록 ${formatKm(km)} km`,
    km,
    since: since ?? null,
    foundAt,
    // Who had the car last (the trip before), and who found it (the driver boarding now)
    lastDriverId,
    foundByDriverId: event.get("driverId") ?? null,
    driverId: lastDriverId,
    tripDocId: `${vehicleId}_unrecorded_${event.id}`,
    createdAt: now,
    updatedAt: now,
    resolvedAt: null,
  });
  await batch.commit();
  logger.info("unrecorded drive", { vehicleId, eventId: event.id, km });
}

function numberIn(detail: string, key: string): number | null {
  const match = new RegExp(`(?:^|\\s)${key}=(-?[0-9.]+)(?:\\s|$)`).exec(detail);
  if (!match) return null;
  const value = Number(match[1]);
  return Number.isFinite(value) ? value : null;
}

function formatKm(km: number): string {
  return km >= 10 ? km.toFixed(0) : km.toFixed(1);
}
