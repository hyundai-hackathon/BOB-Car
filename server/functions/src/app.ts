import express, { type NextFunction, type Request, type Response } from "express";
import * as logger from "firebase-functions/logger";
import { requireIdToken } from "./auth";
import { COL, db } from "./db";
import { checkPayment } from "./reconcile";
import { ensureSeeded } from "./seed";
import { closeStaleTrips } from "./trips";
import { isObject, processUpload, UploadError } from "./uploads";

export interface AppOptions {
  /** Emulator and tests only: seed data on the first call, and dev routes (/v1/dev/...). */
  dev: boolean;
}

const TRANSACTION_CATEGORIES = ["CHARGE", "FUEL", "TOLL", "OTHER"];

/**
 * The HTTP API served by the "api" function at <function URL>/v1/... The vehicle app's base URL ends at
 * the region and its path is /api/v1/..., which lands here. Every route needs an ID token (401 without).
 */
export function createApp(options: AppOptions): express.Express {
  const app = express();
  app.disable("x-powered-by");
  app.use(express.json({ limit: "1mb" }));
  app.use(requireIdToken);
  if (options.dev) {
    app.use(async (_req: Request, _res: Response, next: NextFunction) => {
      await ensureSeeded(Date.now());
      next();
    });
  }

  const v1 = express.Router();

  v1.post("/uploads", async (req, res) => {
    try {
      res.json(await processUpload(req.body, Date.now()));
    } catch (e) {
      if (!(e instanceof UploadError)) throw e;
      res.status(e.status).json({ error: e.message });
    }
  });

  /** Per-vehicle baseline: last odometer, battery %, running trip, last upload. */
  v1.get("/vehicles/:vehicleId", async (req, res) => {
    const snap = await db().collection(COL.vehicles).doc(String(req.params.vehicleId)).get();
    if (!snap.exists) {
      res.status(404).json({ error: "vehicle not found" });
      return;
    }
    res.json(snap.data());
  });

  /** Staff with their consent state ("동의 대기" = PENDING on the admin page). */
  v1.get("/drivers", async (_req, res) => {
    const snaps = await db().collection(COL.drivers).where("active", "==", true).get();
    res.json({
      items: snaps.docs.map((d) => ({
        driverId: d.id,
        name: d.get("name"),
        consentStatus: d.get("consent.status") ?? "PENDING",
        consentAgreedAt: d.get("consent.agreedAt") ?? null,
      })),
    });
  });

  /** Reservations of a vehicle overlapping [from, to) (epoch ms; both optional). */
  v1.get("/reservations", async (req, res) => {
    const vehicleId = requiredQuery(req, res, "vehicleId");
    if (!vehicleId) return;
    const from = Number(req.query.from ?? 0) || 0;
    const to = Number(req.query.to ?? Number.MAX_SAFE_INTEGER) || Number.MAX_SAFE_INTEGER;
    const snaps = await db()
      .collection(COL.reservations)
      .where("vehicleId", "==", vehicleId)
      .where("startAt", "<", to)
      .orderBy("startAt")
      .get();
    res.json({
      items: snaps.docs
        .map((d) => ({ reservationId: d.id, ...(d.data() as { endAt: number }) }))
        .filter((r) => r.endAt > from),
    });
  });

  v1.get("/trips", (req, res) => listByVehicle(req, res, COL.trips, "startedAt"));
  v1.get("/events", (req, res) => listByVehicle(req, res, COL.events, "occurredAt"));
  v1.get("/flags", (req, res) => listByVehicle(req, res, COL.flags, "createdAt"));
  v1.get("/transactions", (req, res) => listByVehicle(req, res, COL.transactions, "paidAt"));

  /** Mock corporate card payment (demo data for the expense check; never a card number). */
  v1.post("/transactions", async (req, res) => {
    const body = req.body;
    const valid =
      isObject(body) &&
      typeof body.vehicleId === "string" &&
      Number.isInteger(body.amount) &&
      (body.amount as number) > 0 &&
      typeof body.merchant === "string" &&
      body.merchant.length > 0 &&
      body.merchant.length <= 60 &&
      typeof body.category === "string" &&
      TRANSACTION_CATEGORIES.includes(body.category) &&
      typeof body.paidAt === "number";
    if (!valid) {
      res.status(400).json({ error: "body must be {vehicleId, amount (KRW), merchant, category, paidAt}" });
      return;
    }
    if (!(await db().collection(COL.vehicles).doc(body.vehicleId as string).get()).exists) {
      res.status(403).json({ error: "vehicle is not registered" });
      return;
    }
    const ref = await db().collection(COL.transactions).add({
      vehicleId: body.vehicleId,
      amount: body.amount,
      merchant: body.merchant,
      category: body.category,
      paidAt: body.paidAt,
      createdAt: Date.now(),
    });
    // Expense check ①: flagged unless a charge the vehicle found explains it
    res.status(201).json({ transactionId: ref.id, flag: await checkPayment(ref.id) });
  });

  if (options.dev) {
    /**
     * Runs the scheduled auto-close now (the emulator does not run scheduled functions by itself).
     * {"now": epoch ms} pretends the check runs at that time.
     */
    v1.post("/dev/auto-close", async (req, res) => {
      const now = isObject(req.body) && typeof req.body.now === "number" ? req.body.now : Date.now();
      res.json({ closed: await closeStaleTrips(now) });
    });
  }

  app.use("/v1", v1);
  app.use((_req: Request, res: Response) => {
    res.status(404).json({ error: "not found" });
  });
  app.use((err: unknown, _req: Request, res: Response, _next: NextFunction) => {
    logger.error("request failed", err);
    res.status(500).json({ error: "internal error" });
  });
  return app;
}

/** Newest first, for one vehicle (?vehicleId=, ?limit= 1..200, default 50). */
async function listByVehicle(req: Request, res: Response, collection: string, orderField: string): Promise<void> {
  const vehicleId = requiredQuery(req, res, "vehicleId");
  if (!vehicleId) return;
  const limit = Math.min(Math.max(Number(req.query.limit ?? 50) || 50, 1), 200);
  const snaps = await db()
    .collection(collection)
    .where("vehicleId", "==", vehicleId)
    .orderBy(orderField, "desc")
    .limit(limit)
    .get();
  res.json({ items: snaps.docs.map((d) => ({ id: d.id, ...d.data() })) });
}

function requiredQuery(req: Request, res: Response, name: string): string | null {
  const value = req.query[name];
  if (typeof value === "string" && value.length > 0) return value;
  res.status(400).json({ error: `${name} is required` });
  return null;
}
