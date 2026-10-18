import express from "express";
import cors from "cors";
import jwt from "jsonwebtoken";
import { JSONFilePreset } from "lowdb/node";
import { nanoid } from "nanoid";
import fs from "node:fs";
import path from "node:path";
import {
  mountGalaxy,
  presentDevice,
  normMac,
  DEVICE_PRIVATE_FIELDS,
  DEVICE_PROTECTED_FIELDS,
} from "./galaxy.js";
import { mountGalaxyLive } from "./galaxy-live.js";
import { mountAutoBackup } from "./autobackup.js";
import { accessControl, findAdminLogin, hashPassword } from "./access.js";
import crypto from "node:crypto";
import { fastWrites, gzipJson } from "./perf.js";

// Baz done a. Sou Render, mete DB_FILE=/data/db.json (disk pèmanan) pou done yo
// pa efase chak fwa sèvè a redemare oswa w fè yon deploy.
function resolveDbFile() {
  const wanted = process.env.DB_FILE;
  if (!wanted || wanted === "./db.json") return "./db.json";
  try {
    fs.mkdirSync(path.dirname(wanted), { recursive: true });
    if (!fs.existsSync(wanted) && fs.existsSync("./db.json")) {
      fs.copyFileSync("./db.json", wanted); // premye fwa: kopye done depa yo
      console.log(`Seeded ${wanted} from ./db.json`);
    }
    fs.accessSync(path.dirname(wanted), fs.constants.W_OK);
    return wanted;
  } catch (err) {
    console.warn(`⚠ DB_FILE ${wanted} pa ka itilize (${err.code || err.message}). ` +
      "Done yo ap sove nan ./db.json epi yo ap efase chak redeploy.");
    return "./db.json";
  }
}
const DB_FILE = resolveDbFile();
console.log(`Database: ${DB_FILE}`);
const db = await JSONFilePreset(DB_FILE, {});
// Ekriti gwoupe + fichye konpak: panel la reponn touswit menm ak anpil done
fastWrites(db, DB_FILE);
const app = express();
app.disable?.("x-powered-by");
app.use(cors());
// Yon playlist ka gen plizyè milye chanèl: app TV a voye lis la nan yon sèl demann
// Restore yon kopi done (ak tout lis chanèl yo) ka pi gwo pase 40 MB
app.use("/api/galaxy/restore", express.json({ limit: "500mb" }));
app.use(express.json({ limit: "40mb" }));
app.use(gzipJson());
// Demenajman: yon sèvè ki poko gen done (nouvo sèvè vid) refize TV yo (503).
// App la (1.0.100+) wè erè a, li reverifye adrès panel la, epi li tounen sou ansyen sèvè a (Render) poukont li.
// Paj panel la ak login/restore admin nan toujou mache, pou n ka remete done yo.
app.use((req, res, next) => {
  if (req.path.startsWith("/api/devices/") && !(db.data.devices || []).some((d) => d.mac) && process.env.ALWAYS_READY !== "1") {
    return res.status(503).json({ ok: false, error: "Server is being set up. Try again later." });
  }
  next();
});

// Nenpòt chanjman admin nan fè nan panel la (kliyan, pakè, grafik, reglaj…): TV ki konekte yo mete yo ajou touswit.
// (Demann TV yo li menm voye — /api/devices/… — ak tès yo pa konte.)
const NO_SYNC = /^\/api\/(devices\/|auth\/|galaxy\/xtream\/test|galaxy\/devices\/[^/]+\/(playlists\/[^/]+\/test|commands)|galaxy\/backup|galaxy\/alerts|galaxy\/ai|galaxy\/notes|galaxy\/watch|galaxy\/notify|galaxy\/storage|galaxy\/media)/;
app.use((req, res, next) => {
  if (req.method !== "GET" && req.method !== "OPTIONS" && req.path.startsWith("/api/") && !NO_SYNC.test(req.path)) {
    res.on("finish", () => { if (res.statusCode < 400) syncTvs(); });
  }
  next();
});

