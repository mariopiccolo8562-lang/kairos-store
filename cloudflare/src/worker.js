/*  KAIROS — Cloudflare Worker
 *  - /panel/*            pannello (asset statici) — protetto da Cloudflare Access se configurato
 *  - /api/admin/*        API del pannello — sessione + 2FA + CSRF + Access JWT
 *  - /api/player/*       API pubblica per l'app (rate limit + segreto dispositivo)
 *  - /api/public/config  configurazione pubblica minima del pannello (chiave Turnstile)
 *
 *  Binding:  DB (D1), ASSETS, RL_LOGIN / RL_DEVICE (rate limiting)
 *  Segreti:  ENC_KEY (base64, 32 byte), ADMIN_BOOTSTRAP_PASS, TURNSTILE_SECRET (opzionale)
 *  Variabili: ACCESS_TEAM_DOMAIN, ACCESS_AUD, TURNSTILE_SITEKEY (opzionali)
 */

const SESSION_TTL = 12 * 3600; // secondi
const COOKIE = '__Host-kx';
const PBKDF2_ITER = 100000; // massimo supportato da Workers
const MAX_PENDING = 2000;
const enc = new TextEncoder();
const dec = new TextDecoder();

/* ====================================================================== */
export default {
  async fetch(req, env, ctx) {
    let res;
    try {
      res = await route(req, env, ctx);
    } catch (e) {
      console.error('ERR', e && e.stack || e);
      res = json({ error: 'Errore interno' }, 500);
    }
    return harden(res, req);
  },
  async scheduled(_ev, env) {
    await cleanup(env);
  },
};

/* ====================================================================== */
/* Utility                                                                */
/* ====================================================================== */
const now = () => Date.now();
const json = (obj, status = 200, headers = {}) =>
  new Response(JSON.stringify(obj), { status, headers: { 'Content-Type': 'application/json; charset=utf-8', ...headers } });
const ipOf = (req) => req.headers.get('CF-Connecting-IP') || req.headers.get('X-Forwarded-For') || '0.0.0.0';
const str = (v, max = 200) => (typeof v === 'string' ? v.trim().slice(0, max) : '');

function b64(buf) { let s = ''; const b = new Uint8Array(buf); for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i]); return btoa(s); }
function unb64(s) { const bin = atob(s); const out = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i); return out; }
const b64url = (buf) => b64(buf).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const unb64url = (s) => unb64(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4));
const rand = (n) => crypto.getRandomValues(new Uint8Array(n));
async function sha256(s) { return b64url(await crypto.subtle.digest('SHA-256', typeof s === 'string' ? enc.encode(s) : s)); }
function safeEq(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const x = enc.encode(a), y = enc.encode(b);
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] || 0) ^ (y[i] || 0);
  return diff === 0;
}
async function readJSON(req, max = 64 * 1024) {
  const len = +(req.headers.get('Content-Length') || 0);
  if (len > max) throw new HttpErr(413, 'Richiesta troppo grande');
  const t = await req.text();
  if (t.length > max) throw new HttpErr(413, 'Richiesta troppo grande');
  try { return JSON.parse(t || '{}'); } catch { throw new HttpErr(400, 'JSON non valido'); }
}
class HttpErr extends Error { constructor(status, msg) { super(msg); this.status = status; } }

/* ---------------- password (PBKDF2-SHA256) ---------------- */
async function hashPass(pass, salt = rand(16), iter = PBKDF2_ITER) {
  const key = await crypto.subtle.importKey('raw', enc.encode(pass), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: iter }, key, 256);
  return `pbkdf2$${iter}$${b64(salt)}$${b64(bits)}`;
}
async function checkPass(pass, stored) {
  const [alg, iter, salt] = String(stored).split('$');
  if (alg !== 'pbkdf2') return false;
  return safeEq(await hashPass(pass, unb64(salt), +iter), stored);
}

/* ---------------- cifratura playlist (AES-256-GCM) ---------------- */
let aesKey;
async function getKey(env) {
  if (!env.ENC_KEY) throw new HttpErr(500, 'ENC_KEY non configurata sul Worker');
  if (!aesKey) {
    const raw = unb64(env.ENC_KEY);
    if (raw.length !== 32) throw new HttpErr(500, 'ENC_KEY deve essere di 32 byte in base64');
    aesKey = await crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);
  }
  return aesKey;
}
async function seal(env, obj) {
  const iv = rand(12);
  const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await getKey(env), enc.encode(JSON.stringify(obj)));
  const out = new Uint8Array(12 + ct.byteLength); out.set(iv); out.set(new Uint8Array(ct), 12);
  return 'v1:' + b64(out);
}
async function unseal(env, s) {
  if (!s) return [];
  if (!s.startsWith('v1:')) return [];
  const raw = unb64(s.slice(3));
  const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: raw.slice(0, 12) }, await getKey(env), raw.slice(12));
  return JSON.parse(dec.decode(pt));
}

