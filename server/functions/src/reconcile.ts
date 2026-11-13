import type { DocumentSnapshot } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { RECONCILE } from "./config";
import { COL, db } from "./db";

/*
 * Expense check ① (design-review F10): corporate card payments for charging / refuelling against the charges
 * the vehicle itself found (F4 "ChargeDetected": battery up between two snapshots).
 *
 * - A charge covers [snapshot before it - 10 min, moment it was found + 10 min] (A and B in the design).
 *   Each payment belongs to one charge only (the one whose span contains it, else the nearest), so charges
 *   close together (overlapping margins) do not share payments.
 * - Payments of that vehicle in the window are added up; expected amount = energy x unit price;
 *   ratio = expected / paid. At or above [RECONCILE.minRatio] the payments are explained.
 * - A payment with no charge around it, or one the charge does not explain, gets an OPEN flag. A flag is
 *   RESOLVED as soon as a charge explains it ("충전 확인 +45%p"), or when the payment is cancelled.
 * - Payments under [RECONCILE.minAmount] and other categories (toll, other) are not checked.
 *
 * Flags: one per payment, ID "payment_<transactionId>". Logs carry IDs and amounts only.
 */

export const EVENT_CHARGE = "ChargeDetected";

/** Card payment categories this check looks at, with the vehicle event that explains them. */
const CHECKED = {
  CHARGE: { event: EVENT_CHARGE, kind: "CHARGE_PAYMENT", missing: "충전 기록 없음" },
  FUEL: { event: "FuelDetected", kind: "FUEL_PAYMENT", missing: "주유 기록 없음" },
} as const;
type CheckedCategory = keyof typeof CHECKED;

export const FLAG_OPEN = "OPEN";
export const FLAG_RESOLVED = "RESOLVED";

/** A charge the vehicle found, with its payment window. */
export interface Charge {
  eventId: string;
  vehicleId: string;
  gainedPercent: number;
  kwh: number | null;
  /** Snapshot before the charge (A) and when it was found (B), epoch ms. */
  since: number;
  foundAt: number;
}

/** The charge described by a ChargeDetected event ("gainedPercent=45 ... kwh=34.83 ... sinceAt=..."). */
export async function chargeOf(event: DocumentSnapshot): Promise<Charge | null> {
  if (event.get("type") !== EVENT_CHARGE) return null;
  const detail = String(event.get("detail") ?? "");
  const gained = numberIn(detail, "gainedPercent");
  const foundAt = event.get("occurredAt") as number | undefined;
  if (gained === null || gained <= 0 || typeof foundAt !== "number") return null;
  const vehicleId = event.get("vehicleId") as string;
  const since = numberIn(detail, "sinceAt") ?? (await lastTripEndBefore(vehicleId, foundAt)) ?? foundAt - RECONCILE.fallbackLookbackMs;
  return { eventId: event.id, vehicleId, gainedPercent: gained, kwh: numberIn(detail, "kwh"), since, foundAt };
}

/**
 * A charge arrived: the payments in its window are judged again, and so are the charges whose windows
 * overlap it (a payment may now belong to the new charge instead).
 */
export async function onChargeFound(charge: Charge): Promise<void> {
  const context = await contextOf(charge.vehicleId, EVENT_CHARGE);
  const overlapping = context.charges.filter((c) => windowStart(c) <= windowEnd(charge) && windowStart(charge) <= windowEnd(c));
  for (const c of overlapping) await judgeCharge(c, context, "CHARGE");
}