// JWT configuration
// Kle sekrè sesyon yo: si l pa mete (oswa li rete valè egzanp lan), sèvè a kreye youn o aza
// epi li sere l bò kote baz done a (pa janm nan kòd la).
const JWT_SECRET = (() => {
  const env = process.env.JWT_SECRET;
  if (env && env !== "change-me-before-production" && env.length >= 16) return env;
  const f = path.join(path.dirname(path.resolve(DB_FILE)), ".jwt-secret");
  try { const v = fs.readFileSync(f, "utf8").trim(); if (v.length >= 32) return v; } catch {}
  const v = crypto.randomBytes(48).toString("hex");
  try { fs.writeFileSync(f, v, { mode: 0o600 }); } catch (e) { console.error("Cannot save JWT secret:", e.message); }
  return v;
})();
const JWT_EXPIRES_IN = "24h";

// Simple admin auth with real JWT
const ADMIN_USER = process.env.ADMIN_USER || "admin";
const ADMIN_PASS = process.env.ADMIN_PASS || "changeme";
// Dwa chak kont admin (wòl): aplike sou tout route panel yo anvan yo kouri
app.use(accessControl(db, JWT_SECRET, ADMIN_USER));

// ---------- helpers ----------

const AUDIT_MAX = 5000;
/** Write an audit-log entry */
function auditLog(action, detail, user = "system") {
  if (!db.data.auditLogs) db.data.auditLogs = [];
  db.data.auditLogs.push({
    id: nanoid(8),
    action,
    detail,
    user,
    timestamp: new Date().toISOString(),
  });
  // Kenbe dènye 5000 aksyon yo sèlman pou baz done a pa gwosi san rete
  if (db.data.auditLogs.length > AUDIT_MAX) db.data.auditLogs.splice(0, db.data.auditLogs.length - AUDIT_MAX);
  // fire-and-forget – don't block the response
  db.write().catch(() => {});
}

/** Middleware: verify JWT and attach req.user */
function authenticate(req, res, next) {
  const header = req.headers.authorization;
  if (!header || !header.startsWith("Bearer ")) {
    return res.status(401).json({ ok: false, error: "Missing or invalid token" });
  }
  try {
    const token = header.split(" ")[1];
    req.user = jwt.verify(token, JWT_SECRET);
    next();
  } catch {
    return res.status(401).json({ ok: false, error: "Token expired or invalid" });
  }
}

// ---------- Imaj / videyo piblisite yo (panel la telechaje yo dirèk, pa bezwen yon lòt sit) ----------
const MEDIA_DIR = path.join(path.dirname(DB_FILE), "media");
try { fs.mkdirSync(MEDIA_DIR, { recursive: true }); } catch {}
const MEDIA_TYPES = { "image/png": "png", "image/jpeg": "jpg", "image/jpg": "jpg", "image/gif": "gif", "image/webp": "webp", "video/mp4": "mp4", "video/webm": "webm" };
const MEDIA_MAX = 80 * 1024 * 1024;
app.use("/media", express.static(MEDIA_DIR, { maxAge: "30d", immutable: true }));
app.post("/api/galaxy/media", authenticate, (req, res) => {
  const type = String(req.headers["content-type"] || "").split(";")[0].trim().toLowerCase();
  const ext = MEDIA_TYPES[type];
  if (!ext) return res.status(400).json({ error: "Fichye sa a pa aksepte. Chwazi yon imaj (PNG, JPG) oswa yon videyo MP4." });
  const len = Number(req.headers["content-length"] || 0);
  if (len > MEDIA_MAX) return res.status(413).json({ error: "Fichye a twò gwo (maks 80 MB)." });
  const name = `${Date.now().toString(36)}-${nanoid(8)}.${ext}`;
  const file = path.join(MEDIA_DIR, name);
  const out = fs.createWriteStream(file);
  let size = 0, failed = false;
  const src = req.raw && typeof req.raw.pipe === "function" ? req.raw : req; // (shim tès la)
  src.on("data", (c) => { size += c.length; if (size > MEDIA_MAX && !failed) { failed = true; src.unpipe(out); out.destroy(); fs.rm(file, () => {}); res.status(413).json({ error: "Fichye a twò gwo (maks 80 MB)." }); } });
  src.pipe(out);
  out.on("finish", () => {
    if (failed) return;
    if (size < 100) { fs.rm(file, () => {}); return res.status(400).json({ error: "Fichye a vid." }); }
    const proto = String(req.headers["x-forwarded-proto"] || req.protocol || "https").split(",")[0];
    res.json({ ok: true, url: `${proto}://${req.headers.host}/media/${name}`, size });
  });
  out.on("error", (e) => { if (!failed) { failed = true; res.status(500).json({ error: "Pa ka sove fichye a: " + e.message }); } });
});

