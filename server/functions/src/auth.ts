import type { NextFunction, Request, Response } from "express";
import { auth } from "./db";

/**
 * Every API call carries a Firebase ID token (anonymous sign-in) in the Authorization header:
 * "Authorization: Bearer <ID token>". Never in the URL. No fixed key exists in code or the repository.
 * A missing or invalid token gets 401.
 */
export async function requireIdToken(req: Request, res: Response, next: NextFunction): Promise<void> {
  const match = /^Bearer\s+(\S+)$/.exec(req.get("authorization") ?? "");
  if (!match) {
    res.status(401).json({ error: "missing bearer token" });
    return;
  }
  try {
    const decoded = await auth().verifyIdToken(match[1]);
    res.locals.uid = decoded.uid;
  } catch {
    res.status(401).json({ error: "invalid token" });
    return;
  }
  next();
}
