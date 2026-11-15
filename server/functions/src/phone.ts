import type { DocumentSnapshot } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { distanceOf, driversById, koreaMonth, logbookView, vehicleOf } from "./admin";
import { COL, db } from "./db";
import { driverBefore, EVENT_CHARGE, FLAG_OPEN, FLAG_RESOLVED } from "./reconcile";
import { corrected, STATUS_ACTIVE, STATUS_ENDED, TRIP_PURPOSES, type TripCorrection } from "./trips";
import { FLAG_KIND_UNRECORDED, onTripDocChanged, TRIP_STATUS_UNRECORDED } from "./usage";

/*
 * The phone app (M1, Figma Mobile M1–M4): a driver finishes on the phone what the car could not settle,
 * and sees their own records and the car's state. The phone talks to the server only (never to the car).
 *
 * - Who: on first run the driver picks themself; the anonymous sign-in (uid) is linked to that driver.
 *   Every later call acts as that driver, never as an ID sent in the request.
 * - Consent: the same collection consent as the car's card, given on the phone. Until then no records.
 * - Records: the driver's own trips, plus unrecorded drives they are a candidate for (they had the car
 *   last, or found the gap). Other people's trips are not shown.
 * - Changes: driver (an unrecorded drive, or handing a trip to someone else), purpose, memo, and "맞습니다"
 *   on a provisional record. Taking a trip as yours confirms it; naming someone else leaves it provisional
 *   for them. Each change is kept in tripEdits (before and after) and applied over later vehicle uploads.
 */

export class PhoneError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

const MEMO_MAX = 200;

export interface Me {
  driverId: string;
  name: string;
  department: string | null;
  agreed: boolean;
}

// ---------------------------------------------------------------- who, consent

/** First run: the driver chose themself. A later choice replaces it (a shared test phone). */
export async function linkPhone(uid: string, body: unknown, now: number): Promise<Me> {
  const driverId = isObject(body) && typeof body.driverId === "string" ? body.driverId : null;
  if (!driverId) throw new PhoneError(400, "body must be {driverId}");
  const driver = await db().collection(COL.drivers).doc(driverId).get();
  if (!driver.exists || driver.get("active") === false) throw new PhoneError(404, "driver not found");
  await db().collection(COL.phoneUsers).doc(uid).set({ driverId, linkedAt: now });
  logger.info("phone linked", { driverId });
  return meOf(driver);
}

/** The driver this phone acts as; 404 until the first-run choice. */
export async function phoneDriver(uid: string): Promise<Me> {
  const link = await db().collection(COL.phoneUsers).doc(uid).get();
  const driverId = link.get("driverId");
  if (typeof driverId !== "string") throw new PhoneError(404, "phone not linked");
  const driver = await db().collection(COL.drivers).doc(driverId).get();
  if (!driver.exists || driver.get("active") === false) throw new PhoneError(404, "phone not linked");
  return meOf(driver);
}

/** Consent given on the phone (the car's card gives the same). Already agreed: kept as it was. */
export async function agreeOnPhone(uid: string, body: unknown, now: number): Promise<Me> {
  const me = await phoneDriver(uid);
  const version = isObject(body) ? body.noticeVersion : undefined;
  if (typeof version !== "number" || !Number.isInteger(version) || version < 1) throw new PhoneError(400, "body must be {noticeVersion}");
  if (!me.agreed) {
    await db()
      .collection(COL.drivers)
      .doc(me.driverId)
      .set({ consent: { status: "AGREED", agreedAt: now, noticeVersion: version, source: "PHONE", receivedAt: now } }, { merge: true });
    logger.info("consent on phone", { driverId: me.driverId });
  }
  return { ...me, agreed: true };
}

async function agreedDriver(uid: string): Promise<Me> {
  const me = await phoneDriver(uid);
  if (!me.agreed) throw new PhoneError(403, "consent required");
  return me;
}