// ---------- Demenajman: kopye imaj/videyo piblisite yo depi ansyen sèvè a (Render) ----------
// Apre yon restore, done yo gen lyen ".../media/<non>" ki la sou ansyen sèvè a sèlman.
// Sèvè sa a telechaje chak fichye ki manke, sove l nan MEDIA_DIR, epi chanje lyen an pou l pwente sou li menm.
const OLD_MEDIA_BASE = (process.env.OLD_MEDIA_BASE || "https://bentech-backend.onrender.com").replace(/\/+$/, "");
const MEDIA_URL_RE = /^https?:\/\/([^/\s]+)\/media\/([A-Za-z0-9_-]+\.(?:png|jpe?g|gif|webp|mp4|webm))$/;
let mediaMig = { running: false, last: 0, base: "" };
async function migrateMedia(base) {
  if (mediaMig.running) return;
  mediaMig.running = true; mediaMig.last = Date.now();
  try {
    const refs = []; // [obj, key, name]
    const walk = (o, depth) => {
      if (!o || typeof o !== "object" || depth > 12) return;
      for (const k of Object.keys(o)) {
        const v = o[k];
        if (typeof v === "string") {
          const m = v.match(MEDIA_URL_RE);
          if (m && ((`https://${m[1]}` !== base && `http://${m[1]}` !== base) || !fs.existsSync(path.join(MEDIA_DIR, m[2])))) refs.push([o, k, m[2]]);
        } else if (v && typeof v === "object") walk(v, depth + 1);
      }
    };
    for (const key of Object.keys(db.data)) if (key !== "auditLogs") walk(db.data[key], 0);
    if (!refs.length) return;
    let moved = 0, failed = 0;
    const done = new Map(); // non → true/false
    for (const [obj, key, name] of refs) {
      if (!done.has(name)) {
        const file = path.join(MEDIA_DIR, name);
        let ok = fs.existsSync(file);
        if (!ok) {
          try {
            const r = await fetch(`${OLD_MEDIA_BASE}/media/${name}`, { signal: AbortSignal.timeout(120000) });
            if (r.ok) {
              const buf = Buffer.from(await r.arrayBuffer());
              if (buf.length >= 100) { fs.writeFileSync(file + ".part", buf); fs.renameSync(file + ".part", file); ok = true; }
            }
          } catch {}
        }
        done.set(name, ok);
      }
      if (done.get(name)) { obj[key] = `${base}/media/${name}`; moved++; } else failed++;
    }
    if (moved) {
      await db.write();
      syncTvs();
      auditLog("media-migrate", `${[...done.values()].filter(Boolean).length} media file(s) copied from the old server (${moved} link(s) updated${failed ? `, ${failed} failed` : ""})`);
    }
    console.log(`Media migration: ${moved} link(s) updated, ${failed} failed`);
  } catch (err) {
    console.warn("Media migration error:", err.message);
  } finally {
    mediaMig.running = false;
  }
}
// Pa janm sou Render li menm (RENDER=true): se sèlman nouvo sèvè a ki rale fichye yo.
app.use((req, res, next) => {
  if (!process.env.RENDER && process.env.MEDIA_MIGRATE !== "0" && Date.now() - mediaMig.last > 10 * 60 * 1000 && (db.data.devices || []).some((d) => d.mac)) {
    const host = String(req.headers["x-forwarded-host"] || req.headers.host || "").split(",")[0].trim().toLowerCase();
    // Sèlman yon vrè non domèn (pa localhost, pa yon adrès IP)
    if (/^[a-z0-9-]+(\.[a-z0-9-]+)+$/.test(host) && !/^[\d.]+$/.test(host) && !host.endsWith("onrender.com")) {
      const base = process.env.PUBLIC_URL ? process.env.PUBLIC_URL.replace(/\/+$/, "") : `https://${host}`;
      migrateMedia(base);
    }
  }
  next();
});

