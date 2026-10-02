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
@media (prefers-reduced-motion:reduce){.fx{animation:none!important}}`;
  if (typeof document !== "undefined" && !document.getElementById("gfx-css")) {
    const st = document.createElement("style"); st.id = "gfx-css"; st.textContent = css; document.head.appendChild(st);
  }

  /** Mete yon "runner" (egz: 🏎️💨) k ap travèse ba a. dir = "left" (dwat → gòch) oswa "right". */
  function runner(box, r, dir, pxPerSec) {
    box.querySelector(".gfx-runner")?.remove();
    if (!r || !r.emoji) return;
    const el = document.createElement("div");
    el.className = "gfx-runner";
    const toRight = dir === "right";
    // Imoji machin yo gade agoch: nou vire yo lè yo kouri adwat (flip envèse sa)
    const flip = toRight !== !!r.flip;
    el.innerHTML = `<span class="${flip ? "flip" : ""}"><span class="fx fx-${first(r.emoji)}">${esc(r.emoji)}</span></span>`;
    box.appendChild(el);
    const W = box.clientWidth, w = el.offsetWidth || 40;
    el.style.setProperty("--from", (toRight ? -w : W) + "px");
    el.style.setProperty("--to", (toRight ? W : -w) + "px");
    el.style.animation = `gfxRunL ${Math.max(1.5, (W + w) / pxPerSec)}s linear infinite`;
  }

  window.GalaxyFx = { fxOf, html, first, runner, GROUPS };
})();
