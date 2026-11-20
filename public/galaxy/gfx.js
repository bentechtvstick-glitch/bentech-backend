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
.gx-wx{display:flex;align-items:center;gap:8px;padding:4px;text-shadow:0 1px 4px rgba(0,0,0,.9)}
.gx-wx i{font-style:normal;font-size:24px;line-height:1}.gx-wx b{font-size:20px;font-weight:800;line-height:1}.gx-wx span{font-size:13px;font-weight:700;white-space:nowrap;max-width:160px;overflow:hidden;text-overflow:ellipsis}
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
.gx-snipe{position:absolute;z-index:6;pointer-events:none;filter:drop-shadow(0 6px 14px rgba(0,0,0,.5))}
.gx-snipe img{display:block;width:auto;max-width:640px;object-fit:contain}
.gx-sn-in{display:flex;align-items:center;gap:12px}
.gx-sn-txt{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Arial,sans-serif;font-weight:800;padding:10px 18px;border-radius:10px;max-width:760px;line-height:1.15;text-shadow:0 1px 3px rgba(0,0,0,.5)}
.gx-sn-roll.gx-sn-bottom-left{animation:gxSnRollL var(--gx-dur,8s) cubic-bezier(.2,.8,.2,1) both}
.gx-sn-roll.gx-sn-bottom-right,.gx-sn-roll.gx-sn-bottom-center{animation:gxSnRollR var(--gx-dur,8s) cubic-bezier(.2,.8,.2,1) both}
.gx-sn-swing{animation:gxIdent var(--gx-dur,8s) ease both}.gx-sn-swing .gx-sn-in{transform-origin:50% 0;animation:gxSnSwing 1.6s ease both,gxSnSway 2.4s 1.6s ease-in-out infinite}
.gx-sn-elastic .gx-sn-in{animation:gxSnElastic var(--gx-dur,8s) both}
.gx-sn-pulse{animation:gxIdent var(--gx-dur,8s) ease both}.gx-sn-pulse .gx-sn-in{animation:gxSnPulse 1.2s .5s ease-in-out infinite}
.gx-sn-float{animation:gxSnUp var(--gx-dur,8s) cubic-bezier(.2,.8,.2,1) both}.gx-sn-float .gx-sn-in{animation:gxSnFloat 2.2s .7s ease-in-out infinite}
.gx-sn-glitch{animation:gxIdent var(--gx-dur,8s) steps(1) both}.gx-sn-glitch .gx-sn-in{animation:gxSnGlitch .7s steps(1) both}
.gx-sn-blink .gx-sn-in{animation:gxSnBlink var(--gx-dur,8s) steps(1) both}
@keyframes gxSnRollR{0%{opacity:0;translate:120% 0;rotate:360deg}11%{opacity:1;translate:0 0;rotate:0deg}90%{opacity:1;translate:0 0;rotate:0deg}100%{opacity:0;translate:120% 0;rotate:360deg}}
@keyframes gxSnRollL{0%{opacity:0;translate:-120% 0;rotate:-360deg}11%{opacity:1;translate:0 0;rotate:0deg}90%{opacity:1;translate:0 0;rotate:0deg}100%{opacity:0;translate:-120% 0;rotate:-360deg}}
@keyframes gxSnSwing{0%{rotate:25deg}20%{rotate:-18deg}40%{rotate:12deg}60%{rotate:-7deg}80%{rotate:4deg}100%{rotate:0deg}}
@keyframes gxSnSway{0%,100%{rotate:0deg}25%{rotate:2.5deg}75%{rotate:-2.5deg}}
@keyframes gxSnElastic{0%{opacity:0;transform:scale(0,0)}3%{opacity:1;transform:scale(1.25,.8)}6%{transform:scale(.85,1.15)}9%{transform:scale(1.08,.94)}12%{transform:scale(1,1)}93%{opacity:1;transform:scale(1,1)}100%{opacity:0;transform:scale(0,0)}}
@keyframes gxSnPulse{0%,100%{transform:scale(1)}50%{transform:scale(1.08)}}
@keyframes gxSnFloat{0%,100%{translate:0 0}50%{translate:0 -9px}}
@keyframes gxSnGlitch{0%{translate:-10px 0;filter:drop-shadow(4px 0 #ff2040) drop-shadow(-4px 0 #00e5ff);opacity:.4}20%{translate:8px 0;opacity:1}40%{translate:-4px 0;opacity:.35}60%{translate:6px 0;opacity:1}80%{translate:-2px 0}100%{translate:0 0;filter:none;opacity:1}}
@keyframes gxSnBlink{0%{opacity:0}3%{opacity:1}6%{opacity:0}9%{opacity:1}12%{opacity:0}15%{opacity:1}94%{opacity:1}96%{opacity:0}98%{opacity:1}100%{opacity:0}}
.gx-sn-slide.gx-sn-bottom-right,.gx-sn-slide.gx-sn-bottom-center{animation:gxSnR var(--gx-dur,8s) cubic-bezier(.2,.8,.2,1) both}
.gx-sn-slide.gx-sn-bottom-left{animation:gxSnL var(--gx-dur,8s) cubic-bezier(.2,.8,.2,1) both}
.gx-sn-rise{animation:gxSnUp var(--gx-dur,8s) cubic-bezier(.2,.8,.2,1) both}
.gx-sn-fade{animation:gxIdent var(--gx-dur,8s) ease both}
.gx-sn-pop .gx-sn-in{animation:gxSnPop var(--gx-dur,8s) cubic-bezier(.2,.9,.3,1.2) both}
.gx-sn-bounce{animation:gxSnBounce var(--gx-dur,8s) linear both}
.gx-sn-flip .gx-sn-in{animation:gxSnFlip var(--gx-dur,8s) ease both}
.gx-sn-wipe .gx-sn-in{animation:gxSnWipe var(--gx-dur,8s) ease both}
.gx-sn-zoom .gx-sn-in{animation:gxSnZoom var(--gx-dur,8s) ease-out both}
.gx-sn-shake.gx-sn-bottom-left{animation:gxSnL var(--gx-dur,8s) cubic-bezier(.2,.8,.2,1) both}
.gx-sn-shake.gx-sn-bottom-right,.gx-sn-shake.gx-sn-bottom-center{animation:gxSnR var(--gx-dur,8s) cubic-bezier(.2,.8,.2,1) both}
.gx-sn-shake .gx-sn-in{animation:gxSnShake .7s calc(var(--gx-dur,8s) * .09) ease both}
.gx-sn-shine{animation:gxIdent var(--gx-dur,8s) ease both}
.gx-sn-shine .gx-sn-in{animation:gxSnShine 1.6s .5s ease-in-out 3}
@keyframes gxSnBounce{0%{opacity:0;translate:0 -240px}3%{opacity:1}6%{translate:0 0}8%{translate:0 -60px}10%{translate:0 0}11.5%{translate:0 -18px}13%{translate:0 0}90%{opacity:1;translate:0 0}100%{opacity:0;translate:0 140%}}
@keyframes gxSnFlip{0%{opacity:0;transform:perspective(700px) rotateY(90deg)}8%{opacity:1;transform:perspective(700px) rotateY(0)}90%{opacity:1;transform:perspective(700px) rotateY(0)}100%{opacity:0;transform:perspective(700px) rotateY(-90deg)}}
@keyframes gxSnWipe{0%{clip-path:inset(0 100% 0 0)}10%{clip-path:inset(0 0 0 0)}90%{clip-path:inset(0 0 0 0)}100%{clip-path:inset(0 0 0 100%)}}
@keyframes gxSnZoom{0%{opacity:0;transform:scale(2.2);filter:blur(14px)}9%{opacity:1;transform:scale(1);filter:blur(0)}90%{opacity:1;transform:scale(1);filter:blur(0)}100%{opacity:0;transform:scale(1.6);filter:blur(10px)}}
@keyframes gxSnShake{0%,100%{translate:0 0}15%{translate:-14px 0}30%{translate:12px 0}45%{translate:-8px 0}60%{translate:6px 0}75%{translate:-3px 0}}
@keyframes gxSnShine{0%,100%{filter:brightness(1)}50%{filter:brightness(1.7) drop-shadow(0 0 14px rgba(255,255,255,.7))}}
@keyframes gxSnR{0%{opacity:0;translate:120% 0}8%{opacity:1;translate:0 0}90%{opacity:1;translate:0 0}100%{opacity:0;translate:120% 0}}
@keyframes gxSnL{0%{opacity:0;translate:-120% 0}8%{opacity:1;translate:0 0}90%{opacity:1;translate:0 0}100%{opacity:0;translate:-120% 0}}
@keyframes gxSnUp{0%{opacity:0;translate:0 140%}8%{opacity:1;translate:0 0}90%{opacity:1;translate:0 0}100%{opacity:0;translate:0 140%}}
@keyframes gxSnPop{0%{opacity:0;transform:scale(.5)}8%{opacity:1;transform:scale(1)}90%{opacity:1;transform:scale(1)}100%{opacity:0;transform:scale(.5)}}
.gx-wb{position:absolute;left:0;right:0;height:78px;display:flex;align-items:center;gap:0;z-index:5;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Arial,sans-serif;animation:gxWbIn .5s ease-out both;box-shadow:0 -2px 10px rgba(0,0,0,.3)}
.gx-wb-tag{width:150px;margin:0 8px 0 16px;display:flex;flex-direction:column;align-items:flex-start;gap:2px;color:#fff}.gx-wb-tag b{font-size:24px;font-weight:900;font-style:italic;letter-spacing:.02em;line-height:1;text-shadow:0 2px 0 rgba(0,0,0,.25)}.gx-wb-tag i{font-style:normal;font-size:13px;font-weight:800;padding:2px 8px;border-radius:4px;line-height:1.2}
.gx-wb-c{flex:1;display:flex;align-items:center;gap:10px;padding:0 10px 0 14px;min-width:0}
.gx-wb-c b{display:block;font-size:18px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.gx-wb-i{font-size:30px}
.gx-wb-t{display:flex;align-items:center;gap:6px;margin-top:3px}
.gx-wb-t i{font-style:normal;font-weight:800;font-size:17px;color:#fff;padding:1px 8px;border-radius:4px}.gx-wb-t .lo{background:#2563EB}.gx-wb-t .hi{background:#F97316}
.gx-wb-t small{font-size:14px;opacity:.85;margin-left:4px}
.gx-wb-sep{width:2px;height:44px;flex:none}
@keyframes gxWbIn{from{transform:translateY(120%)}to{transform:none}}
@keyframes gxIdent{0%{opacity:0}12%{opacity:1}85%{opacity:1}100%{opacity:0}}
@keyframes gxPop{from{opacity:0;transform:scale(.7)}to{opacity:1;transform:scale(1)}}
@keyframes gxUp{from{opacity:0;transform:translateY(16px)}to{opacity:1;transform:none}}
@keyframes gxFade{from{opacity:0}to{opacity:1}}
@media (prefers-reduced-motion:reduce){.gx-snipe,.gx-snipe *,.gx-ident,.gx-ident *,.gx-bump,.gx-bump *{animation-duration:.01s!important}}
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

  // ---- Estil tèks (logo bug, ticker, chyron…): menm lis ak app TV a (ui/TextFx.kt) ----
  const FONTS = [
    ["sans", "Modèn", "system-ui,Roboto,Arial,sans-serif"], ["condensed", "Sere (condensed)", "'Roboto Condensed','Arial Narrow',sans-serif"],
    ["black", "Epè (black)", "'Arial Black',Roboto,sans-serif"], ["serif", "Klasik (serif)", "Georgia,'Times New Roman',serif"],
    ["mono", "Machin a ekri (mono)", "'Roboto Mono',Consolas,monospace"], ["script", "Ekriti men (script)", "'Dancing Script','Brush Script MT',cursive"],
    ["bebas", "Bebas Neue", "'Bebas Neue',sans-serif"], ["oswald", "Oswald", "'Oswald',sans-serif"], ["anton", "Anton", "'Anton',sans-serif"],
    ["teko", "Teko", "'Teko',sans-serif"], ["russo", "Russo One", "'Russo One',sans-serif"], ["orbitron", "Orbitron", "'Orbitron',sans-serif"],
    ["audiowide", "Audiowide", "'Audiowide',sans-serif"], ["righteous", "Righteous", "'Righteous',sans-serif"], ["bangers", "Bangers", "'Bangers',cursive"],
    ["marker", "Permanent Marker", "'Permanent Marker',cursive"], ["pacifico", "Pacifico", "'Pacifico',cursive"], ["lobster", "Lobster", "'Lobster',cursive"],
    ["greatvibes", "Great Vibes", "'Great Vibes',cursive"], ["playfair", "Playfair Display", "'Playfair Display',serif"], ["abril", "Abril Fatface", "'Abril Fatface',serif"],
  ];
  const STYLES = [
    ["plain", "Senp"], ["classic", "Klasik (lonbraj)"], ["outline", "Kontou"], ["glow", "Limyè (glow)"], ["neon", "Neyon"], ["box", "Nan yon bwat"],
    ["gradient", "Degrade"], ["metal", "Metal"], ["fire", "Dife"], ["retro", "Retro (lonbraj plen)"], ["3d", "3D"],
  ];
  const FONT_CSS = Object.fromEntries(FONTS.map(([k, , css]) => [k, css]));
  /** CSS pou yon tèks ak estil li. spec: { style, font, color, color2, italic, caps } */
  function textCss(spec, px) {
    const b = spec || {}, c = b.color || "#FFFFFF", c2 = b.color2 || "#1D4ED8", st = b.style || "classic";
    const heavy = ["sans", "condensed", "serif", "mono", "oswald", "playfair"].includes(b.font || "sans");
    let css = `font-family:${FONT_CSS[b.font] || FONT_CSS.sans};font-weight:${b.font === "black" ? 900 : heavy ? 800 : 400};` +
      `font-style:${b.italic ? "italic" : "normal"};${b.caps === true ? "text-transform:uppercase;" : b.caps === false ? "text-transform:none;" : ""}color:${c};`;
    if (px) css += `font-size:${px}px;`;
    const k = Math.max(1, Math.round((px || 20) / 14));
    if (st === "plain") css += "text-shadow:none;";
    else if (st === "outline") css += `text-shadow:${[[-k, -k], [k, -k], [-k, k], [k, k], [0, -k], [0, k], [-k, 0], [k, 0]].map(([x, y]) => `${x}px ${y}px 0 ${c2}`).join(",")};`;
    else if (st === "glow") css += `text-shadow:0 0 ${k * 3}px ${c2},0 0 ${k * 6}px ${c2},0 0 ${k * 10}px ${c2};`;
    else if (st === "neon") css += `text-shadow:0 0 ${k}px #fff,0 0 ${k * 3}px ${c2},0 0 ${k * 6}px ${c2},0 0 ${k * 12}px ${c2};`;
    else if (st === "box") css += `background:${c2};padding:${Math.round((px || 20) * 0.18)}px ${Math.round((px || 20) * 0.45)}px;border-radius:${Math.round((px || 20) * 0.25)}px;text-shadow:none;`;
    else if (st === "gradient") css += `background:linear-gradient(180deg,${c},${c2});-webkit-background-clip:text;background-clip:text;color:transparent;text-shadow:none;filter:drop-shadow(0 2px 3px rgba(0,0,0,.6));`;
    else if (st === "metal") css += `background:linear-gradient(180deg,#fff 0%,${c} 35%,${c2} 55%,${c} 80%,#fff 100%);-webkit-background-clip:text;background-clip:text;color:transparent;text-shadow:none;filter:drop-shadow(0 2px 2px rgba(0,0,0,.7));`;
    else if (st === "fire") css += `background:linear-gradient(180deg,#FFF59D 0%,#FFB300 45%,#E53935 100%);-webkit-background-clip:text;background-clip:text;color:transparent;text-shadow:none;filter:drop-shadow(0 0 ${k * 3}px #FF6D00);`;
    else if (st === "retro") css += `text-shadow:${k * 2}px ${k * 2}px 0 ${c2};`;
    else if (st === "3d") css += `text-shadow:${Array.from({ length: k * 3 }, (_, i) => `${i + 1}px ${i + 1}px 0 ${c2}`).join(",")},${k * 3 + 2}px ${k * 3 + 3}px ${k * 2}px rgba(0,0,0,.6);`;
    else css += "text-shadow:0 2px 6px rgba(0,0,0,.7);";
    return css;
  }
  const bugTextCss = (b, px) => textCss({ ...b, caps: b.caps !== false }, px);
  function bugHtml(b) {
    const op = (b.opacity ?? 85) / 100, h = b.size || 44;
    return `<div class="gx-bug" style="opacity:${op}">${b.imageUrl ? `<img src="${esc(b.imageUrl)}" style="height:${h}px" alt="">` : `<b style="${bugTextCss(b, Math.round(h * 0.6))}">${esc(b.text)}</b>`}</div>`;
  }
  function sbHtml(s) {
    return `<div class="gx-sb">${s.league ? `<div class="gx-sb-l">${esc(s.league)}</div>` : ""}<div class="gx-sb-r">
      <span class="gx-sb-t" style="background:${esc(s.homeColor)}">${esc(s.home)}</span><span class="gx-sb-n">${esc(s.homeScore)}</span><span class="gx-sb-n">${esc(s.awayScore)}</span><span class="gx-sb-t" style="background:${esc(s.awayColor)}">${esc(s.away)}</span>${s.clock ? `<span class="gx-sb-c">${esc(s.clock)}</span>` : ""}</div></div>`;
  }
  const wxHtml = (w) => `<div class="gx-wx"><i>${esc(w.icon)}</i><b>${esc(w.temp)}</b><span>${esc(w.city)}</span></div>`;
  const cdHtml = (c, now) => `<div class="gx-cd"${c.box === false ? ' style="background:none;box-shadow:none"' : ""}>${c.title ? `<small style="${textCss({ ...c, caps: true, style: c.style === "box" ? "classic" : c.style }, 11)}">${esc(c.title)}</small>` : ""}<b style="${textCss({ ...c, caps: false }, 26)};font-variant-numeric:tabular-nums" data-gxcd="${c.targetAt}" data-gxdone="${esc(c.doneText || "")}">${esc(cdText(c, now))}</b></div>`;

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
      h += `<div class="gx-wm ${esc(w.position)}" data-gxwm="${w.move ? 1 : 0}" style="${textCss({ style: "plain", ...w, caps: false }, w.size || 16)}opacity:${(w.opacity ?? 25) / 100}">${esc(w.text)}</div>`; }
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

  /** Snipe: imaj anime anba ekran an (glise antre, rete, soti). [bottom] = espas anba (ticker). */
  function snipeHtml(n, bottom, idx) {
    const side = n.position === "bottom-left" ? "left:48px" : n.position === "bottom-center" ? "left:50%;margin-left:0;transform:translateX(-50%)" : "right:48px";
    const imgs = n.images && n.images.length ? n.images : n.imageUrl ? [n.imageUrl] : [];
    const img = imgs.length ? imgs[(idx || 0) % imgs.length] : "";
    const fs = Math.max(16, Math.min(46, Math.round((n.size || 120) / 4.2)));
    const txt = n.text ? `<span class="gx-sn-txt" style="font-size:${fs}px;color:${esc(n.textColor || "#fff")};background:${esc(n.bgColor || "#0B1630")}">${esc(n.text)}</span>` : "";
    return `<div class="gx-snipe gx-sn-${esc(n.anim || "slide")} gx-sn-${esc(n.position || "bottom-right")}" style="bottom:${(bottom || 60) + 16}px;${side};--gx-dur:${n.durationSec || 8}s"><div class="gx-sn-in">${img ? `<img src="${esc(img)}" alt="" style="height:${n.size || 120}px">` : ""}${txt}</div></div>`;
  }

  /** Bann meteyo (tankou BFM): etikèt + premye paj vil yo. */
  function weatherBarHtml(w, bottom) {
    const list = (w.items || []).slice(0, w.perPage || 3);
    const unit = w.unit === "C" ? "C" : "F";
    const cell = (x) => `<div class="gx-wb-c"><span class="gx-wb-i">${esc(x.icon || "")}</span><div><b>${esc(x.city)}</b><div class="gx-wb-t"><i class="lo">${x.min == null ? "–" : esc(x.min) + "°"}</i><i class="hi">${x.max == null ? "–" : esc(x.max) + "°"}</i>${w.showNow !== false && x.now != null ? `<small>${esc(x.now)}°${unit}</small>` : ""}</div></div></div>`;
    const off = Number(w.offset) || 0;
    const tag = `<span class="gx-wb-tag"><b>${esc(String(w.title || "METEYO").toUpperCase())}</b>${w.subtitle !== "" ? `<i style="background:${esc(w.accentColor || "#F59E0B")}">${esc(String(w.subtitle ?? "JODI A").toUpperCase())}</i>` : ""}</span>`;
    return `<div class="gx-wb" style="${w.position === "top" ? `top:${Math.max(0, off)}px` : `bottom:${Math.max(0, (bottom || 60) + off)}px`};background:${esc(w.bgColor || "#1D4ED8")};color:${esc(w.textColor || "#fff")}">${tag}${list.map((x, i) => (i ? `<span class="gx-wb-sep" style="background:${esc(w.accentColor || "#F59E0B")}"></span>` : "") + cell(x)).join("")}</div>`;
  }

  window.GalaxyGfx = { html, tick, identHtml, bumperHtml, snipeHtml, weatherBarHtml, fmtLeft, POS6, textCss, FONTS, STYLES };
})();