// ---------- auth routes ----------

// Pwoteksyon login: apre 5 move modpas nan 15 minit, adrès IP sa a bloke 15 minit.
// (Plis yon limit global: 100 move tantativ nan 15 minit, tout IP ansanm → login an fèmen 15 minit.)
const LOGIN_MAX = 5, LOGIN_WINDOW = 15 * 60 * 1000, LOGIN_GLOBAL_MAX = 100;
const loginFails = new Map(); // ip → { n, first, lockUntil }
let globalFails = { n: 0, first: 0, lockUntil: 0 };
const clientIp = (req) => {
  const sock = req.socket?.remoteAddress || "";
  // Dèyè reverse proxy a (Caddy/Nginx sou menm sèvè a): vrè IP a nan X-Forwarded-For
  if (/^(::1|127\.|::ffff:127\.)/.test(sock)) return String(req.headers["x-forwarded-for"] || sock).split(",").pop().trim(); // dènye a = sa proxy nou an ajoute (pa ka fo)
  return sock;
};
const safeEq = (a, b) => { const x = Buffer.from(String(a)), y = Buffer.from(String(b)); return x.length === y.length && crypto.timingSafeEqual(x, y); };
setInterval(() => { const now = Date.now(); for (const [k, v] of loginFails) if (now - v.first > LOGIN_WINDOW && now > v.lockUntil) loginFails.delete(k); }, 10 * 60 * 1000).unref?.();

app.post("/api/auth/login", async (req, res) => {
  const { username, password } = req.body || {};
  const ip = clientIp(req), now = Date.now();
  const f = loginFails.get(ip);
  const lockedFor = Math.max(f?.lockUntil || 0, globalFails.lockUntil) - now;
  if (lockedFor > 0) {
    return res.status(429).json({ ok: false, error: `Too many attempts. Try again in ${Math.ceil(lockedFor / 60000)} min.` });
  }
  const sub = findAdminLogin(db, username, password);
  if (sub) {
    loginFails.delete(ip);
    const token = jwt.sign({ sub: sub.email, uid: sub.email, role: sub.role }, JWT_SECRET, { expiresIn: JWT_EXPIRES_IN });
    auditLog("login", `User ${sub.email} (${sub.role}) logged in`, sub.email);
    return res.json({ ok: true, token, role: sub.role, name: sub.name });
  }
  if (safeEq(username || "", ADMIN_USER) && safeEq(password || "", ADMIN_PASS)) {
    loginFails.delete(ip);
    const token = jwt.sign({ sub: username, role: "admin" }, JWT_SECRET, {
      expiresIn: JWT_EXPIRES_IN,
    });
    auditLog("login", `User ${username} logged in`, username);
    return res.json({ ok: true, token, role: "owner", name: username });
  }
  // Move modpas: konte l, ralanti repons lan, epi bloke si twòp
  const cur = f && now - f.first < LOGIN_WINDOW ? f : { n: 0, first: now, lockUntil: 0 };
  cur.n++; if (cur.n >= LOGIN_MAX) cur.lockUntil = now + LOGIN_WINDOW;
  loginFails.set(ip, cur);
  if (now - globalFails.first > LOGIN_WINDOW) globalFails = { n: 0, first: now, lockUntil: 0 };
  if (++globalFails.n >= LOGIN_GLOBAL_MAX) globalFails.lockUntil = now + LOGIN_WINDOW;
  if (cur.n === LOGIN_MAX) auditLog("login-blocked", `Login blocked for 15 min after ${LOGIN_MAX} wrong passwords (IP ${ip})`, "security");
  await new Promise((r) => setTimeout(r, 600));
  const left = LOGIN_MAX - cur.n;
  res.status(cur.lockUntil ? 429 : 401).json({ ok: false, error: cur.lockUntil ? "Too many attempts. Try again in 15 min." : `Invalid credentials (${left} attempt${left > 1 ? "s" : ""} left)` });
});

