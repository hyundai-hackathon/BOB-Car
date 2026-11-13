import type { DocumentSnapshot, Transaction } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { autoCloseAfterMs } from "./config";
import { COL, db } from "./db";

/*
 * Trips and the per-vehicle baseline.
 *
 * The vehicle app uploads a "trip" item with the whole trip row each time it changes (start, driver
 * confirmed, end). A newer item (higher queueId) replaces the stored trip; an older one is ignored.
 * The vehicle document keeps the baseline the next driver is compared against: last odometer, battery %
 * and trip end, and the running trip. Kept per vehicle so it does not split between profiles.
 */

export const KIND_TRIP = "trip";

export const STATUS_ACTIVE = "ACTIVE";
export const STATUS_ENDED = "ENDED";
/** Closed by the server: nothing came from the vehicle for too long (the trip-end card was never answered). */
export const END_SERVER_TIMEOUT = "SERVER_TIMEOUT";

const PURPOSES = ["BUSINESS", "COMMUTE", "PERSONAL"];
const DEFAULT_PURPOSE = "BUSINESS";

export interface TripSnapshot {
  tripId: number;
  status: string;
  startedAt: number;
  endedAt: number | null;
  startOdometerKm: number | null;
  endOdometerKm: number | null;
  endSocPercent: number | null;
  driverId: string | null;
  driverProvisional: boolean;
  purpose: string | null;
  purposeProvisional: boolean;
  destinationName: string | null;
  expectedTollFare: number | null;
  endReason: string | null;
}

/** The trip row from an upload item, or null when it is not one. Values out of range are dropped. */
export function parseTrip(payload: Record<string, unknown>): TripSnapshot | null {
  const { tripId, status, startedAt } = payload;
  if (typeof tripId !== "number" || !Number.isInteger(tripId) || tripId <= 0) return null;
  if (status !== STATUS_ACTIVE && status !== STATUS_ENDED) return null;
  if (typeof startedAt !== "number") return null;
  const purpose = typeof payload.purpose === "string" && PURPOSES.includes(payload.purpose) ? payload.purpose : null;
  return {
    tripId,
    status,
    startedAt,
    endedAt: num(payload.endedAt),
    startOdometerKm: odometer(payload.startOdometerKm),
    endOdometerKm: odometer(payload.endOdometerKm),
    endSocPercent: soc(payload.endSocPercent),
    driverId: typeof payload.driverId === "string" ? payload.driverId : null,
    driverProvisional: payload.driverProvisional !== false,
    purpose,
    purposeProvisional: payload.purposeProvisional !== false,
    // A personal trip keeps no destination (CLAUDE.md rule 6)
    destinationName: purpose !== "PERSONAL" && typeof payload.destinationName === "string" ? payload.destinationName : null,
    expectedTollFare: num(payload.expectedTollFare),
    endReason: typeof payload.endReason === "string" ? payload.endReason : null,
  };
}

/** What [writeTripSnapshot] needs to have read first (a transaction reads before it writes). */
export interface TripReads {
  trip: DocumentSnapshot;
  vehicle: DocumentSnapshot;
}

export async function readForTrip(tx: Transaction, vehicleId: string, tripId: number): Promise<TripReads> {
  const [trip, vehicle] = await Promise.all([tx.get(tripRef(vehicleId, tripId)), tx.get(vehicleRef(vehicleId))]);
  return { trip, vehicle };
}

/**
 * Stores [snapshot] unless a newer one is already there, and moves the vehicle baseline along.
 * A real trip end that arrives after the server closed the trip replaces the provisional close.
 * Returns false when the snapshot was older than the stored one (nothing changed).
 */
export function writeTripSnapshot(
  tx: Transaction,
  vehicleId: string,
  queueId: number,
  snapshot: TripSnapshot,
  reads: TripReads,
  now: number,
): boolean {
  const storedQueueId = reads.trip.exists ? (reads.trip.get("lastQueueId") as number) : 0;
  if (storedQueueId > queueId) return false;

  tx.set(tripRef(vehicleId, snapshot.tripId), {
    vehicleId,
    ...snapshot,
    closedByServer: false,
    lastQueueId: queueId,
    receivedAt: now,
  });

  const baseline: Record<string, unknown> = {};
  const activeTripId = reads.vehicle.get("activeTripId") ?? null;
  if (snapshot.status === STATUS_ACTIVE) {
    baseline.activeTripId = snapshot.tripId;
  } else {
    if (activeTripId === snapshot.tripId) baseline.activeTripId = null;
    const lastEndedAt = (reads.vehicle.get("lastTripEndedAt") as number | undefined) ?? Number.NEGATIVE_INFINITY;
    // An older trip end arriving late does not move the baseline back
    if (snapshot.endedAt !== null && snapshot.endedAt >= lastEndedAt) {
      baseline.lastTripEndedAt = snapshot.endedAt;
      if (snapshot.endOdometerKm !== null) baseline.lastOdometerKm = snapshot.endOdometerKm;
      if (snapshot.endSocPercent !== null) baseline.lastSocPercent = snapshot.endSocPercent;
    }
  }
  if (Object.keys(baseline).length > 0) tx.set(vehicleRef(vehicleId), baseline, { merge: true });
  return true;
}

/**
 * Closes running trips whose vehicle has sent nothing for [autoCloseAfterMs]: status ENDED, reason
 * SERVER_TIMEOUT, ended at the last time anything arrived (server clock), purpose kept or the default,
 * marked provisional. Returns the closed trip document IDs.
 */
export async function closeStaleTrips(now: number): Promise<string[]> {
  const cutoff = now - autoCloseAfterMs();
  const running = await db().collection(COL.trips).where("status", "==", STATUS_ACTIVE).get();
  const closed: string[] = [];
  for (const doc of running.docs) {
    const vehicleId = doc.get("vehicleId") as string;
    const done = await db().runTransaction(async (tx) => {
      const [trip, vehicle] = await Promise.all([tx.get(doc.ref), tx.get(vehicleRef(vehicleId))]);
      if (trip.get("status") !== STATUS_ACTIVE) return false;
      const lastActivity = Math.max((vehicle.get("lastUploadAt") as number | undefined) ?? 0, (trip.get("receivedAt") as number | undefined) ?? 0);
      if (lastActivity > cutoff) return false;
      tx.update(doc.ref, {
        status: STATUS_ENDED,
        endedAt: lastActivity,
        endReason: END_SERVER_TIMEOUT,
        purpose: trip.get("purpose") ?? DEFAULT_PURPOSE,
        purposeProvisional: true,
        closedByServer: true,
        closedAt: now,
      });
      if (vehicle.get("activeTripId") === trip.get("tripId")) tx.set(vehicle.ref, { activeTripId: null }, { merge: true });
      return true;
    });
    if (done) closed.push(doc.id);
  }
  if (closed.length > 0) logger.info("auto-closed trips", { trips: closed });
  return closed;
}

function tripRef(vehicleId: string, tripId: number) {
  return db().collection(COL.trips).doc(`${vehicleId}_${tripId}`);
}

function vehicleRef(vehicleId: string) {
  return db().collection(COL.vehicles).doc(vehicleId);
}

function num(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function odometer(value: unknown): number | null {
  const km = num(value);
  return km !== null && km >= 0 ? km : null;
}

function soc(value: unknown): number | null {
  const percent = num(value);
  return percent !== null && percent >= 0 && percent <= 100 ? percent : null;
}
