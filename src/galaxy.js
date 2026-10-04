// ---------------------------------------------------------------------------
// Galaxy TV Stick — koneksyon ant app TV a ak panel BenTech la.
//
// App la idantifye chak aparèy ak yon MAC + Device Key. Admin nan antre MAC la
// nan panel la, ajoute playlist Xtream la, epi app la konekte poukont li.
//
// Tout sa ou deja jere nan panel la (tickers, banners, popups, live events,
// channel profiles, customers, settings) voye bay app la atravè /config.
// ---------------------------------------------------------------------------
import crypto from "node:crypto";
import { deviceIndex } from "./perf.js";
import { buildGraphics, fetchWeather, clientIp, ipPlace } from "./graphics.js";

const MAC_RE = /^([0-9A-F]{2}:){5}[0-9A-F]{2}$/;
const ONLINE_MS = 3 * 60 * 1000; // aparèy la "Online" si li pale ak sèvè a nan 3 dènye minit yo

/** Chan ki pa janm soti nan route jenerik /api/devices yo (pa gen modpas oswa kle ki fwit). */
export const DEVICE_PRIVATE_FIELDS = ["deviceKey", "lastIp"];
/** Chan route jenerik yo pa gen dwa chanje (sèlman route Galaxy yo ki pwoteje). */
export const DEVICE_PROTECTED_FIELDS = ["deviceKey", "mac", "lastSeenAt"];

export function normMac(value = "") {
  const hex = String(value).toUpperCase().replace(/[^0-9A-F]/g, "");
  return hex.length === 12 ? hex.match(/.{2}/g).join(":") : String(value).toUpperCase().trim();
}

/** "2 min ago" / "Online" kalkile an dirèk apati lastSeenAt. */
export function presentDevice(d) {
  if (!d || !d.lastSeenAt) return d;
  const ms = Date.now() - Date.parse(d.lastSeenAt);
  return { ...d, status: ms < ONLINE_MS ? "Online" : "Offline", lastSeen: timeAgo(ms) };
}

function timeAgo(ms) {
  const min = Math.floor(ms / 60000);
  if (min < 1) return "Just now";
  if (min < 60) return `${min} min ago`;
  const h = Math.floor(min / 60);
  if (h < 24) return `${h} hour${h > 1 ? "s" : ""} ago`;
  const d = Math.floor(h / 24);
  return `${d} day${d > 1 ? "s" : ""} ago`;
}

const isActive = (x) => {
  if (!x) return false;
  if (x.enabled === false) return false;
  const s = String(x.status ?? x.active ?? "Active").toLowerCase();
  return ["active", "yes", "true", "live", "upcoming", "on"].includes(s);
};
const same = (a, b) => String(a ?? "").trim().toUpperCase() === String(b ?? "").trim().toUpperCase();
const isUrl = (s) => /^https?:\/\//i.test(String(s || "").trim());
const hashId = (s) => crypto.createHash("sha1").update(String(s)).digest("hex").slice(0, 12);

/** Offset (ms) yon zòn lè a yon moman bay. */
function tzOffsetMs(timeZone, utcMs) {
  const dtf = new Intl.DateTimeFormat("en-US", {
    timeZone, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit",
  });
  const p = Object.fromEntries(dtf.formatToParts(new Date(utcMs)).map((x) => [x.type, x.value]));
  const asUtc = Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second);
  return asUtc - Math.floor(utcMs / 1000) * 1000;
}

/**
 * Konvèti "2026-07-12T19:00" (lè lokal panel la) oswa "2026-08-31" an segonn Unix.
 * endOfDay = true → yon dat san lè vle di fen jounen an.
 */
export function toEpochSec(value, timeZone, endOfDay = false) {
  if (!value) return 0;
  if (typeof value === "number") return value > 1e12 ? Math.floor(value / 1000) : value;
  const s = String(value).trim();
  if (/[zZ]$|[+-]\d{2}:?\d{2}$/.test(s)) {
    const t = Date.parse(s);
    return Number.isNaN(t) ? 0 : Math.floor(t / 1000);
  }
  let iso = s.replace(" ", "T");
  if (/^\d{4}-\d{2}-\d{2}$/.test(iso)) iso += endOfDay ? "T23:59:59" : "T00:00:00";
  else if (/T\d{2}:\d{2}$/.test(iso)) iso += ":00";
  const naive = Date.parse(iso + "Z");
  if (Number.isNaN(naive)) return 0;
  // 2 pas pou chanjman lè ete/ivè yo
  let utc = naive - tzOffsetMs(timeZone, naive);
  utc = naive - tzOffsetMs(timeZone, utc);
  return Math.floor(utc / 1000);
}

