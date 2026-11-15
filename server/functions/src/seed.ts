import { COL, db } from "./db";

/**
 * Seed data for the emulator and tests (reservations, vehicles and staff are seed data; no input screens).
 * Same IDs and made-up names as the vehicle app's local mock (LocalMockData), so both sides agree
 * until the vehicle app reads them from the server.
 */
export const SEED_VEHICLE_ID = "veh-001";

/** The emulator car's battery (INFO_EV_BATTERY_CAPACITY 77.4 kWh), used when a charge comes without kWh. */
export const SEED_BATTERY_CAPACITY_WH = 77_400;

/** Made-up staff (blind review: no real names or companies). Departments are made up too (logbook form). */
export const SEED_DRIVERS: ReadonlyArray<{ id: string; name: string; department: string }> = [
  { id: "drv-001", name: "김민준", department: "영업팀" },
  { id: "drv-002", name: "이서연", department: "영업팀" },
  { id: "drv-003", name: "박지호", department: "개발팀" },
  { id: "drv-004", name: "최유나", department: "총무팀" },
  { id: "drv-005", name: "정현우", department: "개발팀" },
  { id: "drv-006", name: "한소희", department: "총무팀" },
];

/** The seed vehicle as the logbook form shows it (made-up registration number). */
export const SEED_VEHICLE = { name: "업무차 1호", model: "아이오닉 5", plate: "12가 3456" } as const;

/** Company line of the logbook form (made up: blind review). */
export const SEED_COMPANY = { name: "법카 시연 법인", registrationNumber: "000-00-00000" } as const;

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
    { vehicleId: SEED_VEHICLE_ID, ...SEED_VEHICLE, energy: "EV", batteryCapacityWh: SEED_BATTERY_CAPACITY_WH },
    { merge: true },
  );
  for (const driver of SEED_DRIVERS) {
    batch.set(
      firestore.collection(COL.drivers).doc(driver.id),
      { driverId: driver.id, name: driver.name, department: driver.department, active: true },
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

/** Korea-time day already seeded by this process (start of that day, epoch ms). */
let seededDay: number | null = null;

/**
 * Emulator only: seeds on the first call of each Korea-time day, so a fresh emulator works without a separate
 * step and an emulator left running past midnight still has today's reservation.
 */
export async function ensureSeeded(now: number): Promise<void> {
  const day = koreaDay(now).startAt;
  if (seededDay === day) return;
  await seed(now);
  seededDay = day;
}
