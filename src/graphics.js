// Grafik TV (tankou chèn televizyon): logo bug, watermark, scoreboard, meteyo, countdown,
// channel ident ak bumper. Panel la sove yo nan settings.gfx; isit la nou netwaye yo
// epi prepare sa app TV a ap resevwa nan "graphics".

const POS6 = ["top-left", "top-center", "top-right", "bottom-left", "bottom-center", "bottom-right"];
const POS_WM = ["top-left", "top-right", "bottom-left", "bottom-right", "center"];
const isUrl = (s) => /^https?:\/\//i.test(String(s || "").trim());
const str = (v, max = 80) => String(v ?? "").trim().slice(0, max);
const num = (v, d, lo, hi) => Math.min(hi, Math.max(lo, Number.isFinite(Number(v)) && v !== "" && v != null ? Number(v) : d));
/** Estil ak polis tèks yo (menm lis ak panel la ak app TV a). */
export const TEXT_STYLES = ["plain", "classic", "outline", "glow", "neon", "box", "gradient", "metal", "fire", "retro", "3d"];
export const TEXT_FONTS = ["sans", "condensed", "black", "serif", "mono", "script", "bebas", "oswald", "anton", "teko", "russo", "orbitron",
  "audiowide", "righteous", "bangers", "marker", "pacifico", "lobster", "greatvibes", "playfair", "abril"];
