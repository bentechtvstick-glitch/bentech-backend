import express from "express";
import cors from "cors";
import jwt from "jsonwebtoken";
import { JSONFilePreset } from "lowdb/node";
import { nanoid } from "nanoid";

const db = await JSONFilePreset("./db.json", {});
const app = express();
app.use(cors());
app.use(express.json());

// JWT configuration
const JWT_SECRET = process.env.JWT_SECRET || "change-me-before-production";
const JWT_EXPIRES_IN = "24h";

// Simple admin auth with real JWT
const ADMIN_USER = process.env.ADMIN_USER || "admin";
const ADMIN_PASS = process.env.ADMIN_PASS || "changeme";

// ---------- helpers ----------

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

// ---------- auth routes ----------

app.post("/api/auth/login", (req, res) => {
  const { username, password } = req.body || {};
  if (username === ADMIN_USER && password === ADMIN_PASS) {
    const token = jwt.sign({ sub: username, role: "admin" }, JWT_SECRET, {
      expiresIn: JWT_EXPIRES_IN,
    });
    auditLog("login", `User ${username} logged in`, username);
    return res.json({ ok: true, token });
  }
  res.status(401).json({ ok: false, error: "Invalid credentials" });
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

// ---------- generic collection helper ----------

function mountCollection(path, key, idField = "id") {
  if (!db.data[key]) {
    db.data[key] = [];
  }

  // GET with pagination: ?page=1&limit=20
  app.get(`/api/${path}`, (req, res) => {
    const items = db.data[key] || [];
    const page = Math.max(parseInt(req.query.page) || 1, 1);
    const limit = Math.min(Math.max(parseInt(req.query.limit) || 20, 1), 100);
    const start = (page - 1) * limit;
    const paginated = items.slice(start, start + limit);
    res.json({
      data: paginated,
      page,
      limit,
      total: items.length,
      totalPages: Math.ceil(items.length / limit),
    });
  });

  // POST – create
  app.post(`/api/${path}`, async (req, res) => {
    const item = { ...req.body };
    if (!item[idField]) item[idField] = nanoid(8);
    db.data[key].push(item);
    await db.write();
    auditLog("create", `${key}: ${item[idField]}`, req.user?.sub || "anonymous");
    res.status(201).json(item);
  });

  // PUT – update
  app.put(`/api/${path}/:id`, async (req, res) => {
    const idx = db.data[key].findIndex((x) => String(x[idField]) === req.params.id);
    if (idx === -1) return res.status(404).json({ error: "Not found" });
    db.data[key][idx] = { ...db.data[key][idx], ...req.body };
    await db.write();
    auditLog("update", `${key}: ${req.params.id}`, req.user?.sub || "anonymous");
    res.json(db.data[key][idx]);
  });

  // DELETE
  app.delete(`/api/${path}/:id`, async (req, res) => {
    db.data[key] = db.data[key].filter((x) => String(x[idField]) !== req.params.id);
    await db.write();
    auditLog("delete", `${key}: ${req.params.id}`, req.user?.sub || "anonymous");
    res.status(204).end();
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

app.get("/api/audit-logs", (req, res) => res.json(db.data.auditLogs || []));

app.get("/api/settings", (req, res) => res.json(db.data.settings || {}));
app.put("/api/settings", async (req, res) => {
  db.data.settings = { ...db.data.settings, ...req.body };
  await db.write();
  auditLog("update", "settings updated", req.user?.sub || "anonymous");
  res.json(db.data.settings);
});

app.get("/api/health", (req, res) => res.json({ ok: true, time: new Date().toISOString() }));

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

app.get("/api/dashboard", (req, res) => {
  res.json({
    customers: db.data.customers.length,
    devices: db.data.devices.length,
    activationCodes: db.data.activationCodes.length,
    channels: db.data.channels.length,
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
