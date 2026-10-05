import { createHash, createHmac } from "node:crypto";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import pg from "pg";
import { databaseConfig } from "./database-config.mjs";
import {
  createSessionToken,
  hashPassword,
  hashSessionToken,
  normalizeUsername,
  validatePassword,
  verifyPassword
} from "./security.mjs";
import { competitionConfig, createCompetition } from "./competition.mjs";

const { Pool } = pg;
const DATABASE_URL = process.env.DATABASE_URL;
const PEPPER = process.env.PASSWORD_PEPPER || "";
const REQUIRE_HTTPS = process.env.REQUIRE_HTTPS === "1";
const TRUST_PROXY = process.env.TRUST_PROXY === "1";
const SESSION_DAYS = 30;
const JSON_LIMIT = 32 * 1024;
const TOKEN_RE = /^[A-Za-z0-9_-]{43}$/u;

if (!DATABASE_URL) throw new Error("DATABASE_URL is required");
if (PEPPER.length < 32) throw new Error("PASSWORD_PEPPER must be at least 32 characters");
if (process.env.NODE_ENV === "production") {
  if (!REQUIRE_HTTPS) throw new Error("REQUIRE_HTTPS=1 is required in production");
  if (!TRUST_PROXY) throw new Error("TRUST_PROXY=1 is required behind the production TLS proxy");
}

const pool = new Pool(databaseConfig(DATABASE_URL, process.env.DATABASE_SSL === "1"));
// Serverless database computes can sleep. An idle connection error must not kill
// the function; pg removes that client and creates a connection for the next request.
pool.on("error", () => console.error("Idle database connection closed."));

if (process.env.APPLY_SCHEMA_ON_STARTUP !== "0") {
  const schemaPath = fileURLToPath(new URL("../schema.sql", import.meta.url));
  await pool.query(await readFile(schemaPath, "utf8"));
}
const competition = await createCompetition(pool, competitionConfig(process.env));

const dummy = await hashPassword("this is only a constant dummy password", PEPPER);

function clientIp(req) {
  if (TRUST_PROXY) {
    const forwarded = req.headers["x-forwarded-for"];
    if (typeof forwarded === "string" && forwarded.trim()) return forwarded.split(",")[0].trim();
  }
  return req.socket.remoteAddress || "unknown";
}

function opaqueRateKey(bucket, key) {
  if (PEPPER) {
    return createHmac("sha256", PEPPER)
      .update("rate-limit\0", "utf8")
      .update(bucket, "utf8")
      .update("\0", "utf8")
      .update(key, "utf8")
      .digest();
  }
  return createHash("sha256").update(bucket, "utf8").update("\0").update(key, "utf8").digest();
}

async function rateLimit(bucket, key, limit, windowMs) {
  const result = await pool.query(
    `INSERT INTO auth_rate_limits(bucket, key_hash, window_started_at, attempts)
     VALUES ($1, $2, CURRENT_TIMESTAMP, 1)
     ON CONFLICT (bucket, key_hash) DO UPDATE SET
       window_started_at = CASE
         WHEN auth_rate_limits.window_started_at <= CURRENT_TIMESTAMP - ($3::bigint * INTERVAL '1 millisecond')
         THEN CURRENT_TIMESTAMP ELSE auth_rate_limits.window_started_at END,
       attempts = CASE
         WHEN auth_rate_limits.window_started_at <= CURRENT_TIMESTAMP - ($3::bigint * INTERVAL '1 millisecond')
         THEN 1 ELSE LEAST(auth_rate_limits.attempts + 1, $4 + 1) END
     RETURNING attempts <= $4 AS allowed`,
    [bucket, opaqueRateKey(bucket, key), windowMs, limit]
  );
  return result.rows[0].allowed === true;
}

