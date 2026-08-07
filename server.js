import express from "express";
import cors from "cors";
import jwt from "jsonwebtoken";
import { JSONFilePreset } from "lowdb/node";
import { nanoid } from "nanoid";

const db = await JSONFilePreset("./db.json", {});
const app = express();
app.use(cors());
app.use(express.json());

// ============================== Auth ==============================
const ADMIN_USER = process.env.ADMIN_USER || "admin";
const ADMIN_PASS = process.env.ADMIN_PASS || "changeme";
const JWT_SECRET = process.env.JWT_SECRET || "bentech-dev-secret-change-me-in-render-env-vars";
const TOKEN_TTL = "12h";

function signToken(username) {
  return jwt.sign({ username }, JWT_SECRET, { expiresIn: TOKEN_TTL });
}

function requireAuth(req, res, next) {
  const header = req.headers.authorization || "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : null;
  if (!token) return res.status(401).json({ ok: false, error: "Missing auth token" });
  try {
    req.user = jwt.verify(token, JWT_SECRET);
    next();
  } catch {
    return res.status(401).json({ ok: false, error: "Invalid or expired session — please log in again" });
  }
}

app.post("/api/auth/login", (req, res) => {
  const { username, password } = req.body || {};
  if (username === ADMIN_USER && password === ADMIN_PASS) {
    return res.json({ ok: true, token: signToken(username) });
  }
  res.status(401).json({ ok: false, error: "Invalid credentials" });
});

// Public: customers use their activation code, not an admin login
app.post("/api/auth/activate", (req, res) => {
  const { code } = req.body || {};
  const full = "BT-" + String(code || "").trim();
  const entry = db.data.activationCodes.find((c) => c.code === full);
  if (!entry) return res.status(404).json({ ok: false, error: "Code not found" });
  if (entry.status !== "Unused") return res.status(400).json({ ok: false, error: `Code is ${entry.status.toLowerCase()}` });
  res.json({ ok: true, code: full });
});

app.get("/api/health", (req, res) => res.json({ ok: true, time: new Date().toISOString() }));

// Everything below this line requires a valid admin session, EXCEPT plain reads (GET):
// the TV app has no admin login and must be able to read channels/programs/tickers/etc.,
// and to report buffering/playback events for Stream Health and Analytics.
const PUBLIC_POST_PATHS = ["/api/buffer-events", "/api/playback-events"];
app.use((req, res, next) => {
  if (req.method === "GET") return next();
  if (req.method === "POST" && PUBLIC_POST_PATHS.includes(req.path)) return next();
  return requireAuth(req, res, next);
});

// ============================== Audit log ==============================
async function logAudit(req, action) {
  db.data.auditLogs = db.data.auditLogs || [];
  db.data.auditLogs.unshift({
    user: req.user?.username || "system",
    action,
    date: new Date().toISOString().slice(0, 16).replace("T", " "),
  });
  db.data.auditLogs = db.data.auditLogs.slice(0, 200); // keep the log from growing forever
  await db.write();
}

// ============================== Validation ==============================
// Lightweight required-field check — keeps bad requests out without a heavy schema library.
function validateRequired(fields) {
  return (req, res, next) => {
    const missing = fields.filter((f) => req.body?.[f] === undefined || req.body?.[f] === "");
    if (missing.length) {
      return res.status(400).json({ ok: false, error: `Missing required field(s): ${missing.join(", ")}` });
    }
    next();
  };
}

const REQUIRED_FIELDS = {
  customers: ["name"],
  channels: ["name"],
  devices: ["deviceName"],
};

// ============================== Generic collection helper ==============================
// Adds GET (list, with ?q= search, ?sort=, ?page=&limit= pagination) / POST / PUT / DELETE
// for a named array in db.json, with validation + audit logging on every write.
function mountCollection(path, key, idField = "id", { skipAudit = false } = {}) {
  app.get(`/api/${path}`, (req, res) => {
    let rows = db.data[key] || [];

    const { q, sort, order = "asc", page, limit } = req.query;
    if (q) {
      const needle = String(q).toLowerCase();
      rows = rows.filter((row) => Object.values(row).some((v) => String(v ?? "").toLowerCase().includes(needle)));
    }
    if (sort) {
      rows = [...rows].sort((a, b) => {
        const av = a[sort], bv = b[sort];
        if (av == null) return 1;
        if (bv == null) return -1;
        const cmp = typeof av === "number" && typeof bv === "number" ? av - bv : String(av).localeCompare(String(bv));
        return order === "desc" ? -cmp : cmp;
      });
    }
    const total = rows.length;
    if (page || limit) {
      const p = Math.max(1, Number(page) || 1);
      const l = Math.max(1, Number(limit) || 20);
      rows = rows.slice((p - 1) * l, p * l);
      return res.json({ rows, total, page: p, limit: l });
    }
    res.json(rows);
  });

  const requiredFields = REQUIRED_FIELDS[key] || [];

  app.post(`/api/${path}`, validateRequired(requiredFields), async (req, res) => {
    try {
      const item = { ...req.body };
      if (!item[idField]) item[idField] = nanoid(8);
      if (db.data[key].some((x) => x[idField] === item[idField])) {
        return res.status(409).json({ ok: false, error: `${idField} "${item[idField]}" already exists` });
      }
      db.data[key].push(item);
      if (!skipAudit) await logAudit(req, `Created ${path} "${item[idField]}"`);
      res.status(201).json(item);
    } catch (e) {
      res.status(500).json({ ok: false, error: "Couldn't save — try again" });
    }
  });

  app.put(`/api/${path}/:id`, async (req, res) => {
    try {
      const idx = db.data[key].findIndex((x) => String(x[idField]) === req.params.id);
      if (idx === -1) return res.status(404).json({ ok: false, error: "Not found" });
      db.data[key][idx] = { ...db.data[key][idx], ...req.body };
      await logAudit(req, `Updated ${path} "${req.params.id}"`);
      res.json(db.data[key][idx]);
    } catch (e) {
      res.status(500).json({ ok: false, error: "Couldn't save — try again" });
    }
  });

  app.delete(`/api/${path}/:id`, async (req, res) => {
    try {
      const before = db.data[key].length;
      db.data[key] = db.data[key].filter((x) => String(x[idField]) !== req.params.id);
      if (db.data[key].length === before) return res.status(404).json({ ok: false, error: "Not found" });
      await logAudit(req, `Deleted ${path} "${req.params.id}"`);
      res.status(204).end();
    } catch (e) {
      res.status(500).json({ ok: false, error: "Couldn't delete — try again" });
    }
  });
}