const hex = (v, d) => (/^#[0-9a-f]{6}([0-9a-f]{2})?$/i.test(v || "") ? v : d);
const pos = (v, list, d) => (list.includes(v) ? v : d);

// ------------------------------------------------------------------ Meteyo (Open-Meteo, gratis, san kle)
const GEO_URL = process.env.WEATHER_GEO_URL || "https://geocoding-api.open-meteo.com/v1/search";
const API_URL = process.env.WEATHER_API_URL || "https://api.open-meteo.com/v1/forecast";
const WEATHER_TTL = 5 * 60_000; // tcheke meteyo a chak 5 minit
const weatherCache = new Map(); // vil (miniskil) → { at, data, error, loading }

/** Kòd meteyo WMO → imoji. */
export function weatherIcon(code, isDay = true) {
  const c = Number(code);
  if (c === 0) return isDay ? "☀️" : "🌙";
  if (c === 1 || c === 2) return isDay ? "🌤️" : "☁️";
  if (c === 3) return "☁️";
  if (c === 45 || c === 48) return "☁️"; // bwouya: yon nwaj senp (admin nan pa vle ikòn bwouya a)
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

// Vil chèf-lye 10 depatman Ayiti yo, ak kowòdone yo (non vil ayisyen yo pa toujou jwenn pa non nan sèvis jewografi a)
const WX_HAITI = [
  { name: "Port-au-Prince", lat: 18.5944, lon: -72.3074 }, { name: "Cap-Haïtien", lat: 19.7578, lon: -72.2042 },
  { name: "Gonaïves", lat: 19.4475, lon: -72.6889 }, { name: "Les Cayes", lat: 18.1933, lon: -73.7461 },
  { name: "Jacmel", lat: 18.2342, lon: -72.5347 }, { name: "Jérémie", lat: 18.65, lon: -74.1167 },
  { name: "Hinche", lat: 19.15, lon: -72.0167 }, { name: "Port-de-Paix", lat: 19.9397, lon: -72.8306 },
  { name: "Fort-Liberté", lat: 19.6667, lon: -71.8333 }, { name: "Miragoâne", lat: 18.4425, lon: -73.0897 },
];
// Chèf-lye 42 awondisman Ayiti yo
const WX_HAITI_ARR = [
  ["Port-au-Prince", 18.5944, -72.3074], ["Arcahaie", 18.7714, -72.5125], ["Croix-des-Bouquets", 18.5769, -72.2269], ["Léogâne", 18.5111, -72.6339], ["Anse-à-Galets", 18.8333, -72.8667],
  ["Jacmel", 18.2342, -72.5347], ["Bainet", 18.1833, -72.75], ["Belle-Anse", 18.2386, -72.0661],
  ["Cap-Haïtien", 19.7578, -72.2042], ["Acul-du-Nord", 19.6833, -72.3167], ["Borgne", 19.85, -72.5333], ["Grande-Rivière-du-Nord", 19.5775, -72.1683], ["Limbé", 19.7056, -72.4], ["Plaisance", 19.6, -72.4667], ["Saint-Raphaël", 19.4383, -72.1983],
  ["Fort-Liberté", 19.6667, -71.8333], ["Ouanaminthe", 19.55, -71.7333], ["Trou-du-Nord", 19.6167, -72.0167], ["Vallières", 19.4333, -71.9167],
  ["Gonaïves", 19.4475, -72.6889], ["Dessalines", 19.25, -72.5167], ["Gros-Morne", 19.6667, -72.6833], ["Saint-Marc", 19.1081, -72.6936], ["Marmelade", 19.5167, -72.35],
  ["Hinche", 19.15, -72.0167], ["Cerca-la-Source", 19.1667, -71.7833], ["Lascahobas", 18.8333, -71.9333], ["Mirebalais", 18.8333, -72.1],
  ["Les Cayes", 18.1933, -73.7461], ["Aquin", 18.2833, -73.4], ["Chardonnières", 18.2667, -74.1667], ["Côteaux", 18.2, -74.0333], ["Port-Salut", 18.0833, -73.9167],
  ["Jérémie", 18.65, -74.1167], ["Anse-d'Hainault", 18.4833, -74.45], ["Corail", 18.5667, -73.8833],
  ["Port-de-Paix", 19.9397, -72.8306], ["Môle-Saint-Nicolas", 19.8, -73.3833], ["Saint-Louis-du-Nord", 19.9333, -72.7167],
  ["Miragoâne", 18.4425, -73.0897], ["Anse-à-Veau", 18.5, -73.35], ["Baradères", 18.4833, -73.6333],
].map(([name, lat, lon]) => ({ name, lat, lon }));
const WX_WORLD = [
  { name: "New York", lat: 40.7128, lon: -74.006 }, { name: "Miami", lat: 25.7617, lon: -80.1918 },
  { name: "Montréal", lat: 45.5019, lon: -73.5674 }, { name: "Port-au-Prince", lat: 18.5944, lon: -72.3074 },
  { name: "Santo Domingo", lat: 18.4861, lon: -69.9312 }, { name: "Mexico", lat: 19.4326, lon: -99.1332 },
  { name: "São Paulo", lat: -23.5505, lon: -46.6333 }, { name: "Paris", lat: 48.8566, lon: 2.3522 },
  { name: "London", lat: 51.5072, lon: -0.1276 }, { name: "Madrid", lat: 40.4168, lon: -3.7038 },
  { name: "Dakar", lat: 14.7167, lon: -17.4677 }, { name: "Dubai", lat: 25.2048, lon: 55.2708 },
  { name: "Tokyo", lat: 35.6762, lon: 139.6503 }, { name: "Sydney", lat: -33.8688, lon: 151.2093 },
];

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
  // Modèl meteyo (Open-Meteo) + lapli nan 30 dènye minit yo (pa tranch 15 minit)
  const f = await getJson(`${API_URL}?latitude=${at.lat}&longitude=${at.lon}&current=temperature_2m,weather_code,is_day,precipitation,rain,showers&minutely_15=precipitation,rain&past_minutely_15=2&forecast_minutely_15=1&models=best_match`);
  const cur = f?.current;
  if (!cur || !Number.isFinite(Number(cur.temperature_2m))) throw new Error("Sèvis meteyo a pa reponn byen");
  let code = Number(cur.weather_code) || 0, tempC = Number(cur.temperature_2m), source = "model";
  const m15 = f?.minutely_15 || {};
  const recent = [...(m15.precipitation || []), ...(m15.rain || [])].map(Number).filter(Number.isFinite);
  let wet = Math.max(Number(cur.precipitation) || 0, Number(cur.rain) || 0, Number(cur.showers) || 0, ...recent, 0);
  // Ozetazini: vrè mezi estasyon meteyo ki pi pre a (National Weather Service), pa yon previzyon
  if (String(at.country || "").toUpperCase() === "US") {
    const obs = await nwsObservation(at).catch(() => null);
    if (obs) {
      if (Number.isFinite(obs.tempC)) { tempC = obs.tempC; source = "station"; }
      if (obs.code != null) code = obs.code;
      if (obs.rainMm > wet) wet = obs.rainMm;
      if (obs.raining && code < 51) code = 61;
    }
  }
  // Lapli ki ap tonbe kounye a (mm): si kòd la di "nyaj" men gen lapli, montre lapli
  if (wet >= 0.1 && code < 51) code = wet >= 2 ? 81 : 61;
  const data = { city: at.name, country: at.country, tempC, code, rainMm: wet, isDay: cur.is_day !== 0, source,
    icon: weatherIcon(code, cur.is_day !== 0), at: Date.now() };
  weatherCache.set(key, { at: Date.now(), data, place: at, error: "" });
  return data;
}

// ---- National Weather Service (Ozetazini sèlman): obsèvasyon reyèl estasyon ki pi pre a ----
const NWS = "https://api.weather.gov";
const NWS_HEAD = { accept: "application/geo+json", "user-agent": "GalaxyTVStick panel (weather)" };
const nwsStations = new Map(); // "lat,lon" → { at, ids }
async function nwsJson(url) {
  const ctl = new AbortController(); const tm = setTimeout(() => ctl.abort(), 8000);
  try { const r = await fetch(url, { signal: ctl.signal, headers: NWS_HEAD }); if (!r.ok) throw new Error(`NWS HTTP ${r.status}`); return await r.json(); }
  finally { clearTimeout(tm); }
}
/** Kondisyon NWS (tèks) → kòd WMO. */
function nwsCode(text) {
  const t = String(text || "").toLowerCase();
  if (!t) return null;
  if (/thunder|t-storm/.test(t)) return 95;
  if (/snow|sleet|ice|flurr/.test(t)) return 71;
  if (/heavy rain|heavy showers/.test(t)) return 81;
  if (/rain|shower/.test(t)) return 61;
  if (/drizzle/.test(t)) return 51;
  if (/fog|mist|haze|smoke/.test(t)) return 45;
  if (/overcast|cloudy/.test(t) && !/partly|mostly clear/.test(t)) return 3;
  if (/partly|mostly|few clouds|scattered/.test(t)) return 2;
  if (/clear|sunny|fair/.test(t)) return 0;
  return null;
}
async function nwsObservation(at) {
  const k = `${Number(at.lat).toFixed(3)},${Number(at.lon).toFixed(3)}`;
  let st = nwsStations.get(k);
  if (!st || Date.now() - st.at > 24 * 3600_000) {
    const pt = await nwsJson(`${NWS}/points/${Number(at.lat).toFixed(4)},${Number(at.lon).toFixed(4)}`);
    const url = pt?.properties?.observationStations; if (!url) return null;
    const list = await nwsJson(url);
    st = { at: Date.now(), ids: (list?.features || []).slice(0, 3).map((f) => f?.properties?.stationIdentifier).filter(Boolean) };
    nwsStations.set(k, st);
  }
  for (const id of st.ids) {
    const o = (await nwsJson(`${NWS}/stations/${id}/observations/latest`).catch(() => null))?.properties;
    if (!o) continue;
    const age = Date.now() - Date.parse(o.timestamp || 0);
    if (!(age < 2 * 3600_000)) continue; // mezi twò ansyen: eseye lòt estasyon an
    const tempC = o.temperature?.value != null ? Number(o.temperature.value) : NaN;
    const pw = (o.presentWeather || []).map((w) => String(w?.weather || "")).join(" ");
    const text = `${o.textDescription || ""} ${pw}`;
    const rainMm = Number(o.precipitationLastHour?.value) || 0;
    if (!Number.isFinite(tempC) && !text.trim()) continue;
    return { tempC, code: nwsCode(text), rainMm, raining: /rain|drizzle|shower|thunder/i.test(text), station: id };
  }
  return null;
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
      size: num(b.size, 44, 16, 160), opacity: num(b.opacity, 85, 10, 100),
      // Estil tèks la (lè pa gen logo): kalite, polis, koulè, italik, majiskil
      style: TEXT_STYLES.includes(b.style) ? b.style : "classic",
      font: TEXT_FONTS.includes(b.font) ? b.font : "sans",
      color: hex(b.color, "#FFFFFF"), color2: hex(b.color2, "#1D4ED8"), italic: !!b.italic, caps: b.caps !== false };
  }

  // Watermark: tèks pal sou imaj la (egz: MAC kliyan an) pou dekouraje moun k ap kopye/rediffize
  const w = g.watermark || {};
  if (w.on && str(w.text)) {
    const text = str(w.text, 60).replace(/\{mac\}/gi, ctx.mac || "").replace(/\{customer\}/gi, ctx.customer || "").replace(/\{device\}/gi, ctx.deviceName || "").trim();
    if (text) out.watermark = { text, position: pos(w.position, POS_WM, "bottom-right"), size: num(w.size, 16, 8, 60), opacity: num(w.opacity, 25, 3, 100), move: !!w.move,
      style: TEXT_STYLES.includes(w.style) ? w.style : "plain", font: TEXT_FONTS.includes(w.font) ? w.font : "sans",
      color: hex(w.color, "#FFFFFF"), color2: hex(w.color2, "#000000"), italic: !!w.italic };
  }

  // Scoreboard
  const s = g.scoreboard || {};
  if (s.on && (str(s.home) || str(s.away))) {
    out.scoreboard = { league: str(s.league, 30), home: str(s.home, 26), away: str(s.away, 26), homeScore: str(s.homeScore, 4) || "0", awayScore: str(s.awayScore, 4) || "0",
      clock: str(s.clock, 16), homeColor: hex(s.homeColor, "#1D4ED8"), awayColor: hex(s.awayColor, "#DC2626"), position: pos(s.position, POS6, "top-left") };
  }

  // Meteyo: otomatik (Open-Meteo) oswa alamen
  const m = g.weather || {};
  // Plizyè vil youn apre lòt (tankou CNN): tout depatman Ayiti, gran vil nan mond lan, oswa pwòp lis admin nan
  const multi = m.rotate === false ? null : m.scope === "haiti" ? WX_HAITI : m.scope === "haiti_arr" ? WX_HAITI_ARR : m.scope === "world" ? WX_WORLD
    : m.scope === "list" ? String(m.cities || "").split(/[\n,;]+/).map((c) => str(c, 40)).filter(Boolean).slice(0, 20) : null;
  if (m.on && multi && multi.length) {
    const unit = m.unit === "F" ? "F" : "C";
    const list = [];
    for (const place of multi) {
      const live = weatherNow(place); if (!live || !Number.isFinite(live.tempC)) continue;
      const t = unit === "F" ? (live.tempC * 9) / 5 + 32 : live.tempC;
      list.push({ city: (typeof place === "string" ? live.city || place : place.name), temp: `${Math.round(t)}°${unit}`, icon: live.icon });
    }
    // Mòd «flash»: vil prensipal la rete sou ekran an; chak {everySec} s yon lòt vil parèt {flashSec} s epi li tounen sou vil prensipal la
    let flashSec = 0;
    if (m.mode === "flash" && str(m.city)) {
      const home = weatherNow(str(m.city, 30));
      if (home && Number.isFinite(home.tempC)) {
        const t = unit === "F" ? (home.tempC * 9) / 5 + 32 : home.tempC;
        const hc = home.city || str(m.city, 30), same = (x) => x.city.toLowerCase() === hc.toLowerCase() || x.city.toLowerCase() === str(m.city).toLowerCase();
        const rest = list.filter((x) => !same(x));
        list.length = 0; list.push({ city: hc, temp: `${Math.round(t)}°${unit}`, icon: home.icon }, ...rest);
        flashSec = num(m.flash, 3, 2, 10);
      }
    }
    if (list.length) {
      const everySec = num(m.every, 8, 4, 60);
      if (flashSec) { out.weather = { ...list[0], position: pos(m.position, POS6, "top-right"), list, everySec, flashSec }; }
      else {
      // App ansyen yo (san "list") wè yon lòt vil chak minit; app 1.0.145+ fè yo vire chak {everySec} segonn
      const first = list[Math.floor(now / 60) % list.length];
      out.weather = { ...first, position: pos(m.position, POS6, "top-right"), list, everySec };
      }
    }
  } else if (m.on && (str(m.city) || m.scope === "client")) {
    const unit = m.unit === "F" ? "F" : "C";
    // "client" = meteyo kote chak kliyan ye: vil aparèy la, sinon vil kliyan an, sinon IP li, sinon vil jeneral la
    const place = m.scope === "client" ? str(ctx.deviceCity, 30) || str(ctx.customerCity, 30) || ipPlace(ctx.ip) || str(m.city, 30) : str(m.city, 30);
    const live = m.auto !== false ? weatherNow(place) : null;
    let tempC = null, icon = str(m.icon, 8) || "🌤️", city = live?.city || (typeof place === "string" ? place : place.name) || str(m.city, 30);
    if (live) { tempC = live.tempC; icon = live.icon; }
    // Tanperati alamen an sèlman lè "Otomatik" etenn: si l limen epi meteyo a poko rive, pa montre yon fo chif (li parèt nan 1–2 minit)
    else if (m.auto === false && m.temp !== "" && m.temp != null && Number.isFinite(Number(m.temp))) tempC = unit === "F" ? ((Number(m.temp) - 32) * 5) / 9 : Number(m.temp);
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
      out.countdown = { title: str(c.title, 40), targetAt, doneText: str(c.doneText, 40), position: pos(c.position, POS6, "top-center"),
        style: TEXT_STYLES.includes(c.style) ? c.style : "classic", font: TEXT_FONTS.includes(c.font) ? c.font : "sans",
        color: hex(c.color, "#FFFFFF"), color2: hex(c.color2, "#1D4ED8"), italic: !!c.italic, box: c.box !== false };
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
