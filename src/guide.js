// Gid TV a (EPG) nan panel la: chèche ki chanèl k ap bay yon pwogram (match, nouvèl, fim, emisyon…) ak lè l ap pase.
// Panel la chaje gid chak founisè (xmltv) yon fwa chak 6 èdtan, li kenbe sèlman 2 jou pwogram (tit + ti deskripsyon),
// epi li konnen ki non chanèl sa a genyen nan app la (epg_channel_id → non chanèl + kategori).
import fs from "node:fs";
import path from "node:path";

const REFRESH_MS = 6 * 3600_000;
const WINDOW_MS = 48 * 3600_000;      // pwogram ki ap pase nan 2 jou ki vini yo
const MAX_PROGS = 400_000;            // limit memwa pou tout founisè yo ansanm
const MAX_BYTES = 400 * 1024 * 1024;  // pa li plis pase 400 MB pou yon sèl gid
const UA = "Mozilla/5.0 (Linux; Android 9; AFTKA) GalaxyTVStick/1.0";

const ENT = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " " };
const decode = (s) => String(s || "").replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
  .replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, e) => e[0] === "#" ? String.fromCodePoint(e[1] === "x" || e[1] === "X" ? parseInt(e.slice(2), 16) : Number(e.slice(1))) : (ENT[e.toLowerCase()] ?? m))
  .replace(/<[^>]+>/g, "").replace(/\s+/g, " ").trim();
export const norm = (s) => String(s || "").normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();
const attr = (tag, name) => { const m = tag.match(new RegExp(`\\s${name}\\s*=\\s*("([^"]*)"|'([^']*)')`, "i")); return m ? (m[2] ?? m[3]) : ""; };
/** "20261114183000 +0100" → epoch ms */
function xmltvTime(s) {
  const m = String(s || "").match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-]\d{2}):?(\d{2})?/);
  if (!m) return NaN;
  const utc = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0));
  if (!m[7]) return utc;
  const sign = m[7][0] === "-" ? -1 : 1;
  return utc - sign * (Math.abs(+m[7]) * 60 + +(m[8] || 0)) * 60_000;
}