function meOf(driver: DocumentSnapshot): Me {
  return {
    driverId: driver.id,
    name: driver.get("name") as string,
    department: (driver.get("department") as string | undefined) ?? null,
    agreed: driver.get("consent.status") === "AGREED",
  };
}

// ---------------------------------------------------------------- M1 records, M2 to check

export interface PhoneTrip {
  tripDocId: string;
  vehicleId: string;
  /** ACTIVE (driving now), ENDED, or UNRECORDED (nobody has taken it yet) */
  status: string;
  startedAt: number;
  endedAt: number | null;
  startKm: number | null;
  endKm: number | null;
  distanceKm: number | null;
  destination: string | null;
  expectedTollFare: number | null;
  purpose: string | null;
  purposeProvisional: boolean;
  driverId: string | null;
  driverName: string | null;
  driverProvisional: boolean;
  memo: string | null;
  endReason: string | null;
  closedByServer: boolean;
  /** Someone else named this driver on the phone (it waits for their "맞습니다") */
  assignedByName: string | null;
  /** UNRECORDED: who may have driven it (had the car last, found the gap) */
  candidates: Array<{ driverId: string; name: string }>;
  /** What M2 asks: UNRECORDED (whose drive), DRIVER or PURPOSE (provisional), or null */
  check: "UNRECORDED" | "DRIVER" | "PURPOSE" | null;
}

/** The driver's trips and the unrecorded drives they are a candidate for, newest first. */
export async function myTrips(uid: string): Promise<{ me: Me; items: PhoneTrip[] }> {
  const me = await agreedDriver(uid);
  const [drivers, mine, unrecordedFlags] = await Promise.all([
    driversById(),
    db().collection(COL.trips).where("driverId", "==", me.driverId).get(),
    db().collection(COL.flags).where("kind", "==", FLAG_KIND_UNRECORDED).where("status", "==", FLAG_OPEN).get(),
  ]);
  const nameOf = (id: unknown) => (typeof id === "string" ? drivers.get(id)?.name ?? id : null);

  const candidateFlags = unrecordedFlags.docs.filter((f) => f.get("lastDriverId") === me.driverId || f.get("foundByDriverId") === me.driverId);
  const unclaimed = (await Promise.all(candidateFlags.map((f) => db().collection(COL.trips).doc(f.get("tripDocId") as string).get()))).filter(
    (t) => t.exists && t.get("status") === TRIP_STATUS_UNRECORDED && t.get("driverId") == null,
  );
  const flagOfTrip = new Map(candidateFlags.map((f) => [f.get("tripDocId") as string, f]));

  const items = [...mine.docs, ...unclaimed]
    .filter((t) => [STATUS_ACTIVE, STATUS_ENDED, TRIP_STATUS_UNRECORDED].includes(t.get("status")))
    .map((t) => phoneTrip(t, me, nameOf, flagOfTrip.get(t.id)))
    .sort((a, b) => b.startedAt - a.startedAt)
    .slice(0, 100);
  return { me, items };
}