/** A payment was added (or changed): flag it unless a charge around it explains it. */
export async function checkPayment(transactionId: string): Promise<FlagView | null> {
  const tx = await db().collection(COL.transactions).doc(transactionId).get();
  if (!tx.exists) return null;
  const category = tx.get("category") as string;
  if (!(category in CHECKED)) return null;
  const checked = CHECKED[category as CheckedCategory];
  if (tx.get("canceled") === true) return resolveCanceled(tx);
  if ((tx.get("amount") as number) < RECONCILE.minAmount) return null;

  const context = await contextOf(tx.get("vehicleId") as string, checked.event);
  const charge = ownerOf(tx.get("paidAt") as number, context);
  if (charge) {
    await judgeCharge(charge, context, category as CheckedCategory);
    return flagOf(transactionId);
  }
  return writeFlag(tx, {
    status: FLAG_OPEN,
    reason: "NO_CHARGE",
    note: checked.missing,
    chargeEventId: null,
    windowPaidTotal: tx.get("amount") as number,
    expectedAmount: null,
    ratio: null,
    gainedPercent: null,
    kwh: null,
  });
}

/** What a flag says, as stored. */
export type FlagView = Record<string, unknown> & { flagId: string; status: string };

export async function flagOf(transactionId: string): Promise<FlagView | null> {
  const snap = await db().collection(COL.flags).doc(flagId(transactionId)).get();
  return snap.exists ? ({ flagId: snap.id, ...snap.data() } as FlagView) : null;
}

// ---------------------------------------------------------------- judging

/** Adds up [payments] in the charge window and flags or resolves each of them. */
async function judge(charge: Charge, payments: DocumentSnapshot[]): Promise<void> {
  const kwh = charge.kwh ?? (await capacityKwh(charge.vehicleId)) * (charge.gainedPercent / 100);
  const expected = Math.round(kwh * RECONCILE.chargePricePerKwh);
  const paid = payments.reduce((sum, p) => sum + (p.get("amount") as number), 0);
  const ratio = Math.round((expected / paid) * 100) / 100;
  const explained = ratio >= RECONCILE.minRatio;
  const kwhText = kwh.toFixed(1);
  for (const payment of payments) {
    const existing = await db().collection(COL.flags).doc(flagId(payment.id)).get();
    // Explained and never flagged: nothing to show
    if (explained && !existing.exists) continue;
    await writeFlag(payment, {
      status: explained ? FLAG_RESOLVED : FLAG_OPEN,
      reason: explained ? null : "AMOUNT_MISMATCH",
      note: explained
        ? `충전 확인 +${charge.gainedPercent}%p (${kwhText} kWh)`
        : `결제 금액이 충전량보다 큼 (예상 ${won(expected)} / 결제 ${won(paid)})`,
      chargeEventId: charge.eventId,
      windowPaidTotal: paid,
      expectedAmount: expected,
      ratio,
      gainedPercent: charge.gainedPercent,
      kwh: Math.round(kwh * 100) / 100,
    });
  }
  logger.info("charge judged", { vehicleId: charge.vehicleId, chargeEventId: charge.eventId, payments: payments.length, paid, expected, ratio });
}

async function resolveCanceled(tx: DocumentSnapshot): Promise<FlagView | null> {
  const existing = await db().collection(COL.flags).doc(flagId(tx.id)).get();
  if (!existing.exists) return null;
  return writeFlag(tx, { status: FLAG_RESOLVED, reason: null, note: "결제 취소" });
}

async function writeFlag(tx: DocumentSnapshot, fields: Record<string, unknown> & { status: string }): Promise<FlagView> {
  const ref = db().collection(COL.flags).doc(flagId(tx.id));
  const now = Date.now();
  const existing = await ref.get();
  const vehicleId = tx.get("vehicleId") as string;
  const paidAt = tx.get("paidAt") as number;
  const category = tx.get("category") as CheckedCategory;
  const data: Record<string, unknown> = {
    vehicleId,
    kind: CHECKED[category]?.kind ?? "PAYMENT",
    transactionId: tx.id,
    paidAt,
    paidAmount: tx.get("amount"),
    // Who had the car: the driver of the last trip that ended before the payment
    driverId: existing.exists ? existing.get("driverId") : await driverBefore(vehicleId, paidAt),
    createdAt: existing.exists ? existing.get("createdAt") : now,
    updatedAt: now,
    resolvedAt: fields.status === FLAG_RESOLVED ? (existing.get("resolvedAt") ?? now) : null,
    ...fields,
  };
  await ref.set(data, { merge: true });
  return { flagId: ref.id, ...data } as FlagView;
}

