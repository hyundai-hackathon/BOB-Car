import { setGlobalOptions } from "firebase-functions/v2";
import { onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { createApp } from "./app";
import { AUTO_CLOSE_SCHEDULE, isEmulator, REGION } from "./config";
import { closeStaleTrips } from "./trips";

setGlobalOptions({ region: REGION, maxInstances: 5 });

/** HTTP API: <region base URL>/api/v1/... (see app.ts). */
export const api = onRequest(createApp({ dev: isEmulator() }));

/** Closes running trips whose vehicle has gone quiet (see trips.ts closeStaleTrips). */
export const autoCloseTrips = onSchedule({ schedule: AUTO_CLOSE_SCHEDULE, timeZone: "Asia/Seoul" }, async () => {
  await closeStaleTrips(Date.now());
});
