/** Keep required certificate verification from being overridden by URI SSL parameters. */
export function databaseConfig(connectionString, requireTls) {
  const config = { connectionString, max: 5, connectionTimeoutMillis: 10_000 };
  if (!requireTls) return config;
  let url;
  try {
    url = new URL(connectionString);
    decodeURIComponent(url.hostname);
  } catch { throw new Error("Invalid DATABASE_URL"); }
  if (!["postgres:", "postgresql:"].includes(url.protocol) || !url.hostname) {
    throw new Error("Invalid DATABASE_URL");
  }
  for (const key of [...url.searchParams.keys()]) {
    if (key.toLowerCase().startsWith("ssl")) url.searchParams.delete(key);
  }
  return { ...config, connectionString: url.toString(),
    ssl: { rejectUnauthorized: true }, enableChannelBinding: true };
}