/* ---------------- TOTP (RFC 6238) ---------------- */
const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
function b32enc(bytes) { let bits = 0, val = 0, out = ''; for (const b of bytes) { val = (val << 8) | b; bits += 8; while (bits >= 5) { out += B32[(val >>> (bits - 5)) & 31]; bits -= 5; } } if (bits) out += B32[(val << (5 - bits)) & 31]; return out; }
function b32dec(s) { const clean = s.replace(/[^A-Z2-7]/gi, '').toUpperCase(); let bits = 0, val = 0; const out = []; for (const c of clean) { val = (val << 5) | B32.indexOf(c); bits += 5; if (bits >= 8) { out.push((val >>> (bits - 8)) & 255); bits -= 8; } } return new Uint8Array(out); }
async function hotp(secret, counter) {
  const key = await crypto.subtle.importKey('raw', b32dec(secret), { name: 'HMAC', hash: 'SHA-1' }, false, ['sign']);
  const msg = new Uint8Array(8); let c = counter;
  for (let i = 7; i >= 0; i--) { msg[i] = c & 255; c = Math.floor(c / 256); }
  const h = new Uint8Array(await crypto.subtle.sign('HMAC', key, msg));
  const o = h[19] & 15;
  const n = ((h[o] & 127) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(n % 1e6).padStart(6, '0');
}
/** ritorna il contatore valido (per anti-replay) oppure -1 */
async function checkTotp(secret, code, last = 0) {
  code = String(code || '').replace(/\D/g, '');
  if (code.length !== 6) return -1;
  const t = Math.floor(now() / 30000);
  for (const w of [0, -1, 1]) {
    const ctr = t + w;
    if (ctr <= last) continue;
    if (safeEq(await hotp(secret, ctr), code)) return ctr;
  }
  return -1;
}

/* ---------------- Cloudflare Access (verifica JWT) ---------------- */
let jwks = null, jwksAt = 0;
async function verifyAccess(req, env) {
  if (!env.ACCESS_AUD || !env.ACCESS_TEAM_DOMAIN) return true; // Access non configurato
  const token = req.headers.get('Cf-Access-Jwt-Assertion') || (req.headers.get('Cookie') || '').match(/CF_Authorization=([^;]+)/)?.[1];
  if (!token) return false;
  const [h, p, s] = token.split('.');
  if (!h || !p || !s) return false;
  let header, payload;
  try { header = JSON.parse(dec.decode(unb64url(h))); payload = JSON.parse(dec.decode(unb64url(p))); } catch { return false; }
  const team = env.ACCESS_TEAM_DOMAIN.replace(/^https?:\/\//, '').replace(/\/$/, '');
  if (!jwks || now() - jwksAt > 3600e3) {
    const r = await fetch(`https://${team}/cdn-cgi/access/certs`);
    if (!r.ok) return false;
    jwks = (await r.json()).keys || []; jwksAt = now();
  }
  const jwk = jwks.find((k) => k.kid === header.kid);
  if (!jwk || header.alg !== 'RS256') return false;
  const key = await crypto.subtle.importKey('jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  const ok = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, unb64url(s), enc.encode(`${h}.${p}`));
  if (!ok) return false;
  const aud = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
  if (!aud.includes(env.ACCESS_AUD)) return false;
  if (payload.iss !== `https://${team}`) return false;
  if (!payload.exp || payload.exp * 1000 < now()) return false;
  return true;
}

/* ---------------- Turnstile ---------------- */
async function verifyTurnstile(env, token, ip) {
  if (!env.TURNSTILE_SECRET) return true;
  if (!token) return false;
  const body = new FormData();
  body.append('secret', env.TURNSTILE_SECRET); body.append('response', token); body.append('remoteip', ip);
  const r = await fetch('https://challenges.cloudflare.com/turnstile/v0/siteverify', { method: 'POST', body });
  const j = await r.json().catch(() => ({}));
  return !!j.success;
}

/* ---------------- rate limit ---------------- */
async function limited(binding, key) {
  if (!binding || typeof binding.limit !== 'function') return false;
  try { const { success } = await binding.limit({ key }); return !success; } catch { return false; }
}

/* ---------------- header di sicurezza ---------------- */
const CSP_PANEL = [
  "default-src 'self'",
  "script-src 'self' https://challenges.cloudflare.com",
  "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
  "font-src https://fonts.gstatic.com",
  "img-src 'self' data:",
  "connect-src 'self'",
  "frame-src https://challenges.cloudflare.com",
  "frame-ancestors 'none'",
  "base-uri 'none'",
  "form-action 'self'",
  "object-src 'none'",
].join('; ');
function harden(res, req) {
  const url = new URL(req.url);
  const r = new Response(res.body, res);
  const h = r.headers;
  h.set('Strict-Transport-Security', 'max-age=63072000; includeSubDomains; preload');
  h.set('X-Content-Type-Options', 'nosniff');
  h.set('X-Frame-Options', 'DENY');
  h.set('Referrer-Policy', 'no-referrer');
  h.set('Permissions-Policy', 'camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()');
  h.set('Cross-Origin-Opener-Policy', 'same-origin');
  h.set('X-Robots-Tag', 'noindex, nofollow');
  h.delete('Server');
  if (url.pathname.startsWith('/api/')) {
    h.set('Cache-Control', 'no-store');
    h.set('Content-Security-Policy', "default-src 'none'; frame-ancestors 'none'");
  } else {
    h.set('Content-Security-Policy', CSP_PANEL);
    if (url.pathname.endsWith('.html') || url.pathname.endsWith('/')) h.set('Cache-Control', 'no-cache');
  }
  if (url.pathname.startsWith('/app/')) {
    h.set('Access-Control-Allow-Origin', '*');
    h.set('Cross-Origin-Resource-Policy', 'cross-origin');
    h.set('Cache-Control', 'no-cache');
  }
  if (url.pathname.startsWith('/api/player/')) {
    h.set('Access-Control-Allow-Origin', '*');
    h.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    h.set('Access-Control-Allow-Headers', 'Content-Type');
    h.set('Access-Control-Max-Age', '86400');
    h.set('Cross-Origin-Resource-Policy', 'cross-origin');
  } else {
    if (!url.pathname.startsWith('/app/')) h.set('Cross-Origin-Resource-Policy', 'same-origin');
  }
  return r;
}

/* ====================================================================== */
/* Dati                                                                   */
/* ====================================================================== */
const DEFAULT_SETTINGS = {
  brand: 'KAIROS', tagline: 'Streaming', accent: 'cinema',
  announcement: 'Benvenuto su KAIROS — buona visione!', supportContact: '',
  allowLocalPlaylists: true, apkUrl: '', apkVersionCode: 0, downloaderCode: '',
  serverUrl: '', showFootball: true,
};
async function getSettings(env) {
  const row = await env.DB.prepare('SELECT v FROM settings WHERE k = ?').bind('app').first();
  let s = {}; try { s = row ? JSON.parse(row.v) : {}; } catch {}
  return { ...DEFAULT_SETTINGS, ...s };
}
const publicSettings = (s) => ({ brand: s.brand, tagline: s.tagline, accent: s.accent, announcement: s.announcement, supportContact: s.supportContact, allowLocalPlaylists: !!s.allowLocalPlaylists, serverUrl: s.serverUrl || '', showFootball: s.showFootball !== false });

/* ---------------- eventi di calcio (calendario pubblico ESPN, in cache 15 minuti) ---------------- */
const LEAGUES = [
  ['ita.1', 'Serie A'], ['uefa.champions', 'Champions League'], ['uefa.europa', 'Europa League'], ['uefa.europa.conf', 'Conference League'],
  ['ita.coppa_italia', 'Coppa Italia'], ['eng.1', 'Premier League'], ['esp.1', 'LaLiga'], ['ger.1', 'Bundesliga'], ['fra.1', 'Ligue 1'],
];
const ymd = (d) => d.toISOString().slice(0, 10).replace(/-/g, '');
async function footballEvents(ctx) {
  const cache = typeof caches !== 'undefined' ? caches.default : null;
  const key = new Request('https://kairos.cache/football-v1');
  if (cache) { const hit = await cache.match(key); if (hit) return hit.json(); }
  const from = new Date(), to = new Date(Date.now() + 8 * 86400e3);
  const all = await Promise.all(LEAGUES.map(async ([code, label]) => {
    try {
      const r = await fetch(`https://site.api.espn.com/apis/site/v2/sports/soccer/${code}/scoreboard?dates=${ymd(from)}-${ymd(to)}&limit=60`, { cf: { cacheTtl: 900 } });
      if (!r.ok) return [];
      const j = await r.json();
      return (j.events || []).map((e) => {
        const c = (e.competitions || [])[0] || {};
        const team = (side) => { const t = ((c.competitors || []).find((x) => x.homeAway === side) || {}).team || {}; return { name: t.shortDisplayName || t.displayName || '', logo: t.logo || '' }; };
        const state = (e.status && e.status.type && e.status.type.state) || 'pre';
        return { id: String(e.id), league: label, start: e.date, home: team('home'), away: team('away'), venue: (c.venue && c.venue.fullName) || '', live: state === 'in', state };
      }).filter((e) => e.state !== 'post' && e.home.name && e.away.name);
    } catch { return []; }
  }));
  const events = all.flat().sort((a, b) => new Date(a.start) - new Date(b.start)).slice(0, 40);
  const body = { events, updated: now() };
  if (cache && events.length) ctx.waitUntil(cache.put(key, new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json', 'Cache-Control': 'max-age=900' } })));
  return body;
}
async function log(env, type, text, ip = '') {
  await env.DB.prepare('INSERT INTO activity (t, type, text, ip) VALUES (?, ?, ?, ?)').bind(now(), type, String(text).slice(0, 300), ip).run();
}
const isExpired = (d) => d.expires && new Date(d.expires + 'T23:59:59Z').getTime() < now();
function statusOf(d, pls) {
  if (!d.active) return pls && pls.length ? 'disabled' : 'pending';
  if (isExpired(d)) return 'expired';
  return 'active';
}
function cleanPlaylists(list) {
  if (!Array.isArray(list)) return [];
  const okUrl = (u) => { if (!u) return ''; try { const x = new URL(u); return /^https?:$/.test(x.protocol) ? x.href.slice(0, 1000) : ''; } catch { return ''; } };
  return list.slice(0, 20).map((p) => ({
    id: str(p.id, 40) || b64url(rand(6)),
    name: str(p.name, 60) || 'Playlist',
    type: p.type === 'xtream' ? 'xtream' : 'm3u',
    url: okUrl(str(p.url, 1000)),
    host: okUrl(str(p.host, 300)).replace(/\/+$/, ''),
    username: str(p.username, 120),
    password: str(p.password, 120),
  }));
}
const CODE_RE = /^KX[A-Z0-9]{2}-[A-Z0-9]{4}$/;
const DATE_RE = /^(\d{4}-\d{2}-\d{2})?$/;

/* ====================================================================== */
/* Router                                                                 */
/* ====================================================================== */
async function route(req, env, ctx) {
  const url = new URL(req.url);
  const path = url.pathname;
  const ip = ipOf(req);

  if (path === '/' || path === '/panel') return Response.redirect(url.origin + '/panel/', 302);

  /* ---------- API app (pubblica) ---------- */
  if (path.startsWith('/api/player/')) {
    if (req.method === 'OPTIONS') return new Response(null, { status: 204 });
    if (path === '/api/player/register' && req.method === 'POST') return playerRegister(req, env, ip, ctx);
    if (path === '/api/player/ping' && req.method === 'GET') return json({ ok: true, service: 'kairos', time: now() });
    if (path === '/api/player/events' && req.method === 'GET') {
      if (await limited(env.RL_DEVICE, 'ev:' + ip)) return json({ events: [] }, 429);
      return json(await footballEvents(ctx));
    }
    if (path === '/api/player/update' && req.method === 'GET') {
      const s = await getSettings(env);
      return json({
        ui: env.UI_VERSION ? { version: env.UI_VERSION, url: '/app/index.html' } : null,
        apk: { versionCode: +s.apkVersionCode || 0, url: s.apkUrl || '' },
      });
    }
    return json({ error: 'Non trovato' }, 404);
  }

  /* ---------- config pubblica del pannello ---------- */
  if (path === '/api/public/config' && req.method === 'GET') {
    if (!(await verifyAccess(req, env))) return json({ error: 'Accesso negato' }, 403);
    return json({ turnstileSiteKey: env.TURNSTILE_SITEKEY || '' });
  }

  /* ---------- API pannello ---------- */
  if (path.startsWith('/api/admin/')) {
    if (!(await verifyAccess(req, env))) return json({ error: 'Accesso negato (Cloudflare Access)' }, 403);
    try { return await admin(req, env, url, ip, ctx); }
    catch (e) { if (e instanceof HttpErr) return json({ error: e.message }, e.status); throw e; }
  }

  /* ---------- interfaccia dell'app per l'aggiornamento automatico ---------- */
  if (path.startsWith('/app/')) {
    if (req.method !== 'GET' && req.method !== 'HEAD') return new Response('Metodo non consentito', { status: 405 });
    return env.ASSETS.fetch(req);
  }

  /* ---------- pannello (asset) ---------- */
  if (path.startsWith('/panel/')) {
    if (!(await verifyAccess(req, env))) return new Response('Accesso negato', { status: 403 });
    if (req.method !== 'GET' && req.method !== 'HEAD') return new Response('Metodo non consentito', { status: 405 });
    return env.ASSETS.fetch(req);
  }

  return new Response('Non trovato', { status: 404 });
}

/* ---------------- app: registrazione / sync ---------------- */
async function playerRegister(req, env, ip, ctx) {
  if (await limited(env.RL_DEVICE, 'dev:' + ip)) return json({ error: 'Troppe richieste, riprova tra poco' }, 429);
  let b;
  try { b = await readJSON(req, 4096); } catch (e) { return json({ error: e.message }, e.status || 400); }
  const code = str(b.code, 20).toUpperCase();
  const secret = str(b.secret, 100);
  if (!CODE_RE.test(code)) return json({ error: 'Codice non valido' }, 400);
  if (!/^[A-Za-z0-9_-]{32,100}$/.test(secret)) return json({ error: 'Segreto non valido' }, 400);
  const sh = await sha256('dev:' + secret);
  const model = str(b.model, 80);

  let d = await env.DB.prepare('SELECT * FROM devices WHERE code = ?').bind(code).first();
  if (!d) {
    if (await limited(env.RL_DEVICE, 'new:' + ip)) return json({ error: 'Troppe richieste' }, 429);
    const pending = await env.DB.prepare('SELECT COUNT(*) AS n FROM devices WHERE active = 0 AND playlists = ?').bind('').first();
    if (pending && pending.n >= MAX_PENDING) return json({ error: 'Registrazioni temporaneamente sospese' }, 503);
    d = { id: b64url(rand(9)), code, secret_hash: sh, name: '', model, notes: '', active: 0, expires: '', playlists: '', created_at: now(), last_seen: now(), last_ip: ip };
    await env.DB.prepare('INSERT INTO devices (id, code, secret_hash, model, created_at, last_seen, last_ip) VALUES (?, ?, ?, ?, ?, ?, ?)')
      .bind(d.id, code, sh, model, d.created_at, d.last_seen, ip).run();
    ctx.waitUntil(log(env, 'new', `Nuovo dispositivo in attesa: ${code}${model ? ' (' + model + ')' : ''}`, ip));
  } else if (!d.secret_hash) {
    // dispositivo creato dal pannello: il primo che si presenta col codice lo "lega" al proprio segreto
    await env.DB.prepare('UPDATE devices SET secret_hash = ? WHERE id = ? AND secret_hash = ?').bind(sh, d.id, '').run();
    d.secret_hash = sh;
    ctx.waitUntil(log(env, 'edit', `Dispositivo collegato: ${d.name || code}`, ip));
  } else if (!safeEq(d.secret_hash, sh)) {
    ctx.waitUntil(log(env, 'off', `Tentativo con codice già in uso: ${code}`, ip));
    return json({ status: 'locked' }, 403);
  }

  ctx.waitUntil(env.DB.prepare('UPDATE devices SET last_seen = ?, last_ip = ?, model = CASE WHEN ? <> \'\' THEN ? ELSE model END WHERE id = ?')
    .bind(now(), ip, model, model, d.id).run());

  const pls = await unseal(env, d.playlists).catch(() => []);
  const status = statusOf(d, pls);
  const s = await getSettings(env);
  return json({ status, name: d.name, expires: d.expires, settings: publicSettings(s), playlists: status === 'active' ? pls : [] });
}

/* ---------------- pannello ---------------- */
function cookieOf(req) { return (req.headers.get('Cookie') || '').match(new RegExp('(?:^|;\\s*)' + COOKIE.replace(/[-$]/g, '\\$&') + '=([^;]+)'))?.[1] || ''; }
async function getSession(req, env) {
  const tok = cookieOf(req);
  if (!tok) return null;
  const id = await sha256('ses:' + tok);
  const s = await env.DB.prepare('SELECT s.id, s.expires, a.id AS admin_id, a.username, a.totp_on FROM sessions s JOIN admins a ON a.id = s.admin_id WHERE s.id = ?').bind(id).first();
  if (!s || s.expires < now()) return null;
  return s;
}
const setCookie = (tok, maxAge) => `${COOKIE}=${tok}; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=${maxAge}`;

async function admin(req, env, url, ip, ctx) {
  const path = url.pathname.slice('/api/admin'.length);
  const m = req.method;

  /* protezione CSRF per tutte le richieste che modificano dati */
  if (m !== 'GET' && m !== 'HEAD') {
    const origin = req.headers.get('Origin');
    const site = req.headers.get('Sec-Fetch-Site');
    if (req.headers.get('X-Kairos') !== '1') throw new HttpErr(403, 'Richiesta non valida');
    if (origin && origin !== url.origin) throw new HttpErr(403, 'Origine non valida');
    if (site && !['same-origin', 'none'].includes(site)) throw new HttpErr(403, 'Origine non valida');
  }

  if (path === '/login' && m === 'POST') return login(req, env, ip, ctx);

  const ses = await getSession(req, env);
  if (!ses) throw new HttpErr(401, 'Sessione scaduta');
  const who = ses.username;

  if (path === '/logout' && m === 'POST') {
    await env.DB.prepare('DELETE FROM sessions WHERE id = ?').bind(ses.id).run();
    return json({ ok: true }, 200, { 'Set-Cookie': setCookie('', 0) });
  }
  if (path === '/logout-all' && m === 'POST') {
    await env.DB.prepare('DELETE FROM sessions WHERE admin_id = ? AND id <> ?').bind(ses.admin_id, ses.id).run();
    await log(env, 'auth', `${who}: chiuse tutte le altre sessioni`, ip);
    return json({ ok: true });
  }

  if (path === '/state' && m === 'GET') {
    const rows = (await env.DB.prepare('SELECT * FROM devices ORDER BY created_at DESC LIMIT 5000').all()).results || [];
    const devices = [];
    for (const d of rows) {
      const pls = await unseal(env, d.playlists).catch(() => []);
      devices.push({ id: d.id, code: d.code, name: d.name, model: d.model, notes: d.notes, active: !!d.active, expires: d.expires, playlists: pls, createdAt: d.created_at, lastSeen: d.last_seen, lastIp: d.last_ip, bound: !!d.secret_hash, status: statusOf(d, pls) });
    }
    const activity = ((await env.DB.prepare('SELECT t, type, text, ip FROM activity ORDER BY id DESC LIMIT 120').all()).results) || [];
    const sessions = (await env.DB.prepare('SELECT COUNT(*) AS n FROM sessions WHERE admin_id = ? AND expires > ?').bind(ses.admin_id, now()).first()).n;
    return json({
      settings: await getSettings(env), devices, activity, user: who,
      security: { totp: !!ses.totp_on, encryption: !!env.ENC_KEY, access: !!(env.ACCESS_AUD && env.ACCESS_TEAM_DOMAIN), turnstile: !!env.TURNSTILE_SECRET, sessions },
    });
  }

  if (path === '/settings' && m === 'PUT') {
    const b = await readJSON(req);
    const s = await getSettings(env);
    for (const k of ['brand', 'tagline', 'accent', 'announcement', 'supportContact', 'downloaderCode']) if (typeof b[k] === 'string') s[k] = str(b[k], k === 'announcement' ? 300 : 80);
    if (typeof b.apkUrl === 'string') { const u = str(b.apkUrl, 500); s.apkUrl = !u || /^https:\/\//.test(u) ? u : s.apkUrl; }
    if (typeof b.allowLocalPlaylists === 'boolean') s.allowLocalPlaylists = b.allowLocalPlaylists;
    if (typeof b.showFootball === 'boolean') s.showFootball = b.showFootball;
    if (b.apkVersionCode !== undefined) s.apkVersionCode = Math.max(0, Math.min(1e9, parseInt(b.apkVersionCode, 10) || 0));
    if (typeof b.serverUrl === 'string') {
      const u = str(b.serverUrl, 300).replace(/\/+$/, '').replace(/\/panel$/i, '');
      if (!u) s.serverUrl = '';
      else if (/^https:\/\/[a-z0-9.-]+(:\d+)?$/i.test(u)) s.serverUrl = u;
      else throw new HttpErr(400, 'Indirizzo server non valido: usa https://dominio (senza percorsi)');
    }
    await env.DB.prepare('INSERT INTO settings (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v').bind('app', JSON.stringify(s)).run();
    await log(env, 'settings', `${who}: impostazioni aggiornate`, ip);
    return json({ settings: s });
  }

  if (path === '/password' && m === 'PUT') {
    const b = await readJSON(req);
    const a = await env.DB.prepare('SELECT * FROM admins WHERE id = ?').bind(ses.admin_id).first();
    if (!(await checkPass(str(b.current, 200), a.pass_hash))) throw new HttpErr(400, 'Password attuale errata');
    const next = typeof b.next === 'string' ? b.next : '';
    if (next.length < 10) throw new HttpErr(400, 'La nuova password deve avere almeno 10 caratteri');
    const user = str(b.user, 40) || a.username;
    if (!/^[A-Za-z0-9._@-]{3,40}$/.test(user)) throw new HttpErr(400, 'Nome utente non valido');
    await env.DB.prepare('UPDATE admins SET username = ?, pass_hash = ? WHERE id = ?').bind(user, await hashPass(next), a.id).run();
    await env.DB.prepare('DELETE FROM sessions WHERE admin_id = ? AND id <> ?').bind(a.id, ses.id).run();
    await log(env, 'auth', `${user}: credenziali aggiornate`, ip);
    return json({ ok: true });
  }

  if (path === '/totp/setup' && m === 'POST') {
    if (ses.totp_on) throw new HttpErr(400, '2FA già attiva');
    const secret = b32enc(rand(20));
    await env.DB.prepare('UPDATE admins SET totp_secret = ? WHERE id = ?').bind(secret, ses.admin_id).run();
    const s = await getSettings(env);
    const label = encodeURIComponent(`${s.brand || 'KAIROS'}:${who}`);
    return json({ secret, uri: `otpauth://totp/${label}?secret=${secret}&issuer=${encodeURIComponent(s.brand || 'KAIROS')}&digits=6&period=30` });
  }
  if (path === '/totp/enable' && m === 'POST') {
    const b = await readJSON(req);
    const a = await env.DB.prepare('SELECT * FROM admins WHERE id = ?').bind(ses.admin_id).first();
    if (!a.totp_secret) throw new HttpErr(400, 'Genera prima il codice segreto');
    const ctr = await checkTotp(a.totp_secret, b.code, a.totp_last);
    if (ctr < 0) throw new HttpErr(400, 'Codice non valido');
    await env.DB.prepare('UPDATE admins SET totp_on = 1, totp_last = ? WHERE id = ?').bind(ctr, a.id).run();
    await log(env, 'auth', `${who}: verifica in due passaggi attivata`, ip);
    return json({ ok: true });
  }
  if (path === '/totp/disable' && m === 'POST') {
    const b = await readJSON(req);
    const a = await env.DB.prepare('SELECT * FROM admins WHERE id = ?').bind(ses.admin_id).first();
    if (!(await checkPass(str(b.password, 200), a.pass_hash))) throw new HttpErr(400, 'Password errata');
    const ctr = await checkTotp(a.totp_secret, b.code, a.totp_last);
    if (ctr < 0) throw new HttpErr(400, 'Codice non valido');
    await env.DB.prepare('UPDATE admins SET totp_on = 0, totp_secret = ?, totp_last = 0 WHERE id = ?').bind('', a.id).run();
    await log(env, 'auth', `${who}: verifica in due passaggi disattivata`, ip);
    return json({ ok: true });
  }

  if (path === '/devices' && m === 'POST') {
    const b = await readJSON(req);
    let code = str(b.code, 20).toUpperCase();
    if (!code) { const A = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789', r = rand(6); const c = [...r].map((x) => A[x % A.length]); code = `KX${c[0]}${c[1]}-${c[2]}${c[3]}${c[4]}${c[5]}`; }
    if (!CODE_RE.test(code)) throw new HttpErr(400, 'Formato codice non valido (es. KXAB-12CD)');
    if (await env.DB.prepare('SELECT 1 FROM devices WHERE code = ?').bind(code).first()) throw new HttpErr(400, 'Codice già esistente');
    if (!DATE_RE.test(str(b.expires, 10))) throw new HttpErr(400, 'Data non valida');
    const id = b64url(rand(9));
    await env.DB.prepare('INSERT INTO devices (id, code, name, notes, active, expires, playlists, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)')
      .bind(id, code, str(b.name, 60), str(b.notes, 500), b.active ? 1 : 0, str(b.expires, 10), await seal(env, cleanPlaylists(b.playlists)), now()).run();
    await log(env, 'new', `${who}: creato dispositivo ${str(b.name, 60) || code}`, ip);
    return json({ ok: true, id, code });
  }

  const dm = path.match(/^\/devices\/([A-Za-z0-9_-]{6,20})(\/reset)?$/);
  if (dm) {
    const d = await env.DB.prepare('SELECT * FROM devices WHERE id = ?').bind(dm[1]).first();
    if (!d) throw new HttpErr(404, 'Dispositivo non trovato');
    const label = d.name || d.code;
    if (dm[2] && m === 'POST') {
      await env.DB.prepare('UPDATE devices SET secret_hash = ? WHERE id = ?').bind('', d.id).run();
      await log(env, 'edit', `${who}: sbloccato il collegamento di ${label}`, ip);
      return json({ ok: true });
    }
    if (m === 'PUT') {
      const b = await readJSON(req);
      const before = statusOf(d, await unseal(env, d.playlists).catch(() => []));
      const name = typeof b.name === 'string' ? str(b.name, 60) : d.name;
      const notes = typeof b.notes === 'string' ? str(b.notes, 500) : d.notes;
      const expires = typeof b.expires === 'string' ? str(b.expires, 10) : d.expires;
      if (!DATE_RE.test(expires)) throw new HttpErr(400, 'Data non valida');
      const active = typeof b.active === 'boolean' ? (b.active ? 1 : 0) : d.active;
      const pls = b.playlists ? cleanPlaylists(b.playlists) : await unseal(env, d.playlists).catch(() => []);
      await env.DB.prepare('UPDATE devices SET name = ?, notes = ?, expires = ?, active = ?, playlists = ? WHERE id = ?')
        .bind(name, notes, expires, active, await seal(env, pls), d.id).run();
      const after = statusOf({ active, expires }, pls);
      await log(env, before !== after ? (after === 'active' ? 'on' : 'off') : 'edit', before !== after ? `${who}: ${name || d.code} ${before} → ${after}` : `${who}: modificato ${name || d.code}`, ip);
      return json({ ok: true });
    }
    if (m === 'DELETE') {
      await env.DB.prepare('DELETE FROM devices WHERE id = ?').bind(d.id).run();
      await log(env, 'del', `${who}: eliminato ${label}`, ip);
      return json({ ok: true });
    }
  }
  throw new HttpErr(404, 'Endpoint sconosciuto');
}

async function login(req, env, ip, ctx) {
  if (await limited(env.RL_LOGIN, 'login:' + ip)) throw new HttpErr(429, 'Troppi tentativi, attendi un minuto');
  const fail = await env.DB.prepare('SELECT * FROM login_fail WHERE ip = ?').bind(ip).first();
  if (fail && fail.until > now()) throw new HttpErr(429, `Accesso bloccato per sicurezza. Riprova tra ${Math.ceil((fail.until - now()) / 60000)} minuti`);

  const b = await readJSON(req, 8192);
  if (!(await verifyTurnstile(env, str(b.turnstile, 4096), ip))) throw new HttpErr(400, 'Verifica anti-bot non superata, riprova');
  const user = str(b.user, 40), pass = typeof b.pass === 'string' ? b.pass.slice(0, 200) : '';

  // primo avvio: crea l'amministratore con la password di bootstrap (segreto del Worker)
  const count = (await env.DB.prepare('SELECT COUNT(*) AS n FROM admins').first()).n;
  if (!count) {
    if (!env.ADMIN_BOOTSTRAP_PASS || env.ADMIN_BOOTSTRAP_PASS.length < 10) throw new HttpErr(503, 'Imposta il segreto ADMIN_BOOTSTRAP_PASS (min 10 caratteri) sul Worker');
    if (user === 'admin' && safeEq(pass, env.ADMIN_BOOTSTRAP_PASS)) {
      await env.DB.prepare('INSERT INTO admins (username, pass_hash, created_at) VALUES (?, ?, ?)').bind('admin', await hashPass(pass), now()).run();
      await log(env, 'auth', 'Amministratore creato al primo accesso', ip);
    }
  }

  const a = await env.DB.prepare('SELECT * FROM admins WHERE username = ?').bind(user).first();
  // calcola comunque un hash per non rivelare se l'utente esiste (timing)
  const ok = a ? await checkPass(pass, a.pass_hash) : (await hashPass(pass), false);
  if (!ok) {
    const c = (fail && fail.until <= now() && now() - fail.updated > 3600e3 ? 0 : (fail ? fail.count : 0)) + 1;
    const until = c >= 5 ? now() + 15 * 60e3 : 0;
    await env.DB.prepare('INSERT INTO login_fail (ip, count, until, updated) VALUES (?, ?, ?, ?) ON CONFLICT(ip) DO UPDATE SET count = excluded.count, until = excluded.until, updated = excluded.updated')
      .bind(ip, until ? 0 : c, until, now()).run();
    await log(env, 'off', `Accesso fallito per "${user.slice(0, 30)}"${until ? ' — IP bloccato 15 min' : ''}`, ip);
    throw new HttpErr(401, 'Credenziali errate');
  }
  if (a.totp_on) {
    if (!b.totp) return json({ needTotp: true }, 200);
    const ctr = await checkTotp(a.totp_secret, b.totp, a.totp_last);
    if (ctr < 0) {
      await log(env, 'off', `Codice 2FA errato per ${a.username}`, ip);
      throw new HttpErr(401, 'Codice di verifica errato');
    }
    await env.DB.prepare('UPDATE admins SET totp_last = ? WHERE id = ?').bind(ctr, a.id).run();
  }
  await env.DB.prepare('DELETE FROM login_fail WHERE ip = ?').bind(ip).run();
  const tok = b64url(rand(32));
  await env.DB.prepare('INSERT INTO sessions (id, admin_id, expires, ip, ua, created_at) VALUES (?, ?, ?, ?, ?, ?)')
    .bind(await sha256('ses:' + tok), a.id, now() + SESSION_TTL * 1000, ip, str(req.headers.get('User-Agent') || '', 200), now()).run();
  await log(env, 'auth', `Accesso di ${a.username}`, ip);
  ctx.waitUntil(cleanup(env));
  return json({ ok: true, user: a.username, totp: !!a.totp_on }, 200, { 'Set-Cookie': setCookie(tok, SESSION_TTL) });
}

async function cleanup(env) {
  const t = now();
  await env.DB.batch([
    env.DB.prepare('DELETE FROM sessions WHERE expires < ?').bind(t),
    env.DB.prepare('DELETE FROM login_fail WHERE until < ? AND updated < ?').bind(t, t - 86400e3),
    env.DB.prepare('DELETE FROM activity WHERE t < ?').bind(t - 90 * 86400e3),
    // dispositivi mai attivati e non più visti da 30 giorni
    env.DB.prepare("DELETE FROM devices WHERE active = 0 AND playlists = '' AND last_seen < ?").bind(t - 30 * 86400e3),
  ]);
}

// esportati per i test
export const _internal = { hashPass, checkPass, hotp, checkTotp, b32enc, seal, unseal };
