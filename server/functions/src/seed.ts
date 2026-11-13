import { COL, db } from "./db";

/**
 * Seed data for the emulator and tests (reservations, vehicles and staff are seed data; no input screens).
 * Same IDs and made-up names as the vehicle app's local mock (LocalMockData), so both sides agree
 * until the vehicle app reads them from the server.
 */
export const SEED_VEHICLE_ID = "veh-001";

export const SEED_DRIVERS: ReadonlyArray<{ id: string; name: string }> = [
  { id: "drv-001", name: "김민준" },
  { id: "drv-002", name: "이서연" },
  { id: "drv-003", name: "박지호" },
  { id: "drv-004", name: "최유나" },
  { id: "drv-005", name: "정현우" },
  { id: "drv-006", name: "한소희" },
];

/** Today's reservation of the seed vehicle (whole day, Korea time), like the vehicle app's mock. */
export const SEED_RESERVED_DRIVER_ID = "drv-001";

const KST_OFFSET_MS = 9 * 60 * 60_000;
const DAY_MS = 24 * 60 * 60_000;

/** Midnight to midnight (Korea time) of the day [at] falls in. */
export function koreaDay(at: number): { startAt: number; endAt: number } {
  const startAt = Math.floor((at + KST_OFFSET_MS) / DAY_MS) * DAY_MS - KST_OFFSET_MS;
  return { startAt, endAt: startAt + DAY_MS };
}

/**
 * Writes the seed vehicle, drivers and today's reservation. Safe to run again: consent answers and
 * vehicle baselines already stored are kept.
 */
export async function seed(now: number): Promise<void> {
  const firestore = db();
  const batch = firestore.batch();
  batch.set(
    firestore.collection(COL.vehicles).doc(SEED_VEHICLE_ID),
    { vehicleId: SEED_VEHICLE_ID, name: "업무차 1호" },
    { merge: true },
  );
  for (const driver of SEED_DRIVERS) {
    batch.set(
      firestore.collection(COL.drivers).doc(driver.id),
      { driverId: driver.id, name: driver.name, active: true },
      { merge: true },
    );
  }
  const day = koreaDay(now);
  const dateKey = new Date(day.startAt + KST_OFFSET_MS).toISOString().slice(0, 10);
  batch.set(firestore.collection(COL.reservations).doc(`seed-${SEED_VEHICLE_ID}-${dateKey}`), {
    vehicleId: SEED_VEHICLE_ID,
    driverId: SEED_RESERVED_DRIVER_ID,
    startAt: day.startAt,
    endAt: day.endAt,
  });
  await batch.commit();
}

let seeded = false;

/** Emulator only: seeds once per process, so a fresh emulator works without a separate step. */
export async function ensureSeeded(now: number): Promise<void> {
  if (seeded) return;
  await seed(now);
  seeded = true;
}
