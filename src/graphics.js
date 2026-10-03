// Grafik TV (tankou chèn televizyon): logo bug, watermark, scoreboard, meteyo, countdown,
// channel ident ak bumper. Panel la sove yo nan settings.gfx; isit la nou netwaye yo
// epi prepare sa app TV a ap resevwa nan "graphics".

const POS6 = ["top-left", "top-center", "top-right", "bottom-left", "bottom-center", "bottom-right"];
const POS_WM = ["top-left", "top-right", "bottom-left", "bottom-right", "center"];
const isUrl = (s) => /^https?:\/\//i.test(String(s || "").trim());
const str = (v, max = 80) => String(v ?? "").trim().slice(0, max);
const num = (v, d, lo, hi) => Math.min(hi, Math.max(lo, Number.isFinite(Number(v)) && v !== "" && v != null ? Number(v) : d));
const hex = (v, d) => (/^#[0-9a-f]{6}([0-9a-f]{2})?$/i.test(v || "") ? v : d);
const pos = (v, list, d) => (list.includes(v) ? v : d);

// ------------------------------------------------------------------ Meteyo (Open-Meteo, gratis, san kle)
const GEO_URL = process.env.WEATHER_GEO_URL || "https://geocoding-api.open-meteo.com/v1/search";
const API_URL = process.env.WEATHER_API_URL || "https://api.open-meteo.com/v1/forecast";
const WEATHER_TTL = 20 * 60_000;
const weatherCache = new Map(); // vil (miniskil) → { at, data, error, loading }

/** Kòd meteyo WMO → imoji. */
export function weatherIcon(code, isDay = true) {
  const c = Number(code);
  if (c === 0) return isDay ? "☀️" : "🌙";
  if (c === 1 || c === 2) return isDay ? "🌤️" : "☁️";
  if (c === 3) return "☁️";
  if (c === 45 || c === 48) return "🌫️";
  if (c >= 51 && c <= 57) return "🌦️";
  if ((c >= 61 && c <= 67) || (c >= 80 && c <= 82)) return "🌧️";
  if ((c >= 71 && c <= 77) || c === 85 || c === 86) return "🌨️";
  if (c >= 95) return "⛈️";
  return "🌡️";
}

async function getJson(url) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 8000);
  try {
    const r = await fetch(url, { signal: ctl.signal, headers: { accept: "application/json" } });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return await r.json();
  } finally { clearTimeout(timer); }
}

// Yon "kote" se swa yon non vil (tèks), swa { lat, lon, name } (egz: jwenn pa IP kliyan an)
const placeKey = (place) => (typeof place === "string" ? "c:" + str(place).toLowerCase() : `g:${Number(place.lat).toFixed(2)},${Number(place.lon).toFixed(2)}`);

/** Chèche meteyo yon kote kounye a (tanperati an °C, kòd, lajounen/lannwit). */
export async function fetchWeather(place) {
  if (typeof place === "string" && !str(place)) throw new Error("Mete non vil la");
  const key = placeKey(place);
  const hit = weatherCache.get(key);
  let at = typeof place === "string" ? hit?.place : { name: place.name || "", country: place.country || "", lat: place.lat, lon: place.lon };
  if (!at) {
    const g = await getJson(`${GEO_URL}?name=${encodeURIComponent(str(place))}&count=1&language=en&format=json`);
    const p = g?.results?.[0];
    if (!p) throw new Error("Vil sa a pa jwenn");
    at = { name: p.name, country: p.country_code || "", lat: p.latitude, lon: p.longitude };
  }
  const f = await getJson(`${API_URL}?latitude=${at.lat}&longitude=${at.lon}&current=temperature_2m,weather_code,is_day`);
  const cur = f?.current;
  if (!cur || !Number.isFinite(Number(cur.temperature_2m))) throw new Error("Sèvis meteyo a pa reponn byen");
  const data = { city: at.name, country: at.country, tempC: Number(cur.temperature_2m), code: Number(cur.weather_code) || 0, isDay: cur.is_day !== 0,
    icon: weatherIcon(cur.weather_code, cur.is_day !== 0), at: Date.now() };
  weatherCache.set(key, { at: Date.now(), data, place: at, error: "" });
  return data;
}

