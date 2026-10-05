import test from "node:test";
import assert from "node:assert/strict";
import {
  createSessionToken,
  hashPassword,
  hashSessionToken,
  normalizeUsername,
  validatePassword,
  verifyPassword
} from "../src/security.mjs";

test("usernames are ASCII-only, normalized, and case-insensitive for uniqueness", () => {
  assert.deepEqual(normalizeUsername("  Road_User9  "), { display: "Road_User9", key: "road_user9" });
  assert.equal(normalizeUsername("road_user9").key, normalizeUsername("ROAD_USER9").key);
  assert.equal(normalizeUsername("ab"), null);
  assert.equal(normalizeUsername("road-user"), null);
  assert.equal(normalizeUsername("rоaduser"), null); // Contains Cyrillic о.
});

test("password policy accepts eight or more characters without composition rules", () => {
  assert.equal(validatePassword("1234567"), false);
  assert.equal(validatePassword("12345678"), true);
  assert.equal(validatePassword("correct horse battery staple"), true);
  assert.equal(validatePassword("x".repeat(128)), true);
  assert.equal(validatePassword("x".repeat(129)), false);
});

test("password hashes are salted, peppered, and verified with constant-time comparison", async () => {
  const password = "correct horse battery staple";
  const pepper = "test-pepper-that-is-long-enough-for-security-tests";
  const first = await hashPassword(password, pepper);
  const second = await hashPassword(password, pepper);
  assert.notDeepEqual(first.salt, second.salt);
  assert.notDeepEqual(first.hash, second.hash);
  assert.equal(await verifyPassword(password, first.salt, first.hash, pepper), true);
  assert.equal(await verifyPassword("wrong password that is long enough", first.salt, first.hash, pepper), false);
  assert.equal(await verifyPassword(password, first.salt, first.hash, "different-pepper"), false);
});

test("session tokens are random and only their hashes need storage", () => {
  const first = createSessionToken();
  const second = createSessionToken();
  assert.notEqual(first.token, second.token);
  assert.deepEqual(first.tokenHash, hashSessionToken(first.token));
});
