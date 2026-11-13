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
