// Notifikasyon sou telefòn admin nan, ak app gratis "ntfy" (iPhone/Android, pa bezwen kont).
// Panel la voye yon mesaj sou yon "topic" sekrè o aza; admin nan abòne ak topic sa a nan app la.
// Sa k voye: pwoblèm siveyans lan jwenn (grav, oswa enpòtan + grav), lè sèvè a redemare, ak yon tès.
// Chak pwoblèm voye yon sèl fwa chak 24 è maksimòm (pa gen spam).
import crypto from "node:crypto";

const H = 3600000;
const NTFY = (process.env.NTFY_URL || "https://ntfy.sh").replace(/\/+$/, "");

// Tèks kout yo, nan lang admin nan (lang li itilize nan panel la)
const TX = {
  ht: {
    tv_offline: (i) => [`📴 TV ${i.name || i.mac} pa konekte`, `Depi ${i.hours} è, pandan kliyan an toujou aktif.`],
    provider_refused: (i) => [`🚫 Founisè a refize kont ${i.name || i.mac}`, `HTTP ${i.code}: kont playlist la ka ekspire oswa gen twòp ekran.`],
    tv_many_fail: (i) => [`📡 ${i.count} chanèl pa mache sou ${i.name || i.mac}`, "Sanble se entènèt TV sa a. Eseye rechaje l nan panel la."],
    weak_net: (i) => [`📶 Entènèt ${i.name || i.mac} fèb`, `Li koupe ${i.cuts} fwa chak 10 minit${i.mbps ? `, vitès ${i.mbps} Mbps` : ""}. Se koneksyon kliyan an, pa founisè a.`],
    channel_down: (i) => [`📺 Chanèl ${i.channel} pa mache`, `Sou ${i.tvs} TV (${i.times} fwa). Li ka tonbe kay founisè a.`],
    old_app: (i) => [`📲 Nouvo vèsyon app ${i.latest} pare`, `${i.count} TV poko genyen l (${(i.names || []).join(", ")}). Voye l nan System → App update.`],
    update_stuck: (i) => [`📲 ${i.count} TV poko mete ${i.latest}`, `${(i.names || []).join(", ")}: kliyan an poko peze "Mete ajou".${i.forced ? "" : " Ou ka fè l obligatwa."}`],
    expiring: (i) => [`⏰ Abònman ${i.customer}`, i.days ? `Ap fini nan ${i.days} jou.` : "Ap fini jodi a."],
    disk_low: (i) => ["💾 Disk sèvè a prèske plen", `Sèlman ${i.freePct}% ki rete.`],
    weak_password: () => ["🔑 Modpas panel la twò fèb", "Chanje ADMIN_PASS sou sèvè a."],
    ai_budget: (i) => ["🤖 Bidjè AI a", `$${i.cost} sou $${i.limit} mwa sa a.`],
    start: () => ["🔄 Panel la redemare", "Sèvè a fèk limen ankò. Si w pa t fè l, tcheke paj Sistèm nan."],
    test: () => ["✅ Tès Galaxy", "Notifikasyon yo mache sou telefòn ou."],
    open: "Louvri panel la",
  },
  en: {
    tv_offline: (i) => [`📴 TV ${i.name || i.mac} offline`, `For ${i.hours} h while the customer is still active.`],
    provider_refused: (i) => [`🚫 Provider refused ${i.name || i.mac}`, `HTTP ${i.code}: the playlist account may be expired or have too many screens.`],
    tv_many_fail: (i) => [`📡 ${i.count} channels failing on ${i.name || i.mac}`, "Looks like this TV's internet. Try reloading it from the panel."],
    weak_net: (i) => [`📶 Weak internet at ${i.name || i.mac}`, `Buffering ${i.cuts} times per 10 min${i.mbps ? `, speed ${i.mbps} Mbps` : ""}. It's the customer's connection, not the provider.`],
    channel_down: (i) => [`📺 Channel ${i.channel} is down`, `On ${i.tvs} TV (${i.times} times). It may be down at the provider.`],
    old_app: (i) => [`📲 New app version ${i.latest} is ready`, `${i.count} TV don't have it yet (${(i.names || []).join(", ")}). Send it from System → App update.`],
    update_stuck: (i) => [`📲 ${i.count} TV still haven't installed ${i.latest}`, `${(i.names || []).join(", ")}: the customer hasn't pressed "Update" yet.${i.forced ? "" : " You can make it mandatory."}`],
    expiring: (i) => [`⏰ ${i.customer}'s subscription`, i.days ? `Ends in ${i.days} day(s).` : "Ends today."],
    disk_low: (i) => ["💾 Server disk almost full", `Only ${i.freePct}% left.`],
    weak_password: () => ["🔑 Panel password too weak", "Change ADMIN_PASS on the server."],
    ai_budget: (i) => ["🤖 AI budget", `$${i.cost} of $${i.limit} this month.`],
    start: () => ["🔄 Panel restarted", "The server just came back on. If you didn't do it, check the System page."],
    test: () => ["✅ Galaxy test", "Notifications work on your phone."],
    open: "Open the panel",
  },
};
TX.fr = { ...TX.en,
  tv_offline: (i) => [`📴 TV ${i.name || i.mac} hors ligne`, `Depuis ${i.hours} h, le client est toujours actif.`],
  channel_down: (i) => [`📺 La chaîne ${i.channel} ne marche pas`, `Sur ${i.tvs} TV (${i.times} fois).`],
  start: () => ["🔄 Panneau redémarré", "Le serveur vient de redémarrer."], test: () => ["✅ Test Galaxy", "Les notifications marchent."], open: "Ouvrir le panneau" };
