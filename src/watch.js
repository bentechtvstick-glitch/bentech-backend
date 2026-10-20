// Siveyans panel la ("AI a ki veyatif"): règ ki pa koute anyen tcheke eta panel la chak 10 minit,
// epi jwenn pwoblèm yo ak koreksyon yo pwopoze. Admin nan deside: panel la PA JANM korije anyen pou kont li
// (bouton yo nan Dashboard la fè aksyon an sèlman lè admin nan peze yo).
// AI a (Claude Haiku) ekri yon ti rezime sèlman lè lis pwoblèm yo chanje (pou depans lan rete piti).
import fs from "node:fs";
import path from "node:path";
import { aiComplete, aiBudget, aiLangName, aiKey } from "./ai.js";
import { createNotifier, mountNotify } from "./notify.js";

const H = 3600000;

export function mountWatch(app, db, h) {
  const { authenticate, latestBuild, customerOf, toEpochSec, tz, playlistsOf, isOnline, isExpired, pkgCheck } = h;
  const data = () => db.data;
  const state = () => (data().watch ??= {});

  const diskFreePct = () => {
    try {
      const st = fs.statfsSync(path.dirname(path.resolve(process.env.DB_FILE || "./db.json")));
      return st.blocks ? Math.round((st.bavail / st.blocks) * 100) : null;
    } catch { return null; }
  };
  const buildOf = (v) => { const m = String(v || "").match(/^1\.0\.(\d+)/); return m ? Number(m[1]) : 0; };
  const pkgOf = (d) => !!(d.channelProfile && (data().channelProfiles || []).find((p) => p.name === d.channelProfile && p.pkg === true));
  // Idantifye yon TV pa kliyan an + MAC li (pa modèl aparèy la: "Fire TV Stick 4K" pa di w kiyès li ye)
  const devName = (d) => { const c = (customerOf(d)?.name || d.customer || "").trim(); return c ? `${c} · ${d.mac}` : d.mac; };

  /** Tout pwoblèm panel la wè kounye a. Chak pwoblèm: { id, kind, level, ...done, actions }. */
  async function issues() {
    const d = data(), now = Date.now(), out = [];
    const devices = (d.devices || []).filter((x) => x.mac && !x.blocked && playlistsOf(x.mac).length);

    // (TV ki etenn / offline pa yon pwoblèm: kliyan an ka jis pa gade TV. Panel la pa siyale yo.)

    // 2) Pwoblèm chanèl/videyo nan dènye 3 è yo
    const recent = (d.channelAlerts || []).filter((a) => now - a.at < 3 * H);
    // a) Yon TV ki gen anpil chanèl diferan ki pa mache → entènèt TV a oswa kont founisè a
    const byMac = new Map();
    for (const a of recent) { if (!byMac.has(a.mac)) byMac.set(a.mac, []); byMac.get(a.mac).push(a); }
    for (const [mac, list] of byMac) {
      const x = (d.devices || []).find((v) => v.mac === mac); if (!x) continue;
      const refused = list.filter((a) => /HTTP (401|403|429|458|509)\b/.test(a.detail || ""));
      if (refused.length >= 2) {
        const code = (refused[0].detail.match(/HTTP (\d{3})/) || [])[1] || "";
        out.push({ id: "ref:" + mac, kind: "provider_refused", level: "warn", mac, name: devName(x), code, count: refused.length,
          actions: [{ type: "goto_device", mac }] });
        continue;
      }
      const distinct = new Set(list.map((a) => `${a.type}:${a.channelId}`)).size;
      if (distinct >= 4) out.push({ id: "net:" + mac, kind: "tv_many_fail", level: "warn", mac, name: devName(x), count: distinct,
        actions: [{ type: "reload_tv", mac }, { type: "goto_device", mac }] });
    }
    // b) Menm chanèl la ki pa mache sou plizyè TV (oswa plizyè fwa) → chanèl la tonbe kay founisè a
    const byCh = new Map();
    for (const a of recent) { if (a.type !== "live" || !a.channelId) continue; const k = String(a.channelId); if (!byCh.has(k)) byCh.set(k, []); byCh.get(k).push(a); }
    for (const [cid, list] of byCh) {
      const macs = [...new Set(list.map((a) => a.mac))], times = list.reduce((n, a) => n + (a.count || 1), 0);
      if (macs.length < 2 && times < 3) continue;
      const hideable = macs.filter((m) => { const x = (d.devices || []).find((v) => v.mac === m); return x && !pkgOf(x) && !(x.hiddenChannels || []).map(Number).includes(Number(cid)); });
      out.push({ id: "ch:" + cid, kind: "channel_down", level: macs.length >= 2 ? "warn" : "info", channelId: Number(cid), channel: list[0].channel || "#" + cid,
        tvs: macs.length, times, detail: String(list[0].detail || "").slice(0, 160),
        actions: [...(hideable.length ? [{ type: "hide_channel", macs: hideable, channelId: Number(cid) }] : []), ...(macs.length === 1 ? [{ type: "reload_tv", mac: macs[0] }] : [])] });
    }

    // 2c) Entènèt yon kliyan fèb: TV a koupe souvan (oswa vitès li ba), pandan lòt TV yo mache byen
    const nets = devices.filter((x) => x.net && now - (x.net.at || 0) < 6 * H);
    for (const x of nets) {
      const n = x.net, enough = (n.n || 0) >= 3;
      const others = nets.filter((o) => o !== x && o.net.cuts != null && (o.net.n || 0) >= 3).map((o) => o.net.cuts);
      const avgOthers = others.length ? others.reduce((a, b) => a + b, 0) / others.length : 0;
      const cutsBad = enough && n.cuts >= 2.5 && (!others.length || n.cuts >= Math.max(1, avgOthers * 2));
      const slow = n.bw > 0 && n.bw < 3000;
      if (!cutsBad && !(slow && enough && n.cuts >= 1)) continue;
      out.push({ id: "net2:" + x.mac, kind: "weak_net", level: "warn", mac: x.mac, name: devName(x),
        cuts: Math.round((n.cuts || 0) * 10) / 10, mbps: n.bw ? Math.round(n.bw / 100) / 10 : null, startSec: n.start ? Math.round(n.start / 100) / 10 : null,
        actions: [{ type: "send_tip", mac: x.mac }, { type: "goto_device", mac: x.mac }] });
    }

    // 2d) Pakè chanèl ki pa ka aplike sou yon TV (non gwoup yo pa menm ak founisè TV sa a, pakè efase, oswa profil san règ)
    for (const x of devices) {
      const k = pkgCheck ? pkgCheck(x) : null; if (!k) continue;
      let why = "";
      if (k.missing) why = "missing";
      else if (k.pkg === false) why = "notpkg";
      else if (k.pkg && !k.noList && k.groupsInPkg > 0 && k.groupsMatched === 0 && k.channelsMatched === 0) why = "nomatch";
      else if (k.pkg && !k.noList && k.mode === "hide" && k.visible === k.total && (k.groupsInPkg + k.channelsInPkg) > 0) why = "nochange";
      if (why) out.push({ id: "pkg:" + x.mac + ":" + k.profile + ":" + why, kind: "pkg_problem", level: "warn", mac: x.mac, name: devName(x), profile: k.profile, why,
        groups: k.groupsInPkg || 0, matched: k.groupsMatched || 0, actions: [{ type: "goto_channels", mac: x.mac }] });
    }

    // 3) Mizajou app la: TV ki gen yon ansyen vèsyon (aktif nan 30 dènye jou yo, kliyan an pa ekspire)
    const l = await latestBuild(false).catch(() => ({}));
    if (l?.build) {
      const seen30 = (x) => now - (Date.parse(x.lastSeenAt || "") || 0) < 30 * 24 * H;
      const old = devices.filter((x) => seen30(x) && !isExpired(x) && buildOf(x.appVersion) && buildOf(x.appVersion) < l.build);
      const pushed = Number(d.settings?.updatePushBuild) || 0, pushedAt = Date.parse(d.settings?.updatePushAt || "") || 0;
      const names = old.map((x) => devName(x) || x.mac);
      if (old.length && pushed < l.build) {
        // Yon nouvo vèsyon pare men li poko voye bay TV yo
        out.push({ id: "upd:" + l.build, kind: "old_app", level: "warn", count: old.length, latest: `1.0.${l.build}`,
          names: names.slice(0, 6), versions: [...new Set(old.map((x) => x.appVersion))].slice(0, 4), actions: [{ type: "push_update" }] });
      } else if (old.length && pushed >= l.build && now - pushedAt > 24 * H) {
        // Mizajou a voye depi plis pase 24 è, men TV sa yo (ki te limen depi lè sa a) poko mete l
        const stuck = old.filter((x) => (Date.parse(x.lastSeenAt || "") || 0) > pushedAt);
        if (stuck.length) out.push({ id: "upds:" + l.build, kind: "update_stuck", level: "warn", count: stuck.length, latest: `1.0.${l.build}`,
          names: stuck.map((x) => devName(x) || x.mac).slice(0, 6), forced: !!d.settings?.updateForce,
          actions: [...(d.settings?.updateForce ? [] : [{ type: "force_update" }]), ...(stuck.length === 1 ? [{ type: "goto_device", mac: stuck[0].mac }] : [])] });
      }
    }

    // 4) Kliyan k ap ekspire nan 3 jou
    const zone = tz(), nowSec = Math.floor(now / 1000);
    for (const c of d.customers || []) {
      if (!c.expiry || String(c.status || "").toLowerCase() === "suspended") continue;
      const exp = toEpochSec(c.expiry, zone, true); if (!exp) continue;
      const days = Math.ceil((exp - nowSec) / 86400);
      if (days >= 0 && days <= 3) out.push({ id: "exp:" + (c.id || c.name) + ":" + c.expiry, kind: "expiring", level: days <= 1 ? "warn" : "info",
        customer: c.name || "", days, actions: [{ type: "goto_customers" }] });
    }

    // 5) Disk sèvè a
    const free = diskFreePct();
    if (free != null && free < 15) out.push({ id: "disk", kind: "disk_low", level: free < 7 ? "crit" : "warn", freePct: free, actions: [{ type: "goto_system" }] });

    // 5b) Modpas pwopriyetè a twò fèb (oswa li rete valè egzanp lan)
    const ap = process.env.ADMIN_PASS || "";
    if (!(db.data.ownerAuth?.passwordHash && process.env.RESET_OWNER !== "1") && (!ap || ap === "changeme" || ap.length < 10)) out.push({ id: "weakpw", kind: "weak_password", level: "crit", actions: [] });

    // 5c) Peman pou sonje (sèvè, domèn…): X jou anvan dat la, jiskaske admin nan di li peye
    {
      const p = Object.fromEntries(new Intl.DateTimeFormat("en-CA", { timeZone: tz(), year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(new Date(now)).map((x) => [x.type, x.value]));
      const today = Date.UTC(+p.year, +p.month - 1, +p.day);
      for (const b of d.bills || []) {
        const yearly = b.every === "year";
        let y = +p.year, m = yearly ? (b.month || 1) - 1 : +p.month - 1;
        const dueOf = (yy, mm) => Date.UTC(yy, mm, Math.min(b.day, new Date(Date.UTC(yy, mm + 1, 0)).getUTCDate()));
        const key = (yy, mm) => yearly ? String(yy) : `${yy}-${String(mm + 1).padStart(2, "0")}`;
        if (b.paidMonth === key(y, m) || today - dueOf(y, m) > 5 * 864e5) { if (yearly) y++; else { m++; if (m > 11) { m = 0; y++; } } }
        if (b.paidMonth === key(y, m)) continue;
        const days = Math.round((dueOf(y, m) - today) / 864e5);
        if (days > b.before) continue;
        out.push({ id: `bill:${b.id}:${key(y, m)}`, kind: "bill_due", level: days <= 1 ? "crit" : "warn", name: b.name, amount: b.amount || "", days,
          date: new Date(dueOf(y, m)).toISOString().slice(0, 10), actions: [{ type: "bill_paid", id: b.id, month: key(y, m) }] });
      }
    }

    // 6) Bidjè AI a
    const b = aiBudget(db);
    if (aiKey() && b.limit > 0 && b.cost >= b.limit * 0.8) out.push({ id: "aib:" + new Date().toISOString().slice(0, 7), kind: "ai_budget", level: "info",
      cost: Math.round(b.cost * 100) / 100, limit: b.limit, actions: [] });

    // Sa admin nan di "pa kounye a" pou yo rete kache 24 è
    const dis = state().dismissed || {};
    const order = { crit: 0, warn: 1, info: 2 };
    return out.filter((i) => !(dis[i.id] > now)).sort((a, b) => order[a.level] - order[b.level]);
  }

  /** Rezime AI a: sèlman si lis pwoblèm yo chanje, maks yon fwa chak 3 è (oswa touswit pou yon pwoblèm grav). */
  async function summarize(list, force = false) {
    const s = state();
    if (!aiKey()) return;
    const key = list.map((i) => i.id).sort().join("|");
    if (!list.some((i) => i.level !== "info")) { if (s.summary) { s.summary = ""; s.summaryKey = ""; db.write().catch(() => {}); } return; }
    if (!force && key === s.summaryKey) return;
    const crit = list.some((i) => i.level === "crit");
    if (!force && !crit && Date.now() - (s.summaryAt || 0) < 3 * H) return;
    const b = aiBudget(db); if (b.left < 0.05) return; // kite yon ti rès pou kesyon admin nan
    const lang = aiLangName(s.lang || "en");
    const system = "You watch an IPTV admin panel (Galaxy TV Stick: Android TV / Fire TV app; streams come from the customer's IPTV provider, not from this panel). " +
      "You get a JSON list of problems the panel detected. Write a short heads-up for the non-technical owner: what matters most first, the likely cause, " +
      "and which fix button to press (the panel shows buttons: 'Reload this TV', 'Hide this channel', 'Send the app update', 'Open the device'). " +
      `Answer in ${lang}, max 6 short lines, simple words, no greeting. Never invent facts that are not in the list. Never say you fixed anything: the owner decides. Always name a TV by its customer and MAC exactly as given in the list, never by device model.`;
    try {
      const r = await aiComplete(db, { system, user: JSON.stringify(list.filter((i) => i.level !== "info").map(({ actions, ...i }) => i)), maxTokens: 350 });
      Object.assign(s, { summary: r.text, summaryAt: Date.now(), summaryKey: key, summaryLang: s.lang || "en" });
      db.write().catch(() => {});
    } catch { /* limit oswa rezo: n ap eseye ankò pi ta */ }
  }

  // Siveyans an aryè plan
  // Notifikasyon sou telefòn (app ntfy)
  const notifier = createNotifier(db);
  mountNotify(app, db, notifier, { authenticate });
  setTimeout(() => notifier.started(), 20000).unref?.();
  const tick = async () => { try { const list = await issues(); await notifier.onIssues(list); await summarize(list); } catch {} };
  setTimeout(tick, 60000);
  setInterval(tick, 10 * 60000).unref?.();

  app.get("/api/galaxy/watch", authenticate, async (req, res) => {
    if (req.query.lang && ["ht", "en", "fr", "es"].includes(req.query.lang)) state().lang = req.query.lang;
    const list = await issues();
    const s = state();
    res.json({ issues: list, summary: s.summary || "", summaryAt: s.summaryAt || 0, aiOn: !!aiKey() });
  });

  /** Mande AI a rezime kounye a (bouton "Analize kounye a"). */
  app.post("/api/galaxy/watch/analyze", authenticate, async (req, res) => {
    if (req.body?.lang && ["ht", "en", "fr", "es"].includes(req.body.lang)) state().lang = req.body.lang;
    const list = await issues();
    if (!list.length) { Object.assign(state(), { summary: "", summaryKey: "", summaryAt: Date.now() }); return res.json({ issues: list, summary: "" }); }
    await summarize(list, true);
    res.json({ issues: list, summary: state().summary || "", summaryAt: state().summaryAt || 0 });
  });

  /** "Pa kounye a": kache yon pwoblèm pou 24 è. */
  app.post("/api/galaxy/watch/dismiss", authenticate, async (req, res) => {
    const id = String(req.body?.id || "").slice(0, 200); if (!id) return res.status(400).json({ error: "Bad request" });
    const s = state(); s.dismissed ??= {};
    const now = Date.now(); for (const [k, v] of Object.entries(s.dismissed)) if (v < now) delete s.dismissed[k];
    s.dismissed[id] = now + 24 * H;
    await db.write();
    res.json({ ok: true });
  });
}
