// ---------------------------------------------------------------------------
// Galaxy TV Stick — "An dirèk": sa chak TV ap gade kounye a + kòmand a distans.
//
// - App la voye estati li (chanèl, pwogram, kalite) chak fwa li chanje chanèl.
// - App la kenbe yon demann "long-poll" ouvè pou l resevwa kòmand panel la
//   an mwens pase yon segonn (chanje chanèl, mesaj, rechaje, redemare, dekonekte).
// - Tout sa rete an memwa: yo pa chaje db.json, epi yo pa gen enpòtans si sèvè a redemare.
// ---------------------------------------------------------------------------
import express from "express";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { normMac } from "./galaxy.js";

const ONLINE_MS = 75_000;           // TV a "online" si li pale ak sèvè a nan dènye 75 segonn yo
const COMMAND_TTL_MS = 2 * 60_000;  // yon kòmand TV a pa resevwa nan 2 minit anile
const MAX_WAIT_S = 25;
const HISTORY = 25;

export const COMMAND_TYPES = {
  message: "Voye yon mesaj sou ekran an",
  play: "Chanje chanèl",
  reload: "Rechaje lis chanèl yo",
  restart: "Redemare app la",
  logout: "Dekonekte playlist la sou TV a",
  refresh: "Aplike chanjman panel la kounye a",
};

