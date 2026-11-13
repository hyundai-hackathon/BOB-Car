import * as logger from "firebase-functions/logger";
import { MAX_ITEMS_PER_UPLOAD } from "./config";
import { COL, db } from "./db";

/*
 * POST /v1/uploads: a batch from the vehicle app's upload queue.
 *
 * Body: {"vehicleId":"veh-001","items":[{"queueId":1,"kind":"vehicle_event","createdAt":..,
 *        "driverId":"drv-001","payload":{"eventId":..,"type":"Boarded","occurredAt":..,"detail":..}}]}
 * (vehicleId and driverId are added to the vehicle app's current body when it is connected to the server.)
 *
 * - Consent items are applied first, so a driver's records sent in the same batch as their consent go through.
 * - Each item is applied once: a resent item (response lost) is reported as a duplicate.
 * - Items of drivers who have not agreed to collection are not stored and are reported as skipped; the rest
 *   of the batch still goes through (the vehicle app already holds those back).
 * - The answer is 200 with what happened to each item. 400 only for a body that is not a batch at all,
 *   403 for a vehicle that is not registered.
 */

export const KIND_VEHICLE_EVENT = "vehicle_event";
export const KIND_CONSENT = "consent";

export type SkipReason = "invalid" | "no-driver" | "unknown-driver" | "no-consent" | "unsupported-kind";

export interface UploadResult {
  stored: number[];
  duplicates: number[];
  skipped: Array<{ queueId: number | null; reason: SkipReason }>;
}

export class UploadError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

export interface UploadItem {
  queueId: number;
  kind: string;
  createdAt: number;
  driverId: string | null;
  payload: Record<string, unknown>;
}

/** Handles one upload batch received at [now] (server time). */
export async function processUpload(body: unknown, now: number): Promise<UploadResult> {
  if (!isObject(body) || !Array.isArray(body.items)) throw new UploadError(400, "body must be {vehicleId, items[]}");
  const vehicleId = body.vehicleId;
  if (typeof vehicleId !== "string" || vehicleId.length === 0) throw new UploadError(400, "vehicleId is required");
  if (body.items.length > MAX_ITEMS_PER_UPLOAD) throw new UploadError(400, `at most ${MAX_ITEMS_PER_UPLOAD} items`);

  const firestore = db();
  const vehicleRef = firestore.collection(COL.vehicles).doc(vehicleId);
  if (!(await vehicleRef.get()).exists) throw new UploadError(403, "vehicle is not registered");

  const result: UploadResult = { stored: [], duplicates: [], skipped: [] };
  const items: UploadItem[] = [];
  for (const raw of body.items) {
    const item = parseItem(raw);
    if (item) items.push(item);
    else result.skipped.push({ queueId: isObject(raw) && typeof raw.queueId === "number" ? raw.queueId : null, reason: "invalid" });
  }
  // Consent first, then in queue order
  items.sort((a, b) => Number(b.kind === KIND_CONSENT) - Number(a.kind === KIND_CONSENT) || a.queueId - b.queueId);

  const drivers = await loadDrivers(items);
  for (const item of items) {
    const driverId = item.driverId;
    if (!driverId) {
      result.skipped.push({ queueId: item.queueId, reason: "no-driver" });
      continue;
    }
    const driver = drivers.get(driverId);
    if (!driver) {
      result.skipped.push({ queueId: item.queueId, reason: "unknown-driver" });
      continue;
    }
    if (item.kind !== KIND_CONSENT && item.kind !== KIND_VEHICLE_EVENT) {
      result.skipped.push({ queueId: item.queueId, reason: "unsupported-kind" });
      continue;
    }
    if (item.kind !== KIND_CONSENT && !driver.agreed) {
      result.skipped.push({ queueId: item.queueId, reason: "no-consent" });
      continue;
    }
    const applied = await applyOnce(vehicleId, item, now);
    (applied ? result.stored : result.duplicates).push(item.queueId);
    if (item.kind === KIND_CONSENT) driver.agreed = true;
  }

  if (result.stored.length + result.duplicates.length > 0) {
    await vehicleRef.set({ lastUploadAt: now }, { merge: true });
  }
  // App-made IDs and counts only (no names, destinations or details)
  logger.info("upload", {
    vehicleId,
    stored: result.stored.length,
    duplicates: result.duplicates.length,
    skipped: result.skipped.map((s) => s.reason),
  });
  return result;
}

/** Writes the item and its receipt in one transaction; false when the receipt already existed. */
async function applyOnce(vehicleId: string, item: UploadItem, now: number): Promise<boolean> {
  const firestore = db();
  const receiptRef = firestore.collection(COL.receipts).doc(`${vehicleId}_${item.queueId}`);
  return firestore.runTransaction(async (tx) => {
    if ((await tx.get(receiptRef)).exists) return false;
    tx.set(firestore.collection(COL.events).doc(`${vehicleId}_${item.queueId}`), {
      vehicleId,
      queueId: item.queueId,
      kind: item.kind,
      driverId: item.driverId,
      type: str(item.payload.type),
      occurredAt: num(item.payload.occurredAt) ?? item.createdAt,
      detail: str(item.payload.detail),
      vehicleEventId: num(item.payload.eventId),
      createdAt: item.createdAt,
      receivedAt: now,
    });
    if (item.kind === KIND_CONSENT && item.driverId) {
      const version = /version=(\d+)/.exec(str(item.payload.detail) ?? "")?.[1];
      tx.set(
        firestore.collection(COL.drivers).doc(item.driverId),
        {
          consent: {
            status: "AGREED",
            agreedAt: num(item.payload.occurredAt) ?? item.createdAt,
            noticeVersion: version ? Number(version) : null,
            vehicleId,
            receivedAt: now,
          },
        },
        { merge: true },
      );
    }
    tx.set(receiptRef, { vehicleId, queueId: item.queueId, kind: item.kind, receivedAt: now });
    return true;
  });
}

/** Active drivers of the batch and whether they agreed to collection. */
async function loadDrivers(items: UploadItem[]): Promise<Map<string, { agreed: boolean }>> {
  const ids = [...new Set(items.map((i) => i.driverId).filter((id): id is string => !!id))];
  const map = new Map<string, { agreed: boolean }>();
  if (ids.length === 0) return map;
  const refs = ids.map((id) => db().collection(COL.drivers).doc(id));
  for (const snap of await db().getAll(...refs)) {
    if (!snap.exists || snap.get("active") === false) continue;
    map.set(snap.id, { agreed: snap.get("consent.status") === "AGREED" });
  }
  return map;
}

function parseItem(raw: unknown): UploadItem | null {
  if (!isObject(raw)) return null;
  const { queueId, kind, createdAt, driverId, payload } = raw;
  if (typeof queueId !== "number" || !Number.isInteger(queueId) || queueId <= 0) return null;
  if (typeof kind !== "string" || typeof createdAt !== "number" || !isObject(payload)) return null;
  if (driverId !== undefined && driverId !== null && typeof driverId !== "string") return null;
  return { queueId, kind, createdAt, driverId: driverId ?? null, payload };
}

export function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function str(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

export function num(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}