async function cleanupExpiredState() {
  await Promise.all([
    pool.query("DELETE FROM sessions WHERE expires_at <= CURRENT_TIMESTAMP"),
    pool.query("DELETE FROM auth_rate_limits WHERE window_started_at < CURRENT_TIMESTAMP - INTERVAL '2 hours'"),
    pool.query("DELETE FROM competition_receipts WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '1 day'"),
    pool.query("DELETE FROM competition_runs WHERE expires_at < CURRENT_TIMESTAMP - INTERVAL '1 day'")
  ]);
}

// Run once on every process/function start as well as periodically. Serverless runtimes do not
// guarantee that an unreferenced background timer survives after a request finishes.
await cleanupExpiredState();
setInterval(() => { cleanupExpiredState().catch(() => {}); }, 15 * 60 * 1000).unref();

function send(res, status, body) {
  const data = JSON.stringify(body);
  const headers = {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(data),
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer"
  };
  if (REQUIRE_HTTPS) {
    headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains";
  }
  res.writeHead(status, headers);
  res.end(data);
}

async function readJson(req) {
  const contentType = req.headers["content-type"];
  if (typeof contentType !== "string" || !contentType.toLowerCase().startsWith("application/json")) {
    throw Object.assign(new Error("application/json required"), { status: 415 });
  }

  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > JSON_LIMIT) throw Object.assign(new Error("request too large"), { status: 413 });
    chunks.push(chunk);
  }
  if (!chunks.length) return {};
  try {
    const body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (!body || Array.isArray(body) || typeof body !== "object") throw new Error("JSON object required");
    return body;
  } catch {
    throw Object.assign(new Error("invalid JSON"), { status: 400 });
  }
}

function bearerToken(req) {
  const auth = req.headers.authorization;
  if (typeof auth !== "string" || !auth.startsWith("Bearer ")) return null;
  const token = auth.slice(7);
  return TOKEN_RE.test(token) ? token : null;
}

async function authenticatedUser(req) {
  const token = bearerToken(req);
  if (!token) return null;
  const result = await pool.query(
    `SELECT u.id, u.username_display, u.leaderboard_visible
       FROM sessions s
       JOIN users u ON u.id = s.user_id
      WHERE s.token_hash = $1 AND s.expires_at > CURRENT_TIMESTAMP`,
    [hashSessionToken(token)]
  );
  return result.rows[0] || null;
}

async function createSession(userId, db = pool) {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const { token, tokenHash } = createSessionToken();
    const result = await db.query(
      `INSERT INTO sessions(token_hash, user_id, expires_at)
       VALUES ($1, $2, CURRENT_TIMESTAMP + ($3 * INTERVAL '1 day'))
       ON CONFLICT (token_hash) DO NOTHING
       RETURNING 1`,
      [tokenHash, userId, SESSION_DAYS]
    );
    if (result.rowCount === 1) return token;
  }
  throw new Error("Could not allocate a unique session token");
}

