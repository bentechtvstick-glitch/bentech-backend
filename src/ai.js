import { visibleDevices } from "./visible.js";
import fs from "node:fs";
import path from "node:path";
// Asistan AI panel la (Claude Haiku): li gade eta panel la epi li reponn kesyon admin nan.
// Ekonomik: yon sèl demann pa kesyon, yon ti rezime done yo (pa tout baz done a), repons kout,
// epi yon limit depans pa mwa (AI_MONTHLY_LIMIT_USD, pa defo $2) pou pa janm gen sipriz.
// Kle a: ANTHROPIC_API_KEY nan /etc/galaxy.env (pa janm nan kòd la ni nan baz done a).

const MODEL = process.env.AI_MODEL || "claude-haiku-4-5";
// Kle AI a: ANTHROPIC_API_KEY nan /etc/galaxy.env, OSWA kle pwopriyetè a kole nan panel la
// (sove nan yon fichye prive bò kote baz done a, mode 600, PA nan db.json ni nan kopi done yo).
const KEY_DIR = path.dirname(path.resolve(process.env.DB_FILE || "./db.json"));
const KEY_FILE = path.join(KEY_DIR, ".anthropic-key"), KEY_FILE2 = path.join(KEY_DIR, ".anthropic-key-2");
let keyCache, key2Cache;
const readKey = (f) => { try { return fs.readFileSync(f, "utf8").trim(); } catch { return ""; } };
/** Kle prensipal la (oswa sa ki nan /etc/galaxy.env). */
function mainKey() {
  if (process.env.ANTHROPIC_API_KEY) return process.env.ANTHROPIC_API_KEY;
  if (keyCache === undefined) keyCache = readKey(KEY_FILE);
  return keyCache;
}
/** Kle rezèv la: panel la sèvi avè l otomatikman si kle prensipal la refize. */
function backupKey() { if (key2Cache === undefined) key2Cache = readKey(KEY_FILE2); return key2Cache; }
/** Tout kle yo, nan lòd: prensipal, answit rezèv. */
const allKeys = () => [mainKey(), backupKey()].filter(Boolean);
export function aiKey() { return allKeys()[0] || ""; }
function saveKey(k, slot = 1) {
  const f = slot === 2 ? KEY_FILE2 : KEY_FILE;
  if (k) fs.writeFileSync(f, k, { mode: 0o600 }); else { try { fs.unlinkSync(f); } catch {} }
  if (slot === 2) key2Cache = k || ""; else keyCache = k || "";
  keyHealth[slot === 2 ? "backup" : "main"] = k ? { ok: true, at: Date.now(), why: "" } : null;
}
/** Eta chak kle (panel la tcheke yo chak 6 è): { main: {ok, at, why}, backup: {…} } */
const keyHealth = { main: null, backup: null };
export const aiKeyHealth = () => ({ main: mainKey() ? keyHealth.main : null, backup: backupKey() ? keyHealth.backup : null, hasBackup: !!backupKey() });
/** Tcheke yon kle san depanse anyen (lis modèl yo). */
async function testKey(k) {
  try {
    const r = await fetch("https://api.anthropic.com/v1/models?limit=1", { headers: { "x-api-key": k, "anthropic-version": "2023-06-01" }, signal: AbortSignal.timeout(15000) });
    if (r.status === 401) return { ok: false, why: "refused" };
    if (r.status === 403) return { ok: false, why: "forbidden" };
    return { ok: true, why: "" };
  } catch { return { ok: true, why: "network" }; } // rezo a pa reponn: pa di kle a move
}
async function checkKeys() {
  for (const [slot, k] of [["main", mainKey()], ["backup", backupKey()]]) {
    if (!k) { keyHealth[slot] = null; continue; }
    const t = await testKey(k);
    keyHealth[slot] = { ok: t.ok, at: Date.now(), why: t.why };
  }
}
setTimeout(() => checkKeys().catch(() => {}), 60_000).unref?.();
setInterval(() => checkKeys().catch(() => {}), 6 * 3600_000).unref?.();
// Pri pa milyon token (Haiku 4.5): $1 antre, $5 sòti
const PRICE_IN = Number(process.env.AI_PRICE_IN || 1), PRICE_OUT = Number(process.env.AI_PRICE_OUT || 5);
const LANG_NAMES = { ht: "Haitian Creole (Kreyòl ayisyen)", en: "English", fr: "French", es: "Spanish" };

