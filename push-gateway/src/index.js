// Moggogram push gateway (Cloudflare Worker).
//
// Telegram delivers notifications to Moggogram as Web Push (account.registerDevice token_type 10,
// "aesgcm" encryption) plus a "simple push" wake-up URL (token_type 4). Both point here:
//   POST|PUT /wp/<fcm token>   encrypted Web Push from Telegram
//   POST|PUT /sp/<fcm token>   simple push wake-up
// The gateway forwards them to the device through Firebase Cloud Messaging. It never sees
// plaintext: the Web Push private key lives only on the phone.
//
// Payloads that do not fit into an FCM data message are parked in KV for 5 minutes and fetched by
// the app with GET /k/<id>.
//
// Secrets / bindings (see wrangler.toml):
//   FCM_SERVICE_ACCOUNT  JSON key of a Firebase service account (wrangler secret put)
//   BLOBS                KV namespace for oversized payloads

const MAX_INLINE_BASE64 = 3000; // FCM data payload limit is 4 KB in total
const BLOB_TTL_SECONDS = 300;

let cachedAccessToken = null;
let cachedAccessTokenExpiry = 0;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);
    try {
      if (parts.length === 2 && (request.method === "POST" || request.method === "PUT")) {
        const token = decodeURIComponent(parts[1]);
        if (parts[0] === "wp") return await handleWebPush(request, env, token);
        if (parts[0] === "sp") return await forward(env, token, { t: "sp" });
      }
      if (parts.length === 2 && parts[0] === "k" && request.method === "GET") {
        return await handleBlobFetch(env, parts[1]);
      }
      if (parts.length === 0) return new Response("moggogram push gateway\n");
      return new Response("not found\n", { status: 404 });
    } catch (e) {
      return new Response("error: " + (e && e.message ? e.message : e) + "\n", { status: 502 });
    }
  },
};

async function handleWebPush(request, env, token) {
  const body = new Uint8Array(await request.arrayBuffer());
  const encoding = request.headers.get("Content-Encoding") || "aesgcm";
  const encryption = request.headers.get("Encryption") || "";
  const cryptoKey = request.headers.get("Crypto-Key") || "";
  if (!encryption || !cryptoKey) return new Response("missing aesgcm headers\n", { status: 400 });

  // Same packing the app's WebPushDecryptor expects: three header lines, then the ciphertext.
  const head = new TextEncoder().encode(
    "Content-Encoding: " + encoding + "\nEncryption: " + encryption + "\nCrypto-Key: " + cryptoKey + "\n"
  );
  const packet = new Uint8Array(head.length + body.length);
  packet.set(head, 0);
  packet.set(body, head.length);
  const b64 = toBase64(packet);

  if (b64.length <= MAX_INLINE_BASE64) {
    return await forward(env, token, { t: "wp", d: b64 });
  }
  if (!env.BLOBS) return new Response("payload too large\n", { status: 413 });
  const id = crypto.randomUUID().replace(/-/g, "");
  await env.BLOBS.put(id, b64, { expirationTtl: BLOB_TTL_SECONDS });
  return await forward(env, token, { t: "wpk", k: id });
}

async function handleBlobFetch(env, id) {
  if (!env.BLOBS || !/^[0-9a-f]{32}$/.test(id)) return new Response("not found\n", { status: 404 });
  const value = await env.BLOBS.get(id);
  if (value === null) return new Response("not found\n", { status: 404 });
  await env.BLOBS.delete(id);
  return new Response(value, { headers: { "Content-Type": "text/plain" } });
}

async function forward(env, token, data) {
  const account = JSON.parse(env.FCM_SERVICE_ACCOUNT);
  const accessToken = await getAccessToken(account);
  const response = await fetch(
    "https://fcm.googleapis.com/v1/projects/" + account.project_id + "/messages:send",
    {
      method: "POST",
      headers: { Authorization: "Bearer " + accessToken, "Content-Type": "application/json" },
      body: JSON.stringify({
        message: { token, data, android: { priority: "HIGH", ttl: "86400s" } },
      }),
    }
  );
  if (response.ok) return new Response(null, { status: 201 });
  const text = await response.text();
  // The app was removed or the token rotated: tell Telegram to drop this subscription.
  if (response.status === 404 || text.includes("UNREGISTERED")) {
    return new Response("gone\n", { status: 410 });
  }
  return new Response("fcm " + response.status + ": " + text + "\n", { status: 502 });
}

async function getAccessToken(account) {
  const now = Math.floor(Date.now() / 1000);
  if (cachedAccessToken && cachedAccessTokenExpiry - 60 > now) return cachedAccessToken;
  const header = { alg: "RS256", typ: "JWT" };
  const claims = {
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  };
  const unsigned = base64UrlJson(header) + "." + base64UrlJson(claims);
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signature = new Uint8Array(
    await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned))
  );
  const jwt = unsigned + "." + toBase64Url(signature);
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=" + jwt,
  });
  if (!response.ok) throw new Error("oauth " + response.status + ": " + (await response.text()));
  const json = await response.json();
  cachedAccessToken = json.access_token;
  cachedAccessTokenExpiry = now + (json.expires_in || 3600);
  return cachedAccessToken;
}

function pemToDer(pem) {
  const b64 = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const raw = atob(b64);
  const der = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) der[i] = raw.charCodeAt(i);
  return der.buffer;
}

function toBase64(bytes) {
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) {
    s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  }
  return btoa(s);
}

function toBase64Url(bytes) {
  return toBase64(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlJson(obj) {
  return toBase64Url(new TextEncoder().encode(JSON.stringify(obj)));
}