function phoneTrip(t: DocumentSnapshot, me: Me, nameOf: (id: unknown) => string | null, flag: DocumentSnapshot | undefined): PhoneTrip {
  const status = t.get("status") as string;
  const driverId = (t.get("driverId") as string | null | undefined) ?? null;
  const purpose = (t.get("purpose") as string | null | undefined) ?? null;
  const driverProvisional = driverId !== null && t.get("driverProvisional") !== false;
  const purposeProvisional = status !== TRIP_STATUS_UNRECORDED && t.get("purposeProvisional") !== false;
  const correction = t.get("correction") as TripCorrection | undefined;
  const candidates = flag
    ? [...new Set([flag.get("lastDriverId"), flag.get("foundByDriverId")].filter((id): id is string => typeof id === "string"))].map((id) => ({
        driverId: id,
        name: nameOf(id) ?? id,
      }))
    : [];
  let check: PhoneTrip["check"] = null;
  if (status === TRIP_STATUS_UNRECORDED && driverId === null) check = "UNRECORDED";
  else if (status === STATUS_ENDED && driverId === me.driverId) check = driverProvisional ? "DRIVER" : purposeProvisional ? "PURPOSE" : null;
  return {
    tripDocId: t.id,
    vehicleId: t.get("vehicleId") as string,
    status,
    startedAt: t.get("startedAt") as number,
    endedAt: num(t.get("endedAt")),
    startKm: num(t.get("startOdometerKm")),
    endKm: num(t.get("endOdometerKm")),
    distanceKm: distanceOf(t),
    // A personal trip keeps no destination (CLAUDE.md rule 6)
    destination: purpose === "PERSONAL" ? null : ((t.get("destinationName") as string | null | undefined) ?? null),
    expectedTollFare: num(t.get("expectedTollFare")),
    purpose,
    purposeProvisional,
    driverId,
    driverName: nameOf(driverId),
    driverProvisional,
    memo: (t.get("memo") as string | null | undefined) ?? null,
    endReason: (t.get("endReason") as string | null | undefined) ?? null,
    closedByServer: t.get("closedByServer") === true,
    assignedByName: correction && correction.byDriverId !== driverId ? nameOf(correction.byDriverId) : null,
    candidates,
    check,
  };
}

// ---------------------------------------------------------------- M2 / M3 changes

/**
 * Changes one trip: {driverId?, purpose?, memo?, confirm?}.
 * - Allowed on the driver's own ended trips, and on unrecorded drives they are a candidate for.
 * - driverId = me: the trip is mine, confirmed. Someone else: provisional for them (they confirm on their phone).
 * - confirm (= "맞습니다"): my provisional driver and purpose become confirmed.
 * - A personal trip loses its destination. Each change is written to tripEdits.
 * Then use outside the reservation is judged again, and an unrecorded drive taken by a driver resolves its flag.
 */
