const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const ss = { get(k) { try { return sessionStorage.getItem(k); } catch { return null; } }, set(k, v) { try { v == null ? sessionStorage.removeItem(k) : sessionStorage.setItem(k, v); } catch {} } };
const THEMES = { cinema: ['#e5202b', '#ff5a36', '#b3121b'], aurora: ['#22e1ff', '#7b5cff', '#ff3df2'], plasma: ['#ff3d8b', '#ff7a3d', '#ffd23d'], matrix: ['#3dffb0', '#22e1ff', '#3d7bff'], solar: ['#ffb23d', '#ff3d6e', '#b13dff'], ice: ['#9ef0ff', '#5d9bff', '#c3b5ff'] };
const applyTheme = (n) => { const t = THEMES[n] || THEMES.cinema; ['--a1', '--a2', '--a3'].forEach((v, i) => document.documentElement.style.setProperty(v, t[i])); };
const STL = { active: 'Attivo', pending: 'In attesa', expired: 'Scaduto', disabled: 'Disattivato' };
const STC = { active: 'var(--ok)', pending: 'var(--warn)', expired: 'var(--bad)', disabled: '#ff7aa0' };
const D = { demo: false, security: {}, settings: {}, devices: [], activity: [], user: 'admin', filter: 'all', q: '', range: 30 };

addEventListener('pointermove', (e) => { const s = $('#spot'); s.style.left = e.clientX + 'px'; s.style.top = e.clientY + 'px'; });