mountCollection("customers", "customers", "id");
mountCollection("devices", "devices", "deviceId");
mountCollection("activation-codes", "activationCodes", "code");
mountCollection("channel-profiles", "channelProfiles", "name");
mountCollection("channels", "channels", "name");
mountCollection("programs", "programs", "id");
mountCollection("banners", "banners", "content");
mountCollection("popups", "popups", "title");
mountCollection("tickers", "tickers", "message");
mountCollection("admin-users", "adminUsers", "email");
mountCollection("countries", "countries", "name");
mountCollection("regions", "regions", "name");
mountCollection("categories", "categories", "name");
mountCollection("languages", "languages", "name");
mountCollection("live-events", "liveEvents", "title");
mountCollection("providers", "providers", "name");
mountCollection("device-history", "deviceHistory", "id");
mountCollection("media-ads", "mediaAds", "id");
mountCollection("buffer-events", "bufferEvents", "id", { skipAudit: true });
mountCollection("playback-events", "playbackEvents", "id", { skipAudit: true });

app.get("/api/audit-logs", (req, res) => res.json(db.data.auditLogs || []));

app.get("/api/settings", (req, res) => res.json(db.data.settings || {}));
app.put("/api/settings", async (req, res) => {
  db.data.settings = { ...db.data.settings, ...req.body };
  await logAudit(req, "Updated settings");
  await db.write();
  res.json(db.data.settings);
});

// Centralized fallback error handler — catches anything a route didn't handle itself
app.use((err, req, res, next) => {
  console.error(err);
  res.status(500).json({ ok: false, error: "Unexpected server error" });
});

// ============================== Automatic Provider Switch ==============================
// Periodically checks the default provider's health. If it stops responding, automatically
// promotes the next-highest-priority enabled provider to default and logs the switch.
// Note: this only runs while the server process is awake — a sleeping free-tier instance
// checks nothing until it's woken by a request, same as everything else on that tier.
async function checkDefaultProviderHealth() {
  const list = db.data.providers || [];
  const current = list.find((p) => p.isDefault);
  if (!current || !current.url) return;

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8000);
  const t0 = Date.now();
  let healthy = false;
  try {
    await fetch(current.url, { signal: controller.signal });
    healthy = true; // any response at all means the server is reachable
  } catch {
    healthy = false;
  } finally {
    clearTimeout(timeout);
  }

  current.status = healthy ? "Active" : "Unreachable";
  current.latencyMs = healthy ? Date.now() - t0 : null;
  current.lastChecked = new Date().toLocaleString();

  if (!healthy) {
    const next = list
      .filter((p) => p.name !== current.name && p.enabled)
      .sort((a, b) => (a.priority ?? 99) - (b.priority ?? 99))[0];
    if (next) {
      current.isDefault = false;
      next.isDefault = true;
      db.data.auditLogs = db.data.auditLogs || [];
      db.data.auditLogs.unshift({
        user: "system",
        action: `Auto Provider Switch: "${current.name}" was unreachable, switched default to "${next.name}"`,
        date: new Date().toISOString().slice(0, 16).replace("T", " "),
      });
      db.data.auditLogs = db.data.auditLogs.slice(0, 200);
    }
  }
  await db.write();
}

const HEALTH_CHECK_INTERVAL_MS = 2 * 60 * 1000; // every 2 minutes while the server is awake
setInterval(() => {
  checkDefaultProviderHealth().catch((e) => console.error("Provider health check failed:", e));
}, HEALTH_CHECK_INTERVAL_MS);

const PORT = process.env.PORT || 4000;
app.listen(PORT, () => console.log(`BenTech backend running on port ${PORT}`));