// ---------------------------------------------------------------- lookups

/** Every charge of [vehicleId] recorded as [eventType] (a handful per vehicle). */
async function chargesOf(vehicleId: string, eventType: string): Promise<Charge[]> {
  if (eventType !== EVENT_CHARGE) return [];
  const events = await db()
    .collection(COL.events)
    .where("vehicleId", "==", vehicleId)
    .where("type", "==", EVENT_CHARGE)
    .get();
  return (await Promise.all(events.docs.map(chargeOf))).filter((c): c is Charge => c !== null);
}

const windowStart = (c: Charge) => c.since - RECONCILE.windowMarginMs;
const windowEnd = (c: Charge) => c.foundAt + RECONCILE.windowMarginMs;

/**
 * What deciding a payment's charge needs: the vehicle's charges, and when it set off and arrived. Departures
 * come from the TripStarted events (a trip continued within 10 minutes keeps its first start time, so the
 * trip rows alone miss the later departures); arrivals from TripEnded events and trip rows.
 */
interface Context {
  charges: Charge[];
  departures: number[];
  arrivals: number[];
}

async function contextOf(vehicleId: string, eventType: string): Promise<Context> {
  const events = (type: string) => db().collection(COL.events).where("vehicleId", "==", vehicleId).where("type", "==", type).get();
  const [charges, trips, started, ended] = await Promise.all([
    chargesOf(vehicleId, eventType),
    db().collection(COL.trips).where("vehicleId", "==", vehicleId).orderBy("startedAt", "desc").limit(50).get(),
    events("TripStarted"),
    events("TripEnded"),
  ]);
  const times = (docs: DocumentSnapshot[], field: string) =>
    docs.map((d) => d.get(field)).filter((t): t is number => typeof t === "number");
  return {
    charges,
    departures: times(started.docs, "occurredAt"),
    arrivals: [...times(ended.docs, "occurredAt"), ...times(trips.docs, "endedAt")],
  };
}

/**
 * The one charge a payment at [paidAt] belongs to. Windows of charges close together overlap by their
 * margins, so a payment goes to the charge whose own span (snapshot to discovery) contains it, else the
 * nearest one; a tie goes to the later charge. A charge is left out when the car moved between it and the
 * payment: set off or arrived again after the charge was found and before the payment, or set off after
 * the payment and before the charge's parking began (the payment belongs to an earlier stop).
 * null when no charge fits.
 */
function ownerOf(paidAt: number, context: Context): Charge | null {
  const distance = (c: Charge) => (paidAt < c.since ? c.since - paidAt : paidAt > c.foundAt ? paidAt - c.foundAt : 0);
  const between = (times: number[], from: number, to: number) => times.some((t) => t > from && t <= to);
  const movedSince = (c: Charge) =>
    paidAt > c.foundAt && (between(context.departures, c.foundAt, paidAt) || between(context.arrivals, c.foundAt, paidAt));
  const movedBefore = (c: Charge) => paidAt < c.since && between(context.departures, paidAt - 1, c.since);
  return (
    context.charges
      .filter((c) => paidAt >= windowStart(c) && paidAt <= windowEnd(c) && !movedSince(c) && !movedBefore(c))
      .sort((a, b) => distance(a) - distance(b) || b.foundAt - a.foundAt)[0] ?? null
  );
}

/** Judges the payments that belong to [charge] (none: nothing to do). */
async function judgeCharge(charge: Charge, context: Context, category: CheckedCategory): Promise<void> {
  const inWindow = await paymentsIn(charge.vehicleId, category, windowStart(charge), windowEnd(charge));
  const own = inWindow.filter((p) => ownerOf(p.get("paidAt") as number, context)?.eventId === charge.eventId);
  if (own.length > 0) await judge(charge, own);
}