const month = () => new Date().toISOString().slice(0, 7);
const limit = () => Number(process.env.AI_MONTHLY_LIMIT_USD || 2);
function usageOf(db) {
  const u = (db.data.aiUsage ??= {});
  if (u.month !== month()) Object.assign(u, { month: month(), inTok: 0, outTok: 0, cost: 0, asks: 0 });
  return u;
}
export const aiLangName = (code) => LANG_NAMES[code] || "English";
export const aiBudget = (db) => { const u = usageOf(db); return { cost: u.cost, limit: limit(), left: limit() - u.cost }; };

/** Yon sèl apèl Claude (konte depans lan, respekte limit mwa a). Voye tèks repons lan, oswa jete yon erè. */
export async function aiComplete(db, { system, user, maxTokens = 600 }) {
  const keys = allKeys();
  if (!keys.length) throw new Error("AI is not set up yet: paste the AI key in Dashboard → 🔑 AI key.");
  const u = usageOf(db);
  if (u.cost >= limit()) throw Object.assign(new Error(`Monthly AI limit reached ($${limit()}).`), { status: 429 });
  let r, j, lastErr = null;
  // Si kle prensipal la refize (efase, dezaktive, pa gen kredi), eseye kle rezèv la otomatikman
  for (let i = 0; i < keys.length; i++) {
    r = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: { "x-api-key": keys[i], "anthropic-version": "2023-06-01", "content-type": "application/json" },
      body: JSON.stringify({ model: MODEL, max_tokens: maxTokens, system, messages: [{ role: "user", content: user }] }),
      signal: AbortSignal.timeout(60000),
    });
    j = await r.json().catch(() => ({}));
    const slot = keys[i] === mainKey() ? "main" : "backup";
    const credit = /credit|balance|billing/i.test(j?.error?.message || "");
    if (r.ok) { keyHealth[slot] = { ok: true, at: Date.now(), why: "" }; lastErr = null; break; }
    if (r.status === 401 || r.status === 403 || credit) { keyHealth[slot] = { ok: false, at: Date.now(), why: credit ? "credit" : r.status === 401 ? "refused" : "forbidden" }; lastErr = { status: r.status, credit, msg: j?.error?.message }; continue; }
    lastErr = { status: r.status, credit, msg: j?.error?.message };
    break;
  }
  if (lastErr) {
    // Mesaj klè pou admin nan olye de mesaj teknik Anthropic la
    const msg = lastErr.credit ? "The Anthropic account has no credit left. Add credit at console.anthropic.com → Billing (turn on auto-reload so it never runs out)."
      : lastErr.status === 401 ? "The AI key no longer works (Anthropic refused it): it was deleted, disabled or mistyped. Create a new key at console.anthropic.com → API Keys and paste it in Dashboard → 🔑 AI key."
      : lastErr.status === 403 ? "Anthropic refused access with this AI key. Check the account at console.anthropic.com."
      : lastErr.status === 429 ? "AI is busy right now (too many requests). Try again in a minute."
      : lastErr.status >= 500 ? "Anthropic's AI is down right now. Try again in a few minutes."
      : lastErr.msg || `AI error HTTP ${lastErr.status}`;
    throw Object.assign(new Error(msg), { status: 502 });
  }
  const inTok = j.usage?.input_tokens || 0, outTok = j.usage?.output_tokens || 0;
  const cost = (inTok * PRICE_IN + outTok * PRICE_OUT) / 1e6;
  u.inTok += inTok; u.outTok += outTok; u.cost += cost; u.asks = (u.asks || 0) + 1;
  db.write().catch(() => {});
  return { text: (j.content || []).filter((c) => c.type === "text").map((c) => c.text).join("\n").trim(), cost, monthCost: u.cost };
}

