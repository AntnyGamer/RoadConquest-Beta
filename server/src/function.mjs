import { serve } from "./application.mjs";
import { createFetchHandler } from "./fetch-adapter.mjs";

export default { fetch: createFetchHandler(serve) };
export { pool } from "./application.mjs";