app.post("/api/auth/activate", async (req, res) => {
  const { code, deviceId } = req.body || {};

  // --- input validation ---
  if (!code || typeof code !== "string" || code.trim().length === 0) {
    return res.status(400).json({ ok: false, error: "code is required" });
  }
  if (!deviceId || typeof deviceId !== "string" || deviceId.trim().length === 0) {
    return res.status(400).json({ ok: false, error: "deviceId is required" });
  }

  const full = "BT-" + String(code).trim();
  const entry = db.data.activationCodes.find((c) => c.code === full);
  if (!entry) return res.status(404).json({ ok: false, error: "Code not found" });
  if (entry.status !== "Unused")
    return res.status(400).json({ ok: false, error: `Code is ${entry.status.toLowerCase()}` });

  entry.status = "Active";
  entry.deviceId = deviceId;
  entry.activatedAt = new Date().toISOString();

  await db.write();
  auditLog("activate", `Code ${full} activated for device ${deviceId}`);

  return res.json({
    ok: true,
    code: full,
    deviceId,
    status: "Active",
  });
});

// PROTECT_API=true → tout route panel yo mande JWT (rekòmande pou pwodiksyon,
// men panel la dwe voye "Authorization: Bearer <token>" nan chak demann).
// Pa defo tout route panel yo mande login (PROTECT_API=false sèlman pou tès lokal)
const guard =
  process.env.PROTECT_API === "false" ? (req, res, next) => next() : authenticate;

// ---------- generic collection helper ----------

// Koleksyon ki parèt sou TV yo: lè yo chanje, TV ki konekte yo mete yo ajou touswit
let syncTvs = () => {};
const TV_COLLECTIONS = new Set(["tickers", "chyrons", "banners", "popups", "liveEvents", "mediaAds"]);

