// Asistan AI panel la (Claude Haiku): li gade eta panel la epi li reponn kesyon admin nan.
// Ekonomik: yon sèl demann pa kesyon, yon ti rezime done yo (pa tout baz done a), repons kout,
// epi yon limit depans pa mwa (AI_MONTHLY_LIMIT_USD, pa defo $2) pou pa janm gen sipriz.
// Kle a: ANTHROPIC_API_KEY nan /etc/galaxy.env (pa janm nan kòd la ni nan baz done a).

const MODEL = process.env.AI_MODEL || "claude-haiku-4-5";
// Pri pa milyon token (Haiku 4.5): $1 antre, $5 sòti
const PRICE_IN = Number(process.env.AI_PRICE_IN || 1), PRICE_OUT = Number(process.env.AI_PRICE_OUT || 5);
const LANG_NAMES = { ht: "Haitian Creole (Kreyòl ayisyen)", en: "English", fr: "French", es: "Spanish" };

const month = () => new Date().toISOString().slice(0, 7);
const limit = () => Number(process.env.AI_MONTHLY_LIMIT_USD || 2);
function usageOf(db) {
  const u = (db.data.aiUsage ??= {});
  if (u.month !== month()) Object.assign(u, { month: month(), inTok: 0, outTok: 0, cost: 0, asks: 0 });
  return u;
}
export const aiLangName = (code) => LANG_NAMES[code] || "English";
export const aiBudget = (db) => { const u = usageOf(db); return { cost: u.cost, limit: limit(), left: limit() - u.cost }; };

/** Yon sèl apèl Claude (konte depans lan, respekte limit mwa a). Voye tèks repons lan, oswa jete yon erè. */
export async function aiComplete(db, { system, user, maxTokens = 600 }) {
  const key = process.env.ANTHROPIC_API_KEY;
  if (!key) throw new Error("AI is not set up yet: add ANTHROPIC_API_KEY on the server.");
  const u = usageOf(db);
  if (u.cost >= limit()) throw Object.assign(new Error(`Monthly AI limit reached ($${limit()}).`), { status: 429 });
  const r = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: { "x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json" },
    body: JSON.stringify({ model: MODEL, max_tokens: maxTokens, system, messages: [{ role: "user", content: user }] }),
    signal: AbortSignal.timeout(60000),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw Object.assign(new Error(j?.error?.message || `AI error HTTP ${r.status}`), { status: 502 });
  const inTok = j.usage?.input_tokens || 0, outTok = j.usage?.output_tokens || 0;
  const cost = (inTok * PRICE_IN + outTok * PRICE_OUT) / 1e6;
  u.inTok += inTok; u.outTok += outTok; u.cost += cost; u.asks = (u.asks || 0) + 1;
  db.write().catch(() => {});
  return { text: (j.content || []).filter((c) => c.type === "text").map((c) => c.text).join("\n").trim(), cost, monthCost: u.cost };
}

export function mountAi(app, db, { authenticate, isOnline }) {
  const usage = () => usageOf(db);

  /** Ti rezime eta panel la (san modpas, san kle, san lyen playlist). */
  function snapshot() {
    const d = db.data, now = Date.now();
    const devices = (d.devices || []).filter((x) => x.mac);
    const online = devices.filter((x) => isOnline(x.mac));
    const versions = {};
    for (const x of devices) versions[x.appVersion || "?"] = (versions[x.appVersion || "?"] || 0) + 1;
    const customers = (d.customers || []).map((c) => ({ name: c.name || "", status: c.status || "", plan: c.plan || "", expiry: c.expiry || "" }));
    const soon = customers.filter((c) => c.expiry && Date.parse(c.expiry) - now < 14 * 86400000).slice(0, 30);
    const alerts = (d.channelAlerts || []).filter((a) => now - a.at < 2 * 86400000).sort((a, b) => b.at - a.at).slice(0, 40)
      .map((a) => ({ when: new Date(a.at).toISOString().slice(0, 16), type: a.type || "live", title: a.channel, tv: a.mac, times: a.count || 1, detail: a.detail }));
    // Chanèl ki pi fèb yo (sante)
    const weak = [];
    for (const [host, byId] of Object.entries(d.channelHealth || {})) {
      for (const h of Object.values(byId || {})) if ((h.n || 0) >= 3 && ((h.fail || 0) > 0.3 || (h.cuts || 0) > 2)) weak.push({ host, name: h.name, failPct: Math.round((h.fail || 0) * 100), cutsPer10min: h.cuts ?? null, tries: h.n });
    }
    weak.sort((a, b) => b.failPct - a.failPct);
    return {
      now: new Date().toISOString().slice(0, 16),
      tvs: { total: devices.length, online: online.length, appVersions: versions,
        list: devices.slice(0, 50).map((x) => ({ mac: x.mac, name: x.name || x.deviceName || "", online: isOnline(x.mac), app: x.appVersion || "", lastSeen: x.lastSeenAt || "" })) },
      customers: { total: customers.length, expiringWithin14Days: soon },
      channelAndVideoProblemsLast48h: alerts,
      weakChannels: weak.slice(0, 15),
    };
  }

  app.get("/api/galaxy/ai", authenticate, (req, res) => {
    const u = usage();
    res.json({ configured: !!process.env.ANTHROPIC_API_KEY, model: MODEL, month: u.month, cost: Math.round(u.cost * 10000) / 10000, asks: u.asks || 0, limit: limit() });
  });

  app.post("/api/galaxy/ai", authenticate, async (req, res) => {
    const key = process.env.ANTHROPIC_API_KEY;
    if (!key) return res.status(400).json({ error: "AI is not set up yet: add ANTHROPIC_API_KEY on the server." });
    const u = usage();
    if (u.cost >= limit()) return res.status(429).json({ error: `Monthly AI limit reached ($${limit()}).` });
    const question = String(req.body?.question || "").trim().slice(0, 1000);
    if (!question) return res.status(400).json({ error: "Empty question." });
    const lang = LANG_NAMES[req.body?.lang] || "English";
    const system = "You are the assistant inside the Galaxy TV Stick admin panel (an IPTV app for Android TV / Fire TV, managed by a non-technical owner). " +
      "You get a JSON snapshot of the panel: TVs, customers, and channel/video problems reported by the TVs. Video streams come straight from the customer's IPTV provider, not from this panel. " +
      "Common causes: HTTP 401/403/429 = provider account refused or too many screens; HTTP 5xx or empty reply = provider/channel down; many channels failing on one provider at once = provider outage; one TV only = that TV's internet/router. " +
      `Answer in ${lang}, in simple words, short (max ~12 lines), with concrete next steps in the panel. Never invent data that is not in the snapshot. Never reveal passwords or keys.`;
    try {
      if (req.body?.lang && LANG_NAMES[req.body.lang]) { (db.data.watch ??= {}).lang = req.body.lang; }
      const r = await aiComplete(db, { system, user: `Panel snapshot:\n${JSON.stringify(snapshot())}\n\nQuestion: ${question}`, maxTokens: 700 });
      res.json({ answer: r.text, cost: Math.round(r.cost * 10000) / 10000, monthCost: Math.round(r.monthCost * 10000) / 10000, limit: limit() });
    } catch (err) {
      res.status(err.status || 502).json({ error: err.status ? err.message : "AI unavailable: " + (err.message || err) });
    }
  });
}