/** Checked, not cancelled payments of [vehicleId] in [from, to], oldest first. */
async function paymentsIn(vehicleId: string, category: CheckedCategory, from: number, to: number): Promise<DocumentSnapshot[]> {
  const snaps = await db()
    .collection(COL.transactions)
    .where("vehicleId", "==", vehicleId)
    .where("paidAt", ">=", from)
    .where("paidAt", "<=", to)
    .orderBy("paidAt")
    .get();
  return snaps.docs.filter(
    (d) => d.get("category") === category && d.get("canceled") !== true && (d.get("amount") as number) >= RECONCILE.minAmount,
  );
}

async function lastTripEndBefore(vehicleId: string, at: number): Promise<number | null> {
  const trip = await lastTripBefore(vehicleId, at);
  return (trip?.get("endedAt") as number | undefined) ?? null;
}

async function driverBefore(vehicleId: string, at: number): Promise<string | null> {
  const trip = await lastTripBefore(vehicleId, at);
  return (trip?.get("driverId") as string | undefined) ?? null;
}

/** The last trip of the vehicle that ended at or before [at]. */
async function lastTripBefore(vehicleId: string, at: number): Promise<DocumentSnapshot | null> {
  const snaps = await db().collection(COL.trips).where("vehicleId", "==", vehicleId).orderBy("startedAt", "desc").limit(50).get();
  const ended = snaps.docs
    .filter((d) => d.get("status") === "ENDED" && typeof d.get("endedAt") === "number" && (d.get("endedAt") as number) <= at)
    .sort((a, b) => (b.get("endedAt") as number) - (a.get("endedAt") as number));
  return ended[0] ?? null;
}

async function capacityKwh(vehicleId: string): Promise<number> {
  const wh = (await db().collection(COL.vehicles).doc(vehicleId).get()).get("batteryCapacityWh");
  return typeof wh === "number" && wh > 0 ? wh / 1_000 : 0;
}

function flagId(transactionId: string): string {
  return `payment_${transactionId}`;
}

function numberIn(detail: string, key: string): number | null {
  const match = new RegExp(`(?:^|\\s)${key}=(-?[0-9.]+)(?:\\s|$)`).exec(detail);
  if (!match) return null;
  const value = Number(match[1]);
  return Number.isFinite(value) ? value : null;
}

function won(amount: number): string {
  return `${amount.toLocaleString("ko-KR")}원`;
}

// ---------------------------------------------------------------- cancellation and the daily check

/**
 * The card payment was cancelled (mock of the card company's cancellation record; whole amount only).
 * Its flag is resolved ("결제 취소"), and the other payments around the same charge are judged again
 * without it. null when there is no such payment.
 */
export async function cancelPayment(transactionId: string, now: number): Promise<FlagView | null | undefined> {
  const ref = db().collection(COL.transactions).doc(transactionId);
  const tx = await ref.get();
  if (!tx.exists) return undefined;
  if (tx.get("canceled") !== true) await ref.set({ canceled: true, canceledAt: now }, { merge: true });
  const flag = await checkPayment(transactionId);
  const category = tx.get("category") as string;
  if (category in CHECKED) {
    const context = await contextOf(tx.get("vehicleId") as string, CHECKED[category as CheckedCategory].event);
    const charge = ownerOf(tx.get("paidAt") as number, context);
    if (charge) await judgeCharge(charge, context, category as CheckedCategory);
  }
  logger.info("payment cancelled", { transactionId });
  return flag;
}

/** Once a day: every open flag is checked again (events that came late, payments cancelled since). */
export async function recheckOpenFlags(): Promise<{ checked: number; resolved: number }> {
  const open = await db().collection(COL.flags).where("status", "==", FLAG_OPEN).get();
  let resolved = 0;
  for (const doc of open.docs) {
    const transactionId = doc.get("transactionId") as string | undefined;
    if (!transactionId) continue;
    const after = await checkPayment(transactionId);
    if (after?.status === FLAG_RESOLVED) resolved++;
  }
  logger.info("open flags checked", { checked: open.size, resolved });
  return { checked: open.size, resolved };
}
