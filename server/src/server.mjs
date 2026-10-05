import http from "node:http";
import { serve, pool } from "./application.mjs";

const PORT = Number(process.env.PORT || 8080);
const server = http.createServer(serve);

server.requestTimeout = 15_000;
server.headersTimeout = 10_000;
server.keepAliveTimeout = 5_000;
server.listen(PORT, () => console.log(`RoadConquest account API listening on :${PORT}`));

async function shutdown() {
  server.close(async () => {
    await pool.end();
    process.exit(0);
  });
}
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
