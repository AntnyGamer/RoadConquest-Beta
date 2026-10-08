import {
  createHash,
  createHmac,
  randomBytes,
  scrypt as scryptCallback,
  timingSafeEqual
} from "node:crypto";
import { promisify } from "node:util";

const scrypt = promisify(scryptCallback);
const USERNAME_RE = /^[A-Za-z0-9_]{3,24}$/u;
const SCRYPT_OPTIONS = Object.freeze({ N: 2 ** 17, r: 8, p: 1, maxmem: 256 * 1024 * 1024 });
const PASSWORD_MIN = 8;
const PASSWORD_MAX = 128;

export function normalizeUsername(value) {
  if (typeof value !== "string") return null;
  const display = value.normalize("NFKC").trim();
  if (!USERNAME_RE.test(display)) return null;
  return { display, key: display.toLowerCase() };
}

export function validatePassword(value) {
  if (typeof value !== "string") return false;
  const length = Array.from(value).length;
  return length >= PASSWORD_MIN && length <= PASSWORD_MAX;
}

function passwordInput(password, pepper) {
  if (!pepper) return password;
  return createHmac("sha256", pepper)
    .update("roadconquest-password\0", "utf8")
    .update(password, "utf8")
    .digest();
}

export async function hashPassword(password, pepper = "") {
  if (!validatePassword(password)) throw new Error("invalid password");
  const salt = randomBytes(16);
  const hash = await scrypt(passwordInput(password, pepper), salt, 64, SCRYPT_OPTIONS);
  return { salt, hash: Buffer.from(hash) };
}

export async function verifyPassword(password, salt, expectedHash, pepper = "") {
  if (typeof password !== "string" || !Buffer.isBuffer(salt) || salt.length !== 16 ||
      !Buffer.isBuffer(expectedHash) || expectedHash.length !== 64
  ) return false;
  const actual = Buffer.from(await scrypt(passwordInput(password, pepper), salt, 64, SCRYPT_OPTIONS));
  return timingSafeEqual(actual, expectedHash);
}

export function createSessionToken() {
  const token = randomBytes(32).toString("base64url");
  return { token, tokenHash: hashSessionToken(token) };
}

export function hashSessionToken(token) {
  return createHash("sha256").update(token, "utf8").digest();
}
