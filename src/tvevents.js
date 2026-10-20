// Konfimasyon TV yo pou panel la: TV a pa montre kliyan an anyen ankò lè admin nan chanje yon bagay,
// se panel la ki resevwa "✅ aplike sou TV …". Rete nan memwa sèlman (dènye 200 yo).
const events = [];
const pendingSync = new Map(); // mac → lè TV a resevwa siyal "sync" la
let seq = 0;

const label = (d) => [d?.customer, d?.mac].filter(Boolean).join(" · ") || d?.mac || "TV";

function push(d, type) {
  events.push({ id: ++seq, at: Date.now(), mac: d?.mac || "", name: label(d), type });
  if (events.length > 200) events.splice(0, events.length - 200);
}

export const tvEvents = {
  /** TV a fèk resevwa kòmand yo (long-poll). "sync" konfime lè l rechache config la. */
  delivered(d, cmds) {
    for (const c of cmds || []) {
      if (c.type === "channels" || c.type === "vodcats") continue;
      if (c.type === "sync" || c.type === "refresh") pendingSync.set(d?.mac, Date.now());
      else push(d, c.type);
    }
  },
  /** TV a fèk chaje config li: si l te gen yon chanjman k ap tann, li aplike kounye a. */
  configFetched(d) {
    const t = pendingSync.get(d?.mac);
    if (!t) return;
    pendingSync.delete(d.mac);
    if (Date.now() - t < 5 * 60000) push(d, "applied");
  },
  since(id) { return { last: seq, events: events.filter((e) => e.id > id) }; },
};