export function mountGuide(app, db, { authenticate, playlistsAll, xtreamClient, plType, providerOf, tz, dataDir }) {
  const cacheFile = path.join(dataDir || ".", "guide-cache.json");
  /** sources: [{ provider, at, error, channels, progs }]  progs: [ch, start, stop, title, desc] */
  let state = { at: 0, building: false, sources: [], chanName: new Map() };

  try { // Gid ki te sove sou disk la (pou yon redemaraj pa efase l)
    const j = JSON.parse(fs.readFileSync(cacheFile, "utf8"));
    if (j?.v === 1 && Date.now() - j.at < 24 * 3600_000) state = { ...state, at: j.at, sources: j.sources || [] };
  } catch { /* poko gen gid */ }

  /** Yon sèl playlist pa founisè (menm sèvè = menm gid). */
  function sourcesWanted() {
    const byHost = new Map();
    for (const pl of playlistsAll()) {
      const t = plType(pl);
      if (t === "stalker") continue;
      if (t === "m3u" && !pl.epgUrl) continue;
      let host = ""; try { host = new URL(/^https?:\/\//i.test(pl.server) ? pl.server : "http://" + pl.server).hostname; } catch { continue; }
      const prev = byHost.get(host);
      // Pi bon chwa: yon playlist Xtream (li bay non chanèl yo), sinon sa ki gen yon lyen EPG
      if (!prev || (plType(prev) !== "xtream" && t === "xtream")) byHost.set(host, pl);
    }
    return [...byHost.values()];
  }

  async function streamXmltv(url, onElement) {
    const ctl = new AbortController(); const tm = setTimeout(() => ctl.abort(), 6 * 60_000);
    try {
      const r = await fetch(url, { signal: ctl.signal, redirect: "follow", headers: { "User-Agent": UA, Accept: "application/xml,text/xml,*/*" } });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const dec = new TextDecoder("utf-8");
      let buf = "", bytes = 0;
      for await (const chunk of r.body) {
        bytes += chunk.length;
        if (bytes > MAX_BYTES) { ctl.abort(); break; }
        buf += dec.decode(chunk, { stream: true });
        let cut = 0;
        const re = /<(programme|channel)\b[^>]*?\/>|<(programme|channel)\b[^>]*>[\s\S]*?<\/\2>/g;
        let m;
        while ((m = re.exec(buf))) { onElement(m[1] || m[2], m[0]); cut = re.lastIndex; }
        buf = buf.slice(cut);
        if (buf.length > 4_000_000) buf = buf.slice(-200_000); // yon eleman ki twò long: lage l
      }
      return bytes;
    } catch (e) {
      if (e.name === "AbortError") throw new Error("Gid la pran twòp tan pou l telechaje.");
      throw e;
    } finally { clearTimeout(tm); }
  }

  async function buildSource(pl, budget) {
    const provider = providerOf(pl.server) || "Founisè";
    const out = { provider, at: Date.now(), error: "", channels: {}, progs: [] };
    let url = String(pl.epgUrl || "").trim();
    // Non chanèl yo jan app la montre yo (Xtream sèlman)
    if (plType(pl) === "xtream") {
      const cl = xtreamClient(pl);
      if (cl.error) { out.error = cl.error; return out; }
      if (!url) url = `${cl.base}/xmltv.php?username=${encodeURIComponent(String(pl.username).trim())}&password=${encodeURIComponent(String(pl.password).trim())}`;
      try {
        const [cats, live] = await Promise.all([cl.get("get_live_categories", 20000), cl.get("get_live_streams", 40000)]);
        const catName = new Map((Array.isArray(cats) ? cats : []).map((c) => [String(c.category_id), String(c.category_name || "")]));
        for (const c of Array.isArray(live) ? live : []) {
          const id = norm(c.epg_channel_id); if (!id) continue;
          const list = (out.channels[id] ??= []);
          if (list.length < 8) list.push([String(c.name || "").slice(0, 80), (catName.get(String(c.category_id)) || "").slice(0, 60)]);
        }
      } catch (e) { out.error = "Lis chanèl: " + (e.message || e); }
    }
    if (!url) { out.error ||= "Pa gen lyen gid (EPG)."; return out; }
    const now = Date.now(), until = now + WINDOW_MS;
    const xmlNames = {};
    try {
      await streamXmltv(url, (kind, el) => {
        if (kind === "channel") {
          const id = norm(attr(el, "id")); const dn = el.match(/<display-name[^>]*>([\s\S]*?)<\/display-name>/i);
          if (id && dn) xmlNames[id] = decode(dn[1]).slice(0, 80);
          return;
        }
        if (out.progs.length >= budget) return;
        const start = xmltvTime(attr(el, "start")), stop = xmltvTime(attr(el, "stop")) || start + 3600_000;
        if (!(stop > now - 60_000 && start < until)) return;
        const ch = norm(attr(el, "channel")); if (!ch) return;
        const titles = [...el.matchAll(/<title[^>]*>([\s\S]*?)<\/title>/gi)].map((m) => decode(m[1])).filter(Boolean);
        const title = [...new Set(titles)].slice(0, 2).join(" / ").slice(0, 140); if (!title) return;
        const sub = el.match(/<sub-title[^>]*>([\s\S]*?)<\/sub-title>/i), desc = el.match(/<desc[^>]*>([\s\S]*?)<\/desc>/i);
        const d = [sub && decode(sub[1]), desc && decode(desc[1])].filter(Boolean).join(" — ").slice(0, 180);
        out.progs.push([ch, Math.round(start / 1000), Math.round(stop / 1000), title, d]);
      });
    } catch (e) { out.error = "Gid: " + (e.message || e); }
    // Chanèl ki pa nan lis Xtream la: sèvi ak non gid la bay
    for (const [id, name] of Object.entries(xmlNames)) if (!out.channels[id]) out.channels[id] = [[name, ""]];
    return out;
  }

  async function rebuild() {
    if (state.building) return;
    state.building = true;
    try {
      const want = sourcesWanted(), sources = [];
      let left = MAX_PROGS;
      for (const pl of want) {
        const s = await buildSource(pl, Math.max(0, left));
        left -= s.progs.length; sources.push(s);
      }
      state = { ...state, at: Date.now(), sources };
      try { fs.writeFileSync(cacheFile, JSON.stringify({ v: 1, at: state.at, sources })); } catch { /* disk la plen: kenbe l nan memwa */ }
      console.log(`Guide: ${sources.length} provider(s), ${sources.reduce((n, s) => n + s.progs.length, 0)} programmes`);
    } finally { state.building = false; }
  }
  setTimeout(() => { if (Date.now() - state.at > REFRESH_MS) rebuild().catch(() => {}); }, 90_000).unref?.();
  setInterval(() => rebuild().catch(() => {}), REFRESH_MS).unref?.();

  /** Chèche yon oswa plizyè mo (nenpòt lang) nan tit ak deskripsyon pwogram yo. */
  function search(terms, { limit = 40 } = {}) {
    const ts = (Array.isArray(terms) ? terms : [terms]).map((x) => norm(x).trim()).filter((x) => x.length >= 2).slice(0, 12);
    if (!ts.length) return [];
    const now = Date.now() / 1000, hits = new Map();
    for (const s of state.sources) {
      for (const p of s.progs) {
        if (p[2] < now) continue;
        const inTitle = ts.some((x) => norm(p[3]).includes(x));
        if (!inTitle && !ts.some((x) => norm(p[4]).includes(x))) continue;
        const names = s.channels[p[0]] || [[p[0], ""]];
        const key = `${p[1]}|${norm(p[3])}`;
        const h = hits.get(key) || { title: p[3], desc: p[4], start: p[1] * 1000, stop: p[2] * 1000, live: p[1] <= now, inTitle, channels: [] };
        for (const [name, cat] of names) if (h.channels.length < 10 && !h.channels.some((c) => c.name === name)) h.channels.push({ name, category: cat, provider: s.provider });
        hits.set(key, h);
      }
    }
    return [...hits.values()].sort((a, b) => (b.live - a.live) || (b.inTitle - a.inTitle) || (a.start - b.start)).slice(0, limit);
  }

  const status = () => ({
    at: state.at ? new Date(state.at).toISOString() : null, building: state.building, tz: tz(),
    sources: state.sources.map((s) => ({ provider: s.provider, programmes: s.progs.length, channels: Object.keys(s.channels).length, error: s.error })),
  });

  app.get("/api/galaxy/guide", authenticate, (req, res) => res.json(status()));
  app.get("/api/galaxy/guide/search", authenticate, (req, res) => {
    const q = String(req.query.q || "").slice(0, 120);
    const terms = q.split(/[|,;]/).map((x) => x.trim()).filter(Boolean);
    res.json({ ...status(), results: search(terms, { limit: 60 }) });
  });
  let lastManual = 0;
  app.post("/api/galaxy/guide/refresh", authenticate, (req, res) => {
    if (state.building) return res.json({ ok: true, building: true });
    if (Date.now() - lastManual < 10 * 60_000) return res.status(429).json({ ok: false, error: "Gid la fèk mete ajou. Eseye ankò nan 10 minit." });
    lastManual = Date.now();
    rebuild().catch(() => {});
    res.json({ ok: true, building: true });
  });

  globalThis.galaxyGuide = { search, status, tz };
}
