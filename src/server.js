import express from "express";
import cors from "cors";
import jwt from "jsonwebtoken";
import { JSONFilePreset } from "lowdb/node";
import { nanoid } from "nanoid";

import {
  getPackages,
  createActiveCode,
  getActiveCode,
  getActivatedCodes,
  getInactiveCodes,
  extendActiveCode,
  refundActiveCode,
} from "./goldenott.js";

const DB_PATH =
  process.env.NODE_ENV === "production"
    ? "/data/db.json"
    : "./db.json";

const db = await JSONFilePreset(DB_PATH, {});
const app = express();

app.use(cors());
app.use(express.json());

const JWT_SECRET =
  process.env.JWT_SECRET || "change-me-before-production";

const JWT_EXPIRES_IN = "24h";

const ADMIN_USER =
  process.env.ADMIN_USER || "admin";

const ADMIN_PASS =
  process.env.ADMIN_PASS || "changeme";

function auditLog(action, detail, user = "system") {
  if (!db.data.auditLogs) {
    db.data.auditLogs = [];
  }

  db.data.auditLogs.push({
    id: nanoid(8),
    action,
    detail,
    user,
    timestamp: new Date().toISOString(),
  });

  db.write().catch(() => {});
}

function authenticate(req, res, next) {
  const header = req.headers.authorization;

  if (!header || !header.startsWith("Bearer ")) {
    return res.status(401).json({
      ok: false,
      error: "Missing or invalid token",
    });
  }

  try {
    const token = header.split(" ")[1];
    req.user = jwt.verify(token, JWT_SECRET);
    next();
  } catch {
    return res.status(401).json({
      ok: false,
      error: "Token expired or invalid",
    });
  }
}

// -----------------------------
// Admin login
// -----------------------------
app.post("/api/auth/login", (req, res) => {
  const { username, password } = req.body || {};

  if (username === ADMIN_USER && password === ADMIN_PASS) {
    const token = jwt.sign(
      {
        sub: username,
        role: "admin",
      },
      JWT_SECRET,
      { expiresIn: JWT_EXPIRES_IN }
    );

    auditLog("login", `User ${username} logged in`, username);

    return res.json({
      ok: true,
      token,
    });
  }

  return res.status(401).json({
    ok: false,
    error: "Invalid credentials",
  });
});

// -----------------------------
// GoldenOTT packages
// -----------------------------
app.get("/api/goldenott/packages", async (req, res) => {
  try {
    const data = await getPackages();
    res.json(data);
  } catch (error) {
    res.status(error.status || 500).json({
      ok: false,
      error: error.message,
    });
  }
});

// -----------------------------
// GoldenOTT create code
// -----------------------------
app.post("/api/goldenott/active-codes", async (req, res) => {
  try {
    const data = await createActiveCode(req.body || {});
    res.json(data);
  } catch (error) {
    res.status(error.status || 500).json({
      ok: false,
      error: error.message,
    });
  }
});

// -----------------------------
// GoldenOTT activated codes
// -----------------------------
app.get("/api/goldenott/active-codes/activated", async (req, res) => {
  try {
    const data = await getActivatedCodes();
    res.json(data);
  } catch (error) {
    res.status(error.status || 500).json({
      ok: false,
      error: error.message,
    });
  }
});

// -----------------------------
// GoldenOTT single code
// -----------------------------
app.get("/api/goldenott/active-codes/:codeId", async (req, res) => {
  try {
    const data = await getActiveCode(req.params.codeId);
    res.json(data);
  } catch (error) {
    res.status(error.status || 500).json({
      ok: false,
      error: error.message,
    });
  }
});

// -----------------------------
// GoldenOTT extend
// -----------------------------
app.post(
  "/api/goldenott/active-codes/:codeId/extend",
  async (req, res) => {
    try {
      const data = await extendActiveCode(
        req.params.codeId,
        req.body?.package_id
      );

      res.json(data);
    } catch (error) {
      res.status(error.status || 500).json({
        ok: false,
        error: error.message,
      });
    }
  }
);

