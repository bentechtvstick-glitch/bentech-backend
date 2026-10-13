/* Galaxy TV Stick — animasyon imoji yo (menm règ ak app TV a: ui/EmojiFx.kt)
 *  wave  : drapo yo flote          drive : machin, avyon, bato kouri
 *  spin  : balon yo vire           pulse : kè, 🔥, ⭐, 🏆 bat
 *  blink : 🔴 🚨 ⚡ kliyote          shake : 📢 🔔 📞 souke
 *  bounce: moun, bèt k ap kouri sote
 */
(() => {
  const GROUPS = {
    drive: "🏎🚗🚕🚙🚓🚑🚒🚐🛻🚚🚛🚜🏍🛵🚲🛴🚌🚎🚍🚘🚖🚔🚂🚆🚄🚅🚈✈🛩🚀🛸🚁⛵🚤🛥🚢🛶🛺🚋",
    bounce: "🐎🏇🏃🐕🐆🐇🦘⛹🤸💃🕺🐸🦅🐦🐬🐒🦓🦒🐘👟🤾🏄🚴",
    spin: "⚽🏀🏈⚾🎾🏐🏉🥎🎱🌀🎡💿📀🪀⚙🌍🌎🌏🪙🥏🎯",
    pulse: "❤🧡💛💚💙💜🖤🤍🤎💖💗💓💕💞❣🔥⭐🌟✨💥💯🎉🎊🏆🥇💰💎👑🎁💝",
    blink: "🔴🟥🚨🆕⚡❗‼⚠🟢🟡🔵🆓🆙📍⭕",
    shake: "📢📣📞☎📱⏰🔊🔔👋🙌👏🥁🎺📯🎤📺",
  };
  const MAP = new Map();
  for (const [fx, chars] of Object.entries(GROUPS)) for (const ch of chars) MAP.set(ch, fx);

  // Yon "grap" imoji: drapo (2 lèt rejyonal) oswa imoji ak ZWJ / koulè po
  const RE = /\p{Regional_Indicator}{2}|\p{Extended_Pictographic}️?(?:[\u{1F3FB}-\u{1F3FF}])?(?:‍\p{Extended_Pictographic}️?(?:[\u{1F3FB}-\u{1F3FF}])?)*(?:[\u{E0020}-\u{E007F}]+)?/gu;

  function fxOf(cluster) {
    const cps = [...cluster];
    const first = cps[0] || "";
    const cp = first.codePointAt(0) || 0;
    if (cp >= 0x1f1e6 && cp <= 0x1f1ff) return "wave";
    if ("🏴🏳🏁🚩🎌".includes(first)) return "wave";
    return MAP.get(first) || "";
  }

  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  /** HTML tèks la, ak imoji anime yo nan <span class="fx fx-…">. */
  function html(text, on = true) {
    const s = String(text ?? "");
    if (!on) return esc(s);
    let out = "", last = 0;
    for (const m of s.matchAll(RE)) {
      const f = fxOf(m[0]);
      if (!f) continue;
      out += esc(s.slice(last, m.index)) + `<span class="fx fx-${f}">${esc(m[0])}</span>`;
      last = m.index + m[0].length;
    }
    return out + esc(s.slice(last));
  }

  /** Premye efè ki nan yon tèks (pou "runner" oswa sticker la). */
  function first(text) {
    for (const m of String(text ?? "").matchAll(RE)) { const f = fxOf(m[0]); if (f) return f; }
    return "bounce";
  }

  // Animasyon CSS yo (panel preview + demo TV)
  const css = `
.fx{display:inline-block;will-change:transform}
.fx-wave{transform-origin:12% 88%;animation:gfxWave 1.5s ease-in-out infinite}
.fx-drive{animation:gfxDrive .48s ease-in-out infinite}
.fx-bounce{animation:gfxBounce .55s ease-in-out infinite}
.fx-spin{animation:gfxSpin 1.3s linear infinite}
.fx-pulse{animation:gfxPulse 1s ease-in-out infinite}
.fx-blink{animation:gfxBlink 1s ease-in-out infinite}
.fx-shake{transform-origin:50% 90%;animation:gfxShake 1.2s ease-in-out infinite}
@keyframes gfxWave{0%,100%{transform:rotate(-6deg) skewY(5deg) scaleX(1)}50%{transform:rotate(5deg) skewY(-6deg) scaleX(.9)}}
@keyframes gfxDrive{0%,100%{transform:translate(0,0) rotate(0)}25%{transform:translate(1px,-2px) rotate(-3deg)}50%{transform:translate(2px,0) rotate(0)}75%{transform:translate(1px,-1px) rotate(2deg)}}
@keyframes gfxBounce{0%,100%{transform:translateY(0)}50%{transform:translateY(-18%)}}
@keyframes gfxSpin{to{transform:rotate(360deg)}}
@keyframes gfxPulse{0%,100%{transform:scale(1)}50%{transform:scale(1.22)}}
@keyframes gfxBlink{0%,100%{opacity:1}50%{opacity:.25}}
@keyframes gfxShake{0%,50%,100%{transform:rotate(0)}10%,30%{transform:rotate(-14deg)}20%,40%{transform:rotate(14deg)}}
.gfx-runner{position:absolute;top:50%;left:0;pointer-events:none;z-index:2;white-space:nowrap;will-change:transform}
.gfx-runner>.flip{display:inline-block;transform:scaleX(-1)}
@keyframes gfxRunL{from{transform:translate(var(--from),-50%)}to{transform:translate(var(--to),-50%)}}
.gfx-car{display:block;height:1.15em;width:auto;overflow:visible}
.gfx-car .cb{animation:gfxCarBob .3s ease-in-out infinite}
.gfx-car .cw{transform-box:fill-box;transform-origin:center;animation:gfxCarWheel .4s linear infinite}
.gfx-car .cs line{animation:gfxCarSpeed .33s linear infinite}.gfx-car .cs line:nth-child(2){animation-delay:-.11s}.gfx-car .cs line:nth-child(3){animation-delay:-.22s}
@keyframes gfxCarBob{0%,100%{transform:translateY(0)}50%{transform:translateY(-.7px)}}
@keyframes gfxCarWheel{to{transform:rotate(-360deg)}}
@keyframes gfxCarSpeed{from{transform:translateX(0);opacity:.8}to{transform:translateX(6px);opacity:0}}
@media (prefers-reduced-motion:reduce){.fx{animation:none!important}}`;
  if (typeof document !== "undefined" && !document.getElementById("gfx-css")) {
    const st = document.createElement("style"); st.id = "gfx-css"; st.textContent = css; document.head.appendChild(st);
  }

  /** Machin desine (pa yon imoji): "car:E50914" → koulè a. Menm desen ak app TV a (ui/CarFx.kt). */
  const carColor = (s) => { const m = /^car:#?([0-9a-f]{6})$/i.exec(String(s || "").trim()); return m ? "#" + m[1] : null; };
  function carSvg(color) {
    const wheel = (cx) => `<g class="cw"><circle cx="${cx}" cy="31" r="8" fill="#111"/><circle cx="${cx}" cy="31" r="4.6" fill="#C9CED6"/>`
      + [0, 60, 120].map((a) => `<line x1="${cx - 4.4}" y1="31" x2="${cx + 4.4}" y2="31" stroke="#555" stroke-width="1.4" stroke-linecap="round" transform="rotate(${a} ${cx} 31)"/>`).join("")
      + `<circle cx="${cx}" cy="31" r="1.5" fill="#555"/></g>`;
    return `<svg class="gfx-car" viewBox="0 0 122 44" aria-hidden="true"><ellipse cx="56" cy="41" rx="46" ry="2.5" fill="rgba(0,0,0,.35)"/>`
      + `<g class="cs" stroke="#fff" stroke-width="1.6" stroke-linecap="round"><line x1="109" y1="12" x2="116" y2="12"/><line x1="109" y1="20" x2="115" y2="20"/><line x1="109" y1="27" x2="114" y2="27"/></g>`
      + `<g class="cb"><path fill="${color}" d="M8 31C4 31 3 28 3.5 25L5 21C6 18.5 9 17.5 13 17L30 15L40 6C42 4.5 44 4 47 4L72 4C75 4 77 5 79 7L88 15L101 16.5C105 17 107 19 107 23L107 28C107 30 106 31 103 31Z"/>`
      + `<path fill="#BFE6FF" opacity=".9" d="M33 15L42 7.5L55 7.5L55 15ZM59 15L59 7.5L72 7.5C74 7.5 75 8 76 9L82 15Z"/>`
      + `<rect x="3.5" y="21" width="5" height="3" rx="1" fill="#FFE066"/><rect x="104" y="19" width="3" height="4" fill="#FF3B3B"/>`
      + `<path d="M8 22L104 22" stroke="rgba(255,255,255,.25)"/><path d="M57 16L57 29" stroke="rgba(0,0,0,.25)"/></g>`
      + wheel(26) + wheel(86) + `</svg>`;
  }
  const CAR_COLORS = [["Wouj", "E50914"], ["Ble", "1565C0"], ["Jòn", "FFC107"], ["Vèt", "00C853"], ["Nwa", "222222"], ["Blan", "F5F5F5"], ["Mov", "7C4DFF"]];

  /** Mete yon "runner" (egz: 🏎️💨) k ap travèse ba a. dir = "left" (dwat → gòch) oswa "right". */
  function runner(box, r, dir, pxPerSec) {
    box.querySelector(".gfx-runner")?.remove();
    if (!r || !r.emoji) return;
    const el = document.createElement("div");
    el.className = "gfx-runner";
    const toRight = dir === "right";
    // Imoji machin yo gade agoch: nou vire yo lè yo kouri adwat (flip envèse sa)
    const flip = toRight !== !!r.flip;
    const car = carColor(r.emoji);
    el.innerHTML = car ? `<span class="${flip ? "flip" : ""}">${carSvg(car)}</span>`
      : `<span class="${flip ? "flip" : ""}"><span class="fx fx-${first(r.emoji)}">${esc(r.emoji)}</span></span>`;
    box.appendChild(el);
    const W = box.clientWidth, w = el.offsetWidth || 40;
    el.style.setProperty("--from", (toRight ? -w : W) + "px");
    el.style.setProperty("--to", (toRight ? W : -w) + "px");
    el.style.animation = `gfxRunL ${Math.max(1.5, (W + w) / pxPerSec)}s linear infinite`;
  }

  window.GalaxyFx = { fxOf, html, first, runner, GROUPS, carColor, carSvg, CAR_COLORS };
})();