export function mountAi(app, db, { authenticate, isOnline }) {
  const usage = () => usageOf(db);

  /** Ti rezime eta panel la (san modpas, san kle, san lyen playlist). */
  function snapshot() {
    const d = db.data, now = Date.now();
    const devices = visibleDevices(d).filter((x) => x.mac);
    const online = devices.filter((x) => isOnline(x.mac));
    const versions = {};
    for (const x of devices) versions[x.appVersion || "?"] = (versions[x.appVersion || "?"] || 0) + 1;
    const customers = (d.customers || []).map((c) => ({ name: c.name || "", status: c.status || "", plan: c.plan || "", expiry: c.expiry || "" }));
    const soon = customers.filter((c) => c.expiry && Date.parse(c.expiry) - now < 14 * 86400000).slice(0, 30);
    const alerts = (d.channelAlerts || []).filter((a) => now - a.at < 2 * 86400000).sort((a, b) => b.at - a.at).slice(0, 40)
      .map((a) => ({ when: new Date(a.at).toISOString().slice(0, 16), type: a.type || "live", title: a.channel, tv: a.mac, times: a.count || 1, detail: a.detail }));
    // Chanèl ki pi fèb yo (sante)
    const weak = [];
    for (const [host, byId] of Object.entries(d.channelHealth || {})) {
      for (const h of Object.values(byId || {})) if ((h.n || 0) >= 3 && ((h.fail || 0) > 0.3 || (h.cuts || 0) > 2)) weak.push({ host, name: h.name, failPct: Math.round((h.fail || 0) * 100), cutsPer10min: h.cuts ?? null, tries: h.n });
    }
    weak.sort((a, b) => b.failPct - a.failPct);
    return {
      now: new Date().toISOString().slice(0, 16),
      tvs: { total: devices.length, online: online.length, appVersions: versions,
        list: devices.slice(0, 50).map((x) => { const L = globalThis.galaxyLive?.get(x.mac);
          return { mac: x.mac, customer: x.customer || "", online: isOnline(x.mac), app: x.appVersion || "", lastSeen: x.lastSeenAt || "",
            screen: L?.screen || "", providerError: L?.screen === "activation" && L?.error ? L.error : "",
            panelKeyRejected: !!(x.keyConflictAt && Date.parse(x.keyConflictAt) > (Date.parse(x.lastSeenAt || "") || 0)) }; }) },
      customers: { total: customers.length, expiringWithin14Days: soon },
      channelAndVideoProblemsLast48h: alerts,
      // Kategori fim/seri pou granmoun ki toujou vizib sou chak TV (panel la li yo kay founisè a)
      adultVodVisible: devices.filter((x) => !x.forceHideAdult && x.vodCats).map((x) => {
        const rx = /(xxx|porn|adult(?!\s*swim)|adulte|adulto|18\s*\+|\+\s*18)/i, bl = x.blockedCats || {};
        const hid = (k) => new Set((bl[k] || []).map((n) => String(n).toUpperCase()));
        const vis = (names, k) => (names || []).filter((n) => rx.test(n) && !hid(k).has(String(n).toUpperCase()));
        return { tv: `${x.customer || ""} · ${x.mac}`, movies: vis(x.vodCats.movies, "movies").slice(0, 10), series: vis(x.vodCats.series, "series").slice(0, 10) };
      }).filter((r) => r.movies.length || r.series.length).slice(0, 30),
      weakChannels: weak.slice(0, 15),
    };
  }

  // Pwopriyetè a kole kle AI a nan panel la (li pa janm voye kle a tounen, sèlman 4 dènye karaktè yo)
  app.get("/api/galaxy/ai-key", authenticate, (req, res) => {
    const k = mainKey(), k2 = backupKey();
    res.json({ set: !!k, fromEnv: !!process.env.ANTHROPIC_API_KEY, last4: k ? k.slice(-4) : "", backupSet: !!k2, last4b: k2 ? k2.slice(-4) : "", health: aiKeyHealth() });
  });
  app.put("/api/galaxy/ai-key", authenticate, async (req, res) => {
    const slot = Number(req.body?.slot) === 2 ? 2 : 1;
    if (slot === 1 && process.env.ANTHROPIC_API_KEY) return res.status(400).json({ error: "The key is set in /etc/galaxy.env on the server: remove it there first." });
    const k = String(req.body?.key || "").trim();
    if (!k) { saveKey("", slot); return res.json({ ok: true, set: false }); }
    if (slot === 2 && k === mainKey()) return res.status(400).json({ error: "This is the same key as the main one. Create a second key in console.anthropic.com → API Keys." });
    if (!/^sk-ant-[A-Za-z0-9_-]{20,}$/.test(k)) return res.status(400).json({ error: "This doesn't look like an Anthropic API key (it starts with sk-ant-)." });
    try {
      const r = await fetch("https://api.anthropic.com/v1/models?limit=1", { headers: { "x-api-key": k, "anthropic-version": "2023-06-01" }, signal: AbortSignal.timeout(15000) });
      if (r.status === 401 || r.status === 403) return res.status(400).json({ error: "Anthropic refused this key. Check that you copied all of it." });
    } catch { /* rezo a pa reponn: sove l kanmenm */ }
    saveKey(k, slot);
    res.json({ ok: true, set: true, last4: k.slice(-4) });
  });

  app.get("/api/galaxy/ai", authenticate, (req, res) => {
    const u = usage();
    res.json({ configured: !!aiKey(), model: MODEL, month: u.month, cost: Math.round(u.cost * 10000) / 10000, asks: u.asks || 0, limit: limit() });
  });

  /** Tradui yon ti fraz (egz: tèks gid TV a) an Kreyòl, English, Français, Español. Non mak yo pa tradui. */
  app.post("/api/galaxy/ai/translate", authenticate, async (req, res) => {
    const max = Math.min(200, Math.max(20, Number(req.body?.max) || 80));
    const text = String(req.body?.text || "").trim().slice(0, max);
    if (!text) return res.status(400).json({ ok: false, error: "Empty text." });
    if (!aiKey()) return res.status(400).json({ ok: false, noKey: true, error: "No AI key: automatic translation is off." });
    try {
      const r = await aiComplete(db, { maxTokens: 300,
        system: "You translate short TV screen slogans. Keep brand names (Galaxy TV, Ben Tech TV, etc.), phone numbers, emojis and punctuation like '—' unchanged. Natural and short (keep about the same length). Haitian Creole must be real Haitian Creole (Kreyòl ayisyen). Reply with JSON only: {\"ht\":\"…\",\"en\":\"…\",\"fr\":\"…\",\"es\":\"…\"}",
        user: text });
      const m = r.text.match(/\{[\s\S]*\}/); const j = m ? JSON.parse(m[0]) : {};
      const out = {}; for (const l of ["ht", "en", "fr", "es"]) out[l] = String(j[l] || text).trim().slice(0, max);
      res.json({ ok: true, ...out });
    } catch (err) { res.status(err.status || 500).json({ ok: false, error: err.message }); }
  });

  app.post("/api/galaxy/ai", authenticate, async (req, res) => {
    const key = aiKey();
    if (!key) return res.status(400).json({ error: "AI is not set up yet: paste the AI key in Dashboard → 🔑 AI key." });
    const u = usage();
    if (u.cost >= limit()) return res.status(429).json({ error: `Monthly AI limit reached ($${limit()}).` });
    const question = String(req.body?.question || "").trim().slice(0, 1000);
    if (!question) return res.status(400).json({ error: "Empty question." });
    const lang = LANG_NAMES[req.body?.lang] || "English";
    const system = "You are the assistant inside the Galaxy TV Stick admin panel (an IPTV app for Android TV / Fire TV, managed by a non-technical owner). " +
      "You get a JSON snapshot of the panel: TVs, customers, and channel/video problems reported by the TVs. Video streams come straight from the customer's IPTV provider, not from this panel. " +
      "Common causes: HTTP 401/403/429 = provider account refused or too many screens; HTTP 5xx or empty reply = provider/channel down; many channels failing on one provider at once = provider outage; one TV only = that TV's internet/router. " +
      "You can ALSO answer general support questions (installing the app, which TVs work, setup, troubleshooting) using these facts and your general knowledge, even if they are not about the snapshot. " +
      "Facts about the Galaxy TV Stick app: it is an Android app (Android 5.1 or newer). It installs with the Downloader app (enter the app's Downloader code) on: Amazon Fire TV Stick / Fire TV Cube / Fire TV Edition TVs (Hisense, Toshiba, Insignia Fire TV), Android TV / Google TV TVs and boxes (Sony, TCL, Hisense, Chromecast with Google TV, Nvidia Shield, Onn), and Android phones/tablets. " +
      "It does NOT install on TVs with VIDAA (most recent Hisense in the US), Roku TV, Samsung Tizen, LG webOS, or Apple TV: those customers need a Fire TV Stick or an Android TV box plugged into HDMI. To know a Hisense TV's system: look for Google TV / Android TV / Play Store or Fire TV on the home screen (works), VIDAA or Roku (does not). " +
      "On Google TV/Android TV: install Downloader from the Play Store, then allow Install unknown apps for Downloader in Settings. On Fire TV: Settings > My Fire TV > Developer options > Install unknown apps > Downloader ON. " +
      "Activation: the TV shows a MAC address and a 6-digit Device Key; the admin adds the playlist to that MAC in the panel (Devices). Playlists can be Xtream (server+user+password), M3U link, or Stalker/MAC portal. " +
      "App updates: automatic (the TV checks every 6 hours and when the app opens), or manual from System > App update in the panel, or on the TV in Settings > Update app. Panel changes (channels, packages, banners) reach the TVs silently in seconds. " +
      "Subscription end date is taken automatically from the IPTV provider when the TV is linked to a customer. " +
      "Always identify a TV by its customer name and MAC address (e.g. \"Jean · AA:BB:CC:DD:EE:FF\"), never by the device model. " +
      `Reply in the SAME language the question is written in (any language: Haitian Creole, English, French, Spanish, Portuguese…); if the language is unclear, use ${lang}. Use simple words, short (max ~12 lines), with concrete next steps in the panel. Never invent panel data (TVs, customers, numbers) that is not in the snapshot. Never reveal passwords or keys.`;
    try {
      if (req.body?.lang && LANG_NAMES[req.body.lang]) { (db.data.watch ??= {}).lang = req.body.lang; }
      const G = globalThis.galaxyGuide;
      const guideRule = G ? " TV GUIDE: the panel can search the providers' TV guide (EPG) for the next 2 days. If the question asks which channel shows, or when, any programme (a match, team, league, news, show, series, movie, event, person…), reply with ONLY one line: GUIDE_SEARCH: term1 | term2 | … — up to 8 short search terms in the forms a TV guide may use (English, French, Spanish, Portuguese, and the original name; e.g. Brazil | Brasil | Brésil). Nothing else on that reply." : "";
      const userMsg = `Panel snapshot:\n${JSON.stringify(snapshot())}\n\nQuestion: ${question}`;
      let r = await aiComplete(db, { system: system + guideRule, user: userMsg, maxTokens: 700 });
      let cost = r.cost;
      const gm = G && r.text.trim().match(/^GUIDE_SEARCH:\s*(.+)$/im);
      if (gm) {
        const terms = gm[1].split("|").map((x) => x.trim()).filter(Boolean).slice(0, 8);
        const st = G.status(), zone = G.tz();
        const fmt = (ms) => new Date(ms).toLocaleString("en-US", { timeZone: zone, weekday: "short", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
        const hits = G.search(terms, { limit: 25 }).map((h) => ({ title: h.title, info: h.desc, starts: fmt(h.start), ends: fmt(h.stop), onNow: h.live,
          channels: h.channels.map((c) => `${c.name}${c.category ? " [" + c.category + "]" : ""} (provider: ${c.provider})`) }));
        const guideInfo = { searchedFor: terms, timeZone: zone, guideUpdated: st.at, providersInGuide: st.sources.map((x) => ({ provider: x.provider, programmes: x.programmes, error: x.error || undefined })), results: hits };
        r = await aiComplete(db, { maxTokens: 700,
          system: system + " You now have the TV guide search results. Answer from them only: which channel(s) (name and category as shown in the app, and which provider has it when there are several providers), day and time (in the given time zone), and whether it is on now. If the results look unrelated or empty, say it is not in the providers' guide for the next 2 days (the provider may not list it, or the name may differ) and suggest searching another name. Never invent channels or times.",
          user: `${userMsg}\n\nTV guide search:\n${JSON.stringify(guideInfo)}` });
        cost += r.cost;
      }
      res.json({ answer: r.text, cost: Math.round(cost * 10000) / 10000, monthCost: Math.round(r.monthCost * 10000) / 10000, limit: limit() });
    } catch (err) {
      res.status(err.status || 502).json({ error: err.status ? err.message : "AI unavailable: " + (err.message || err) });
    }
  });
}
