import { setGlobalOptions } from "firebase-functions/v2";
import { onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { createApp } from "./app";
import { AUTO_CLOSE_SCHEDULE, isEmulator, RECONCILE, REGION } from "./config";
import { recheckOpenFlags } from "./reconcile";
import { closeStaleTrips } from "./trips";

setGlobalOptions({ region: REGION, maxInstances: 5 });

/** HTTP API: <region base URL>/api/v1/... (see app.ts). */
export const api = onRequest(createApp({ dev: isEmulator() }));

/** Closes running trips whose vehicle has gone quiet (see trips.ts closeStaleTrips). */
export const autoCloseTrips = onSchedule({ schedule: AUTO_CLOSE_SCHEDULE, timeZone: "Asia/Seoul" }, async () => {
  await closeStaleTrips(Date.now());
});

/** Expense check ①: open payment flags are checked again once a day (late charges, cancellations). */
export const recheckPaymentFlags = onSchedule({ schedule: RECONCILE.sweepSchedule, timeZone: "Asia/Seoul" }, async () => {
  await recheckOpenFlags();
});