export function mountGalaxyLive(app, db, { authenticate, auditLog }) {
  const live = new Map();     // MAC → dènye estati
  const history = new Map();  // MAC → [ {id,num,name,at} ]
  const queues = new Map();   // MAC → [ kòmand ]
  const waiters = new Map();  // MAC → Set(fonksyon pou reponn long-poll la)

  const findDevice = (mac) => (db.data.devices || []).find((d) => d.mac === mac || d.deviceId === mac);

  /** Verifye MAC + Device Key app la voye. Retounen aparèy la oswa null (e li deja reponn). */
  const deviceFromApp = (req, res) => {
    const mac = normMac(req.params.mac);
    const device = findDevice(mac);
    if (!device) { res.status(404).json({ ok: false, error: "Aparèy pa jwenn" }); return null; }
    if (device.deviceKey && req.get("X-Device-Key") !== device.deviceKey) {
      res.status(403).json({ ok: false, error: "Device Key pa bon" }); return null;
    }
    device.lastSeenAt = new Date().toISOString();
    return device;
  };

  const isOnline = (mac) => {
    if (waiters.get(mac)?.size) return true;
    const d = findDevice(mac);
    const t = Math.max(live.get(mac)?.updatedAt || 0, d?.lastSeenAt ? Date.parse(d.lastSeenAt) : 0);
    return Date.now() - t < ONLINE_MS;
  };

  const pendingFor = (mac) => {
    const q = (queues.get(mac) || []).filter((c) => Date.now() - c.createdAt < COMMAND_TTL_MS);
    queues.set(mac, q);
    return q;
  };

  /** Voye kòmand ki ap tann yo bay TV a si l gen yon long-poll ouvè. */
  const flush = (mac) => {
    const set = waiters.get(mac);
    const q = pendingFor(mac);
    if (!set?.size || !q.length) return false;
    const cmds = q.splice(0);
    const [first] = set;
    first(cmds);
    return true;
  };

  // =========================================================================
  // 1) APP TV A
  // =========================================================================

  /** App la di sa l ap montre kounye a. */
  app.post("/api/devices/:mac/status", (req, res) => {
    const device = deviceFromApp(req, res);
    if (!device) return;
    const mac = device.mac;
    const b = req.body || {};
    const prev = live.get(mac);
    const ch = b.channel && typeof b.channel === "object"
      ? {
          id: Number(b.channel.id) || 0,
          num: Number(b.channel.num) || 0,
          name: String(b.channel.name || "").slice(0, 200),
          category: String(b.channel.category || "").slice(0, 200),
        }
      : null;
    const changed = ch && ch.id !== prev?.channel?.id;
    const state = {
      screen: String(b.screen || "player"),
      channel: ch,
      program: b.program && typeof b.program === "object"
        ? { title: String(b.program.title || "").slice(0, 200), start: Number(b.program.start) || 0, end: Number(b.program.end) || 0 }
        : null,
      quality: String(b.quality || ""),
      resolution: String(b.resolution || ""),
      codec: String(b.codec || ""),
      buffering: !!b.buffering,
      error: b.error ? String(b.error).slice(0, 200) : "",
      playlistName: String(b.playlistName || ""),
      appVersion: String(b.appVersion || device.appVersion || ""),
      since: changed || !prev ? Date.now() : prev.since,
      updatedAt: Date.now(),
    };
    live.set(mac, state);
    if (changed) {
      const h = history.get(mac) || [];
      h.unshift({ id: ch.id, num: ch.num, name: ch.name, at: Date.now() });
      history.set(mac, h.slice(0, HISTORY));
    }
    res.json({ ok: true });
  });

  /** Long-poll: TV a tann kòmand panel la (repons touswit si genyen, sinon apre ~25 s). */
  app.get("/api/devices/:mac/commands", (req, res) => {
    const device = deviceFromApp(req, res);
    if (!device) return;
    const mac = device.mac;
    const q = pendingFor(mac);
    if (q.length) return res.json({ commands: q.splice(0) });

    const wait = Math.min(Math.max(parseInt(req.query.wait, 10) || 20, 0), MAX_WAIT_S);
    if (!wait) return res.json({ commands: [] });

    let done = false;
    const set = waiters.get(mac) || new Set();
    waiters.set(mac, set);
    const finish = (commands) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      set.delete(finish);
      if (!res.headersSent) res.json({ commands });
    };
    const timer = setTimeout(() => finish([]), wait * 1000);
    set.add(finish);
    // "close" sou res (pa sou req: nan Node resan, req fèmen touswit pou yon GET)
    res.on?.("close", () => { if (!done) { done = true; clearTimeout(timer); set.delete(finish); } });
  });

  // =========================================================================
  // 2) PANEL ADMIN NAN (JWT)
  // =========================================================================

  const liveView = (d) => {
    const mac = d.mac;
    return {
      mac,
      deviceName: d.deviceName || "",
      customer: d.customer || "",
      model: d.model || "",
      blocked: !!d.blocked,
      online: isOnline(mac),
      live: live.get(mac) || null,
      pendingCommands: pendingFor(mac).length,
    };
  };

  /** Tout TV Galaxy yo, sa ki online an premye, ak sa yo ap gade. */
  app.get("/api/galaxy/live", authenticate, (req, res) => {
    const list = (db.data.devices || []).filter((d) => d.mac).map(liveView);
    list.sort((a, b) => (b.online - a.online) || ((b.live?.updatedAt || 0) - (a.live?.updatedAt || 0)));
    res.json({
      online: list.filter((x) => x.online).length,
      watching: list.filter((x) => x.online && x.live?.channel && ["player", "vod", "catchup"].includes(x.live.screen)).length,
      devices: list,
    });
  });

  app.get("/api/galaxy/devices/:mac/live", authenticate, (req, res) => {
    const d = findDevice(normMac(req.params.mac));
    if (!d) return res.status(404).json({ ok: false, error: "Aparèy pa jwenn" });
    res.json({ ...liveView(d), history: history.get(d.mac) || [] });
  });

  /** Voye yon kòmand bay yon TV. */
  app.post("/api/galaxy/devices/:mac/commands", authenticate, (req, res) => {
    const d = findDevice(normMac(req.params.mac));
    if (!d) return res.status(404).json({ ok: false, error: "Aparèy pa jwenn" });
    const b = req.body || {};
    const type = String(b.type || "");
    if (!COMMAND_TYPES[type]) return res.status(400).json({ ok: false, error: "Kòmand sa a pa egziste" });
    const cmd = { id: Math.random().toString(36).slice(2, 10), type, createdAt: Date.now() };
    if (type === "message") {
      cmd.text = String(b.text || "").trim().slice(0, 500);
      if (!cmd.text) return res.status(400).json({ ok: false, error: "Mesaj la vid" });
    }
    if (type === "play") {
      cmd.channelId = Number(b.channelId);
      if (!Number.isFinite(cmd.channelId) || cmd.channelId <= 0) return res.status(400).json({ ok: false, error: "Chwazi yon chanèl" });
    }
    const q = pendingFor(d.mac);
    q.push(cmd);
    const delivered = flush(d.mac);
    auditLog("device-command", `${type} → ${d.mac}`, req.user?.sub || "admin");
    res.status(201).json({ ok: true, command: cmd, delivered, online: isOnline(d.mac) });
  });

  /**
   * Panel la chanje yon bagay ki parèt sou TV yo (paramèt, ticker, chyron, banner…):
   * voye yon siyal "sync" an silans bay tout TV ki konekte, pou yo chèche nouvo konfig la touswit
   * (olye yo tann pwochen minit lan). Regroupe chanjman ki rive youn dèyè lòt.
   */
  let syncTimer = null;
  const broadcastSync = () => {
    clearTimeout(syncTimer);
    syncTimer = setTimeout(() => {
      for (const [mac, set] of waiters) {
        if (!set?.size) continue;
        const q = pendingFor(mac);
        if (!q.some((c) => c.type === "sync")) q.push({ id: Math.random().toString(36).slice(2, 10), type: "sync", createdAt: Date.now() });
        flush(mac);
      }
    }, 400);
  };

  // =========================================================================
  // 3) PAJ PANEL GALAXY LA (/galaxy)
  // =========================================================================
  const here = path.dirname(fileURLToPath(import.meta.url));
  const publicDir = path.join(here, "..", "public", "galaxy");
  app.use("/galaxy", express.static(publicDir));

  return { broadcastSync };
}