// -----------------------------
// GoldenOTT refund
// -----------------------------
app.post(
  "/api/goldenott/active-codes/:codeId/refund",
  async (req, res) => {
    try {
      const data = await refundActiveCode(req.params.codeId);
      res.json(data);
    } catch (error) {
      res.status(error.status || 500).json({
        ok: false,
        error: error.message,
      });
    }
  }
);

// -----------------------------
// Device activation
// -----------------------------
app.post("/api/auth/activate", async (req, res) => {
  try {
    const rawCode = String(req.body?.code || "").trim();
    const deviceId = String(req.body?.deviceId || "").trim();

    if (!rawCode) {
      return res.status(400).json({
        ok: false,
        error: "code is required",
      });
    }

    if (!deviceId) {
      return res.status(400).json({
        ok: false,
        error: "deviceId is required",
      });
    }

    let allCodes = [];

    try {
      const activated = await getActivatedCodes();
      const activatedItems =
        activated?.data ||
        activated?.items ||
        activated ||
        [];

      if (Array.isArray(activatedItems)) {
        allCodes.push(...activatedItems);
      }
    } catch {}

    try {
      const inactive = await getInactiveCodes();
      const inactiveItems =
        inactive?.data ||
        inactive?.items ||
        inactive ||
        [];

      if (Array.isArray(inactiveItems)) {
        allCodes.push(...inactiveItems);
      }
    } catch {}

    const match = allCodes.find(
      (item) =>
        String(
          item?.code ??
          item?.activation_code ??
          item?.customer_code ??
          ""
        ).trim() === rawCode
    );

    if (!match) {
      return res.status(404).json({
        ok: false,
        error: "Activation code was not found in GoldenOTT",
      });
    }

    const goldenottCodeId =
      Number(match.id ?? match.codeId ?? match.code_id);

    if (!goldenottCodeId) {
      return res.status(500).json({
        ok: false,
        error: "GoldenOTT code ID is missing",
      });
    }

    let current = match;

    try {
      current = await getActiveCode(goldenottCodeId);
    } catch {}

    const maxConnections = Number(
      current?.max_connections ??
      match?.max_connections ??
      1
    );

    const localCodes = db.data.activationCodes || [];

    let localCode = localCodes.find(
      (x) =>
        String(x.code || "").replace(/^BT-/, "") === rawCode
    );

    const configuredDevices = Number(
      localCode?.devicesAllowed ??
      localCode?.limit ??
      maxConnections
    );

    const devicesAllowed = Math.max(
      1,
      Math.min(configuredDevices, maxConnections)
    );

    if (!db.data.devices) {
      db.data.devices = [];
    }

    const existingDevices = db.data.devices.filter(
      (d) =>
        String(d.goldenottCodeId) === String(goldenottCodeId) ||
        String(d.activationCode).replace(/^BT-/, "") === rawCode
    );

    const alreadyRegistered = existingDevices.some(
      (d) => String(d.deviceId) === deviceId
    );

    if (!alreadyRegistered && existingDevices.length >= devicesAllowed) {
      return res.status(400).json({
        ok: false,
        error: "Maximum number of devices reached",
        devicesAllowed,
        devicesUsed: existingDevices.length,
      });
    }

    if (!alreadyRegistered) {
      db.data.devices.push({
        deviceId,
        activationCode: rawCode,
        goldenottCodeId,
        status: "Active",
        activatedAt: new Date().toISOString(),
      });
    }

    if (localCode) {
      localCode.status = "Active";
      localCode.goldenottCodeId = goldenottCodeId;
      localCode.deviceId = deviceId;
      localCode.devicesAllowed = devicesAllowed;
      localCode.activatedAt =
        localCode.activatedAt || new Date().toISOString();
    }

    await db.write();

    auditLog(
      "activate",
      `GoldenOTT code ${rawCode} activated for device ${deviceId}`
    );

    return res.json({
      ok: true,
      code: rawCode,
      goldenottCodeId,
      deviceId,
      status: "Active",
      packageId:
        current?.package_id ??
        current?.packageId ??
        match?.package_id ??
        null,
      packageName:
        current?.package_name ??
        current?.packageName ??
        match?.package_name ??
        null,
      devicesAllowed,
      devicesUsed: existingDevices.length + (alreadyRegistered ? 0 : 1),
      maxConnections,
    });
  } catch (error) {
    console.error("Activation error:", error);

    return res.status(error.status || 500).json({
      ok: false,
      error: error.message || "Activation failed",
    });
  }
});

