// ---------------------------------------------------------------------------
// Revandè (wòl "Reseller" nan Admin Users): yo wè ak jere SÈLMAN pwòp kliyan ak TV yo.
// - Yon TV ki "ap tann aktivasyon" (pa gen playlist, pa gen mèt) vin pou revandè a depi li louvri l.
// - Kliyan revandè a kreye yo make owner = imèl li.
// - Lis yo (kliyan, TV, an dirèk, konfimasyon) filtre pou li sèlman.
// Middleware sa a mache apre accessControl (ki mete req.role ak req.uid).
// ---------------------------------------------------------------------------
const normMac = (v) => { const h = String(v || "").toUpperCase().replace(/[^0-9A-F]/g, ""); return h.length === 12 ? h.match(/.{2}/g).join(":") : ""; };
const same = (a, b) => String(a || "").trim().toLowerCase() === String(b || "").trim().toLowerCase();

export function resellerScope(db) {
  const devices = () => db.data.devices || [];
  const customers = () => db.data.customers || [];
  const pls = (mac) => (db.data.devicePlaylists || {})[mac] || [];
  const deny = (res, msg) => res.status(403).json({ ok: false, error: msg, forbidden: true });

  /** TV sa a pou revandè a? Si l "ap tann aktivasyon" san mèt, li vin pou li. */
  function claim(me, mac) {
    const d = devices().find((x) => x.mac === mac);
    if (!d) return { ok: true }; // pa egziste: route la ap di 404
    if (d.owner) return { ok: same(d.owner, me), d };
    if (pls(mac).length) return { ok: false, d }; // TV yon lòt moun (pwopriyetè a) ki deja aktive
    d.owner = me;
    return { ok: true, d, claimed: true };
  }
  const myCustomer = (me, idOrName) => customers().find((c) => (c.id === idOrName || same(c.name, idOrName)) && same(c.owner, me));

  return async (req, res, next) => {
    if (req.role !== "Reseller") return next();
    const me = req.uid || "";
    if (!me) return deny(res, "Reseller account missing.");
    const p = req.path;

    // ---- TV yo ----
    const m = /^\/api\/galaxy\/devices\/([^/]+)/.exec(p);
    if (m) {
      const mac = normMac(m[1] === "lookup" ? req.query?.mac : decodeURIComponent(m[1]));
      if (!mac) return deny(res, "Invalid MAC.");
      const r = claim(me, mac);
      if (!r.ok) return deny(res, "This TV belongs to another account.");
      if (/\/replace$/.test(p) && req.method === "POST") {
        const r2 = claim(me, normMac(req.body?.to));
        if (!r2.ok) return deny(res, "The new TV belongs to another account.");
      }
      // Yon revandè ka sèlman mete pwòp kliyan li sou TV a
      if (req.method === "PATCH" && req.body && typeof req.body.customer === "string" && req.body.customer && !myCustomer(me, req.body.customer)) {
        return res.status(400).json({ ok: false, error: "Choose one of your own customers." });
      }
      if (r.claimed) await db.write().catch(() => {});
    }

    // ---- Kliyan yo ----
    const cm = /^\/api\/customers(?:\/([^/]+))?$/.exec(p);
    if (cm) {
      if (cm[1]) {
        const id = decodeURIComponent(cm[1]);
        const c = customers().find((x) => String(x.id) === id);
        if (c && !same(c.owner, me)) return deny(res, "This customer belongs to another account.");
      }
      if ((req.method === "POST" || req.method === "PUT") && req.body && typeof req.body === "object") req.body.owner = me;
    }

    // ---- Lis yo: filtre repons lan pou revandè a wè pa l sèlman ----
    if (req.method === "GET") {
      const myMacs = () => new Set(devices().filter((d) => same(d.owner, me)).map((d) => d.mac));
      const orig = res.json.bind(res);
      if (p === "/api/customers") {
        res.json = (body) => {
          if (body && Array.isArray(body.data)) { const data = body.data.filter((c) => same(c.owner, me)); return orig({ ...body, data, total: data.length, totalPages: 1 }); }
          return orig(body);
        };
      } else if (p === "/api/galaxy/devices") {
        res.json = (body) => orig(Array.isArray(body) ? body.filter((d) => same(d.owner, me)) : body);
      } else if (p === "/api/galaxy/live") {
        res.json = (body) => {
          if (!body || !Array.isArray(body.devices)) return orig(body);
          const mine = myMacs(), list = body.devices.filter((x) => mine.has(x.mac));
          return orig({ ...body, devices: list, online: list.filter((x) => x.online && x.configured).length,
            watching: list.filter((x) => x.online && x.live?.channel && ["player", "vod", "catchup"].includes(x.live.screen)).length });
        };
      } else if (p === "/api/galaxy/tv-events") {
        res.json = (body) => { const mine = myMacs(); return orig(body && Array.isArray(body.events) ? { ...body, events: body.events.filter((e) => mine.has(e.mac)) } : body); };
      }
    }
    next();
  };
}