function mountCollection(path, key, idField = "id", opts = {}) {
  const tvSync = () => { if (TV_COLLECTIONS.has(key)) syncTvs(); };
  const { present = (x) => x, protectedFields = [], onDelete, keepIfEmpty = [], prepare = (x) => x } = opts;
  const clean = (body) => {
    const item = { ...(body || {}) };
    for (const f of protectedFields) delete item[f];
    // Yon chan vid (egz: password) pa efase valè ki te la deja
    for (const f of keepIfEmpty) if (item[f] === "" || item[f] === undefined) delete item[f];
    return item;
  };
  if (!db.data[key]) {
    db.data[key] = [];
  }
  // Chak eleman bezwen yon ID pou panel la ka modifye l oswa efase l
  for (const item of db.data[key]) {
    if (item && (item[idField] === undefined || item[idField] === null || item[idField] === "")) item[idField] = nanoid(8);
  }

  // GET with pagination: ?page=1&limit=20
  app.get(`/api/${path}`, guard, (req, res) => {
    let items = db.data[key] || [];
    // ?q=tèks → rechèch sou sèvè a (tout chan tèks/nimewo yo)
    const q = String(req.query.q || "").trim().toLowerCase();
    if (q) items = items.filter((it) => it && Object.values(it).some((v) => (typeof v === "string" || typeof v === "number") && String(v).toLowerCase().includes(q)));
    const page = Math.max(parseInt(req.query.page) || 1, 1);
    const limit = Math.min(Math.max(parseInt(req.query.limit) || 20, 1), 5000);
    const start = (page - 1) * limit;
    res.json({
      data: items.slice(start, start + limit).map(present), // prepare sèlman paj yo mande a
      page,
      limit,
      total: items.length,
      totalPages: Math.ceil(items.length / limit),
    });
  });

  // POST – create
  app.post(`/api/${path}`, guard, async (req, res) => {
    let item; try { item = prepare(clean(req.body)); } catch (e) { return res.status(e.status || 400).json({ error: e.message }); }
    if (!item[idField]) item[idField] = nanoid(8);
    db.data[key].push(item);
    await db.write();
    tvSync();
    auditLog("create", `${key}: ${item[idField]}`, req.user?.sub || "anonymous");
    res.status(201).json(present(item));
  });

  // PUT – update
  app.put(`/api/${path}/:id`, guard, async (req, res) => {
    const idx = db.data[key].findIndex((x) => String(x[idField]) === req.params.id);
    if (idx === -1) return res.status(404).json({ error: "Not found" });
    let upd; try { upd = prepare(clean(req.body)); } catch (e) { return res.status(e.status || 400).json({ error: e.message }); }
    db.data[key][idx] = { ...db.data[key][idx], ...upd };
    await db.write();
    tvSync();
    auditLog("update", `${key}: ${req.params.id}`, req.user?.sub || "anonymous");
    res.json(present(db.data[key][idx]));
  });

  // DELETE
  app.delete(`/api/${path}/:id`, guard, async (req, res) => {
    const removed = db.data[key].filter((x) => String(x[idField]) === req.params.id);
    db.data[key] = db.data[key].filter((x) => String(x[idField]) !== req.params.id);
    if (onDelete) removed.forEach(onDelete);
    await db.write();
    tvSync();
    auditLog("delete", `${key}: ${req.params.id}`, req.user?.sub || "anonymous");
    res.status(204).end();
  });
}

mountCollection("customers", "customers", "id");
mountCollection("devices", "devices", "deviceId", {
  // Pa janm voye Device Key nan lis jenerik la; kalkile Online/Offline an dirèk
  present: (d) => {
    const out = presentDevice(d);
    const copy = { ...out };
    for (const f of DEVICE_PRIVATE_FIELDS) delete copy[f];
    return copy;
  },
  protectedFields: DEVICE_PROTECTED_FIELDS,
  // Si yo efase yon aparèy Galaxy nan paj Devices la, efase playlist li yo tou
  onDelete: (d) => {
    if (!d.mac) return;
    const mac = normMac(d.mac);
    delete db.data.devicePlaylists?.[mac];
    delete db.data.deviceChannels?.[mac];
    db.data.deletedDevices ??= [];
    if (!db.data.deletedDevices.includes(mac)) db.data.deletedDevices.push(mac);
  },
});
mountCollection("activation-codes", "activationCodes", "code");
mountCollection("providers", "providers", "id");
mountCollection("channel-profiles", "channelProfiles", "name");
mountCollection("channels", "channels", "name");
mountCollection("programs", "programs", "id");
mountCollection("banners", "banners", "content");
mountCollection("popups", "popups", "title");
mountCollection("tickers", "tickers", "message");
mountCollection("admin-users", "adminUsers", "email", {
  present: ({ passwordHash, password, ...x }) => ({ ...x, hasPassword: !!passwordHash }),
  protectedFields: ["passwordHash", "hasPassword"],
  prepare: (item) => {
    const pw = String(item.password || "");
    delete item.password;
    if (pw) { if (pw.length < 8) throw Object.assign(new Error("The password must have at least 8 characters."), { status: 400 }); item.passwordHash = hashPassword(pw); }
    if (item.email) item.email = String(item.email).trim().toLowerCase();
    return item;
  },
});
mountCollection("countries", "countries", "name");
mountCollection("regions", "regions", "name");
mountCollection("categories", "categories", "name");
mountCollection("languages", "languages", "name");
mountCollection("live-events", "liveEvents", "title");
mountCollection("subscriptions", "subscriptions", "id");
mountCollection("media-ads", "mediaAds", "id");
mountCollection("chyrons", "chyrons", "id"); // Chyron / Lower third (plizyè, chak ak estil pa l)

