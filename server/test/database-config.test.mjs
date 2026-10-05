import test from "node:test";
import assert from "node:assert/strict";
import pg from "pg";
import { databaseConfig } from "../src/database-config.mjs";

test("required database TLS survives node-postgres URI parsing", () => {
  for (const query of ["sslmode=disable", "ssl=no-verify", "sslmode=require&uselibpqcompat=true",
    "sslmode=verify-ca", "sslnegotiation=direct", "sslcert=/missing&sslkey=/missing&sslrootcert=/missing"]) {
    const client = new pg.Client(databaseConfig("postgresql://driver:secret@db.example/roads?" + query, true));
    assert.equal(client.connectionParameters.ssl.rejectUnauthorized, true);
    assert.equal(client.connectionParameters.ssl.checkServerIdentity, undefined);
    assert.equal(client.connectionParameters.host, "db.example");
  }
});

test("invalid database URLs fail without exposing credentials", () => {
  for (const url of ["postgresql://driver:private-password@%invalid/db", "https://driver:private-password@db.example/db"]) {
    assert.throws(() => databaseConfig(url, true), /^Error: Invalid DATABASE_URL$/);
  }
});