/* ---------------- API (con fallback demo) ---------------- */
async function api(method, path, body) {
  if (D.demo) return demoApi(method, path, body);
  const r = await fetch('/api' + path, { method, credentials: 'same-origin', cache: 'no-store', headers: { 'Content-Type': 'application/json', 'X-Kairos': '1' }, body: body ? JSON.stringify(body) : undefined });
  const j = await r.json().catch(() => ({}));
  if (r.status === 401 && path !== '/admin/login') { showLogin(); throw new Error('Sessione scaduta, accedi di nuovo'); }
  if (!r.ok) throw new Error(j.error || 'Errore ' + r.status);
  return j;
}
function rndCode() { const A = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789', r = () => A[Math.floor(Math.random() * A.length)]; return 'KX' + r() + r() + '-' + r() + r() + r() + r(); }
function statusOf(d) { if (!d.active) return d.playlists.length ? 'disabled' : 'pending'; if (d.expires && new Date(d.expires) < new Date()) return 'expired'; return 'active'; }
const DEMO = (() => {
  const names = ['Marco Rossi', 'Giulia Bianchi', 'Luca Ferrari', 'Sara Romano', 'Paolo Colombo', 'Elena Ricci', 'Andrea Marino', 'Chiara Greco', 'Davide Bruno', 'Francesca Gallo', 'Matteo Conti', 'Alessia De Luca', 'Simone Costa', 'Martina Giordano', 'Stefano Mancini', 'Valentina Rizzo', 'Fabio Lombardi', 'Laura Moretti', 'Bar Centrale', 'Hotel Belvedere', 'Roberto Barbieri', 'Silvia Fontana'];
  const models = ['AFTKA Fire TV Stick 4K', 'Android TV · Chromecast', 'Android 13 · Galaxy Tab', 'AFTMM Fire TV Cube', 'Google TV · TCL', 'Android 14 · Pixel 8', 'Fire TV Stick Lite', 'Android TV · Xiaomi Box'];
  const now = Date.now(), day = 864e5;
  const devices = names.map((n, i) => {
    const created = now - Math.floor(Math.pow(Math.random(), 1.6) * 88 + 1) * day;
    const exp = i % 7 === 3 ? new Date(now - 3 * day) : i % 5 === 1 ? new Date(now + (2 + i) * day) : new Date(now + (30 + i * 11) * day);
    return { bound: true, id: 'd' + i, code: rndCode(), name: n, model: models[i % models.length], notes: '', active: i % 9 !== 4, expires: exp.toISOString().slice(0, 10), playlists: [{ id: 'p' + i, name: 'Principale', type: i % 2 ? 'xtream' : 'm3u', url: 'http://esempio.tv/lista.m3u', host: 'http://esempio.tv:8080', username: 'user' + i, password: '••••', epg: '' }], createdAt: created, lastSeen: now - Math.floor(Math.random() * 60) * 3600e3 };
  });
  for (let i = 0; i < 3; i++) devices.unshift({ id: 'n' + i, code: rndCode(), name: '', model: models[(i + 3) % models.length], notes: '', active: false, expires: '', playlists: [], createdAt: now - i * 3600e3, lastSeen: now - i * 600e3 });
  const activity = [
    { t: now - 120e3, type: 'new', text: `Nuovo dispositivo in attesa: ${devices[0].code} (${devices[0].model})` },
    { t: now - 3600e3, type: 'on', text: 'Giulia Bianchi: pending → active' },
    { t: now - 7200e3, type: 'edit', text: 'Modificato Hotel Belvedere' },
    { t: now - 5 * 3600e3, type: 'settings', text: 'Impostazioni aggiornate' },
    { t: now - 9 * 3600e3, type: 'off', text: 'Bar Centrale: active → disabled' },
    { t: now - 26 * 3600e3, type: 'auth', text: 'Accesso al pannello' },
  ];
  return { settings: { brand: 'KAIROS', tagline: 'Next-gen streaming', accent: 'cinema', announcement: 'Benvenuto su KAIROS — buona visione!', allowLocalPlaylists: true, showFootball: true, serverUrl: '', apkVersionCode: 112, supportContact: 'WhatsApp +39 333 000 0000', apkUrl: 'https://github.com/tuo-utente/kairos-iptv/releases/latest/download/kairos.apk', downloaderCode: '1234567' }, devices, activity };
})();
async function demoApi(method, path, body) {
  await new Promise((r) => setTimeout(r, 160));
  const pub = (d) => ({ ...d, status: statusOf(d) });
  const log = (type, text) => DEMO.activity.unshift({ t: Date.now(), type, text });
  if (path === '/admin/login') return { ok: true, user: body.user || 'admin' };
  if (path === '/admin/state') return { settings: DEMO.settings, devices: DEMO.devices.map(pub), activity: DEMO.activity, user: 'admin', security: DEMO.sec || (DEMO.sec = { totp: false, encryption: true, access: true, turnstile: true, sessions: 1 }) };
  if (path === '/admin/totp/setup') return { secret: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP', uri: 'otpauth://totp/KAIROS:admin?secret=JBSWY3DPEHPK3PXP' };
  if (path === '/admin/totp/enable') { DEMO.sec.totp = true; return { ok: true }; }
  if (path === '/admin/totp/disable') { DEMO.sec.totp = false; return { ok: true }; }
  if (path === '/admin/logout' || path === '/admin/logout-all') return { ok: true };
  if (/\/reset$/.test(path)) { const d = DEMO.devices.find((x) => path.includes(x.id)); d.bound = false; return { ok: true }; }
  if (path === '/admin/settings') { Object.assign(DEMO.settings, body); log('settings', 'Impostazioni aggiornate'); return { settings: DEMO.settings }; }
  if (path === '/admin/password') return { ok: true };
  if (path === '/admin/devices' && method === 'POST') { const d = { id: 'x' + Date.now(), code: body.code || rndCode(), createdAt: Date.now(), lastSeen: 0, model: '', ...body }; DEMO.devices.unshift(d); log('new', 'Dispositivo creato: ' + (d.name || d.code)); return pub(d); }
  const m = path.match(/^\/admin\/devices\/(.+)$/);
  if (m) { const d = DEMO.devices.find((x) => x.id === m[1]); if (method === 'DELETE') { DEMO.devices = DEMO.devices.filter((x) => x !== d); log('del', 'Eliminato ' + (d.name || d.code)); return { ok: 1 }; } const was = statusOf(d); Object.assign(d, body); const now = statusOf(d); log(was !== now ? (now === 'active' ? 'on' : 'off') : 'edit', was !== now ? `${d.name || d.code}: ${was} → ${now}` : 'Modificato ' + (d.name || d.code)); return pub(d); }
  throw new Error('demo');
}

/* ---------------- toast / modal ---------------- */
function toast(t, bad) { const e = document.createElement('div'); e.className = 'toast' + (bad ? ' bad' : ''); e.innerHTML = `<i></i>${esc(t)}`; $('#toasts').appendChild(e); setTimeout(() => { e.style.transition = '.4s'; e.style.opacity = 0; e.style.transform = 'translateX(30px)'; setTimeout(() => e.remove(), 400); }, 2800); }
function ask(title, body, yes = 'Conferma') { return new Promise((res) => { $('#mT').textContent = title; $('#mB').innerHTML = body; $('#mYes').textContent = yes; $('#modal').classList.add('on'); const done = (v) => { $('#modal').classList.remove('on'); res(v); }; $('#mYes').onclick = () => done(true); $('#mNo').onclick = () => done(false); }); }

/* ---------------- login ---------------- */
$('#lform').addEventListener('submit', async (e) => {
  e.preventDefault(); $('#lerr').textContent = '';
  try {
    const j = await api('POST', '/admin/login', { user: $('#lu').value.trim(), pass: $('#lp').value, totp: $('#lt').value.trim(), turnstile: tsToken() });
    if (j.needTotp) { $('#lt').style.display = 'block'; $('#lt').focus(); $('#lerr').innerHTML = '<span class="mut">Inserisci il codice dell\'app di autenticazione</span>'; return; }
    $('#lp').value = ''; $('#lt').value = ''; await enter();
  } catch (err) { tsReset(); $('#lerr').textContent = D.demo ? '' : err.message; $('.lcard').animate([{ transform: 'translateX(0)' }, { transform: 'translateX(-10px)' }, { transform: 'translateX(10px)' }, { transform: 'translateX(0)' }], { duration: 300 }); }
});
let poll;
function showLogin() { clearInterval(poll); $('#app').classList.remove('on'); $('#login').style.display = 'grid'; $('#lt').style.display = 'none'; tsReset(); }
async function logout() { try { await api('POST', '/admin/logout'); } catch {} showLogin(); }
$('#logout').onclick = logout;

/* ---------------- Turnstile (anti-bot) ---------------- */
let tsId = null;
function tsToken() { try { return tsId !== null && window.turnstile ? window.turnstile.getResponse(tsId) || '' : ''; } catch { return ''; } }
function tsReset() { try { tsId !== null && window.turnstile && window.turnstile.reset(tsId); } catch {} }
function loadTurnstile(key) {
  if (!key) return;
  const sc = document.createElement('script');
  sc.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
  sc.async = true;
  sc.onload = () => { tsId = window.turnstile.render('#ts', { sitekey: key, theme: 'dark', language: 'it' }); };
  document.head.appendChild(sc);
}

async function load() {
  const s = await api('GET', '/admin/state');
  D.settings = s.settings; D.devices = s.devices; D.activity = s.activity; D.user = s.user; D.security = s.security || {};
  applyTheme(D.settings.accent);
  $$('.brandName').forEach((e) => (e.textContent = (D.settings.brand || 'KAIROS').toUpperCase()));
  $('#av').textContent = (D.user || 'A')[0].toUpperCase();
  const pend = D.devices.filter((d) => d.status === 'pending').length;
  $('#pendB').style.display = pend ? '' : 'none'; $('#pendB').textContent = pend;
  renderAll();
}
async function enter() {
  await load();
  $('#login').style.display = 'none'; $('#app').classList.add('on'); requestAnimationFrame(drawChart);
  $('#copyLink').onclick = () => { const l = D.settings.apkUrl; if (!l) return toast('Imposta il link APK in Impostazioni', 1); navigator.clipboard.writeText(l).then(() => toast('Link copiato'), () => toast('Copia non consentita', 1)); };
  clearInterval(poll);
  if (!D.demo) poll = setInterval(() => load().catch(() => {}), 20000);
}
function renderSide() {
  $('#dlCode').textContent = D.settings.downloaderCode ? 'Downloader ' + D.settings.downloaderCode : '';
  $('#plLink').textContent = D.settings.apkUrl || 'Link APK non impostato';
}
function renderAll() { renderSide(); renderSecurity(); renderDash(); renderDevices(); renderFeed(); renderSettings(); }

/* ---------------- navigazione ---------------- */
const TITLES = { dash: 'Dashboard', devices: 'Dispositivi', activity: 'Attività', settings: 'Impostazioni' };
function view(v) { $$('.nav[data-v]').forEach((n) => n.classList.toggle('on', n.dataset.v === v)); $$('.view').forEach((x) => x.classList.toggle('on', x.id === 'v-' + v)); $('#vTitle').textContent = TITLES[v]; $('#side').classList.remove('open'); if (v === 'dash') renderDash(); }
$$('.nav[data-v]').forEach((n) => (n.onclick = () => view(n.dataset.v)));
$('#menuB').onclick = () => $('#side').classList.toggle('open');
$('#gq').addEventListener('input', (e) => { D.q = e.target.value.toLowerCase(); if (!$('#v-devices').classList.contains('on')) view('devices'); renderDevices(); });

/* ---------------- helpers ---------------- */
const daysLeft = (d) => (d.expires ? Math.ceil((new Date(d.expires) - Date.now()) / 864e5) : null);
const ago = (t) => { if (!t) return 'mai'; const s = (Date.now() - t) / 1000; if (s < 90) return 'adesso'; if (s < 3600) return Math.floor(s / 60) + ' min fa'; if (s < 86400) return Math.floor(s / 3600) + ' h fa'; return Math.floor(s / 86400) + ' g fa'; };
const fmtD = (d) => (d ? new Date(d).toLocaleDateString('it-IT', { day: '2-digit', month: 'short', year: 'numeric' }) : 'Illimitata');
const stPill = (s) => `<span class="st ${s}"><i></i>${STL[s]}</span>`;
function countUp(el, to) { const t0 = performance.now(), from = 0; const f = (t) => { const p = Math.min(1, (t - t0) / 900), e = 1 - Math.pow(1 - p, 3); el.textContent = Math.round(from + (to - from) * e); if (p < 1) requestAnimationFrame(f); }; requestAnimationFrame(f); }
function spark(vals, color) { const max = Math.max(1, ...vals), w = 200, h = 60; const pts = vals.map((v, i) => [i * (w / (vals.length - 1 || 1)), h - 6 - (v / max) * (h - 16)]); const d = pts.map((p, i) => (i ? 'L' : 'M') + p[0].toFixed(1) + ' ' + p[1].toFixed(1)).join(' '); const id = 'sg' + Math.random().toString(36).slice(2, 7); return `<svg class="sl" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none"><defs><linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${color}" stop-opacity=".45"/><stop offset="1" stop-color="${color}" stop-opacity="0"/></linearGradient></defs><path d="${d} L${w} ${h} L0 ${h}Z" fill="url(#${id})"/><path d="${d}" fill="none" stroke="${color}" stroke-width="2"/></svg>`; }
function series(days, pred) { const out = [], now = Date.now(); for (let i = days - 1; i >= 0; i--) { const end = now - i * 864e5; out.push(D.devices.filter((d) => d.createdAt <= end && pred(d)).length); } return out; }

/* ---------------- dashboard ---------------- */
function renderDash() {
  const by = (s) => D.devices.filter((d) => d.status === s).length;
  const soon = D.devices.filter((d) => d.status === 'active' && daysLeft(d) !== null && daysLeft(d) <= 14);
  const online = D.devices.filter((d) => Date.now() - d.lastSeen < 24 * 3600e3).length;
  const k = [
    ['Dispositivi attivi', by('active'), `su ${D.devices.length} totali`, 'var(--ok)', series(14, (d) => d.status === 'active')],
    ['In attesa', by('pending'), 'da attivare', 'var(--warn)', series(14, (d) => d.status === 'pending')],
    ['In scadenza', soon.length, 'entro 14 giorni', 'var(--a3)', series(14, () => true).map((v, i, a) => (i ? v - a[i - 1] : 0))],
    ['Visti nelle 24h', online, 'player che si sono collegati', 'var(--a1)', series(14, (d) => d.lastSeen > Date.now() - 7 * 864e5)],
  ];
  const kc = getComputedStyle(document.documentElement);
  const col = (c) => (c.startsWith('var') ? kc.getPropertyValue(c.slice(4, -1)).trim() : c);
  $('#kpis').innerHTML = k.map(([l, v, s, c, sp]) => `<div class="kpi glass"><div class="halo" style="background:${c}"></div><div class="lb"><i style="background:${c};box-shadow:0 0 10px ${c}"></i>${l}</div><div class="v" data-v="${v}">0</div><div class="s">${s}</div>${spark(sp, col(c))}</div>`).join('');
  $$('#kpis .v').forEach((e) => countUp(e, +e.dataset.v));
  // donut
  const parts = ['active', 'pending', 'expired', 'disabled'].map((s) => [s, by(s)]), tot = Math.max(1, D.devices.length);
  let acc = 0; const R = 70, C = 2 * Math.PI * R;
  $('#donut').innerHTML = `<svg viewBox="0 0 180 180"><circle cx="90" cy="90" r="${R}" fill="none" stroke="rgba(255,255,255,.06)" stroke-width="18"/>${parts.map(([s, n]) => { const len = (n / tot) * C, el = `<circle cx="90" cy="90" r="${R}" fill="none" stroke="${col(STC[s])}" stroke-width="18" stroke-dasharray="0 ${C}" stroke-dashoffset="${-acc}" transform="rotate(-90 90 90)" stroke-linecap="${n ? 'round' : 'butt'}" data-len="${Math.max(0, len - 4)}" style="transition:stroke-dasharray 1.1s cubic-bezier(.2,.8,.2,1);filter:drop-shadow(0 0 6px ${col(STC[s])})"/>`; acc += len; return el; }).join('')}<text x="90" y="88" text-anchor="middle" fill="#fff" font-family="Orbitron" font-weight="700" font-size="34">${D.devices.length}</text><text x="90" y="110" text-anchor="middle" fill="${col('var(--mut)')}" font-size="12">dispositivi</text></svg><div class="legend">${parts.map(([s, n]) => `<div><i style="background:${STC[s]}"></i>${STL[s]}<b>${n}</b></div>`).join('')}</div>`;
  requestAnimationFrame(() => setTimeout(() => $$('#donut circle[data-len]').forEach((c) => c.setAttribute('stroke-dasharray', `${c.dataset.len} ${C}`)), 50));
  // liste
  const pend = D.devices.filter((d) => d.status === 'pending').slice(0, 6);
  $('#pendList').innerHTML = pend.length ? pend.map((d) => `<div class="mini"><div class="ic" style="color:var(--warn)"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8"/></svg></div><div class="tx"><b class="mono">${esc(d.code)}</b><small>${esc(d.model || 'Dispositivo sconosciuto')} · ${ago(d.lastSeen)}</small></div><button class="btn sm pri" data-open="${d.id}">Attiva</button></div>`).join('') : '<div class="emptyc">Nessun dispositivo in attesa ✓</div>';
  $('#expList').innerHTML = soon.length ? soon.sort((a, b) => daysLeft(a) - daysLeft(b)).slice(0, 6).map((d) => `<div class="mini"><div class="ic" style="color:var(--a3)"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg></div><div class="tx"><b>${esc(d.name || d.code)}</b><small>scade ${fmtD(d.expires)} · tra ${daysLeft(d)} g</small></div><button class="btn sm" data-ext="${d.id}">+1 mese</button></div>`).join('') : '<div class="emptyc">Nessuna scadenza imminente</div>';
  $$('[data-open]').forEach((b) => (b.onclick = () => openDrawer(D.devices.find((d) => d.id === b.dataset.open), true)));
  $$('[data-ext]').forEach((b) => (b.onclick = () => extend(D.devices.find((d) => d.id === b.dataset.ext), 1)));
  drawChart();
}
function drawChart() {
  const cv = $('#chart'); if (!cv.offsetParent) return;
  const dpr = devicePixelRatio || 1, W = cv.clientWidth, H = 260;
  cv.width = W * dpr; cv.height = H * dpr; const x = cv.getContext('2d'); x.scale(dpr, dpr);
  const n = D.range, tot = series(n, () => true), act = series(n, (d) => d.status === 'active');
  const max = Math.max(4, ...tot) * 1.15, pad = { l: 34, r: 10, t: 14, b: 26 };
  const cs = getComputedStyle(document.documentElement), a1 = cs.getPropertyValue('--a1').trim(), a2 = cs.getPropertyValue('--a2').trim(), a3 = cs.getPropertyValue('--a3').trim();
  const X = (i) => pad.l + (i / (n - 1)) * (W - pad.l - pad.r), Y = (v) => pad.t + (1 - v / max) * (H - pad.t - pad.b);
  let prog = 0; const t0 = performance.now();
  function frame(t) {
    prog = Math.min(1, (t - t0) / 1100); const e = 1 - Math.pow(1 - prog, 3);
    x.clearRect(0, 0, W, H);
    x.strokeStyle = 'rgba(255,255,255,.06)'; x.fillStyle = 'rgba(139,147,184,.8)'; x.font = '11px Space Grotesk'; x.lineWidth = 1;
    for (let g = 0; g <= 4; g++) { const v = (max / 4) * g, y = Y(v); x.beginPath(); x.moveTo(pad.l, y); x.lineTo(W - pad.r, y); x.stroke(); x.fillText(Math.round(v), 4, y + 4); }
    const step = Math.ceil(n / 6);
    for (let i = 0; i < n; i += step) { const d = new Date(Date.now() - (n - 1 - i) * 864e5); x.fillText(d.toLocaleDateString('it-IT', { day: '2-digit', month: 'short' }), X(i) - 16, H - 6); }
    const line = (arr, c1, c2, fill) => {
      x.save(); x.beginPath(); x.rect(0, 0, pad.l + (W - pad.l) * e, H); x.clip();
      x.beginPath(); arr.forEach((v, i) => { const px = X(i), py = Y(v); if (!i) x.moveTo(px, py); else { const qx = X(i - 1), qy = Y(arr[i - 1]); x.bezierCurveTo((qx + px) / 2, qy, (qx + px) / 2, py, px, py); } });
      const lg = x.createLinearGradient(0, 0, W, 0); lg.addColorStop(0, c1); lg.addColorStop(1, c2);
      x.strokeStyle = lg; x.lineWidth = 2.5; x.shadowColor = c1; x.shadowBlur = 14; x.stroke(); x.shadowBlur = 0;
      if (fill) { x.lineTo(X(n - 1), H - pad.b); x.lineTo(X(0), H - pad.b); x.closePath(); const fg = x.createLinearGradient(0, pad.t, 0, H); fg.addColorStop(0, c1 + '55'); fg.addColorStop(1, c1 + '00'); x.fillStyle = fg; x.fill(); }
      x.restore();
    };
    line(tot, a1, a2, true); line(act, a3, a2, false);
    if (prog >= 1) { const px = X(n - 1), py = Y(tot[n - 1]); x.fillStyle = a1; x.shadowColor = a1; x.shadowBlur = 16; x.beginPath(); x.arc(px, py, 5, 0, 7); x.fill(); x.shadowBlur = 0; }
    if (prog < 1) requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);
}
$$('#rng button').forEach((b) => (b.onclick = () => { D.range = +b.dataset.r; $$('#rng button').forEach((x) => x.classList.toggle('on', x === b)); drawChart(); }));
addEventListener('resize', () => drawChart());

/* ---------------- dispositivi ---------------- */
function renderDevices() {
  const cnt = (s) => (s === 'all' ? D.devices.length : D.devices.filter((d) => d.status === s).length);
  $('#filters').innerHTML = ['all', 'active', 'pending', 'expired', 'disabled'].map((s) => `<button class="fchip ${D.filter === s ? 'on' : ''}" data-fl="${s}">${s === 'all' ? 'Tutti' : STL[s]}<span>${cnt(s)}</span></button>`).join('');
  $$('[data-fl]').forEach((b) => (b.onclick = () => { D.filter = b.dataset.fl; renderDevices(); }));
  const list = D.devices.filter((d) => (D.filter === 'all' || d.status === D.filter) && (!D.q || [d.code, d.name, d.notes, d.model].join(' ').toLowerCase().includes(D.q)));
  $('#noRows').style.display = list.length ? 'none' : 'block';
  $('#rows').innerHTML = list.map((d, i) => {
    const dl = daysLeft(d);
    return `<tr data-id="${d.id}" style="animation-delay:${Math.min(i, 20) * 25}ms">
      <td><span class="code">${esc(d.code)}</span></td>
      <td><b>${esc(d.name) || '<span class="mut">— senza nome —</span>'}</b>${d.notes ? `<span class="days">${esc(d.notes.slice(0, 40))}</span>` : ''}</td>
      <td class="hm mut">${esc(d.model || '—')}</td>
      <td>${stPill(d.status)}</td>
      <td class="hm">${fmtD(d.expires)}${dl !== null ? `<span class="days" style="color:${dl < 0 ? 'var(--bad)' : dl <= 14 ? 'var(--warn)' : ''}">${dl < 0 ? 'scaduto da ' + -dl + ' g' : 'tra ' + dl + ' g'}</span>` : ''}</td>
      <td class="hm">${d.playlists.length}</td>
      <td class="hm mut">${ago(d.lastSeen)}</td>
      <td>${d.status === 'pending' ? `<button class="btn sm pri" data-act="open">Attiva</button>` : `<button class="icb" title="+1 mese" data-act="ext"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 5v14M5 12h14"/></svg></button><button class="icb" title="${d.active ? 'Disattiva' : 'Riattiva'}" data-act="tog"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2v10"/><path d="M18.4 6.6a9 9 0 1 1-12.8 0"/></svg></button>`}<button class="icb" title="Modifica" data-act="open"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/></svg></button></td></tr>`;
  }).join('');
}
$('#rows').addEventListener('click', async (e) => {
  const tr = e.target.closest('tr'); if (!tr) return;
  const d = D.devices.find((x) => x.id === tr.dataset.id), act = e.target.closest('[data-act]')?.dataset.act;
  if (act === 'ext') return extend(d, 1);
  if (act === 'tog') { await save(d, { active: !d.active }); return toast(d.active ? 'Dispositivo disattivato' : 'Dispositivo riattivato'); }
  openDrawer(d, d.status === 'pending');
});
function addMonths(base, m) { const b = base && new Date(base) > new Date() ? new Date(base) : new Date(); b.setMonth(b.getMonth() + m); return b.toISOString().slice(0, 10); }
async function extend(d, m) { await save(d, { expires: addMonths(d.expires, m), active: true }); toast(`${d.name || d.code}: +${m} ${m === 1 ? 'mese' : 'mesi'}`); }
async function save(d, patch) { try { await api('PUT', '/admin/devices/' + d.id, patch); await load(); } catch (e) { toast(e.message, 1); } }

/* ---------------- drawer ---------------- */
let edit = null, isNew = false;
function openDrawer(d, activating) {
  isNew = !d;
  edit = d ? JSON.parse(JSON.stringify(d)) : { code: '', name: '', notes: '', active: true, expires: addMonths('', 12), playlists: [] };
  if (activating) { edit.active = true; if (!edit.expires) edit.expires = addMonths('', 12); if (!edit.playlists.length) edit.playlists.push({ id: 'p' + Date.now(), name: 'Principale', type: 'xtream', url: '', host: '', username: '', password: '' }); }
  $('#dTitle').innerHTML = isNew ? 'Nuovo dispositivo' : `${esc(edit.name || 'Dispositivo')} <span class="code" style="margin-left:6px">${esc(edit.code)}</span>`;
  $('#dAv').textContent = (edit.name || 'K')[0].toUpperCase();
  $('#dDel').style.display = isNew ? 'none' : '';
  renderDrawer();
  $('#shade').classList.add('on'); $('#drawer').classList.add('on');
}
function closeDrawer() { $('#shade').classList.remove('on'); $('#drawer').classList.remove('on'); }
$('#shade').onclick = closeDrawer; $('#dClose').onclick = closeDrawer; $('#dCancel').onclick = closeDrawer;
addEventListener('keydown', (e) => { if (e.key === 'Escape') { closeDrawer(); $('#modal').classList.remove('on'); } });
function renderDrawer() {
  $('#dBody').innerHTML = `
    ${isNew ? `<label class="lbl">Codice dispositivo</label><input class="inp mono" id="eCode" placeholder="Vuoto = generato, oppure quello mostrato dall'app (KXAB-12CD)" value="${esc(edit.code)}">` : `<div class="tog" style="cursor:default"><div><b>${edit.bound ? '🔒 Collegato al dispositivo' : '🔓 Non ancora collegato'}</b><small>${edit.bound ? 'Il codice funziona solo sul dispositivo che l\'ha generato. Se il cliente cambia TV o reinstalla l\'app, sblocca il collegamento.' : 'Il primo dispositivo che usa questo codice verrà collegato.'}</small></div>${edit.bound ? '<button class="btn sm" id="eReset">Sblocca</button>' : ''}</div>`}
    <label class="lbl">Nome cliente</label><input class="inp" id="eName" placeholder="es. Mario Rossi" value="${esc(edit.name)}">
    <label class="lbl">Abbonamento</label>
    <div class="tog ${edit.active ? 'on' : ''}" id="eAct"><div><b>Servizio attivo</b><small>Se spento il player mostra “dispositivo disattivato”</small></div><i></i></div>
    <div class="row"><input class="inp" type="date" id="eExp" value="${esc(edit.expires)}"><button class="btn" id="eInf">${edit.expires ? 'Rendi illimitato' : 'Illimitato ✓'}</button></div>
    <div class="quick">${[1, 3, 6, 12].map((m) => `<button class="btn sm" data-m="${m}">+${m} ${m === 1 ? 'mese' : 'mesi'}</button>`).join('')}</div>
    <label class="lbl">Playlist assegnate</label>
    <div id="pls">${edit.playlists.map((p, i) => `<div class="pl" data-i="${i}">
      <div class="ph"><input class="inp" data-k="name" value="${esc(p.name)}" placeholder="Nome"><div class="seg"><button class="${p.type === 'm3u' ? 'on' : ''}" data-t="m3u">M3U</button><button class="${p.type === 'xtream' ? 'on' : ''}" data-t="xtream">Xtream</button></div><button class="icb" data-rm><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14"/></svg></button></div>
      ${p.type === 'm3u' ? `<input class="inp mono" data-k="url" value="${esc(p.url)}" placeholder="http://…/get.php?…&type=m3u_plus">` : `<input class="inp mono" data-k="host" value="${esc(p.host)}" placeholder="http://host:porta"><div class="row"><input class="inp" data-k="username" value="${esc(p.username)}" placeholder="Username"><input class="inp" data-k="password" value="${esc(p.password)}" placeholder="Password"></div>`}
    </div>`).join('')}</div>
    <button class="btn" id="addPl" style="width:100%;border-style:dashed">+ Aggiungi playlist</button>
    <label class="lbl">Note interne</label><textarea class="inp" id="eNotes" placeholder="Pagamento, contatto, dettagli…">${esc(edit.notes)}</textarea>
    ${!isNew ? `<p class="mut" style="font-size:12px;margin-top:14px">Registrato ${fmtD(edit.createdAt)} · ultimo accesso ${ago(edit.lastSeen)}${edit.model ? ' · ' + esc(edit.model) : ''}</p>` : ''}`;
  $('#eReset') && ($('#eReset').onclick = async () => { if (await ask('Sbloccare il collegamento?', 'Il prossimo dispositivo che usa questo codice verrà collegato al suo posto.', 'Sblocca')) { try { await api('POST', '/admin/devices/' + edit.id + '/reset'); edit.bound = false; renderDrawer(); await load(); toast('Collegamento sbloccato'); } catch (e) { toast(e.message, 1); } } });
  const sync = () => { edit.name = $('#eName').value; edit.notes = $('#eNotes').value; edit.expires = $('#eExp').value; if ($('#eCode')) edit.code = $('#eCode').value.toUpperCase(); $$('#pls .pl').forEach((el) => { const p = edit.playlists[+el.dataset.i]; $$('[data-k]', el).forEach((i) => (p[i.dataset.k] = i.value)); }); };
  $('#eAct').onclick = () => { edit.active = !edit.active; $('#eAct').classList.toggle('on', edit.active); };
  $('#eInf').onclick = () => { sync(); edit.expires = ''; renderDrawer(); };
  $$('[data-m]').forEach((b) => (b.onclick = () => { sync(); edit.expires = addMonths(edit.expires, +b.dataset.m); edit.active = true; renderDrawer(); }));
  $('#addPl').onclick = () => { sync(); edit.playlists.push({ id: 'p' + Date.now(), name: 'Playlist ' + (edit.playlists.length + 1), type: 'm3u', url: '', host: '', username: '', password: '' }); renderDrawer(); };
  $$('#pls .pl').forEach((el) => { const i = +el.dataset.i; $$('[data-t]', el).forEach((b) => (b.onclick = () => { sync(); edit.playlists[i].type = b.dataset.t; renderDrawer(); })); $('[data-rm]', el).onclick = () => { sync(); edit.playlists.splice(i, 1); renderDrawer(); }; });
  $('#dSave').onclick = async () => {
    sync();
    try {
      if (isNew) await api('POST', '/admin/devices', edit); else await api('PUT', '/admin/devices/' + edit.id, { name: edit.name, notes: edit.notes, expires: edit.expires, active: edit.active, playlists: edit.playlists });
      closeDrawer(); await load(); toast(isNew ? 'Dispositivo creato' : 'Modifiche salvate');
    } catch (e) { toast(e.message, 1); }
  };
  $('#dDel').onclick = async () => { if (await ask('Eliminare il dispositivo?', `Il codice <b class="mono">${esc(edit.code)}</b> verrà rimosso e il player tornerà alla schermata di attivazione.`, 'Elimina')) { await api('DELETE', '/admin/devices/' + edit.id); closeDrawer(); await load(); toast('Eliminato'); } };
}
$('#newDev').onclick = () => openDrawer(null);

/* ---------------- attività ---------------- */
const AIC = { new: ['var(--warn)', 'M12 5v14M5 12h14'], on: ['var(--ok)', 'M5 12l5 5L20 7'], off: ['var(--bad)', 'M18 6 6 18M6 6l12 12'], edit: ['var(--a1)', 'M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z'], del: ['var(--bad)', 'M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14'], settings: ['var(--a2)', 'M4 6h16M4 12h16M4 18h16'], auth: ['var(--a3)', 'M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M15 12H3'] };
function renderFeed() {
  $('#feed').innerHTML = D.activity.length ? D.activity.map((a) => { const [c, p] = AIC[a.type] || AIC.edit; return `<div class="mini"><div class="ic" style="color:${c}"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="${p}"/></svg></div><div class="tx"><b>${esc(a.text)}</b><small>${new Date(a.t).toLocaleString('it-IT', { dateStyle: 'medium', timeStyle: 'short' })} · ${ago(a.t)}</small></div></div>`; }).join('') : '<div class="emptyc">Nessuna attività</div>';
}

/* ---------------- impostazioni ---------------- */
let tmpAccent = 'cinema';
function renderSettings() {
  const s = D.settings; tmpAccent = s.accent || 'cinema';
  $('#sBrand').value = s.brand || ''; $('#sTag').value = s.tagline || ''; $('#sAnn').value = s.announcement || ''; $('#sSup').value = s.supportContact || '';
  $('#sLocal').classList.toggle('on', !!s.allowLocalPlaylists); $('#sApk').value = s.apkUrl || ''; $('#sDl').value = s.downloaderCode || '';
  $('#sFoot').classList.toggle('on', s.showFootball !== false); $('#sSrv').value = s.serverUrl || ''; $('#sVer').value = s.apkVersionCode || '';
  $('#aUser').value = D.user;
  $('#themes').innerHTML = Object.entries(THEMES).map(([n, c]) => `<button class="th ${n === tmpAccent ? 'on' : ''}" data-th="${n}"><div class="sw" style="background:linear-gradient(120deg,${c.join(',')})"></div><b>${n}</b></button>`).join('');
  $$('[data-th]').forEach((b) => (b.onclick = () => { tmpAccent = b.dataset.th; $$('[data-th]').forEach((x) => x.classList.toggle('on', x === b)); applyTheme(tmpAccent); preview(); drawChart(); }));
  preview();
}
function preview() { const c = THEMES[tmpAccent]; $('#tvA').style.background = `radial-gradient(circle at 20% 30%,${c[0]},transparent 45%),radial-gradient(circle at 80% 50%,${c[1]},transparent 45%),radial-gradient(circle at 50% 100%,${c[2]},transparent 45%)`; $('#tvN').textContent = ($('#sBrand').value || 'KAIROS').toUpperCase(); $('#tvT').textContent = $('#sAnn').value || '—'; }
['#sBrand', '#sAnn'].forEach((s) => $(s).addEventListener('input', preview));
$('#sLocal').onclick = () => $('#sLocal').classList.toggle('on');
$('#sFoot').onclick = () => $('#sFoot').classList.toggle('on');
$('#sSave').onclick = async () => {
  try { const r = await api('PUT', '/admin/settings', { brand: $('#sBrand').value.trim() || 'KAIROS', tagline: $('#sTag').value, accent: tmpAccent, announcement: $('#sAnn').value, supportContact: $('#sSup').value, allowLocalPlaylists: $('#sLocal').classList.contains('on'), apkUrl: $('#sApk').value.trim(), downloaderCode: $('#sDl').value.trim(), showFootball: $('#sFoot').classList.contains('on'), serverUrl: $('#sSrv').value.trim(), apkVersionCode: $('#sVer').value.trim() }); D.settings = r.settings; await load(); toast('Impostazioni salvate — i player si aggiornano al prossimo avvio'); } catch (e) { toast(e.message, 1); }
};
function renderSecurity() {
  const x = D.security || {};
  const row = (ok, t, d) => `<div class="sec"><i class="${ok ? 'y' : 'n'}">${ok ? '✓' : '!'}</i><div><b>${t}</b><small>${d}</small></div></div>`;
  $('#secList').innerHTML = [
    row(true, 'Password cifrate (PBKDF2)', 'Le password non sono mai salvate in chiaro'),
    row(true, 'Sessione protetta', 'Cookie sicuro, scadenza 12 ore, protezione CSRF e blocco dopo 5 tentativi falliti'),
    row(x.encryption, 'Playlist cifrate (AES-256)', x.encryption ? 'Le credenziali delle playlist sono cifrate nel database' : 'Manca il segreto ENC_KEY sul Worker'),
    row(x.totp, 'Verifica in due passaggi', x.totp ? 'Attiva sul tuo account' : 'Consigliata: attivala qui sotto'),
    row(x.turnstile, 'Anti-bot Turnstile', x.turnstile ? 'Attivo sulla pagina di accesso' : 'Non configurato (vedi guida)'),
    row(x.access, 'Cloudflare Access', x.access ? 'Il pannello è dietro Zero Trust' : 'Non configurato (vedi guida)'),
    row(true, 'Codici legati al dispositivo', 'Un codice copiato su un\'altra TV non funziona'),
  ].join('') + `<p class="mut" style="font-size:12px;margin-top:8px">Sessioni aperte: ${x.sessions ?? 1}</p>`;
  $('#totpBox').innerHTML = x.totp
    ? '<button class="btn sm danger" id="tOff">Disattiva verifica in due passaggi</button>'
    : '<button class="btn sm pri" id="tOn">Attiva verifica in due passaggi</button>';
  $('#tOn') && ($('#tOn').onclick = async () => {
    try {
      const r = await api('POST', '/admin/totp/setup');
      $('#totpBox').innerHTML = `<p class="mut" style="font-size:13px">Apri Google Authenticator, Microsoft Authenticator o Authy, scegli <b>Inserisci chiave</b> e scrivi questo codice:</p><div class="secret">${esc(r.secret.replace(/(.{4})/g, '$1 ').trim())}</div><div class="row"><input class="inp mono" id="tCode" inputmode="numeric" maxlength="6" placeholder="Codice a 6 cifre"><button class="btn pri" id="tConf">Conferma</button></div>`;
      $('#tConf').onclick = async () => { try { await api('POST', '/admin/totp/enable', { code: $('#tCode').value }); toast('Verifica in due passaggi attivata'); await load(); } catch (e) { toast(e.message, 1); } };
    } catch (e) { toast(e.message, 1); }
  });
  $('#tOff') && ($('#tOff').onclick = () => {
    $('#totpBox').innerHTML = '<div class="row"><input class="inp" type="password" id="tPw" placeholder="Password" autocomplete="current-password"><input class="inp mono" id="tCode" maxlength="6" placeholder="Codice 2FA"></div><button class="btn sm danger" id="tOffC">Conferma disattivazione</button>';
    $('#tOffC').onclick = async () => { try { await api('POST', '/admin/totp/disable', { password: $('#tPw').value, code: $('#tCode').value }); toast('Verifica in due passaggi disattivata'); await load(); } catch (e) { toast(e.message, 1); } };
  });
}
$('#killSes').onclick = async () => { try { await api('POST', '/admin/logout-all'); toast('Altre sessioni chiuse'); await load(); } catch (e) { toast(e.message, 1); } };
$('#aSave').onclick = async () => { try { await api('PUT', '/admin/password', { user: $('#aUser').value.trim(), current: $('#aCur').value, next: $('#aNew').value }); $('#aCur').value = $('#aNew').value = ''; toast('Credenziali aggiornate'); } catch (e) { toast(e.message, 1); } };

/* ---------------- avvio ---------------- */
(async () => {
  // se non c'è un server Kairos dietro questa pagina → demo
  try {
    const c = await fetch('/api/public/config', { credentials: 'same-origin' });
    if (!c.ok || !(c.headers.get('content-type') || '').includes('json')) throw 0;
    loadTurnstile((await c.json()).turnstileSiteKey);
    const r = await fetch('/api/admin/state', { credentials: 'same-origin', headers: { 'X-Kairos': '1' } });
    if (r.ok) return enter();
  } catch { D.demo = true; $('#demoTag').style.display = 'block'; $('#lp').value = 'demo'; $('#lerr').innerHTML = '<span class="mut">Nessun server collegato: premi Accedi per esplorare la demo.</span>'; }
})();
