import { getApps, initializeApp, type App } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { DEMO_PROJECT_ID } from "./config";

/** Collections. Document IDs are app-made (vehicle "veh-001", driver "drv-001"); no VIN is ever stored. */
export const COL = {
  vehicles: "vehicles",
  drivers: "drivers",
  reservations: "reservations",
  trips: "trips",
  events: "events",
  /** One per received upload item ("<vehicleId>_<queueId>"): the same item is applied only once. */
  receipts: "receipts",
  flags: "flags",
  transactions: "transactions",
} as const;

function app(): App {
  return getApps()[0] ?? initializeApp({ projectId: process.env.GCLOUD_PROJECT ?? DEMO_PROJECT_ID });
}

export const db = () => getFirestore(app());
export const auth = () => getAuth(app());