/** Dènye meteyo nou konnen pou kote a (san tann); li rafrechi dèyè si l fin vye. */
function weatherNow(place) {
  if (!place || (typeof place === "string" && !str(place))) return null;
  const key = placeKey(place);
  const hit = weatherCache.get(key);
  if ((!hit || Date.now() - hit.at > WEATHER_TTL) && !hit?.loading) {
    const slot = hit || { at: 0, data: null };
    slot.loading = true; weatherCache.set(key, slot);
    fetchWeather(place).catch((err) => { const s = weatherCache.get(key) || slot; s.error = err.message; s.at = Date.now() - WEATHER_TTL + 120_000; weatherCache.set(key, s); })
      .finally(() => { const s = weatherCache.get(key); if (s) s.loading = false; });
  }
  if (weatherCache.size > 5000) weatherCache.delete(weatherCache.keys().next().value); // pa kite l gwosi san limit
  return hit?.data || null;
}

// ------------------------------------------------------------------ Vil kliyan an selon adrès IP li
const IP_URL = process.env.WEATHER_IP_URL || "https://ipwho.is/";
const IP_TTL = 24 * 3600_000;
const ipCache = new Map(); // IP → { at, place, loading }

/** Adrès IP TV a (dèyè Render/Cloudflare: premye adrès nan X-Forwarded-For). */
export function clientIp(req) {
  const fwd = String(req.headers?.["x-forwarded-for"] || "").split(",")[0].trim();
  const ip = (fwd || req.socket?.remoteAddress || req.ip || "").replace(/^::ffff:/, "");
  return /^[0-9a-f:.]{3,45}$/i.test(ip) ? ip : "";
}
const isPrivateIp = (ip) => !ip || /^(10\.|127\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.|::1$|f[cd]|fe80)/i.test(ip);

/** Kote IP sa a ye ({ lat, lon, name, country }) si nou konnen l deja; sinon null epi nou chèche l dèyè. */
export function ipPlace(ip) {
  if (isPrivateIp(ip)) return null;
  const hit = ipCache.get(ip);
  if ((!hit || Date.now() - hit.at > IP_TTL) && !hit?.loading) {
    const slot = hit || { at: 0, place: null };
    slot.loading = true; ipCache.set(ip, slot);
    getJson(IP_URL + encodeURIComponent(ip))
      .then((r) => { slot.place = r && r.success !== false && Number.isFinite(Number(r.latitude)) && r.city ? { lat: Number(r.latitude), lon: Number(r.longitude), name: str(r.city, 30), country: str(r.country_code, 4) } : null; slot.at = Date.now(); })
      .catch(() => { slot.at = Date.now() - IP_TTL + 3600_000; }) // eseye ankò nan 1 è
      .finally(() => { slot.loading = false; });
    if (ipCache.size > 20000) ipCache.delete(ipCache.keys().next().value);
  }
  return hit?.place || null;
}

// ------------------------------------------------------------------ Konfig pou app TV a
/**
 * @param g        settings.gfx (sa panel la sove)
 * @param ctx      { mac, deviceName, customer, toEpoch(value) }
 * @returns objè "graphics" (sèlman grafik ki limen yo) ak "edge" = segonn anvan pwochen chanjman (countdown)
 */
