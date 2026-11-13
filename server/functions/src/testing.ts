import { DEMO_PROJECT_ID } from "./config";
import { seed } from "./seed";

/*
 * Test helpers. Tests run against the local emulators (npm run test:emulators), which set
 * FIRESTORE_EMULATOR_HOST and FIREBASE_AUTH_EMULATOR_HOST. Never used by the deployed functions.
 */

function emulatorHost(name: "FIRESTORE_EMULATOR_HOST" | "FIREBASE_AUTH_EMULATOR_HOST"): string {
  const host = process.env[name];
  if (!host) throw new Error(`${name} is not set: run the tests with "npm run test:emulators"`);
  return host;
}

/** Empties the Firestore emulator and writes the seed data. */
export async function resetData(now: number): Promise<void> {
  const projectId = process.env.GCLOUD_PROJECT ?? DEMO_PROJECT_ID;
  const url = `http://${emulatorHost("FIRESTORE_EMULATOR_HOST")}/emulator/v1/projects/${projectId}/databases/(default)/documents`;
  const res = await fetch(url, { method: "DELETE" });
  if (!res.ok) throw new Error(`firestore reset failed: ${res.status}`);
  await seed(now);
}

/** An anonymous sign-in on the Auth emulator: returns its ID token (what the vehicle app sends). */
export async function anonymousIdToken(): Promise<string> {
  const url = `http://${emulatorHost("FIREBASE_AUTH_EMULATOR_HOST")}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=emulator`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ returnSecureToken: true }),
  });
  if (!res.ok) throw new Error(`anonymous sign-in failed: ${res.status}`);
  return ((await res.json()) as { idToken: string }).idToken;
}
