/** Adapt the account API to a platform Fetch handler without trusting client proxy headers. */
export function createFetchHandler(serve) {
  return async function fetch(request) {
    const url = new URL(request.url);
    if (url.protocol !== "https:") {
      return new Response(JSON.stringify({ error: "HTTPS required" }), {
        status: 400,
        headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" }
      });
    }
    const headers = Object.fromEntries(request.headers);
    // Fetch supplies the transport URL. Callers cannot make an HTTP request secure by
    // supplying x-forwarded-proto, or evade rate limits by inventing an IP address.
    delete headers["x-forwarded-for"];
    delete headers["x-real-ip"];
    headers["x-forwarded-proto"] = "https";
    const req = {
      method: request.method,
      url: url.pathname + url.search,
      headers,
      // Until the platform documents an authenticated client-IP source, anonymous
      // requests share the existing conservative database-backed attempt budget.
      socket: { remoteAddress: "function-anonymous" },
      async *[Symbol.asyncIterator]() {
        if (request.body) yield* request.body;
      }
    };
    return new Promise((resolve, reject) => {
      const res = {
        headersSent: false,
        writeHead(status, responseHeaders) {
          this.status = status;
          this.headers = responseHeaders;
          this.headersSent = true;
        },
        end(body) { resolve(new Response(body, { status: this.status, headers: this.headers })); },
        destroy() { reject(new Error("Account response failed.")); }
      };
      try { Promise.resolve(serve(req, res)).catch(reject); } catch (error) { reject(error); }
    });
  };
}