export function buildGraphics(g, ctx) {
  g = g && typeof g === "object" ? g : {};
  const out = {};
  const now = Math.floor(Date.now() / 1000);
  let edge = Infinity;

  // Logo bug: ti logo chèn nan nan yon kwen, toutan
  const b = g.bug || {};
  if (b.on && (isUrl(b.imageUrl) || str(b.text))) {
    out.bug = { imageUrl: isUrl(b.imageUrl) ? str(b.imageUrl, 500) : null, text: str(b.text, 24), position: pos(b.position, POS6, "top-right"),
      size: num(b.size, 44, 16, 160), opacity: num(b.opacity, 85, 10, 100) };
  }

  // Watermark: tèks pal sou imaj la (egz: MAC kliyan an) pou dekouraje moun k ap kopye/rediffize
  const w = g.watermark || {};
  if (w.on && str(w.text)) {
    const text = str(w.text, 60).replace(/\{mac\}/gi, ctx.mac || "").replace(/\{customer\}/gi, ctx.customer || "").replace(/\{device\}/gi, ctx.deviceName || "").trim();
    if (text) out.watermark = { text, position: pos(w.position, POS_WM, "bottom-right"), size: num(w.size, 16, 8, 60), opacity: num(w.opacity, 25, 3, 100), move: !!w.move };
  }

  // Scoreboard
  const s = g.scoreboard || {};
  if (s.on && (str(s.home) || str(s.away))) {
    out.scoreboard = { league: str(s.league, 30), home: str(s.home, 20), away: str(s.away, 20), homeScore: str(s.homeScore, 4) || "0", awayScore: str(s.awayScore, 4) || "0",
      clock: str(s.clock, 16), homeColor: hex(s.homeColor, "#1D4ED8"), awayColor: hex(s.awayColor, "#DC2626"), position: pos(s.position, POS6, "top-left") };
  }

  // Meteyo: otomatik (Open-Meteo) oswa alamen
  const m = g.weather || {};
  if (m.on && (str(m.city) || m.scope === "client")) {
    const unit = m.unit === "F" ? "F" : "C";
    // "client" = meteyo kote chak kliyan ye: vil aparèy la, sinon vil kliyan an, sinon IP li, sinon vil jeneral la
    const place = m.scope === "client" ? str(ctx.deviceCity, 30) || str(ctx.customerCity, 30) || ipPlace(ctx.ip) || str(m.city, 30) : str(m.city, 30);
    const live = m.auto !== false ? weatherNow(place) : null;
    let tempC = null, icon = str(m.icon, 8) || "🌤️", city = live?.city || (typeof place === "string" ? place : place.name) || str(m.city, 30);
    if (live) { tempC = live.tempC; icon = live.icon; }
    else if (m.temp !== "" && m.temp != null && Number.isFinite(Number(m.temp))) tempC = unit === "F" ? ((Number(m.temp) - 32) * 5) / 9 : Number(m.temp);
    if (tempC !== null) {
      const t = unit === "F" ? (tempC * 9) / 5 + 32 : tempC;
      out.weather = { city, temp: `${Math.round(t)}°${unit}`, icon, position: pos(m.position, POS6, "top-right") };
    }
  }

  // Countdown: konte jiska yon dat/lè
  const c = g.countdown || {};
  if (c.on && c.target) {
    const targetAt = ctx.toEpoch(c.target);
    const keep = num(c.keepMin, 10, 0, 1440) * 60; // konbyen tan mesaj "fini" an rete
    if (targetAt && now < targetAt + (str(c.doneText) ? keep : 0)) {
      out.countdown = { title: str(c.title, 40), targetAt, doneText: str(c.doneText, 40), position: pos(c.position, POS6, "top-center") };
      edge = Math.min(edge, now < targetAt ? targetAt - now + 1 : targetAt + keep - now + 1);
    }
  }

  // Channel ident: logo chèn nan parèt nan mitan ekran an pou kèk segonn, chak X minit
  const i = g.ident || {};
  if (i.on && (isUrl(i.imageUrl) || str(i.text))) {
    out.ident = { imageUrl: isUrl(i.imageUrl) ? str(i.imageUrl, 500) : null, text: str(i.text, 40), tagline: str(i.tagline, 60),
      everyMin: num(i.everyMin, 30, 0, 240), durationSec: num(i.durationSec, 5, 2, 15), onChannelChange: !!i.onChannelChange };
  }

  // Bumper: ekran anvan ak apre koupi piblisite a ("N ap tounen touswit" / "Nou tounen")
  const u = g.bumper || {};
  if (u.on && (isUrl(u.inUrl) || str(u.inText) || isUrl(u.outUrl) || str(u.outText))) {
    out.bumper = { inUrl: isUrl(u.inUrl) ? str(u.inUrl, 500) : null, inText: str(u.inText, 60), outUrl: isUrl(u.outUrl) ? str(u.outUrl, 500) : null, outText: str(u.outText, 60),
      durationSec: num(u.durationSec, 4, 2, 15), bgColor: hex(u.bgColor, "#0B1630"), textColor: hex(u.textColor, "#FFFFFF") };
  }

  return { graphics: out, edge };
}