TX.es = { ...TX.en,
  tv_offline: (i) => [`📴 TV ${i.name || i.mac} desconectada`, `Hace ${i.hours} h, el cliente sigue activo.`],
  channel_down: (i) => [`📺 El canal ${i.channel} no funciona`, `En ${i.tvs} TV (${i.times} veces).`],
  start: () => ["🔄 Panel reiniciado", "El servidor acaba de reiniciar."], test: () => ["✅ Prueba Galaxy", "Las notificaciones funcionan."], open: "Abrir el panel" };

export function createNotifier(db) {
  const cfg = () => (db.data.notify ??= { on: false, topic: "", level: "warn", sent: {} });
  const lang = () => TX[db.data.watch?.lang] || TX.ht;
  const panelUrl = () => (process.env.PUBLIC_URL || "https://panel.galaxytvstick.net").replace(/\/+$/, "") + "/galaxy";

  const sendText = (title, body, opts) => sendRaw(title, body, opts);
  async function sendRaw(title, body, { priority = 3, tags = "" } = {}) {
    const c = cfg();
    if (!c.topic) throw new Error("No topic");
    // Fòma JSON ntfy: tit/tèks an UTF-8 san pwoblèm
    const r = await fetch(NTFY, {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ topic: c.topic, title, message: String(body).slice(0, 1000), priority, tags: tags ? tags.split(",") : [], click: panelUrl() }),
      signal: AbortSignal.timeout(15000),
    });
    if (!r.ok) throw new Error(`ntfy HTTP ${r.status}`);
  }

  /** Voye pwoblèm ki nouvo yo (dapre nivo admin nan chwazi a), yon fwa chak 24 è maksimòm. */
  async function onIssues(list) {
    const c = cfg(); if (!c.on || !c.topic) return;
    const now = Date.now(); c.sent ??= {};
    for (const [k, v] of Object.entries(c.sent)) if (now - v > 48 * H) delete c.sent[k];
    const want = list.filter((i) => (c.level === "crit" ? i.level === "crit" : i.level !== "info") && !(now - (c.sent[i.id] || 0) < 24 * H));
    for (const i of want.slice(0, 5)) {
      const f = lang()[i.kind]; if (!f) continue;
      const [title, body] = f(i);
      try { await sendText(title, body, { priority: i.level === "crit" ? 5 : 4, tags: i.level === "crit" ? "rotating_light" : "warning" }); c.sent[i.id] = now; } catch {}
    }
    if (want.length) db.write().catch(() => {});
  }

  return {
    cfg, sendText, onIssues,
    texts: () => lang(),
    async started() {
      const c = cfg(); if (!c.on || !c.topic || c.noStart) return;
      const [t, b] = lang().start(); sendText(t, b, { priority: 3, tags: "arrows_counterclockwise" }).catch(() => {});
    },
    newTopic: () => "galaxy-" + crypto.randomBytes(12).toString("hex"),
  };
}

export function mountNotify(app, db, notifier, { authenticate }) {
  const view = () => { const c = notifier.cfg(); return { on: !!c.on, topic: c.topic || "", level: c.level || "warn", server: (process.env.NTFY_URL || "https://ntfy.sh").replace(/\/+$/, "") }; };
  app.get("/api/galaxy/notify", authenticate, (req, res) => res.json(view()));
  app.put("/api/galaxy/notify", authenticate, async (req, res) => {
    const c = notifier.cfg(), b = req.body || {};
    if (typeof b.on === "boolean") c.on = b.on;
    if (["warn", "crit"].includes(b.level)) c.level = b.level;
    if (b.newTopic || (c.on && !c.topic)) { c.topic = notifier.newTopic(); c.sent = {}; }
    await db.write();
    res.json(view());
  });
  app.post("/api/galaxy/notify/test", authenticate, async (req, res) => {
    const c = notifier.cfg();
    if (!c.topic) { c.topic = notifier.newTopic(); await db.write(); }
    try { const [t, b] = notifier.texts().test(); await notifier.sendText(t, b, { priority: 4, tags: "white_check_mark" }); res.json({ ok: true, ...view() }); }
    catch (e) { res.status(502).json({ error: "Could not send: " + e.message }); }
  });
}
