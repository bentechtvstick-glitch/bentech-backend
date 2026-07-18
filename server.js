import express from "express";
import cors from "cors";
import { JSONFilePreset } from "lowdb/node";
import { nanoid } from "nanoid";

const db = await JSONFilePreset("./db.json", {});
const app = express();
app.use(cors());
app.use(express.json());
 
// Simple admin auth. Replace with real auth (JWT/sessions) before going to production.
const ADMIN_USER = process.env.ADMIN_USER || "admin";
const ADMIN_PASS = process.env.ADMIN_PASS || "changeme";

app.post("/api/auth/login", (req, res) => {
  const { username, password } = req.body || {};
  if (username === ADMIN_USER && password === ADMIN_PASS) {
    return res.json({ ok: true, token: "demo-token" }); // swap for a real signed token
  }
  res.status(401).json({ ok: false, error: "Invalid credentials" });
});

app.post("/api/auth/activate", (req, res) => {
const { code, deviceId } = req.body || {};
 const full = "BT-" + String(code || "").trim();
  const entry = db.data.activationCodes.find((c) => c.code === full);
  if (!entry) return res.status(404).json({ ok: false, error: "Code not found" });
  if (entry.status !== "Unused") return res.status(400).json({ ok: false, error: `Code is ${entry.status.toLowerCase()}` });
  entry.status = "Active";
entry.deviceId = deviceId;
entry.activatedAt = new Date().toISOString();

await db.write();

return res.json({
    ok: true,
    code: full,
    deviceId,
    status: "Active"
});
 
// Generic collection helper — mounts GET/POST/PUT/DELETE for a named array in db.json
function mountCollection(path, key, idField = "id") {
  app.get(`/api/${path}`, (req, res) => res.json(db.data[key] || []));

  app.post(`/api/${path}`, async (req, res) => {
    const item = { ...req.body };
    if (!item[idField]) item[idField] = nanoid(8);
    db.data[key].push(item);
    await db.write();
    res.status(201).json(item);
  });

  app.put(`/api/${path}/:id`, async (req, res) => {
    const idx = db.data[key].findIndex((x) => String(x[idField]) === req.params.id);
    if (idx === -1) return res.status(404).json({ error: "Not found" });
    db.data[key][idx] = { ...db.data[key][idx], ...req.body };
    await db.write();
    res.json(db.data[key][idx]);
  });

  app.delete(`/api/${path}/:id`, async (req, res) => {
    db.data[key] = db.data[key].filter((x) => String(x[idField]) !== req.params.id);
    await db.write();
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
  res.json(db.data.settings);
});

app.get("/api/health", (req, res) => res.json({ ok: true, time: new Date().toISOString() }));

app.get("/", (req, res) => {
  res.json({
    ok: true,
    app: "BenTech Backend",
    version: "1.0.0",
    status: "Running"
  });
});

app.get("/api/version", (req, res) => {
  res.json({
    app: "BenTech TV",
    version: "1.0.0"
  });
});

app.get("/api/dashboard", (req, res) => {
  res.json({
    customers: db.data.customers.length,
    devices: db.data.devices.length,
    activationCodes: db.data.activationCodes.length,
    channels: db.data.channels.length
  });
});

const PORT = process.env.PORT || 4000;
app.listen(PORT, () => console.log(`BenTech backend running on port ${PORT}`));