// -----------------------------
// Generic collections
// -----------------------------
function mountCollection(path, key, idField = "id") {
  if (!db.data[key]) {
    db.data[key] = [];
  }

  app.get(`/api/${path}`, (req, res) => {
    const items = db.data[key] || [];

    const page = Math.max(
      parseInt(req.query.page) || 1,
      1
    );

    const limit = Math.min(
      Math.max(parseInt(req.query.limit) || 20, 1),
      100
    );

    const start = (page - 1) * limit;

    res.json({
      data: items.slice(start, start + limit),
      page,
      limit,
      total: items.length,
      totalPages: Math.ceil(items.length / limit),
    });
  });

  app.post(`/api/${path}`, async (req, res) => {
    const item = { ...req.body };

    if (!item[idField]) {
      item[idField] = nanoid(8);
    }

    db.data[key].push(item);
    await db.write();

    auditLog(
      "create",
      `${key}: ${item[idField]}`,
      req.user?.sub || "anonymous"
    );

    res.status(201).json(item);
  });

  app.put(`/api/${path}/:id`, async (req, res) => {
    const idx = db.data[key].findIndex(
      (x) => String(x[idField]) === req.params.id
    );

    if (idx === -1) {
      return res.status(404).json({
        ok: false,
        error: "Not found",
      });
    }

    db.data[key][idx] = {
      ...db.data[key][idx],
      ...req.body,
    };

    await db.write();

    res.json(db.data[key][idx]);
  });

  app.delete(`/api/${path}/:id`, async (req, res) => {
    db.data[key] = db.data[key].filter(
      (x) => String(x[idField]) !== req.params.id
    );

    await db.write();

    res.status(204).end();
  });
}

mountCollection("customers", "customers", "id");
mountCollection("devices", "devices", "deviceId");
mountCollection("activation-codes", "activationCodes", "code");
mountCollection("providers", "providers", "id");
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
mountCollection("subscriptions", "subscriptions", "id");

app.get("/api/audit-logs", (req, res) => {
  res.json(db.data.auditLogs || []);
});

app.get("/api/settings", (req, res) => {
  res.json(db.data.settings || {});
});

app.put("/api/settings", async (req, res) => {
  db.data.settings = {
    ...(db.data.settings || {}),
    ...req.body,
  };

  await db.write();

  res.json(db.data.settings);
});

app.get("/api/health", (req, res) => {
  res.json({
    ok: true,
    time: new Date().toISOString(),
  });
});

app.get("/", (req, res) => {
  res.json({
    ok: true,
    app: "BenTech Backend",
    version: "2.0.0",
    status: "Running",
  });
});

app.get("/api/version", (req, res) => {
  res.json({
    app: "BenTech TV",
    version: "2.0.0",
  });
});

app.get("/api/dashboard", (req, res) => {
  res.json({
    customers: (db.data.customers || []).length,
    devices: (db.data.devices || []).length,
    activationCodes: (db.data.activationCodes || []).length,
    channels: (db.data.channels || []).length,
  });
});

app.use((err, req, res, _next) => {
  console.error("Unhandled error:", err);

  res.status(err.status || 500).json({
    ok: false,
    error: err.message || "Internal server error",
  });
});

const PORT = process.env.PORT || 4000;

app.listen(PORT, () => {
  console.log(`BenTech backend running on port ${PORT}`);
});