export async function correctTrip(uid: string, tripDocId: string, body: unknown, now: number): Promise<PhoneTrip> {
  const me = await agreedDriver(uid);
  const change = parseChange(body);
  const tripRef = db().collection(COL.trips).doc(tripDocId);

  const outcome = await db().runTransaction(async (tx) => {
    const trip = await tx.get(tripRef);
    if (!trip.exists) throw new PhoneError(404, "trip not found");
    const status = trip.get("status") as string;
    if (status !== STATUS_ENDED && status !== TRIP_STATUS_UNRECORDED) throw new PhoneError(409, "trip is not over");
    const current = (trip.get("driverId") as string | null | undefined) ?? null;
    const unrecordedFlagRef = typeof trip.get("sourceEventId") === "string" ? db().collection(COL.flags).doc(`unrecorded_${trip.get("sourceEventId")}`) : null;
    const flag = unrecordedFlagRef ? await tx.get(unrecordedFlagRef) : null;
    const candidate = current === null && flag?.exists === true && (flag.get("lastDriverId") === me.driverId || flag.get("foundByDriverId") === me.driverId);
    if (current !== me.driverId && !candidate) throw new PhoneError(403, "not your trip");

    const driverId = change.driverId ?? current;
    if (driverId === null) throw new PhoneError(400, "driverId is required for an unrecorded drive");
    if (change.driverId && change.driverId !== me.driverId) {
      const other = await tx.get(db().collection(COL.drivers).doc(change.driverId));
      if (!other.exists || other.get("active") === false) throw new PhoneError(404, "driver not found");
    }
    const mine = driverId === me.driverId;
    const driverChanged = driverId !== current;
    const before = {
      status,
      driverId: current,
      driverProvisional: trip.get("driverProvisional") !== false,
      purpose: (trip.get("purpose") as string | null | undefined) ?? null,
      purposeProvisional: trip.get("purposeProvisional") !== false,
      memo: (trip.get("memo") as string | null | undefined) ?? null,
    };
    const purpose = change.purpose ?? before.purpose ?? "BUSINESS";
    // Mine: taking it (an unrecorded drive) or "맞습니다" confirms the driver; choosing a purpose or
    // "맞습니다" confirms the purpose. Someone else: provisional until they confirm on their phone.
    const correction: TripCorrection = {
      driverId,
      driverProvisional: mine ? (driverChanged || change.confirm ? false : before.driverProvisional) : true,
      purposeProvisional: mine ? (change.purpose !== undefined || change.confirm ? false : before.purposeProvisional) : true,
      purpose,
      memo: change.memo !== undefined ? change.memo : before.memo,
      byDriverId: me.driverId,
      at: now,
    };
    const after = {
      status: status === TRIP_STATUS_UNRECORDED ? STATUS_ENDED : status,
      driverId: correction.driverId,
      driverProvisional: correction.driverProvisional,
      purpose: correction.purpose,
      purposeProvisional: correction.purposeProvisional,
      memo: correction.memo,
    };
    const changes = Object.fromEntries(
      (Object.keys(after) as Array<keyof typeof after>).filter((k) => before[k] !== after[k]).map((k) => [k, { from: before[k], to: after[k] }]),
    );
    if (Object.keys(changes).length === 0) return { changed: false, driverChanged: false, takenUnrecorded: false, flagRef: unrecordedFlagRef };

    tx.update(tripRef, {
      ...corrected((trip.get("destinationName") as string | null | undefined) ?? null, correction),
      correction,
      status: after.status,
      ...(status === TRIP_STATUS_UNRECORDED ? { recordedFrom: TRIP_STATUS_UNRECORDED } : {}),
    });
    tx.set(db().collection(COL.tripEdits).doc(), {
      tripDocId,
      vehicleId: trip.get("vehicleId"),
      byDriverId: me.driverId,
      uid,
      at: now,
      changes,
    });
    const fromUnrecorded = status === TRIP_STATUS_UNRECORDED || trip.get("recordedFrom") === TRIP_STATUS_UNRECORDED;
    return {
      changed: true,
      driverChanged,
      takenUnrecorded: fromUnrecorded && mine && !correction.driverProvisional,
      flagRef: unrecordedFlagRef,
    };
  });

  if (outcome.changed) {
    logger.info("trip corrected on phone", { tripDocId, driverChanged: outcome.driverChanged });
    if (outcome.takenUnrecorded && outcome.flagRef) {
      const flag = await outcome.flagRef.get();
      if (flag.exists && flag.get("status") === FLAG_OPEN) {
        await outcome.flagRef.set(
          { status: FLAG_RESOLVED, note: "운전자가 휴대폰에서 본인 운행으로 확인", resolvedDriverId: me.driverId, updatedAt: now, resolvedAt: now },
          { merge: true },
        );
      }
    }
    // ③ the driver against the reservation, again (and other drivers' flags for this trip withdrawn)
    if (outcome.driverChanged) await onTripDocChanged(tripDocId);
  }
  const drivers = await driversById();
  const trip = await tripRef.get();
  const flag = outcome.flagRef ? await outcome.flagRef.get() : undefined;
  return phoneTrip(trip, me, (id) => (typeof id === "string" ? drivers.get(id)?.name ?? id : null), flag?.exists ? flag : undefined);
}

interface Change {
  driverId?: string;
  purpose?: string;
  memo?: string | null;
  confirm: boolean;
}

function parseChange(body: unknown): Change {
  if (!isObject(body)) throw new PhoneError(400, "body must be an object");
  const change: Change = { confirm: body.confirm === true };
  if (body.driverId !== undefined) {
    if (typeof body.driverId !== "string" || body.driverId.length === 0) throw new PhoneError(400, "driverId must be a driver ID");
    change.driverId = body.driverId;
  }
  if (body.purpose !== undefined) {
    if (typeof body.purpose !== "string" || !TRIP_PURPOSES.includes(body.purpose)) throw new PhoneError(400, "purpose must be BUSINESS, COMMUTE or PERSONAL");
    change.purpose = body.purpose;
  }
  if (body.memo !== undefined) {
    if (body.memo !== null && typeof body.memo !== "string") throw new PhoneError(400, "memo must be text");
    const memo = typeof body.memo === "string" ? body.memo.trim() : "";
    if (memo.length > MEMO_MAX) throw new PhoneError(400, `memo is at most ${MEMO_MAX} characters`);
    change.memo = memo.length > 0 ? memo : null;
  }
  if (change.driverId === undefined && change.purpose === undefined && change.memo === undefined && !change.confirm) {
    throw new PhoneError(400, "nothing to change");
  }
  return change;
}