async function handle(req, res) {
  const url = new URL(req.url || "/", "http://localhost");

  if (req.method === "GET" && url.pathname === "/health") {
    await pool.query("SELECT 1");
    return send(res, 200, { ok: true });
  }

  if (REQUIRE_HTTPS && req.headers["x-forwarded-proto"] !== "https") {
    return send(res, 400, { error: "HTTPS required" });
  }

  const ip = clientIp(req);
  if (req.method === "GET" && url.pathname === "/v1/competition") {
    return send(res, 200, competition?.status() || { available: false });
  }
  if (req.method === "GET" && url.pathname === "/v1/leaderboard") {
    if (!await rateLimit("competition-ip", ip, 180, 60 * 1000)) return send(res, 429, { error: "Try again later." });
    const metric = url.searchParams.get("metric") || "miles";
    if (metric !== "miles" && metric !== "roads") return send(res, 400, { error: "Choose miles or roads." });
    // The public page can load before verification is provisioned. No scores are invented,
    // and every drive-writing endpoint below remains gated on strict competition setup.
    return send(res, 200, competition
      ? { ...await competition.leaderboard(metric), verification_available: true }
      : { metric, dataset: null, entries: [], verification_available: false });
  }
  if (url.pathname.startsWith("/v1/verified/")) {
    if (!competition) return send(res, 404, { error: "Verified leaderboards are unavailable." });
    if (!await rateLimit("competition-ip", ip, 180, 60 * 1000)) return send(res, 429, { error: "Try again later." });
    const user = await authenticatedUser(req);
    if (!user) return send(res, 401, { error: "Authentication required." });
    if (req.method === "GET" && url.pathname === "/v1/verified/me") {
      return send(res, 200, await competition.totals(user));
    }
    if (req.method === "POST" && url.pathname === "/v1/verified/start") {
      if (!await rateLimit("drive-start", String(user.id), 6, 60 * 1000)) return send(res, 429, { error: "Try again later." });
      return send(res, 201, await competition.begin(user));
    }
    if (req.method === "POST" && url.pathname === "/v1/verified/batch") {
      if (!await rateLimit("drive-batch", String(user.id), 12, 60 * 1000)) return send(res, 429, { error: "Try again later." });
      const body = await readJson(req);
      if (Object.keys(body).sort().join(",") !== "evidence,integrity_token") return send(res, 400, { error: "Evidence and integrity token required." });
      return send(res, 200, await competition.submit(user, body.evidence, body.integrity_token));
    }
  }
  if (req.method === "POST" && url.pathname === "/v1/signup") {
    const body = await readJson(req);
    const username = normalizeUsername(body.username);
    const password = body.password;

    if (!username || !validatePassword(password)) {
      return send(res, 400, {
        error: "Username must be 3-24 letters, numbers, or underscores; password must be 8-128 characters."
      });
    }
    if (!await rateLimit("signup-ip", ip, 8, 60 * 60 * 1000)) {
      return send(res, 429, { error: "Too many attempts. Try again later." });
    }

    const { salt, hash } = await hashPassword(password, PEPPER);
    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      const result = await client.query(
        `INSERT INTO users(username_display, username_key, password_salt, password_hash)
         VALUES ($1, $2, $3, $4)
         RETURNING id, username_display, leaderboard_visible`,
        [username.display, username.key, salt, hash]
      );
      const user = result.rows[0];
      const token = await createSession(user.id, client);
      await client.query("COMMIT");
      return send(res, 201, {
        username: user.username_display,
        leaderboard_visible: user.leaderboard_visible,
        token
      });
    } catch (error) {
      await client.query("ROLLBACK").catch(() => {});
      if (error?.code === "23505") {
        return send(res, 409, { error: "Username unavailable." });
      }
      throw error;
    } finally {
      client.release();
    }
  }

  if (req.method === "POST" && url.pathname === "/v1/login") {
    const body = await readJson(req);
    const username = normalizeUsername(body.username);
    const password = typeof body.password === "string" && Array.from(body.password).length <= 128 ? body.password : "";
    const key = username?.key || "invalid";

    if (!await rateLimit("login-ip", ip, 20, 15 * 60 * 1000) ||
        !await rateLimit("login-user", key, 8, 15 * 60 * 1000)) {
      return send(res, 429, { error: "Too many attempts. Try again later." });
    }

    const result = username
      ? await pool.query(
          "SELECT id, username_display, password_salt, password_hash, leaderboard_visible FROM users WHERE username_key = $1",
          [username.key]
        )
      : { rows: [] };
    const user = result.rows[0];
    const valid = user
      ? await verifyPassword(password, user.password_salt, user.password_hash, PEPPER)
      : await verifyPassword(password, dummy.salt, dummy.hash, PEPPER);

    if (!valid) return send(res, 401, { error: "Invalid username or password." });
    const token = await createSession(user.id);
    return send(res, 200, {
      username: user.username_display,
      leaderboard_visible: user.leaderboard_visible,
      token
    });
  }

  if (req.method === "GET" && url.pathname === "/v1/me") {
    const user = await authenticatedUser(req);
    if (!user) return send(res, 401, { error: "Authentication required." });
    return send(res, 200, {
      username: user.username_display,
      leaderboard_visible: user.leaderboard_visible
    });
  }

  const renameAccount = req.method === "PUT" && url.pathname === "/v1/username";
  const deleteAccount = req.method === "DELETE" && url.pathname === "/v1/account";
  if (renameAccount || deleteAccount) {
    const user = await authenticatedUser(req);
    if (!user) return send(res, 401, { error: "Authentication required." });
    const body = await readJson(req);
    const username = renameAccount ? normalizeUsername(body.username) : null;
    const fields = renameAccount ? "password,username" : "password";
    if (Object.keys(body).sort().join(",") !== fields ||
        !validatePassword(body.password) || (renameAccount && !username)) {
      return send(res, 400, { error: renameAccount
        ? "Enter your current password and a valid username." : "Enter your current password." });
    }
    if (!await rateLimit("account-change", String(user.id), 5, 15 * 60 * 1000)) {
      return send(res, 429, { error: "Too many attempts. Try again later." });
    }
    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      const result = await client.query(
        "SELECT password_salt, password_hash, username_key FROM users WHERE id = $1 FOR UPDATE",
        [user.id]
      );
      const saved = result.rows[0];
      if (!saved) {
        await client.query("ROLLBACK");
        return send(res, 401, { error: "Authentication required." });
      }
      if (!await verifyPassword(body.password, saved.password_salt, saved.password_hash, PEPPER)) {
        await client.query("ROLLBACK");
        return send(res, 403, { error: "Current password is incorrect." });
      }
      if (renameAccount) {
        const renamed = await client.query(
          "UPDATE users SET username_display = $1, username_key = $2 WHERE id = $3 RETURNING username_display, leaderboard_visible",
          [username.display, username.key, user.id]
        );
        await client.query("COMMIT");
        return send(res, 200, {
          username: renamed.rows[0].username_display,
          leaderboard_visible: renamed.rows[0].leaderboard_visible
        });
      }
      // Foreign keys remove every session, score, unlocked way, live run and receipt.
      await client.query("DELETE FROM users WHERE id = $1", [user.id]);
      for (const [bucket, key] of [
        ["account-change", String(user.id)], ["drive-start", String(user.id)],
        ["drive-batch", String(user.id)], ["login-user", saved.username_key]
      ]) {
        await client.query("DELETE FROM auth_rate_limits WHERE bucket = $1 AND key_hash = $2", [bucket, opaqueRateKey(bucket, key)]);
      }
      await client.query("COMMIT");
      return send(res, 200, { ok: true });
    } catch (error) {
      await client.query("ROLLBACK").catch(() => {});
      if (renameAccount && error?.code === "23505") {
        return send(res, 409, { error: "Username unavailable." });
      }
      throw error;
    } finally {
      client.release();
    }
  }

  if (req.method === "PUT" && url.pathname === "/v1/privacy") {
    const user = await authenticatedUser(req);
    if (!user) return send(res, 401, { error: "Authentication required." });
    const body = await readJson(req);
    if (typeof body.leaderboard_visible !== "boolean") {
      return send(res, 400, { error: "leaderboard_visible must be boolean." });
    }
    await pool.query(
      "UPDATE users SET leaderboard_visible = $1 WHERE id = $2",
      [body.leaderboard_visible, user.id]
    );
    return send(res, 200, { leaderboard_visible: body.leaderboard_visible });
  }

  if (req.method === "POST" && url.pathname === "/v1/logout") {
    const token = bearerToken(req);
    if (token) {
      await pool.query("DELETE FROM sessions WHERE token_hash = $1", [hashSessionToken(token)]);
    }
    return send(res, 200, { ok: true });
  }

  return send(res, 404, { error: "Not found." });
}

export function serve(req, res) {
  handle(req, res).catch(error => {
    if (!error?.status || error.status >= 500) {
      console.error("request failed", error instanceof Error ? error.message : "unknown error");
    }
    if (!res.headersSent) send(res, error?.status || 500, { error: "Request failed." });
    else res.destroy();
  });
}


export { pool };
