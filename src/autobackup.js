// Kopi done otomatik: chak jou (vè 3è dimaten lè sèvè a) panel la ekri yon kopi konprese
// tout done yo nan yon dosye "backups" bò kote baz done a, epi li kenbe 7 dènye yo.
// Admin nan ka wè lis la, fè yon kopi kounye a, oswa telechaje youn nan paj Sistèm nan.
// (Kopi yo sou menm disk la: yo pwoteje kont erè ak done ki gate. Pou yon pàn disk, telechaje youn sou òdinatè w tanzantan.)
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";

const KEEP = Number(process.env.BACKUP_KEEP || 7);
const NAME_RE = /^galaxy-auto-(\d{4}-\d{2}-\d{2}T\d{2}-\d{2}(?:-\d{2})?)\.json\.gz$/;

export function mountAutoBackup(app, db, { authenticate, auditLog }) {
  const dbFile = path.resolve(process.env.DB_FILE || "./db.json");
  const dir = process.env.BACKUP_DIR || path.join(path.dirname(dbFile), "backups");
  const enabled = !process.env.RENDER && process.env.AUTO_BACKUP !== "0";

  const list = () => {
    try {
      return fs.readdirSync(dir).filter((f) => NAME_RE.test(f)).sort().reverse()
        .map((f) => { const st = fs.statSync(path.join(dir, f)); return { name: f, size: st.size, at: st.mtimeMs }; });
    } catch { return []; }
  };

  let running = null;
  async function makeBackup(reason = "auto") {
    if (running) return running;
    running = (async () => {
      fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
      // Pa janm plen disk la: si mwens pase 10% disk la rete lib, pa fè kopi a (panel la ap avèti w)
      try { const st = fs.statfsSync(dir); if (st.blocks && st.bavail / st.blocks < 0.10) throw new Error("Not enough free disk space for a backup (less than 10% left)."); }
      catch (e) { if (/free disk/.test(e.message)) throw e; }
      const stamp = new Date().toISOString().slice(0, 19).replace(/:/g, "-");
      const name = `galaxy-auto-${stamp}.json.gz`;
      const tmp = path.join(dir, name + ".tmp");
      const json = JSON.stringify({ galaxyBackup: 1, savedAt: new Date().toISOString(), auto: reason, data: db.data });
      const gz = await new Promise((ok, ko) => zlib.gzip(json, { level: 6 }, (e, b) => (e ? ko(e) : ok(b))));
      fs.writeFileSync(tmp, gz, { mode: 0o600 });
      fs.renameSync(tmp, path.join(dir, name));
      // Kenbe sèlman KEEP dènye yo
      for (const old of list().slice(KEEP)) { try { fs.unlinkSync(path.join(dir, old.name)); } catch {} }
      db.data.settings ??= {}; db.data.settings.lastAutoBackupAt = new Date().toISOString();
      return { name, size: gz.length };
    })().finally(() => { running = null; });
    return running;
  }

  // Yon fwa pa jou: tcheke chak 30 minit si dènye kopi a gen plis pase 20 è (oswa si l poko janm fèt)
  if (enabled) {
    const due = () => { const last = list()[0]; return !last || Date.now() - last.at > 20 * 3600000; };
    const tick = () => {
      const h = new Date().getHours();
      if (due() && (h >= 3 || !list().length)) makeBackup("auto").catch((e) => console.error("Auto backup failed:", e.message));
    };
    setTimeout(tick, 2 * 60000).unref?.();
    setInterval(tick, 30 * 60000).unref?.();
  }

  app.get("/api/galaxy/backups", authenticate, (req, res) => {
    res.setHeader("Cache-Control", "no-store");
    res.json({ enabled, keep: KEEP, items: list() });
  });

  app.post("/api/galaxy/backups/now", authenticate, async (req, res) => {
    try {
      const r = await makeBackup("manual");
      auditLog?.("backup", "Server backup created", req.user?.sub || "admin");
      res.json({ ok: true, ...r, items: list() });
    } catch (e) { res.status(500).json({ error: e.message }); }
  });

  app.get("/api/galaxy/backups/:name", authenticate, (req, res) => {
    const name = String(req.params.name || "");
    if (!NAME_RE.test(name)) return res.status(400).json({ error: "Bad name" });
    const file = path.join(dir, name);
    if (!fs.existsSync(file)) return res.status(404).json({ error: "Not found" });
    // Voye l dekonprese (.json): menm fòma ak bouton "Remete yon kopi" a
    res.setHeader("Content-Type", "application/json; charset=utf-8");
    res.setHeader("Content-Disposition", `attachment; filename="${name.replace(/\.gz$/, "")}"`);
    res.setHeader("Cache-Control", "no-store");
    fs.createReadStream(file).pipe(zlib.createGunzip()).on("error", () => res.end()).pipe(res);
    auditLog?.("backup", `Server backup ${name} downloaded`, req.user?.sub || "admin");
  });
}