// Galaxy TV Stick: aparèy MAC, playlist, config pou app TV a
mountGalaxy(app, db, { authenticate, auditLog, syncTvs: () => syncTvs() }); // playlist/aparèy chanje → TV yo mete yo ajou touswit
// Galaxy TV Stick: sa TV yo ap gade an dirèk, kòmand a distans, ak paj panel /galaxy
const galaxyLive = mountGalaxyLive(app, db, { authenticate, auditLog });
syncTvs = galaxyLive.broadcastSync; // TV yo mete yo ajou touswit lè panel la chanje yon bagay
// Kopi done otomatik chak jou (7 dènye yo) + lis/telechajman nan paj Sistèm nan
mountAutoBackup(app, db, { authenticate, auditLog });

// Efase tout jounal aksyon yo (bouton "Efase tout" nan paj Audit Logs)
app.delete("/api/audit-logs", guard, async (req, res) => {
  const n = (db.data.auditLogs || []).length;
  db.data.auditLogs = [];
  await db.write();
  auditLog("audit-clear", `${n} audit log entries cleared`, req.user?.sub || "anonymous");
  res.json({ ok: true, cleared: n });
});

app.get("/api/audit-logs", guard, (req, res) => {
  const limit = Math.min(Math.max(parseInt(req.query.limit) || 1000, 1), AUDIT_MAX);
  res.json((db.data.auditLogs || []).slice(-limit));
});

app.get("/api/settings", guard, (req, res) => res.json(db.data.settings || {}));
app.put("/api/settings", guard, async (req, res) => {
  db.data.settings = { ...db.data.settings, ...req.body };
  await db.write();
  syncTvs();
  auditLog("update", "settings updated", req.user?.sub || "anonymous");
  res.json(db.data.settings);
});

// Lyen kout pou telechaje app la sou Fire Stick la (app "Downloader"): https://…onrender.com/apk
const APK_URL = process.env.APK_URL || "https://github.com/bentechtvstick-glitch/bentech-backend/releases/download/apk-latest/GalaxyTvStick.apk";
app.get(["/apk", "/galaxy.apk"], (req, res) => res.redirect(302, APK_URL));

// "ok" sèlman si sèvè a gen done (omwen yon TV). App la chwazi adrès panel la ak /health:
// konsa yon nouvo sèvè vid (demenajman) pa pran TV yo anvan done yo fin remete sou li.
app.get("/api/health", (req, res) => {
  const ready = (db.data.devices || []).some((d) => d.mac) || process.env.ALWAYS_READY === "1";
  res.json({ ok: ready, ready, time: new Date().toISOString() });
});

app.get("/", (req, res) => {
  res.json({
    ok: true,
    app: "BenTech Backend",
    version: "1.0.0",
    status: "Running",
  });
});

app.get("/api/version", (req, res) => {
  res.json({
    app: "BenTech TV",
    version: "1.0.0",
  });
});

app.get("/api/dashboard", guard, (req, res) => {
  res.json({
    customers: (db.data.customers || []).length,
    devices: (db.data.devices || []).length,
    activationCodes: (db.data.activationCodes || []).length,
    channels: (db.data.channels || []).length,
  });
});

// ---------- error handling middleware (must be last) ----------

app.use((err, req, res, _next) => {
  console.error("Unhandled error:", err);
  res.status(err.status || 500).json({
    ok: false,
    error: err.message || "Internal server error",
  });
});

const PORT = process.env.PORT || 4000;
app.listen(PORT, () => console.log(`BenTech backend running on port ${PORT}`));
