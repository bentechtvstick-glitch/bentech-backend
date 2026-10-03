/* Galaxy TV Stick — Grafik TV (menm desen ak app TV a: ui/GraphicsView.kt)
 * Logo bug, watermark, scoreboard, meteyo, countdown, channel ident, bumper.
 * Tout mezi yo fèt pou yon ekran 960×540 (panel la ak demo a redimansyone l).
 */
(() => {
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const POS6 = ["top-left", "top-center", "top-right", "bottom-left", "bottom-center", "bottom-right"];

  const CSS = `
.gx{position:absolute;inset:0;pointer-events:none;overflow:hidden;font-family:inherit;color:#fff;--gx-bottom:60px}
.gx-s{position:absolute;display:flex;flex-direction:column;gap:8px;max-width:420px}
.gx-s.top-left{left:36px;top:30px;align-items:flex-start}.gx-s.top-center{left:50%;top:30px;transform:translateX(-50%);align-items:center}.gx-s.top-right{right:36px;top:30px;align-items:flex-end}
.gx-s.bottom-left{left:36px;bottom:var(--gx-bottom);align-items:flex-start;flex-direction:column-reverse}.gx-s.bottom-center{left:50%;bottom:var(--gx-bottom);transform:translateX(-50%);align-items:center;flex-direction:column-reverse}.gx-s.bottom-right{right:36px;bottom:var(--gx-bottom);align-items:flex-end;flex-direction:column-reverse}
.gx-bug img{display:block;width:auto;max-width:320px;object-fit:contain}
.gx-bug b{font-weight:800;letter-spacing:.04em;text-shadow:0 2px 6px rgba(0,0,0,.7);white-space:nowrap}
.gx-sb{display:flex;flex-direction:column;border-radius:6px;overflow:hidden;box-shadow:0 4px 14px rgba(0,0,0,.4);font-weight:800}
.gx-sb-l{background:rgba(8,12,26,.92);font-size:11px;letter-spacing:.1em;text-transform:uppercase;padding:3px 10px;color:#cbd5f5;white-space:nowrap}
.gx-sb-r{display:flex;align-items:stretch;font-size:18px;line-height:1}
.gx-sb-t{padding:8px 12px;white-space:nowrap;max-width:150px;overflow:hidden;text-overflow:ellipsis}
.gx-sb-n{background:rgba(8,12,26,.94);padding:8px 10px;min-width:22px;text-align:center;font-variant-numeric:tabular-nums}
.gx-sb-c{background:rgba(8,12,26,.94);color:#00E5FF;padding:8px 12px;font-size:14px;display:flex;align-items:center;border-left:1px solid rgba(255,255,255,.14);white-space:nowrap;font-variant-numeric:tabular-nums}
.gx-wx{display:flex;align-items:center;gap:8px;background:rgba(8,12,26,.72);border-radius:10px;padding:6px 12px;box-shadow:0 4px 14px rgba(0,0,0,.3)}
.gx-wx i{font-style:normal;font-size:24px;line-height:1}.gx-wx b{font-size:20px;font-weight:800;line-height:1}.gx-wx span{font-size:13px;opacity:.85;white-space:nowrap;max-width:160px;overflow:hidden;text-overflow:ellipsis}
.gx-cd{display:flex;flex-direction:column;align-items:center;background:rgba(8,12,26,.8);border-radius:10px;padding:6px 16px 8px;border-bottom:3px solid #00E5FF;box-shadow:0 4px 14px rgba(0,0,0,.3)}
.gx-cd small{font-size:11px;letter-spacing:.1em;text-transform:uppercase;opacity:.85;white-space:nowrap}
.gx-cd b{font-size:26px;font-weight:800;font-variant-numeric:tabular-nums;line-height:1.15;white-space:nowrap}
.gx-wm{position:absolute;font-weight:700;white-space:nowrap;text-shadow:0 1px 2px rgba(0,0,0,.5);transition:opacity .6s}
.gx-wm.top-left{left:48px;top:90px}.gx-wm.top-right{right:48px;top:90px}.gx-wm.bottom-left{left:48px;bottom:calc(var(--gx-bottom) + 60px)}.gx-wm.bottom-right{right:48px;bottom:calc(var(--gx-bottom) + 60px)}
.gx-wm.center{left:50%;top:50%;transform:translate(-50%,-50%)}
.gx-full{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;text-align:center;color:#fff;padding:40px 80px}
.gx-ident{background:radial-gradient(ellipse at center,rgba(8,12,26,.72),rgba(8,12,26,.35) 60%,transparent 80%);animation:gxIdent var(--gx-dur,5s) ease both}
.gx-ident img{max-width:420px;max-height:220px;object-fit:contain;animation:gxPop .9s cubic-bezier(.2,.9,.3,1.2) both}
.gx-ident b{font-size:54px;font-weight:800;letter-spacing:.04em;text-shadow:0 4px 18px rgba(0,0,0,.6);animation:gxPop .9s cubic-bezier(.2,.9,.3,1.2) both}
.gx-ident span{font-size:22px;opacity:.9;text-shadow:0 2px 8px rgba(0,0,0,.6);animation:gxUp 1s .3s ease both}
.gx-bump{animation:gxFade .45s ease both}
.gx-bump img{position:absolute;inset:0;width:100%;height:100%;object-fit:contain}
.gx-bump b{font-size:48px;font-weight:800;line-height:1.15;animation:gxUp .7s ease both}
@keyframes gxIdent{0%{opacity:0}12%{opacity:1}85%{opacity:1}100%{opacity:0}}
@keyframes gxPop{from{opacity:0;transform:scale(.7)}to{opacity:1;transform:scale(1)}}
@keyframes gxUp{from{opacity:0;transform:translateY(16px)}to{opacity:1;transform:none}}
@keyframes gxFade{from{opacity:0}to{opacity:1}}
@media (prefers-reduced-motion:reduce){.gx-ident,.gx-ident *,.gx-bump,.gx-bump *{animation-duration:.01s!important}}
`;
  if (typeof document !== "undefined" && !document.getElementById("gx-css")) {
    const st = document.createElement("style"); st.id = "gx-css"; st.textContent = CSS; document.head.appendChild(st);
  }

  /** "2j 03:14:09" / "03:14:09" / "14:09" */
  function fmtLeft(sec) {
    sec = Math.max(0, Math.floor(sec));
    const d = Math.floor(sec / 86400), h = Math.floor((sec % 86400) / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60, p = (n) => String(n).padStart(2, "0");
    return (d ? d + "d " : "") + (d || h ? p(h) + ":" : "") + p(m) + ":" + p(s);
  }
  const cdText = (c, now) => (now >= c.targetAt ? c.doneText || "00:00" : fmtLeft(c.targetAt - now));

  function bugHtml(b) {
    const op = (b.opacity ?? 85) / 100, h = b.size || 44;
    return `<div class="gx-bug" style="opacity:${op}">${b.imageUrl ? `<img src="${esc(b.imageUrl)}" style="height:${h}px" alt="">` : `<b style="font-size:${Math.round(h * 0.6)}px">${esc(b.text)}</b>`}</div>`;
  }
  function sbHtml(s) {
    return `<div class="gx-sb">${s.league ? `<div class="gx-sb-l">${esc(s.league)}</div>` : ""}<div class="gx-sb-r">
      <span class="gx-sb-t" style="background:${esc(s.homeColor)}">${esc(s.home)}</span><span class="gx-sb-n">${esc(s.homeScore)}</span><span class="gx-sb-n">${esc(s.awayScore)}</span><span class="gx-sb-t" style="background:${esc(s.awayColor)}">${esc(s.away)}</span>${s.clock ? `<span class="gx-sb-c">${esc(s.clock)}</span>` : ""}</div></div>`;
  }
  const wxHtml = (w) => `<div class="gx-wx"><i>${esc(w.icon)}</i><b>${esc(w.temp)}</b><span>${esc(w.city)}</span></div>`;
  const cdHtml = (c, now) => `<div class="gx-cd">${c.title ? `<small>${esc(c.title)}</small>` : ""}<b data-gxcd="${c.targetAt}" data-gxdone="${esc(c.doneText || "")}">${esc(cdText(c, now))}</b></div>`;

  /** Kouch grafik ki rete sou ekran an (bug, scoreboard, meteyo, countdown, watermark). */
  function html(g, opts = {}) {
    g = g || {};
    const now = opts.now || Date.now() / 1000;
    const stacks = Object.fromEntries(POS6.map((p) => [p, []]));
    const put = (item, def, h) => { if (item) stacks[POS6.includes(item.position) ? item.position : def].push(h); };
    put(g.bug, "top-right", g.bug && bugHtml(g.bug));
    put(g.scoreboard, "top-left", g.scoreboard && sbHtml(g.scoreboard));
    put(g.weather, "top-right", g.weather && wxHtml(g.weather));
    put(g.countdown, "top-center", g.countdown && cdHtml(g.countdown, now));
    let h = POS6.filter((p) => stacks[p].length).map((p) => `<div class="gx-s ${p}">${stacks[p].join("")}</div>`).join("");
    if (g.watermark) { const w = g.watermark;
      h += `<div class="gx-wm ${esc(w.position)}" data-gxwm="${w.move ? 1 : 0}" style="font-size:${w.size || 16}px;opacity:${(w.opacity ?? 25) / 100}">${esc(w.text)}</div>`; }
    return `<div class="gx" style="--gx-bottom:${opts.bottom ?? 60}px">${h}</div>`;
  }

  const WM_CORNERS = ["top-left", "bottom-right", "top-right", "bottom-left"];
  /** Chak segonn: mete countdown yo ajou; chak 30 s: deplase watermark la si "move" limen. */
  function tick(root, now) {
    now = now || Date.now() / 1000;
    root.querySelectorAll("[data-gxcd]").forEach((el) => { const txt = cdText({ targetAt: +el.dataset.gxcd, doneText: el.dataset.gxdone }, now); if (el.textContent !== txt) el.textContent = txt; });
    root.querySelectorAll('[data-gxwm="1"]').forEach((el) => { const want = WM_CORNERS[Math.floor(now / 30) % 4]; if (!el.classList.contains(want)) { el.classList.remove(...WM_CORNERS, "center"); el.classList.add(want); } });
  }

  /** Channel ident: logo/non chèn nan nan mitan ekran an pou kèk segonn. */
  function identHtml(i) {
    return `<div class="gx-full gx-ident" style="--gx-dur:${i.durationSec || 5}s">${i.imageUrl ? `<img src="${esc(i.imageUrl)}" alt="">` : ""}${i.text ? `<b>${esc(i.text)}</b>` : ""}${i.tagline ? `<span>${esc(i.tagline)}</span>` : ""}</div>`;
  }
  /** Bumper anvan ("in") oswa apre ("out") koupi piblisite a. Retounen "" si pa gen youn pou bò sa a. */
  function bumperHtml(b, which) {
    const url = which === "out" ? b.outUrl : b.inUrl, text = which === "out" ? b.outText : b.inText;
    if (!url && !text) return "";
    return `<div class="gx-full gx-bump" style="background:${esc(b.bgColor || "#0B1630")};color:${esc(b.textColor || "#fff")}">${url ? `<img src="${esc(url)}" alt="">` : `<b>${esc(text)}</b>`}</div>`;
  }

  window.GalaxyGfx = { html, tick, identHtml, bumperHtml, fmtLeft, POS6 };
})();
