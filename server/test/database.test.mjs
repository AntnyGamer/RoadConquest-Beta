import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import pg from "pg";
import { hashPassword, normalizeUsername } from "../src/security.mjs";

const { Pool } = pg;
const connectionString = process.env.DATABASE_URL;

test("database rejects concurrent duplicate usernames case-insensitively", { skip: !connectionString }, async () => {
  const pool = new Pool({ connectionString });
  try {
    const schemaPath = fileURLToPath(new URL("../schema.sql", import.meta.url));
    await pool.query(await readFile(schemaPath, "utf8"));
    await pool.query("TRUNCATE auth_rate_limits, sessions, users RESTART IDENTITY CASCADE");

    const first = normalizeUsername("DriverOne");
    const second = normalizeUsername("driverone");
    assert.equal(first.key, second.key);
    const password = await hashPassword("correct horse battery staple");

    const insert = username => pool.query(
      `INSERT INTO users(username_display, username_key, password_salt, password_hash)
       VALUES ($1, $2, $3, $4)`,
      [username.display, username.key, password.salt, password.hash]
    );

    const results = await Promise.allSettled([insert(first), insert(second)]);
    assert.equal(results.filter(result => result.status === "fulfilled").length, 1);
    const rejected = results.find(result => result.status === "rejected");
    assert.equal(rejected.reason.code, "23505");
    assert.equal(rejected.reason.constraint, "users_username_key_unique");
    const saved = await pool.query("SELECT username_key, leaderboard_visible FROM users");
    assert.equal(saved.rowCount, 1);
    assert.equal(saved.rows[0].username_key, "driverone");
    assert.equal(saved.rows[0].leaderboard_visible, true);
  } finally {
    await pool.end();
  }
});
