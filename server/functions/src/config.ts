/**
 * Server settings, kept in one place. Values that a demo may need to change read an environment
 * variable each time (so tests and the emulator can set them without a rebuild).
 */

/** Seoul (CLAUDE.md: Firebase, Seoul region). */
export const REGION = "asia-northeast3";

/** Project ID used with the local emulators: no real Firebase project, account or key is needed. */
export const DEMO_PROJECT_ID = "demo-bobcar";

/**
 * A running trip whose vehicle has uploaded nothing for this long is closed provisionally by the server
 * (the car was switched off before the trip-end card was answered). Long enough not to close a normal
 * long drive: while driving the vehicle sends few events. Override with BOBCAR_AUTO_CLOSE_MINUTES (demo).
 */
export function autoCloseAfterMs(): number {
  const minutes = Number(process.env.BOBCAR_AUTO_CLOSE_MINUTES);
  return (Number.isFinite(minutes) && minutes > 0 ? minutes : DEFAULT_AUTO_CLOSE_MINUTES) * 60_000;
}

const DEFAULT_AUTO_CLOSE_MINUTES = 12 * 60;

/** How often the server looks for trips to close. */
export const AUTO_CLOSE_SCHEDULE = "every 10 minutes";

/** Upload batches larger than this are refused (the vehicle app sends 50 at a time). */
export const MAX_ITEMS_PER_UPLOAD = 200;

/** True inside the Firebase emulators (dev-only routes and seed data are enabled there only). */
export function isEmulator(): boolean {
  return process.env.FUNCTIONS_EMULATOR === "true";
}

/**
 * Card payment vs. charge check (design-review F10, demo scene 5). A payment is matched with a charge found by
 * the vehicle when it falls between the snapshot before the charge minus [windowMarginMs] and the moment the
 * charge was found plus [windowMarginMs]. Expected amount = energy x unit price; ratio = expected / paid.
 */
export const RECONCILE = {
  /** KRW per kWh used to price a charge (demo: +45%p of 77.4 kWh = 34.8 kWh = about 12,191 KRW). */
  chargePricePerKwh: 350,
  /** KRW per litre used to price a refuel (real ICE cars; the emulator is an EV). */
  fuelPricePerLiter: 1_700,
  /** At or above this ratio the payment is explained by the charge. */
  minRatio: 0.7,
  /** Payments below this amount are not checked. */
  minAmount: 10_000,
  /** Margin before the snapshot and after the charge was found. */
  windowMarginMs: 10 * 60_000,
  /** When the event has no snapshot time and no trip end is known: look back this far. */
  fallbackLookbackMs: 24 * 60 * 60_000,
  /** Open flags are checked again once a day (late events, cancellations). */
  sweepSchedule: "every day 04:00",
} as const;
