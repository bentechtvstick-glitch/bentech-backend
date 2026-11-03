// ---------------------------------------------------------------------------
// Pòtal STB (MAG, TVIP an mòd "Web portal / API MAG"): pwòp ekran Galaxy / Ben Tech sou box STB yo.
// Separe nèt ak rès panel la: done yo nan db.data.stbBoxes, route yo anba /api/stb/… ak /stb/…
// Box la louvri /stb/c/ (Portal URL), li voye MAC li, sèvè a di si l aktif epi ba l chanèl ki pa kache yo.
// ---------------------------------------------------------------------------
import express from "express";
import crypto from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";

const MAC_RE = /^([0-9A-F]{2}:){5}[0-9A-F]{2}$/;
const normMac = (v) => { const h = String(v || "").toUpperCase().replace(/[^0-9A-F]/g, ""); return h.length === 12 ? h.match(/.{2}/g).join(":") : ""; };

function normServer(v) {
  let u = String(v || "").trim();
  if (!u) return "";
  if (!/^https?:\/\//i.test(u)) u = "http://" + u;
  u = u.replace(/\/(player_api\.php|get\.php|xmltv\.php)[^]*$/i, "").replace(/\/+$/, "");
  return u.replace(/\/(stalker_portal\/)?(c|server\/load\.php|portal\.php)$/i, "").replace(/\/+$/, "");
}

/** Chanèl live yon kont Xtream (kenbe 10 minit). */
const liveCache = new Map();
async function xtreamLive(pl, fresh = false) {
  const base = normServer(pl.server);
  if (!base || !pl.username || !pl.password) throw new Error("Playlist la pa konplè (sèvè, username, password).");
  let host; try { host = new URL(base).hostname; } catch { throw new Error("Adrès sèvè a pa valid."); }
  if (/^(localhost|127\.|0\.|10\.|192\.168\.|169\.254\.|::1)/i.test(host)) throw new Error("Adrès sa a pa otorize.");
  const key = `${base}|${pl.username}|${pl.password}`;
  const hit = liveCache.get(key);
  if (hit && !fresh && Date.now() - hit.at < 600_000) return hit.data;
  const q = `username=${encodeURIComponent(pl.username)}&password=${encodeURIComponent(pl.password)}`;
  const get = async (action, ms) => {
    const ctl = new AbortController(); const tm = setTimeout(() => ctl.abort(), ms);
    try {
      const r = await fetch(`${base}/player_api.php?${q}${action ? "&action=" + action : ""}`, { signal: ctl.signal, redirect: "follow", headers: { "User-Agent": "Mozilla/5.0 (Linux; Android 9) GalaxySTB/1.0", Accept: "application/json,*/*" } });
      if (!r.ok) throw new Error(`Sèvè a reponn HTTP ${r.status}.`);
      return JSON.parse(await r.text());
    } catch (e) { throw new Error(e.name === "AbortError" ? "Sèvè a pran twòp tan pou l reponn." : (e.message || "Sèvè a pa reponn.")); }
    finally { clearTimeout(tm); }
  };
  const info = await get("", 12000);
  if (!info?.user_info || Number(info.user_info.auth) === 0) throw new Error("Username oswa password la pa bon.");
  const [cats, live] = await Promise.all([get("get_live_categories", 20000), get("get_live_streams", 30000)]);
  const groups = (Array.isArray(cats) ? cats : []).map((c) => ({ id: String(c.category_id), name: String(c.category_name || "").slice(0, 120) }));
  const channels = (Array.isArray(live) ? live : []).slice(0, 60000).map((c, i) => ({
    id: Number(c.stream_id), num: Number(c.num) || i + 1, name: String(c.name || "").slice(0, 120), group: String(c.category_id || ""),
    logo: /^https?:\/\//i.test(c.stream_icon || "") ? String(c.stream_icon).slice(0, 400) : "",
  })).filter((c) => Number.isFinite(c.id));
  const exp = Number(info.user_info.exp_date) || 0;
  const data = { base, groups, channels, exp, status: String(info.user_info.status || "") };
  liveCache.set(key, { at: Date.now(), data });
  if (liveCache.size > 100) liveCache.delete(liveCache.keys().next().value);
  return data;
}

export function mountStb(app, db, { authenticate, auditLog }) {
  // Yon erè nan yon route STB pa janm fè sèvè a tonbe
  const safeRoute = (fn) => (req, res) => Promise.resolve().then(() => fn(req, res)).catch((e) => { console.error("STB:", e?.message || e); if (!res.headersSent) res.status(500).json({ ok: false, error: "Erè sèvè STB" }); });
  const dir = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "public", "stb");
  const boxes = () => (db.data.stbBoxes ||= []);
  const find = (mac) => boxes().find((b) => b.mac === mac);
  const who = (req) => req.user?.sub || "admin";
  const canEdit = (req) => ["owner", "Super Administrator", "Administrator", "Support"].includes(req.role);
  const safe = (b) => {
    const { playlist, ...rest } = b;
    return { ...rest, playlist: playlist ? { server: playlist.server, username: playlist.username, hasPassword: !!playlist.password } : null };
  };
  const customerOf = (b) => b.customer ? (db.data.customers || []).find((c) => c.id === b.customer || String(c.name || "").toLowerCase() === String(b.customer).toLowerCase()) : null;
  /** active | pending | expired | blocked */
  const stateOf = (b) => {
    if (b.blocked) return "blocked";
    if (!b.playlist?.username) return "pending";
    const c = customerOf(b);
    if (c) {
      const cs = String(c.status || "").toLowerCase();
      if (cs === "suspended") return "blocked";
      if (cs === "expired") return "expired";
      if (c.expiry && Date.parse(c.expiry) && Date.parse(c.expiry) < Date.now()) return "expired";
    }
    return "active";
  };

  // ---- Pòtal la (fichye statik yo) ----
  const sendPortal = (req, res) => { res.set("Cache-Control", "no-cache"); res.sendFile(path.join(dir, "index.html")); };
  app.get(["/stb/c", "/stb/c/", "/stb/c/index.html", "/stb/stalker_portal/c/", "/stb/stalker_portal/c/index.html"], sendPortal);
  app.use("/stb", express.static(dir, { maxAge: "1h", index: false }));

  // ---- Sa box la mande (pa gen JWT: box la idantifye pa MAC li) ----
  app.get("/api/stb/portal", safeRoute(async (req, res) => {
    res.set("Cache-Control", "no-store");
    const mac = normMac(req.query.mac);
    if (!MAC_RE.test(mac)) return res.status(400).json({ ok: false, error: "MAC pa valid" });
    let b = find(mac);
    if (!b && boxes().filter((x) => !x.playlist).length >= 200) return res.json({ ok: true, mac, state: "pending", brand: "galaxy", name: "" }); // pa kite moun ranpli lis la ak fo MAC
    if (!b) {
      // Nouvo box: li parèt nan panel la kòm "ap tann", admin nan aktive l
      b = { mac, name: "", brand: "galaxy", customer: "", playlist: null, hiddenGroups: [], hiddenChannels: [], blocked: false, createdAt: new Date().toISOString() };
      boxes().push(b);
      auditLog?.("stb-new", `STB ${mac} opened the portal`, "stb");
    }
    b.lastSeenAt = new Date().toISOString();
    b.ua = String(req.get("user-agent") || "").slice(0, 160);
    await db.write().catch(() => {});
    const state = stateOf(b);
    const out = { ok: true, mac, state, brand: b.brand === "bentech" ? "bentech" : "galaxy", name: b.name || "" };
    const c = customerOf(b);
    if (c) out.customer = { name: c.name || "", expiry: c.expiry || "" };
    if (state !== "active") return res.json(out);
    try {
      const L = await xtreamLive(b.playlist);
      const hg = new Set((b.hiddenGroups || []).map(String)), hc = new Set((b.hiddenChannels || []).map(Number));
      const u = encodeURIComponent(b.playlist.username), p = encodeURIComponent(b.playlist.password);
      const groups = L.groups.filter((g) => !hg.has(g.id));
      const ok = new Set(groups.map((g) => g.id));
      out.groups = groups;
      out.channels = L.channels.filter((ch) => ok.has(ch.group) && !hc.has(ch.id))
        .map((ch) => ({ ...ch, url: `${L.base}/live/${u}/${p}/${ch.id}.ts` }));
      res.json(out);
    } catch (e) {
      res.json({ ...out, state: "error", error: e.message });
    }
  }));

  // ---- Panel admin nan ----
  app.get("/api/stb/boxes", authenticate, (req, res) => {
    res.json(boxes().map((b) => ({ ...safe(b), state: stateOf(b) })));
  });
  app.post("/api/stb/boxes", authenticate, safeRoute(async (req, res) => {
    if (!canEdit(req)) return res.status(403).json({ ok: false, error: "Kont ou an pa ka chanje sa.", forbidden: true });
    const mac = normMac(req.body?.mac);
    if (!MAC_RE.test(mac)) return res.status(400).json({ ok: false, error: "MAC pa valid (egz: 00:1A:79:12:34:56)" });
    if (find(mac)) return res.status(409).json({ ok: false, error: "STB sa a deja nan lis la." });
    const b = { mac, name: String(req.body?.name || "").trim().slice(0, 60), brand: req.body?.brand === "bentech" ? "bentech" : "galaxy", customer: "", playlist: null, hiddenGroups: [], hiddenChannels: [], blocked: false, createdAt: new Date().toISOString() };
    boxes().push(b);
    await db.write();
    auditLog?.("stb-add", `STB ${mac} added`, who(req));
    res.status(201).json({ ...safe(b), state: stateOf(b) });
  }));
  app.put("/api/stb/boxes/:mac", authenticate, safeRoute(async (req, res) => {
    if (!canEdit(req)) return res.status(403).json({ ok: false, error: "Kont ou an pa ka chanje sa.", forbidden: true });
    const b = find(normMac(req.params.mac));
    if (!b) return res.status(404).json({ ok: false, error: "STB pa jwenn" });
    const x = req.body || {};
    if (typeof x.name === "string") b.name = x.name.trim().slice(0, 60);
    if (x.brand) b.brand = x.brand === "bentech" ? "bentech" : "galaxy";
    if (typeof x.customer === "string") b.customer = x.customer.trim().slice(0, 80);
    if (typeof x.blocked === "boolean") b.blocked = x.blocked;
    if (Array.isArray(x.hiddenGroups)) b.hiddenGroups = x.hiddenGroups.map(String).slice(0, 5000);
    if (Array.isArray(x.hiddenChannels)) b.hiddenChannels = x.hiddenChannels.map(Number).filter(Number.isFinite).slice(0, 20000);
    if (x.playlist && typeof x.playlist === "object") {
      const server = normServer(x.playlist.server), username = String(x.playlist.username || "").trim();
      const password = String(x.playlist.password || "").trim() || b.playlist?.password || "";
      if (!server || !username || !password) return res.status(400).json({ ok: false, error: "Mete sèvè (DNS), username ak password." });
      b.playlist = { server, username, password };
    }
    if (x.playlist === null) b.playlist = null;
    await db.write();
    res.json({ ...safe(b), state: stateOf(b) });
  }));
  app.delete("/api/stb/boxes/:mac", authenticate, safeRoute(async (req, res) => {
    if (!canEdit(req) || req.role === "Support") return res.status(403).json({ ok: false, error: "Kont ou an pa ka efase.", forbidden: true });
    const mac = normMac(req.params.mac);
    db.data.stbBoxes = boxes().filter((b) => b.mac !== mac);
    await db.write();
    auditLog?.("stb-delete", `STB ${mac} deleted`, who(req));
    res.json({ ok: true });
  }));
  /** Gwoup ak chanèl kont lan (pou admin nan chwazi sa pou kache). */
  app.get("/api/stb/boxes/:mac/channels", authenticate, safeRoute(async (req, res) => {
    const b = find(normMac(req.params.mac));
    if (!b) return res.status(404).json({ ok: false, error: "STB pa jwenn" });
    if (!b.playlist) return res.status(400).json({ ok: false, error: "Ajoute playlist la an premye." });
    try {
      const L = await xtreamLive(b.playlist, req.query.refresh === "1");
      res.json({ ok: true, groups: L.groups, channels: L.channels.map(({ id, num, name, group }) => ({ id, num, name, group })), exp: L.exp, status: L.status });
    } catch (e) { res.status(502).json({ ok: false, error: e.message }); }
  }));
}