// ---------------------------------------------------------------- M4 vehicle

/**
 * The car's state for the phone: parked or driving, battery % (the newer of the last trip end and the last
 * charge found), last charge, odometer, this month's distance and business ratio (the logbook figures), the
 * last driver, and when the car last sent anything. The car's own queue is not visible to the server.
 * [vehicleId] empty: the car of the driver's latest trip, else the first registered car.
 */
export async function vehicleStatus(uid: string, vehicleId: string | null, now: number) {
  const me = await phoneDriver(uid);
  const id = vehicleId ?? (await latestVehicleOf(me.driverId)) ?? (await firstVehicle());
  if (!id) throw new PhoneError(404, "vehicle not found");
  const [vehicle, snap, charges, logbook, lastDriverId, drivers] = await Promise.all([
    vehicleOf(id),
    db().collection(COL.vehicles).doc(id).get(),
    db().collection(COL.events).where("vehicleId", "==", id).where("type", "==", EVENT_CHARGE).get(),
    logbookView(id, koreaMonth(now)),
    driverBefore(id, now),
    driversById(),
  ]);
  if (!vehicle) throw new PhoneError(404, "vehicle not found");

  const charge = charges.docs.sort((a, b) => (b.get("occurredAt") as number) - (a.get("occurredAt") as number))[0];
  const detail = charge ? String(charge.get("detail") ?? "") : "";
  const lastCharge = charge
    ? { at: charge.get("occurredAt") as number, fromPercent: numberIn(detail, "from"), toPercent: numberIn(detail, "to") }
    : null;
  const tripEndSoc = num(snap.get("lastSocPercent"));
  const tripEndAt = num(snap.get("lastTripEndedAt")) ?? 0;
  const chargeIsNewer = lastCharge !== null && lastCharge.toPercent !== null && lastCharge.at >= tripEndAt;

  return {
    vehicle,
    state: snap.get("activeTripId") != null ? "DRIVING" : "PARKED",
    socPercent: chargeIsNewer ? lastCharge!.toPercent : tripEndSoc,
    socAt: chargeIsNewer ? lastCharge!.at : num(snap.get("lastTripEndedAt")),
    lastCharge,
    odometerKm: num(snap.get("lastOdometerKm")),
    month: logbook?.month ?? koreaMonth(now),
    monthKm: logbook?.summary.totalKm ?? 0,
    monthTrips: logbook?.summary.trips ?? 0,
    businessRatioPercent: logbook && logbook.summary.totalKm > 0 ? logbook.summary.ratioPercent : null,
    lastDriverName: lastDriverId ? drivers.get(lastDriverId)?.name ?? lastDriverId : null,
    lastUploadAt: num(snap.get("lastUploadAt")),
  };
}

async function latestVehicleOf(driverId: string): Promise<string | null> {
  const trips = await db().collection(COL.trips).where("driverId", "==", driverId).get();
  const latest = trips.docs.sort((a, b) => (b.get("startedAt") as number) - (a.get("startedAt") as number))[0];
  return (latest?.get("vehicleId") as string | undefined) ?? null;
}

async function firstVehicle(): Promise<string | null> {
  const snaps = await db().collection(COL.vehicles).limit(1).get();
  return snaps.docs[0]?.id ?? null;
}

// ---------------------------------------------------------------- helpers

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function num(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function numberIn(detail: string, key: string): number | null {
  const match = new RegExp(`(?:^|\\s)${key}=(-?[0-9.]+)(?:\\s|$)`).exec(detail);
  if (!match) return null;
  const value = Number(match[1]);
  return Number.isFinite(value) ? value : null;
}
