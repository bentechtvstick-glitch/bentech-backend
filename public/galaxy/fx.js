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
.gfx-car .vb{animation:gfxVB .5s steps(1) infinite}.gfx-car .vb1{animation-delay:-.25s}
@keyframes gfxVB{0%{fill:#E53935}50%{fill:#1E88E5}}
@keyframes gfxCarWheel{to{transform:rotate(-360deg)}}
@keyframes gfxCarSpeed{from{transform:translateX(0);opacity:.8}to{transform:translateX(6px);opacity:0}}
@media (prefers-reduced-motion:reduce){.fx{animation:none!important}}`;
  if (typeof document !== "undefined" && !document.getElementById("gfx-css")) {
    const st = document.createElement("style"); st.id = "gfx-css"; st.textContent = css; document.head.appendChild(st);
  }

  /** Machin desine (pa yon imoji): "car:E50914", "bus:FFC107"… → kalite + koulè. Menm desen ak app TV a (ui/CarFx.kt, menm SPEC). */
  const VEH = {"car":{"w":108,"wheels":[26,86],"parts":[["B",1,"M8 31C4 31 3 28 3.5 25L5 21C6 18.5 9 17.5 13 17L30 15L40 6C42 4.5 44 4 47 4L72 4C75 4 77 5 79 7L88 15L101 16.5C105 17 107 19 107 23L107 28C107 30 106 31 103 31Z"],["#BFE6FF",0.9,"M33 15L42 7.5L55 7.5L55 15ZM59 15L59 7.5L72 7.5C74 7.5 75 8 76 9L82 15Z"],["#FFE066",1,"M4.5 21H8.5V24H3.5Z"],["#FF3B3B",1,"M104 19H107V23H104Z"],["#FFFFFF",0.25,"M8 21.5H104V22.5H8Z"],["#000000",0.25,"M56.5 16H57.5V29H56.5Z"]]},"race":{"w":111,"wheels":[26,88],"parts":[["B",1,"M6 31C3 31 2 29 2.5 27L4 24C5 22 8 21 12 20.5L34 18L46 11C48 10 50 9.5 53 9.5L70 9.5C74 9.5 77 11 80 13L92 18L104 17L107 14L111 14L111 28C111 30 110 31 107 31Z"],["#BFE6FF",0.9,"M40 18L49 12.5L60 12.5L60 18ZM63 18L63 12.5L70 12.5C73 12.5 75 13.5 77 15L80 18Z"],["#FFFFFF",0.85,"M5 24H108V26H5Z"],["#FFE066",1,"M3.5 24.5H8.5V27H3.5Z"],["#FF3B3B",1,"M107 19H111V23H107Z"]]},"bus":{"w":141,"wheels":[28,116],"parts":[["B",1,"M6 31C3 31 2 29 2 27L2 6C2 3 4 1 7 1L136 1C139 1 140 3 140 6L140 28C140 30 139 31 137 31Z"],["#BFE6FF",0.9,"M4 4H15V17H4ZM32 4H45V15H32ZM47 4H60V15H47ZM62 4H75V15H62ZM77 4H90V15H77ZM92 4H105V15H92ZM107 4H120V15H107ZM122 4H135V15H122Z"],["#000000",0.28,"M18 4H28V29H18Z"],["#FFFFFF",0.6,"M2 19H140V21.5H2Z"],["#FFE066",1,"M2 23H6V26H2Z"],["#FF3B3B",1,"M137 20H140V25H137Z"]]},"truck":{"w":140,"wheels":[20,100,122],"parts":[["#ECEFF1",1,"M35 2L136 2C138 2 139 3 139 5L139 29C139 30 138 31 137 31L35 31Z"],["B",1,"M7 31C4 31 3 29 3 27L3 18C3 16 4 14 6 13L10 7C11 5 13 4 16 4L30 4C32 4 33 5 33 7L33 31Z"],["B",1,"M35 22H139V25H35Z"],["#BFE6FF",0.9,"M8 14L12 7.5L28 7.5L28 14Z"],["#FFE066",1,"M3 21H7V24H3Z"],["#FF3B3B",1,"M136 24H139V29H136Z"]]},"police":{"w":108,"wheels":[26,86],"parts":[["B",1,"M8 31C4 31 3 28 3.5 25L5 21C6 18.5 9 17.5 13 17L30 15L40 6C42 4.5 44 4 47 4L72 4C75 4 77 5 79 7L88 15L101 16.5C105 17 107 19 107 23L107 28C107 30 106 31 103 31Z"],["#FFFFFF",0.95,"M30 21H84V30H30Z"],["#BFE6FF",0.9,"M33 15L42 7.5L55 7.5L55 15ZM59 15L59 7.5L72 7.5C74 7.5 75 8 76 9L82 15Z"],["#111111",1,"M48 3H64V4.5H48Z"],["#FFE066",1,"M4.5 21H8.5V24H3.5Z"],["#FF3B3B",1,"M104 19H107V23H104Z"],["#FFFFFF",0.25,"M8 21.5H104V22.5H8Z"],["#000000",0.25,"M56.5 16H57.5V29H56.5Z"]],"blink":["M49 0H56V3.5H49Z","M56 0H63V3.5H56Z"]},"ambulance":{"w":108,"wheels":[24,88],"parts":[["B",1,"M7 31C4 31 3 29 3 27L3 19C3 17 4 15 6 14L14 6C15 4.5 17 4 20 4L104 4C106 4 107 5 107 7L107 28C107 30 106 31 104 31Z"],["#BFE6FF",0.9,"M8 15L15 8L28 8L28 15Z"],["#E53935",1,"M3 19H107V22H3Z"],["#E53935",1,"M62 7h5v4h4v5h-4v4h-5v-4h-4v-5h4Z"],["#FFE066",1,"M3 23H7V26H3Z"],["#FF3B3B",1,"M104 20H107V25H104Z"]],"blink":["M28 0.5H34V4H28Z","M34 0.5H40V4H34Z"]}};
  const VEH_DEF = {"car": "E50914", "race": "FFC107", "bus": "FFC107", "truck": "1565C0", "police": "222222", "ambulance": "F5F5F5"};
  const vehOf = (s) => { const m = /^(car|race|bus|truck|police|ambulance):#?([0-9a-f]{6})$/i.exec(String(s || "").trim()); return m ? { type: m[1].toLowerCase(), color: "#" + m[2] } : null; };
  const carColor = (s) => vehOf(s)?.color || null;
  function vehSvg(type, color) {
    const v = VEH[type] || VEH.car, w = v.w, cy = 31;
    const wheel = (cx) => `<g class="cw"><circle cx="${cx}" cy="${cy}" r="8" fill="#111"/><circle cx="${cx}" cy="${cy}" r="4.6" fill="#C9CED6"/>`
      + [0, 60, 120].map((a) => `<line x1="${cx - 4.4}" y1="${cy}" x2="${cx + 4.4}" y2="${cy}" stroke="#555" stroke-width="1.4" stroke-linecap="round" transform="rotate(${a} ${cx} ${cy})"/>`).join("")
      + `<circle cx="${cx}" cy="${cy}" r="1.5" fill="#555"/></g>`;
    const parts = v.parts.map(([f, o, d]) => `<path fill="${f === "B" ? color : f}"${o < 1 ? ` opacity="${o}"` : ""} d="${d}"/>`).join("")
      + (v.blink || []).map((d, i) => `<path class="vb vb${i}" d="${d}"/>`).join("");
    return `<svg class="gfx-car" viewBox="0 0 ${w + 14} 44" aria-hidden="true"><ellipse cx="${w / 2}" cy="41" rx="${w / 2 - 6}" ry="2.5" fill="rgba(0,0,0,.35)"/>`
      + `<g class="cs" stroke="#fff" stroke-width="1.6" stroke-linecap="round"><line x1="${w + 2}" y1="12" x2="${w + 9}" y2="12"/><line x1="${w + 2}" y1="20" x2="${w + 8}" y2="20"/><line x1="${w + 2}" y1="27" x2="${w + 7}" y2="27"/></g>`
      + `<g class="cb">${parts}</g>` + v.wheels.map(wheel).join("") + `</svg>`;
  }
  const carSvg = (color) => vehSvg("car", color);
  const CAR_COLORS = [["Wouj", "E50914"], ["Ble", "1565C0"], ["Jòn", "FFC107"], ["Vèt", "00C853"], ["Nwa", "222222"], ["Blan", "F5F5F5"], ["Mov", "7C4DFF"]];
  const VEH_NAMES = { car: "Machin", race: "Machin kous", bus: "Otobis", truck: "Kamyon", police: "Lapolis", ambulance: "Anbilans" };

  /** Mete yon "runner" (egz: 🏎️💨) k ap travèse ba a. dir = "left" (dwat → gòch) oswa "right". */
  function runner(box, r, dir, pxPerSec) {
    box.querySelector(".gfx-runner")?.remove();
    if (!r || !r.emoji) return;
    const el = document.createElement("div");
    el.className = "gfx-runner";
    const toRight = dir === "right";
    // Imoji machin yo gade agoch: nou vire yo lè yo kouri adwat (flip envèse sa)
    const flip = toRight !== !!r.flip;
    const car = vehOf(r.emoji);
    el.innerHTML = car ? `<span class="${flip ? "flip" : ""}">${vehSvg(car.type, car.color)}</span>`
      : `<span class="${flip ? "flip" : ""}"><span class="fx fx-${first(r.emoji)}">${esc(r.emoji)}</span></span>`;
    box.appendChild(el);
    const W = box.clientWidth, w = el.offsetWidth || 40;
    el.style.setProperty("--from", (toRight ? -w : W) + "px");
    el.style.setProperty("--to", (toRight ? W : -w) + "px");
    el.style.animation = `gfxRunL ${Math.max(1.5, (W + w) / pxPerSec)}s linear infinite`;
  }

  window.GalaxyFx = { fxOf, html, first, runner, GROUPS, carColor, carSvg, vehOf, vehSvg, VEH_DEF, VEH_NAMES, CAR_COLORS };
})();