export function mountGalaxy(app, db, { authenticate, auditLog, syncTvs = () => {} }) {
  const data = () => db.data;
  const ensure = () => {
    const d = data();
    d.devices ??= [];
    d.devicePlaylists ??= {};   // { MAC: [ {id,name,server,username,password,createdAt} ] }
    d.deviceChannels ??= {};    // { MAC: hash lis chanèl li }
    d.channelLists ??= {};      // { hash: [ {id,num,name,categoryId,categoryName} ] } — pataje ant aparèy ki gen menm lis
    d.deletedDevices ??= [];    // MAC admin nan efase (pou app la pa rekreye yo otomatikman)
    d.mediaAds ??= [];
  };
  ensure();

  // Yon sèl fwa: retire 4 fo egzanp Channel Profile ki te vin ak panel la (yo te sèlman yon non + yon chif).
  // Nou efase yo sèlman si yo egzakteman jan yo te ye; yon pakè admin nan kreye (pkg) pa janm touche.
  if (!data().samplesRemoved) {
    const SAMPLES = { "50 Channels Starter": 50, "Sports Plus": 45, "Haiti Bundle": 32, "USA Bundle": 110 };
    const gone = new Set();
    data().channelProfiles = (data().channelProfiles || []).filter((p) => {
      const sample = p.pkg !== true && SAMPLES[p.name] === Number(p.channels);
      if (sample) gone.add(p.name);
      return !sample;
    });
    for (const d of data().devices) if (gone.has(d.channelProfile)) d.channelProfile = "";
    data().samplesRemoved = true;
    db.write().catch(() => {});
  }

  const findDevice = deviceIndex(() => data().devices);
  const playlistsOf = (mac) => (data().devicePlaylists[mac] ??= []);
  const settings = () => data().settings || {};
  const tz = () => settings().timezone || process.env.PANEL_TZ || "America/New_York";
  const admin = (req) => req.user?.sub || "admin";

  /** Lis chanèl yon aparèy (li ka pataje ak lòt aparèy ki sou menm pakè a). */
  const channelsOf = (mac) => {
    const ref = data().deviceChannels[mac];
    if (Array.isArray(ref)) return ref; // ansyen fòma
    return (ref && data().channelLists[ref]) || [];
  };

  /** Efase lis chanèl pèsonn pa itilize ankò. */
  const pruneChannelLists = () => {
    const used = new Set(Object.values(data().deviceChannels).filter((v) => typeof v === "string"));
    for (const h of Object.keys(data().channelLists)) if (!used.has(h)) delete data().channelLists[h];
  };

  // "lastSeen" chanje chak minit pou chak TV: pa reekri tout db.json la chak fwa.
  let lastTouchWrite = 0;
  const touchWrite = () => {
    if (Date.now() - lastTouchWrite < 30_000) return;
    lastTouchWrite = Date.now();
    db.write().catch(() => {});
  };

  // =========================================================================
  // 1) ROUTE APP TV A RELE (pa bezwen JWT; pwoteje pa MAC + Device Key)
  // =========================================================================

  /** App la anrejistre tèt li. Si l te gentan gen yon playlist e sèvè a pèdi done yo, li restore l. */
  app.post("/api/devices/register", async (req, res) => {
    ensure();
    const b = req.body || {};
    const mac = normMac(b.mac || b.deviceId);
    const key = String(b.deviceKey || "").trim();
    if (!MAC_RE.test(mac)) return res.status(400).json({ ok: false, error: "MAC pa valid" });
    if (!/^\d{6}$/.test(key)) return res.status(400).json({ ok: false, error: "Device Key pa valid" });

    let device = findDevice(mac);
    if (device?.deviceKey && device.deviceKey !== key) {
      // Yon lòt aparèy ap eseye itilize MAC sa a
      return res.status(409).json({ ok: false, error: "Device Key pa matche" });
    }

    const nowIso = new Date().toISOString();
    const isFireTv = /amazon|aft/i.test(b.model || "");
    if (!device) {
      device = {
        deviceId: mac,
        mac,
        deviceName: b.model || "Galaxy TV Stick",
        customer: "",
        type: isFireTv ? "Fire TV" : "Android TV",
        status: "Online",
        activated: "",
        lastSeen: "Just now",
        limit: 1,
        blocked: false,
        channelProfile: "",
        maxChannels: 0,
        hiddenChannels: [],
        hiddenCategories: [],
        createdAt: nowIso,
      };
      data().devices.push(device);
      auditLog("device-register", `Device ${mac} registered`);

      // Restore: sèvè a te pèdi done yo, men app la toujou gen playlist panel la te ba li
      const r = b.restorePlaylist;
      if (r && r.username && r.password && !data().deletedDevices.includes(mac)) {
        playlistsOf(mac).push({
          id: String(r.id || crypto.randomUUID()),
          name: String(r.name || r.username),
          server: String(r.server || ""),
          username: String(r.username),
          password: String(r.password),
          createdAt: nowIso,
          restored: true,
        });
        device.activated = nowIso.slice(0, 10);
        auditLog("device-restore", `Playlist restored for ${mac}`);
      }
    }

    device.deviceKey = device.deviceKey || key;
    device.model = b.model || device.model || "";
    device.androidVersion = b.androidVersion || device.androidVersion || "";
    device.appVersion = b.appVersion || device.appVersion || "";
    if (b.xtreamUser) device.xtreamUser = b.xtreamUser;
    device.lastSeenAt = nowIso;
    await db.write();
    res.json({ ok: true });
  });

  /** Konfigirasyon konplè aparèy la. App la rele l chak minit. */
  app.get("/api/devices/:mac/config", async (req, res) => {
    ensure();
    const mac = normMac(req.params.mac);
    const device = findDevice(mac);
    const s = settings();
    const refreshSec = Number(s.appRefreshSec) || 60;

    if (!device) {
      // known:false → app la pa chanje anyen, li jis re-anrejistre tèt li
      return res.json({ known: false, status: "pending", playlists: [], refreshSec: 15 });
    }
    if (device.deviceKey && req.get("X-Device-Key") !== device.deviceKey) {
      return res.status(403).json({ ok: false, error: "Device Key pa bon" });
    }

    device.lastSeenAt = new Date().toISOString();
    touchWrite();

    const now = Math.floor(Date.now() / 1000);
    const zone = tz();
    const playlists = playlistsOf(mac);
    // Chak 6 è: reverifye plan/ekspirasyon kliyan an kay founisè a (an aryè plan, TV a pa tann)
    syncCustomerFromProvider(device).then((c) => { if (c) syncTvs(); }).catch(() => {});

    // ---- Estati ----
    const customer = device.customer
      ? (data().customers || []).find((c) => same(c.name, device.customer) || c.id === device.customer) // majiskil/miniskil pa konte
      : null;
    let status = playlists.length ? "active" : "pending";
    let statusMessage = "";
    if (customer) {
      const cs = String(customer.status || "").toLowerCase();
      if (cs === "suspended") status = "blocked";
      else if (cs === "expired" || (customer.expiry && toEpochSec(customer.expiry, zone, true) < now)) status = "expired";
    }
    if (device.blocked) status = "blocked";
    if (s.maintenanceMode) {
      status = "maintenance";
      statusMessage = s.maintenanceMessage || "";
    }

    // ---- Limit chanèl: maxChannels aparèy la, oswa Channel Profile li ----
    let maxChannels = Number(device.maxChannels) || 0;
    if (!maxChannels && device.channelProfile) {
      const profile = (data().channelProfiles || []).find((p) => p.name === device.channelProfile);
      maxChannels = Number(profile?.channels) || 0;
    }

    // ---- Pakè: si Channel Profile aparèy la se yon pakè, se pakè a ki deside gwoup/chanèl/non TV a wè ----
    const eff = effectiveChannels(device);

    // ---- Ticker (style chèn TV): mesaj ak koulè pa yo, etikèt agoch, separatè, lè adwat ----
    // Chak mesaj ka gen yon orè (start/end nan zòn lè panel la): li parèt sèlman ant de lè sa yo
    const tickerItems = (data().tickers || []).filter(isActive)
      .filter((t) => { const a = toEpochSec(t.start, zone), b = toEpochSec(t.end, zone, true); return (!a || a <= now) && (!b || b >= now); })
      .map((t) => ({ text: String(t.message || "").trim(), color: /^#[0-9a-f]{6}([0-9a-f]{2})?$/i.test(t.color || "") ? t.color : "" }))
      .filter((t) => t.text);
    // Pwochen lè yon mesaj kòmanse oswa fini: TV a rechaje konfig la jis lè sa a
    const tickerEdge = (data().tickers || []).filter(isActive)
      .flatMap((t) => [toEpochSec(t.start, zone), toEpochSec(t.end, zone, true) + 1])
      .filter((x) => x > now).reduce((m, x) => Math.min(m, x - now), Infinity);
    const sep = String(s.tickerSeparator ?? "•");
    const tickerOn = s.tickerActive !== false && String(s.tickerActive).toLowerCase() !== "inactive";
    const ticker = tickerOn && tickerItems.length
      ? {
          text: tickerItems.map((t) => t.text).join(`   ${sep}   `), // pou ansyen vèsyon app la
          items: tickerItems,
          separator: sep,
          textColor: s.tickerTextColor || "#FFFFFF",
          bgColor: s.tickerBgTransparent ? "#00000000" : s.tickerBgColor || "#CC7C4DFF",
          transparent: !!s.tickerBgTransparent, // fon transparan: app la mete lonbraj anba tèks la pou l rete lizib
          speed: Number(s.tickerSpeed) || 5,
          textSize: Math.min(40, Math.max(12, Number(s.tickerTextSize) || 20)), // sp
          label: s.tickerLabelOn === false ? "" : String(s.tickerLabel || ""), // switch etikèt la (tèks la kenbe menm si l etenn)
          labelBg: s.tickerLabelBgTransparent ? "#00000000" : s.tickerLabelBg || "#E50914",
          labelColor: s.tickerLabelColor || "#FFFFFF",
          showClock: !!s.tickerClock,
          clockFormat: String(s.tickerClockFormat) === "12" ? "12" : "24", // lè a: 12h (8:45 PM) oswa 24h (20:45)
          direction: s.tickerDirection === "right" ? "right" : "left", // left = defile de dwat a gòch (tankou chèn TV yo)
          animateEmoji: s.tickerAnimEmoji !== false, // drapo flote, machin kouri, balon vire, kè bat…
          // Animasyon k ap kouri sou ba ticker a (egz: 🏎️💨)
          runner: s.tickerRunnerOn && String(s.tickerRunner || "").trim()
            ? { emoji: String(s.tickerRunner).trim().slice(0, 32), speed: Math.min(10, Math.max(1, Number(s.tickerRunnerSpeed) || 6)), flip: !!s.tickerRunnerFlip }
            : null,
        }
      : null;

    // ---- Banner: imaj (URL) oswa tèks ----
    const banners = (data().banners || []).filter(isActive).map((b) => {
      const img = b.imageUrl || (isUrl(b.content) ? b.content : "");
      return {
        id: b.id || hashId(b.content || img),
        imageUrl: img,
        text: img ? "" : String(b.content || ""),
        position: b.position || "top",
      };
    }).filter((b) => b.imageUrl || b.text);

    // ---- Pop-up nan dat yo ----
    const popups = (data().popups || []).filter((p) => {
      if (!isActive(p)) return false;
      const start = toEpochSec(p.start, zone);
      const end = toEpochSec(p.end, zone, true);
      return (!start || start <= now) && (!end || end >= now);
    }).map((p) => ({
      id: p.id || hashId(`${p.title}|${p.start}|${p.end}`),
      title: p.title || "",
      message: p.message || p.content || p.body || "",
      imageUrl: p.imageUrl || null,
      showOnce: p.showOnce !== false,
    }));

    // ---- Media Ads ----
    const ads = (data().mediaAds || []).filter(isActive).filter((a) => a.url).map((a) => ({
      id: a.id || hashId(a.url),
      name: String(a.name || ""),
      type: a.type === "video" || /\.(mp4|m3u8|webm|mkv)(\?|$)/i.test(a.url) ? "video" : "image",
      url: a.url,
      placement: a.placement || "corner",
      durationSec: Number(a.durationSec) || 10,
      skipAfterSec: Number(a.skipAfterSec ?? 5),
    }));

    // ---- Koupi piblisite (tankou chèn TV): spot yo pase plen ekran pandan kliyan an ap gade live ----
    const orderOf = new Map((data().mediaAds || []).map((a, i) => [String(a.id), Number.isFinite(Number(a.order)) && a.order !== "" && a.order != null ? Number(a.order) : 1e6 + i]));
    const breakSpots = ads.filter((a) => a.placement === "break").sort((a, b) => (orderOf.get(String(a.id)) ?? 0) - (orderOf.get(String(b.id)) ?? 0));
    const adBreak = breakSpots.length
      ? {
          auto: !!s.adBreakAuto,
          everyMin: s.adBreakEveryMin === undefined || s.adBreakEveryMin === "" ? 15 : Math.min(240, Math.max(0, Number(s.adBreakEveryMin) || 0)), // chak konbyen minit (0 = sèlman lè fiks)
          times: String(s.adBreakTimes || "").split(/[\s,;]+/).filter((t) => /^([01]?\d|2[0-3]):[0-5]\d$/.test(t)).map((t) => t.padStart(5, "0")), // lè fiks (zòn lè TV a)
          spotsPerBreak: Math.min(10, Math.max(1, Number(s.adBreakSpots) || 2)),
          skipAfterSec: Math.min(120, Math.max(0, Number(s.adBreakSkipSec) || 0)), // 0 = kliyan an pa ka sote
          spots: breakSpots,
        }
      : null;

    // ---- Broadcast: mesaj ijans nan Settings ----
    const broadcast = s.emergencyActive && s.emergencyText
      ? {
          id: hashId(`${s.emergencyText}|${s.emergencySeverity}`),
          message: s.emergencyText,
          level: s.emergencySeverity === "critical" || s.emergencySeverity === "danger" ? "urgent" : (s.emergencySeverity || "info"),
        }
      : null;

    // ---- Live Events (chanèl pa non, oswa lyen dirèk) ----
    const liveEvents = (data().liveEvents || []).filter((e) => String(e.status || "").toLowerCase() !== "cancelled")
      .map((e) => ({
        id: e.id || hashId(`${e.title}|${e.start}`),
        title: e.title || "",
        channelId: Number(e.channelId) || 0,
        channelName: e.channel || "",
        streamUrl: e.streamUrl || "",
        startsAt: toEpochSec(e.start, zone),
        endsAt: toEpochSec(e.end, zone),
        imageUrl: e.imageUrl || null,
        featured: !!e.featured,
      }))
      .filter((e) => !e.endsAt || e.endsAt >= now);

    // ---- Chyron / Lower third: plizyè, chak ak pozisyon, animasyon, dire ak koulè pa l ----
    const hex = (v, d) => (/^#[0-9a-f]{6}([0-9a-f]{2})?$/i.test(v || "") ? v : d);
    const num = (v, d, lo, hi) => Math.min(hi, Math.max(lo, Number.isFinite(Number(v)) && v !== "" && v != null ? Number(v) : d));
    const POS = ["bottom-left", "bottom-center", "bottom-right", "top-left", "top-center", "top-right"];
    const ANIM = ["slide", "fade", "wipe", "none"];
    const chyrons = (data().chyrons || []).filter(isActive)
      .map((c) => ({
        id: String(c.id),
        headline: String(c.headline || "").trim(),
        name: String(c.name || "").trim(),
        title: String(c.title || "").trim(),
        logoUrl: isUrl(c.logoUrl) ? c.logoUrl : null,
        position: POS.includes(c.position) ? c.position : "bottom-left",
        animation: ANIM.includes(c.animation) ? c.animation : "slide",
        duration: num(c.duration, 10, 0, 3600), // segonn sou ekran an; 0 = toujou
        opacity: num(c.opacity, 92, 10, 100), // % opasite fon an
        bgColor: hex(c.bgColor, "#0B1630"),
        textColor: hex(c.textColor, "#FFFFFF"),
        accentColor: hex(c.accentColor, "#E50914"),
        headlineSize: num(c.headlineSize, 14, 8, 40),
        nameSize: num(c.nameSize, 26, 10, 60),
        titleSize: num(c.titleSize, 16, 8, 40),
        sticker: String(c.sticker || "").trim().slice(0, 32), // gwo imoji anime bò kote chyron an (egz: 🇭🇹 k ap flote)
        animateEmoji: c.animateEmoji !== false,
      }))
      .filter((c) => c.headline || c.name || c.title);
    // Ansyen paramèt (chyronActive/chyronTitle) toujou mache si pa gen chyron nan lis la
    if (!chyrons.length && s.chyronActive && s.chyronTitle) {
      chyrons.push({ id: "legacy", headline: "", name: s.chyronTitle, title: s.chyronSubtitle || "", logoUrl: s.chyronLogoUrl || null,
        position: "bottom-left", animation: "slide", duration: 0, opacity: 92, bgColor: "#0B1630", textColor: "#FFFFFF", accentColor: "#7C4DFF",
        headlineSize: 14, nameSize: 26, titleSize: 16, sticker: "", animateEmoji: true });
    }

    // ---- Grafik TV: logo bug, watermark, scoreboard, meteyo, countdown, ident, bumper ----
    const ip = clientIp(req);
    if (ip && device.lastIp !== ip) device.lastIp = ip;
    const geo = ipPlace(ip); // vil selon IP a (pou meteyo "kote kliyan an ye")
    if (geo && device.geoCity !== geo.name) device.geoCity = geo.name;
    const gfx = buildGraphics(s.gfx, { mac, deviceName: device.deviceName || "", customer: customer?.name || device.customer || "", toEpoch: (v) => toEpochSec(v, zone),
      ip, deviceCity: device.city || "", customerCity: customer?.city || "" });
    const nextEdge = Math.min(tickerEdge, gfx.edge);

    res.json({
      known: true,
      status,
      statusMessage,
      deviceName: device.deviceName || "",
      maxChannels,
      hiddenChannels: eff.hiddenChannels,
      hiddenCategories: eff.hiddenCategories,
      // Non admin nan chanje nan panel la (egz: gwoup "CARIBBEAN" → "HAITI")
      categoryNames: eff.categoryNames,
      channelNames: eff.channelNames,
      playlists: playlists.map(({ id, name, server, username, password }) => ({ id, name, server, username, password })),
      ticker,
      chyrons,
      // Pou ansyen vèsyon app la: premye chyron aktif la
      chyron: chyrons[0] ? { title: chyrons[0].name || chyrons[0].headline, subtitle: chyrons[0].title, logoUrl: chyrons[0].logoUrl } : null,
      banners,
      popups,
      ads,
      adBreak,
      graphics: gfx.graphics,
      broadcast,
      liveEvents,
      forceRefreshAt: s.forceRefreshAt || "",
      refreshSec: Number.isFinite(nextEdge) ? Math.max(5, Math.min(refreshSec, nextEdge)) : refreshSec,
    });
  });

  /** App la voye lis chanèl Xtream kliyan an, pou admin nan ka hide/show yo. */
  app.post("/api/devices/:mac/channels", async (req, res) => {
    ensure();
    const mac = normMac(req.params.mac);
    const device = findDevice(mac);
    if (!device) return res.status(404).json({ ok: false, error: "Aparèy pa jwenn" });
    if (device.deviceKey && req.get("X-Device-Key") !== device.deviceKey) {
      return res.status(403).json({ ok: false, error: "Device Key pa bon" });
    }
    const list = (Array.isArray(req.body?.channels) ? req.body.channels : []).slice(0, 20000).map((c) => ({
      id: Number(c.id), num: Number(c.num) || 0, name: String(c.name || "").slice(0, 200),
      categoryId: String(c.categoryId || ""), categoryName: String(c.categoryName || "").slice(0, 200),
    }));
    const hash = crypto.createHash("sha1").update(JSON.stringify(list)).digest("hex");
    const changed = data().deviceChannels[mac] !== hash;
    if (changed) {
      data().channelLists[hash] ??= list;
      data().deviceChannels[mac] = hash;
      pruneChannelLists();
      await db.write();
    }
    res.json({ ok: true, count: list.length });
  });

  // =========================================================================
  // 2) ROUTE PANEL ADMIN NAN (JWT obligatwa: modpas playlist yo sansib)
  // =========================================================================

  const adminDevice = (d) => {
    const mac = d.mac || null; // aparèy ansyen panel la (DVC-…) pa gen MAC
    const { deviceKey: _k, ...rest } = presentDevice(d);
    return {
      ...rest,
      deviceKey: mac ? d.deviceKey || "" : undefined,
      galaxy: !!mac,
      playlists: mac ? playlistsOf(mac).map(({ id, name, server, username, createdAt, restored }) => ({
        id, name, server, username, createdAt, restored: !!restored,
      })) : [],
      channelCount: mac ? channelsOf(mac).length : 0,
    };
  };

  const loadDevice = (req, res) => {
    ensure();
    const mac = normMac(req.params.mac || req.query.mac);
    const device = findDevice(mac);
    if (!device) {
      res.status(404).json({ ok: false, error: "Pa gen aparèy ak MAC sa a. Louvri app Galaxy TV Stick la sou aparèy la an premye." });
      return null;
    }
    return device;
  };

  /** Admin nan tape MAC la → aparèy la parèt (Device Key, modèl, estati, playlist). */
  /** Panel la teste meteyo yon vil (pou grafik Meteyo a). */
  app.get("/api/galaxy/weather", authenticate, async (req, res) => {
    try { res.json({ ok: true, ...(await fetchWeather(req.query.city)) }); }
    catch (err) { res.status(502).json({ ok: false, error: err.name === "AbortError" ? "Sèvis meteyo a pa reponn" : err.message }); }
  });

  app.get("/api/galaxy/devices/lookup", authenticate, (req, res) => {
    const device = loadDevice(req, res);
    if (device) res.json(adminDevice(device));
  });

  app.get("/api/galaxy/devices", authenticate, (req, res) => {
    ensure();
    // Tout aparèy yo: TV Galaxy yo (ak MAC) ak aparèy ki te deja nan panel la (DVC-…)
    res.json(data().devices.map(adminDevice));
  });

  /** Chanje aparèy la: blocked, deviceName, customer, channelProfile, maxChannels, hide/show, reset kle. */
  app.patch("/api/galaxy/devices/:mac", authenticate, async (req, res) => {
    const device = loadDevice(req, res);
    if (!device) return;
    const b = req.body || {};
    for (const k of ["deviceName", "customer", "channelProfile", "blocked", "limit", "city"]) {
      if (b[k] !== undefined) device[k] = b[k];
    }
    if (b.maxChannels !== undefined) device.maxChannels = Math.max(0, parseInt(b.maxChannels, 10) || 0);
    if (Array.isArray(b.hiddenChannels)) device.hiddenChannels = b.hiddenChannels.map(Number).filter(Number.isFinite);
    if (Array.isArray(b.hiddenCategories)) device.hiddenCategories = b.hiddenCategories.map(String);
    // Non chanje: { id: "nouvo non" }; yon non vid retire chanjman an
    const names = (o) => Object.fromEntries(Object.entries(o).map(([k, v]) => [String(k), String(v ?? "").trim().slice(0, 80)]).filter(([, v]) => v).slice(0, 20000));
    if (b.categoryNames && typeof b.categoryNames === "object" && !Array.isArray(b.categoryNames)) device.categoryNames = names(b.categoryNames);
    if (b.channelNames && typeof b.channelNames === "object" && !Array.isArray(b.channelNames)) device.channelNames = names(b.channelNames);
    if (b.resetDeviceKey) device.deviceKey = ""; // pwochen fwa app la anrejistre, li pran nouvo kle a
    await db.write();
    auditLog("device-update", `Device ${device.mac} updated`, admin(req));
    if (b.customer !== undefined) await syncCustomerFromProvider(device, { force: true });
    syncTvs();
    res.json(adminDevice(device));
  });

  app.delete("/api/galaxy/devices/:mac", authenticate, async (req, res) => {
    const device = loadDevice(req, res);
    if (!device) return;
    const mac = device.mac;
    data().devices = data().devices.filter((d) => d !== device);
    delete data().devicePlaylists[mac];
    delete data().deviceChannels[mac];
    pruneChannelLists();
    if (!data().deletedDevices.includes(mac)) data().deletedDevices.push(mac);
    await db.write();
    auditLog("device-delete", `Device ${mac} deleted`, admin(req));
    res.status(204).end();
  });

  /**
   * Lis chanèl live playlist aparèy la, chèche pa sèvè a (yon navigatè pa ka rele sèvè IPTV a dirèkteman).
   * Se "Customer TV" (TV tès panel la) ki sèvi ak li. Kenbe 10 minit nan memwa.
   */
  const liveListCache = new Map();
  app.get("/api/devices/:mac/xtream/live", async (req, res) => {
    ensure();
    const mac = normMac(req.params.mac);
    const device = findDevice(mac);
    if (!device) return res.status(404).json({ ok: false, error: "Aparèy pa jwenn" });
    if (device.deviceKey && req.get("X-Device-Key") !== device.deviceKey) return res.status(403).json({ ok: false, error: "Device Key pa bon" });
    const pl = playlistsOf(mac).find((p) => p.id === req.query.playlist) || playlistsOf(mac)[0];
    if (!pl) return res.status(404).json({ ok: false, error: "Pa gen playlist sou aparèy sa a" });
    const key = `${pl.server}|${pl.username}|${pl.password}`;
    const hit = liveListCache.get(key);
    if (hit && Date.now() - hit.at < 600_000 && req.query.fresh !== "1") return res.json(hit.data);
    const cl = xtreamClient(pl);
    if (cl.error) return res.status(400).json({ ok: false, error: cl.error });
    try {
      const info = await cl.get("");
      if (!info?.user_info || Number(info.user_info.auth) === 0) return res.status(502).json({ ok: false, error: "Username oswa password la pa bon (sèvè a refize kont lan)." });
      const [cats, live] = await Promise.all([cl.get("get_live_categories", 20000), cl.get("get_live_streams", 30000)]);
      const catName = new Map((Array.isArray(cats) ? cats : []).map((c) => [String(c.category_id), String(c.category_name || "")]));
      const channels = (Array.isArray(live) ? live : []).slice(0, 20000).map((c, i) => ({
        id: Number(c.stream_id), num: Number(c.num) || i + 1, name: String(c.name || "").slice(0, 200),
        categoryId: String(c.category_id || ""), categoryName: (catName.get(String(c.category_id)) || "").slice(0, 200),
        icon: isUrl(c.stream_icon) ? String(c.stream_icon).slice(0, 500) : "",
      })).filter((c) => Number.isFinite(c.id));
      const data = { ok: true, playlist: pl.name || pl.username, categories: [...catName].map(([id, name]) => ({ id, name })), channels };
      liveListCache.set(key, { at: Date.now(), data });
      if (liveListCache.size > 200) liveListCache.delete(liveListCache.keys().next().value);
      res.json(data);
    } catch (e) {
      res.status(502).json({ ok: false, error: e.message || "Sèvè IPTV a pa reponn." });
    }
  });

  // =========================================================================
  // Jwe yon chanèl nan "Customer TV" panel la (navigatè a)
  // Yon navigatè pa ka li flux IPTV yo dirèkteman (http sou yon paj https, pa gen CORS).
  // Sèvè a sèvi kòm relè: li chèche playlist HLS la ak moso videyo yo, epi li pase yo bay navigatè a.
  // Sèlman pou admin ki konekte (JWT), e sèlman sou sèvè playlist aparèy la.
  // =========================================================================
  const playHosts = new Map(); // MAC → Set(host otorize pou relè a)
  const PLAY_UA = { "User-Agent": "Mozilla/5.0 (Linux; Android 9; AFTKA) GalaxyTVStick/1.0", Accept: "*/*" };
  const b64u = (v) => Buffer.from(v).toString("base64url");
  const adminPlay = (req, res, next) => {
    // hls.js voye JWT a nan header; Safari (HLS natif) pa kapab, kidonk nou aksepte ?t= tou
    if (!req.headers.authorization && req.query.t) req.headers.authorization = "Bearer " + req.query.t;
    authenticate(req, res, next);
  };
  const isPrivateHost = (h) => process.env.XTREAM_ALLOW_LOCAL !== "1" && /^(localhost|127\.|0\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.|::1|\[)/i.test(h);
  const allowHost = (mac, url) => { const h = new URL(url).host; (playHosts.get(mac) || playHosts.set(mac, new Set()).get(mac)).add(h); };
  const targetOf = (req, res, mac) => {
    let url;
    try { url = new URL(Buffer.from(String(req.query.u || ""), "base64url").toString()); } catch { res.status(400).json({ ok: false, error: "Lyen pa valid" }); return null; }
    if (!/^https?:$/.test(url.protocol) || isPrivateHost(url.hostname) || !playHosts.get(mac)?.has(url.host)) { res.status(403).json({ ok: false, error: "Lyen sa a pa otorize" }); return null; }
    return url;
  };
  /** Reekri yon playlist HLS pou tout lyen ladan l pase pa relè a. */
  const rewriteM3u8 = (text, baseUrl, mac, t) => {
    const prefix = `/api/galaxy/devices/${encodeURIComponent(mac)}/play`;
    const tq = t ? `&t=${encodeURIComponent(t)}` : "";
    const via = (uri, kind) => { const abs = new URL(uri, baseUrl).toString(); allowHost(mac, abs); return `${prefix}/${kind}?u=${b64u(abs)}${tq}`; };
    return text.split(/\r?\n/).map((line) => {
      const l = line.trim();
      if (!l) return line;
      if (l.startsWith("#")) return line.replace(/URI="([^"]+)"/g, (_, uri) => `URI="${via(uri, /\.m3u8(\?|$)/i.test(uri) ? "pl" : "seg")}"`);
      return via(l, /\.m3u8(\?|$)/i.test(l) ? "pl" : "seg");
    }).join("\n");
  };
  const sendPlaylist = async (res, url, mac, t) => {
    const ctl = new AbortController(); const tm = setTimeout(() => ctl.abort(), 15000);
    try {
      const r = await fetch(url, { signal: ctl.signal, redirect: "follow", headers: PLAY_UA });
      if (!r.ok) return res.status(502).json({ ok: false, error: `Sèvè IPTV a reponn HTTP ${r.status} pou chanèl sa a.` });
      const text = await r.text();
      if (!text.includes("#EXTM3U")) return res.status(502).json({ ok: false, error: "Sèvè IPTV a pa bay chanèl sa a an HLS (.m3u8)." });
      allowHost(mac, r.url || url);
      res.setHeader("Content-Type", "application/vnd.apple.mpegurl");
      res.setHeader("Cache-Control", "no-store");
      res.end(rewriteM3u8(text, r.url || url, mac, t));
    } catch (e) {
      if (!res.headersSent) res.status(502).json({ ok: false, error: e.name === "AbortError" ? "Sèvè IPTV a pran twòp tan." : "Sèvè IPTV a pa reponn." });
    } finally { clearTimeout(tm); }
  };

  app.get("/api/galaxy/devices/:mac/play/pl", adminPlay, async (req, res) => {
    const mac = normMac(req.params.mac);
    const url = targetOf(req, res, mac);
    if (url) await sendPlaylist(res, url.toString(), mac, req.query.t);
  });

  app.get("/api/galaxy/devices/:mac/play/seg", adminPlay, async (req, res) => {
    const mac = normMac(req.params.mac);
    const url = targetOf(req, res, mac);
    if (!url) return;
    const ctl = new AbortController(); const tm = setTimeout(() => ctl.abort(), 30000);
    res.on?.("close", () => ctl.abort()); // navigatè a fèmen: kanpe telechajman an
    try {
      const r = await fetch(url, { signal: ctl.signal, redirect: "follow", headers: PLAY_UA });
      if (!r.ok || !r.body) return res.status(502).json({ ok: false, error: `HTTP ${r.status}` });
      res.setHeader("Content-Type", r.headers.get("content-type") || "video/mp2t");
      res.setHeader("Cache-Control", "no-store");
      const len = r.headers.get("content-length"); if (len) res.setHeader("Content-Length", len);
      for await (const chunk of r.body) { if (res.write(chunk) === false) await new Promise((ok) => res.once?.("drain", ok) || ok()); }
      res.end();
    } catch {
      if (!res.headersSent) res.status(502).json({ ok: false, error: "Sèvè IPTV a pa reponn." }); else res.end();
    } finally { clearTimeout(tm); }
  });

  app.get("/api/galaxy/devices/:mac/play/:streamId/index.m3u8", adminPlay, async (req, res) => {
    const device = loadDevice(req, res);
    if (!device) return;
    const pl = playlistsOf(device.mac)[0];
    if (!pl) return res.status(404).json({ ok: false, error: "Pa gen playlist sou aparèy sa a" });
    const id = String(req.params.streamId);
    if (!/^\d+$/.test(id)) return res.status(400).json({ ok: false, error: "Chanèl pa valid" });
    const base = normServer(pl.server);
    let host; try { host = new URL(base).hostname; } catch { return res.status(400).json({ ok: false, error: "Adrès sèvè a pa valid." }); }
    if (isPrivateHost(host)) return res.status(403).json({ ok: false, error: "Adrès sa a pa otorize." });
    playHosts.set(device.mac, new Set()); // nouvo chanèl: rekòmanse lis host otorize yo
    await sendPlaylist(res, `${base}/live/${encodeURIComponent(pl.username)}/${encodeURIComponent(pl.password)}/${id}.m3u8`, device.mac, req.query.t);
  });

  /** Lis chanèl kliyan an ak eta hide/show chak grenn. */
  app.get("/api/galaxy/devices/:mac/channels", authenticate, (req, res) => {
    const device = loadDevice(req, res);
    if (!device) return;
    const hidden = new Set((device.hiddenChannels || []).map(Number));
    const hiddenCats = new Set((device.hiddenCategories || []).map(String));
    res.json(channelsOf(device.mac).map((c) => ({
      ...c, hidden: hidden.has(c.id), categoryHidden: hiddenCats.has(c.categoryId),
    })));
  });

  // ---- Tès playlist Xtream (sèvè a li menm kontakte sèvè IPTV a: pa gen pwoblèm CORS/http nan navigatè a) ----
  const normServer = (v) => {
    let u = String(v || "").trim();
    if (!u) return "";
    if (!/^https?:\/\//i.test(u)) u = "http://" + u;
    return u.replace(/\/(player_api\.php|get\.php|xmltv\.php)[^]*$/i, "").replace(/\/+$/, "");
  };
  /** Prepare yon kliyan Xtream (player_api.php) pou yon playlist. Retounen { error } oswa { get, host, base }. */
  // ---- Pakè chanèl (Channel Profile ki gen pkg:true) ----
  // Yon pakè sove chwa yo pa NON founisè a (pa ID), pou menm pakè a mache sou tout aparèy ki gen menm gwoup yo.
  const nkey = (v) => String(v ?? "").trim().toUpperCase();
  const pkgCache = new Map();
  function effectiveChannels(device) {
    const p = device.channelProfile ? (data().channelProfiles || []).find((x) => x.name === device.channelProfile) : null;
    if (!p || p.pkg !== true) {
      return {
        hiddenChannels: (device.hiddenChannels || []).map(Number).filter(Number.isFinite),
        hiddenCategories: (device.hiddenCategories || []).map(String),
        categoryNames: device.categoryNames || {}, channelNames: device.channelNames || {},
      };
    }
    const chs = channelsOf(device.mac);
    const ck = `${p.name}|${p.updatedAt || ""}|${chs.length}|${chs[0]?.id ?? ""}|${chs[chs.length - 1]?.id ?? ""}`;
    const hit = pkgCache.get(device.mac);
    if (hit && hit.ck === ck) return hit.out;
    const hg = new Set((p.hiddenGroups || []).map(nkey)), hc = new Set((p.hiddenChannels || []).map(nkey));
    const gn = Object.fromEntries(Object.entries(p.groupNames || {}).map(([k, v]) => [nkey(k), v]));
    const cn = Object.fromEntries(Object.entries(p.channelNames || {}).map(([k, v]) => [nkey(k), v]));
    const cats = new Set(); const out = { hiddenChannels: [], hiddenCategories: [], categoryNames: {}, channelNames: {} };
    for (const c of chs) {
      const g = nkey(c.categoryName), n = nkey(c.name), cid = String(c.categoryId);
      if (hg.has(g)) cats.add(cid);
      if (gn[g]) out.categoryNames[cid] = gn[g];
      if (hc.has(n)) out.hiddenChannels.push(Number(c.id));
      if (cn[n]) out.channelNames[String(c.id)] = cn[n];
    }
    out.hiddenCategories = [...cats];
    if (pkgCache.size > 5000) pkgCache.clear();
    pkgCache.set(device.mac, { ck, out });
    return out;
  }

  // ---- Plan ak ekspirasyon kliyan an soti nan founisè a (Xtream user_info) ----

  const customerOf = (device) => device?.customer
    ? (data().customers || []).find((c) => same(c.name, device.customer) || c.id === device.customer) || null
    : null;

  /** Segonn → "YYYY-MM-DDTHH:MM" nan zòn lè panel la (menm fòma ak kaz Ekspirasyon an). */
  const localStamp = (sec) => new Intl.DateTimeFormat("sv-SE", {
    timeZone: tz(), year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false,
  }).format(new Date(sec * 1000)).replace(" ", "T").replace(/^(\d{4}-\d{2}-\d{2})T24:/, "$1T00:");

  const planForDays = (days) => days < 8 ? "Trial" : days <= 49 ? "1 Month" : days <= 137 ? "3 Months" : days <= 274 ? "6 Months" : "12 Months";

  /**
   * Plan selon founisè a. Xtream pa bay non pakè a, donk nou dedui l:
   * - kont esè (is_trial) → Trial
   * - premye fwa nou wè kont lan → dire total li (kreyasyon → ekspirasyon)
   * - founisè a pwolonje dat la (renouvèlman) → dire renouvèlman an
   * - anyen pa chanje → kite plan an jan l ye
   */
  function providerPlan(u, customer) {
    if (String(u.is_trial) === "1") return "Trial";
    const exp = Number(u.exp_date), created = Number(u.created_at), prev = Number(customer.providerExp) || 0;
    if (!(exp > 0)) return null;
    const now = Date.now() / 1000;
    if (prev > 0) return exp > prev + 86400 ? planForDays((exp - Math.max(prev, now)) / 86400) : null;
    return created > 0 && exp > created ? planForDays((exp - created) / 86400) : null;
  }

  const SYNC_EVERY_MS = 6 * 60 * 60 * 1000;
  const syncing = new Set();

  /**
   * Mete plan + ekspirasyon kliyan an ajou ak sa founisè playlist la di.
   * Sèlman si kliyan an gen "Plan otomatik" limen (autoPlan !== false) epi aparèy la gen yon playlist.
   */
  async function syncCustomerFromProvider(device, { force = false } = {}) {
    const customer = customerOf(device);
    if (!customer || customer.autoPlan === false) return null;
    const pl = playlistsOf(device.mac)[0];
    if (!pl) return null;
    if (!force && customer.providerSyncAt && Date.now() - Date.parse(customer.providerSyncAt) < SYNC_EVERY_MS) return null;
    const key = String(customer.id || customer.name);
    if (syncing.has(key)) return null;
    const cl = xtreamClient(pl);
    if (cl.error) return null;
    syncing.add(key);
    try {
      customer.providerSyncAt = new Date().toISOString(); // menm si l echwe: pa relanse chak segonn
      const info = await cl.get("").catch(() => null);
      const u = info?.user_info;
      if (!u || Number(u.auth) === 0) return null;
      const plan = providerPlan(u, customer);
      if (plan) customer.plan = plan;
      const exp = Number(u.exp_date);
      customer.providerExp = exp > 0 ? exp : 0;
      customer.expiry = exp > 0 ? localStamp(exp) : ""; // pa gen dat = kont san limit
      const ps = String(u.status || "").toLowerCase();
      if (String(customer.status || "").toLowerCase() !== "suspended") {
        if (ps === "expired" || (exp > 0 && exp * 1000 < Date.now())) customer.status = "Expired";
        else if (ps === "active") customer.status = "Active";
      }
      customer.providerStatus = String(u.status || "");
      await db.write();
      return customer;
    } catch { return null; } finally { syncing.delete(key); }
  }

  function xtreamClient({ server, username, password }) {
    const base = normServer(server);
    if (!base) return { error: "Mete adrès sèvè a (DNS) pou n ka teste playlist la." };
    if (!String(username || "").trim() || !String(password || "").trim()) return { error: "Username ak password obligatwa." };
    let host;
    try { host = new URL(base).hostname; } catch { return { error: "Adrès sèvè a pa valid." }; }
    if (process.env.XTREAM_ALLOW_LOCAL !== "1" && /^(localhost|127\.|0\.|10\.|192\.168\.|169\.254\.|::1)/i.test(host)) return { error: "Adrès sa a pa otorize." };
    const q = `username=${encodeURIComponent(String(username).trim())}&password=${encodeURIComponent(String(password).trim())}`;
    const get = async (action, ms = 12000) => {
      const ctl = new AbortController(); const tm = setTimeout(() => ctl.abort(), ms);
      try {
        const r = await fetch(`${base}/player_api.php?${q}${action ? "&action=" + action : ""}`, {
          signal: ctl.signal, redirect: "follow", headers: { "User-Agent": "Mozilla/5.0 (Linux; Android 9; AFTKA) GalaxyTVStick/1.0", Accept: "application/json,*/*" },
        });
        if (!r.ok) throw Object.assign(new Error(`Sèvè a reponn HTTP ${r.status}.`), { http: r.status });
        const text = await r.text();
        try { return JSON.parse(text); } catch { throw new Error("Sèvè a pa bay yon repons Xtream (verifye adrès la ak pò a)."); }
      } catch (e) {
        if (e.name === "AbortError") throw new Error("Sèvè a pran twòp tan pou l reponn.");
        const c = e.cause?.code || e.code;
        if (c === "ENOTFOUND" || c === "EAI_AGAIN") throw new Error("Adrès sèvè a pa egziste (DNS).");
        if (c === "ECONNREFUSED" || c === "ECONNRESET" || c === "EHOSTUNREACH" || c === "UND_ERR_CONNECT_TIMEOUT") throw new Error("Sèvè a pa reponn sou pò sa a.");
        throw e;
      } finally { clearTimeout(tm); }
    };
    return { get, host, base };
  }

  async function xtreamTest(pl) {
    const cl = xtreamClient(pl);
    if (cl.error) return { ok: false, error: cl.error };
    const { get, host, base } = cl;
    const t0 = Date.now();
    try {
      const info = await get("");
      const ms = Date.now() - t0;
      const u = info?.user_info || {};
      if (!info?.user_info || Number(u.auth) === 0) return { ok: false, error: "Username oswa password la pa bon (sèvè a refize kont lan).", ms };
      const [cats, live] = await Promise.all([get("get_live_categories", 20000).catch(() => null), get("get_live_streams", 25000).catch(() => null)]);
      const exp = Number(u.exp_date) > 0 ? new Date(Number(u.exp_date) * 1000).toISOString() : null;
      return {
        ok: true, ms, base,
        status: String(u.status || "Unknown"),
        expDate: exp,
        isTrial: String(u.is_trial) === "1",
        maxConnections: Number(u.max_connections) || 0,
        activeConnections: Number(u.active_cons) || 0,
        createdAt: Number(u.created_at) > 0 ? new Date(Number(u.created_at) * 1000).toISOString() : null,
        liveCategories: Array.isArray(cats) ? cats.length : null,
        liveChannels: Array.isArray(live) ? live.length : null,
        categories: Array.isArray(cats) ? cats.slice(0, 12).map((c) => String(c.category_name || "")).filter(Boolean) : [],
        server: { url: info.server_info?.url || host, port: info.server_info?.port || "", timezone: info.server_info?.timezone || "", https: String(info.server_info?.server_protocol || "").toLowerCase() === "https" },
      };
    } catch (e) {
      return { ok: false, error: e.message || "Tès la echwe.", ms: Date.now() - t0 };
    }
  }

  /** Bouton "Teste" nan fòm Ajoute Playlist la (anvan w sove l). */
  app.post("/api/galaxy/xtream/test", authenticate, async (req, res) => {
    res.json(await xtreamTest(req.body || {}));
  });

  /** Teste yon playlist ki deja sou yon aparèy (ak password ki sove a). */
  app.post("/api/galaxy/devices/:mac/playlists/:id/test", authenticate, async (req, res) => {
    const device = loadDevice(req, res);
    if (!device) return;
    const pl = playlistsOf(device.mac).find((p) => p.id === req.params.id);
    if (!pl) return res.status(404).json({ ok: false, error: "Playlist pa jwenn" });
    const out = await xtreamTest(pl);
    if (out.ok) { await syncCustomerFromProvider(device, { force: true }); syncTvs(); }
    auditLog("playlist-test", `Playlist "${pl.name}" on ${device.mac}: ${out.ok ? out.status : out.error}`, admin(req));
    res.json(out);
  });

  // ---- Playlist ----

  app.get("/api/galaxy/devices/:mac/playlists", authenticate, (req, res) => {
    const device = loadDevice(req, res);
    if (device) res.json(adminDevice(device).playlists);
  });

  /** Bouton "Submit" fòm Ajoute Playlist la. */
  app.post("/api/galaxy/devices/:mac/playlists", authenticate, async (req, res) => {
    const device = loadDevice(req, res);
    if (!device) return;
    const { name, server = "", username, password, deviceKey } = req.body || {};
    if (!String(name || "").trim() || !String(username || "").trim() || !String(password || "").trim()) {
      return res.status(400).json({ ok: false, error: "Non playlist, username ak password obligatwa" });
    }
    if (server && !/^https?:\/\/|^[\w.-]+(:\d+)?(\/.*)?$/i.test(String(server).trim())) {
      return res.status(400).json({ ok: false, error: "Adrès sèvè a pa valid" });
    }
    if (deviceKey && device.deviceKey && String(deviceKey).trim() !== device.deviceKey) {
      return res.status(403).json({ ok: false, error: "Device Key la pa matche ak aparèy sa a" });
    }
    const playlist = {
      id: crypto.randomUUID(),
      name: String(name).trim(),
      server: String(server).trim(),
      username: String(username).trim(),
      password: String(password).trim(),
      createdAt: new Date().toISOString(),
    };
    playlistsOf(device.mac).push(playlist);
    if (!device.activated) device.activated = playlist.createdAt.slice(0, 10);
    data().deletedDevices = data().deletedDevices.filter((m) => m !== device.mac);
    await db.write();
    auditLog("playlist-add", `Playlist "${playlist.name}" added to ${device.mac}`, admin(req));
    await syncCustomerFromProvider(device, { force: true });
    syncTvs();
    const { password: _pw, ...safe } = playlist;
    res.status(201).json({ ok: true, playlist: safe, device: adminDevice(device) });
  });

  app.put("/api/galaxy/devices/:mac/playlists/:id", authenticate, async (req, res) => {
    const device = loadDevice(req, res);
    if (!device) return;
    const pl = playlistsOf(device.mac).find((p) => p.id === req.params.id);
    if (!pl) return res.status(404).json({ ok: false, error: "Playlist pa jwenn" });
    for (const k of ["name", "server", "username", "password"]) {
      if (req.body?.[k] !== undefined && String(req.body[k]).trim() !== "") pl[k] = String(req.body[k]).trim();
    }
    if (req.body?.server === "") pl.server = "";
    await db.write();
    await syncCustomerFromProvider(device, { force: true });
    syncTvs();
    auditLog("playlist-update", `Playlist "${pl.name}" updated on ${device.mac}`, admin(req));
    res.json(adminDevice(device));
  });

  app.delete("/api/galaxy/devices/:mac/playlists/:id", authenticate, async (req, res) => {
    const device = loadDevice(req, res);
    if (!device) return;
    const list = playlistsOf(device.mac);
    const pl = list.find((p) => p.id === req.params.id);
    data().devicePlaylists[device.mac] = list.filter((p) => p.id !== req.params.id);
    await db.write();
    syncTvs();
    auditLog("playlist-delete", `Playlist "${pl?.name || req.params.id}" removed from ${device.mac}`, admin(req));
    res.json(adminDevice(device));
  });
}
