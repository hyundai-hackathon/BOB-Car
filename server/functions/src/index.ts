import { setGlobalOptions } from "firebase-functions/v2";
import { onRequest } from "firebase-functions/v2/https";
import { createApp } from "./app";
import { isEmulator, REGION } from "./config";

setGlobalOptions({ region: REGION, maxInstances: 5 });

/** HTTP API: <region base URL>/api/v1/... (see app.ts). */
export const api = onRequest(createApp({ dev: isEmulator() }));
