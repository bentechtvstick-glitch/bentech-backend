// Kapasite ak rapidite: ekriti baz done a gwoupe, repons konprese, endèks aparèy.
// Tout bagay isit la sèvi ak Node sèlman (pa gen nouvo depandans pou enstale).
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";

/**
 * Ekriti rapid: olye sèvè a reekri tout db.json la nan chak demann (epi fè demann nan tann),
 * chanjman yo rete nan memwa epi yo ekri sou disk la an gwoup, yon ti moman apre.
 * - Fichye a ekri san espas (pi piti, pi vit) nan yon fichye tanporè, epi li ranplase ansyen an (atomik).
 * - Si ekriti a pran tan (gwo baz done), delè a alonje poukont li pou sèvè a pa janm bloke.
 * - Lè Render fèmen sèvè a (deploy/restart), dènye chanjman yo ekri anvan l soti.
 */
export function fastWrites(db, file, { delay = 150 } = {}) {
  const stats = { writes: 0, lastMs: 0, lastBytes: 0, lastAt: null, errors: 0, pending: false };
  const tmp = path.join(path.dirname(file), `.${path.basename(file)}.tmp`);
  let dirty = false, timer = null, running = false;

  const schedule = (ms) => { if (!timer && !running) timer = setTimeout(flush, ms); };
  async function flush() {
    timer = null;
    if (running || !dirty) return;
    running = true; dirty = false;
    const t0 = Date.now();
    let retry = 0;
    try {
      const json = JSON.stringify(db.data);
      await fs.promises.writeFile(tmp, json);
      await fs.promises.rename(tmp, file);
      stats.writes++; stats.lastMs = Date.now() - t0; stats.lastBytes = Buffer.byteLength(json); stats.lastAt = new Date().toISOString();
    } catch (err) {
      stats.errors++; dirty = true; retry = 2000;
      console.error("DB write failed:", err.code || err.message);
    }
    running = false; stats.pending = dirty;
    // Pa pase plis pase ~20% tan an ap ekri: yon gwo baz done ekri mwens souvan
    if (dirty) schedule(retry || Math.max(delay, stats.lastMs * 4));
  }

  db.write = () => { dirty = true; stats.pending = true; schedule(Math.max(delay, stats.lastMs * 4)); return Promise.resolve(); };

  /** Ekri touswit (lè sèvè a ap fèmen). */
  db.flushSync = () => {
    if (!dirty) return;
    try { fs.writeFileSync(tmp, JSON.stringify(db.data)); fs.renameSync(tmp, file); dirty = false; stats.pending = false; }
    catch (err) { console.error("DB flush failed:", err.code || err.message); }
  };
  for (const sig of ["SIGTERM", "SIGINT"]) process.once(sig, () => { db.flushSync(); process.exit(0); });
  process.once("beforeExit", () => db.flushSync());

  db.perf = stats;
  return stats;
}

/** Konprese (gzip) gwo repons JSON yo: lis aparèy, chanèl, Live TV… 5 a 10 fwa pi piti sou rezo a. */
export function gzipJson({ min = 1024 } = {}) {
  return (req, res, next) => {
    if (!/\bgzip\b/.test(String(req.headers["accept-encoding"] || ""))) return next();
    const plain = res.json.bind(res);
    res.json = (body) => {
      let text;
      try { text = JSON.stringify(body); } catch { return plain(body); }
      if (text === undefined || text.length < min) return plain(body);
      zlib.gzip(text, { level: 4 }, (err, buf) => {
        if (err || res.headersSent) return plain(body);
        res.setHeader("Content-Type", "application/json; charset=utf-8");
        res.setHeader("Content-Encoding", "gzip");
        res.setHeader("Vary", "Accept-Encoding");
        res.setHeader("Content-Length", buf.length);
        res.end(buf);
      });
      return res;
    };
    next();
  };
}

/**
 * Fichye panel la (HTML/JS/CSS) konprese yon sèl fwa epi kenbe nan memwa.
 * Navigatè a resevwa "304 pa chanje" si l gen menm vèsyon an deja.
 */
export function gzipStatic(prefix, dir) {
  const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".json": "application/json; charset=utf-8", ".svg": "image/svg+xml" };
  const cache = new Map(); // chemen → { tag, gz }
  const root = path.resolve(dir);
  return (req, res, next) => {
    if (req.method !== "GET" && req.method !== "HEAD") return next();
    let p = String(req.path || "");
    if (p !== prefix && !p.startsWith(prefix + "/")) return next();
    if (!/\bgzip\b/.test(String(req.headers["accept-encoding"] || ""))) return next();
    let rel = p.slice(prefix.length);
    if (rel === "") return next(); // kite express ajoute "/" la
    if (rel.endsWith("/")) rel += "index.html";
    const type = TYPES[path.extname(rel).toLowerCase()];
    if (!type) return next();
    let full;
    try { full = path.resolve(root, "." + decodeURIComponent(rel)); } catch { return next(); }
    if (full !== root && !full.startsWith(root + path.sep)) return next();
    fs.stat(full, (err, st) => {
      if (err || !st.isFile()) return next();
      const tag = `"${st.size.toString(36)}-${Math.floor(st.mtimeMs).toString(36)}-gz"`;
      res.setHeader("ETag", tag);
      res.setHeader("Vary", "Accept-Encoding");
      res.setHeader("Cache-Control", "no-cache"); // toujou verifye, men 304 si anyen pa chanje
      if (req.headers["if-none-match"] === tag) { res.statusCode = 304; return res.end(); }
      const send = (gz) => {
        res.statusCode = 200;
        res.setHeader("Content-Type", type);
        res.setHeader("Content-Encoding", "gzip");
        res.setHeader("Content-Length", gz.length);
        res.end(req.method === "HEAD" ? undefined : gz);
      };
      const hit = cache.get(full);
      if (hit && hit.tag === tag) return send(hit.gz);
      fs.readFile(full, (e1, raw) => {
        if (e1) return next();
        zlib.gzip(raw, { level: 9 }, (e2, gz) => { if (e2) return next(); cache.set(full, { tag, gz }); send(gz); });
      });
    });
  };
}

/**
 * Jwenn yon aparèy pa MAC / Device ID san l pa pase sou tout lis la chak fwa
 * (chak TV rele sèvè a plizyè fwa pa minit).
 */
export function deviceIndex(getList) {
  let arr = null, len = -1, map = new Map();
  return (key) => {
    const list = getList() || [];
    if (list !== arr || list.length !== len) {
      arr = list; len = list.length; map = new Map();
      for (const d of list) {
        if (!d) continue;
        if (d.mac && !map.has(d.mac)) map.set(d.mac, d);
        if (d.deviceId != null && !map.has(d.deviceId)) map.set(d.deviceId, d);
      }
    }
    const hit = map.get(key);
    if (hit && (hit.mac === key || hit.deviceId === key)) return hit;
    const found = list.find((d) => d && (d.deviceId === key || d.mac === key)); // MAC chanje sou plas
    if (found) map.set(key, found); else if (hit) map.delete(key);
    return found;
  };
}
