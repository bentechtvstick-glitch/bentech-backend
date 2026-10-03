/* Galaxy TV Stick — "Customer TV" (TV tès) pou vre panel la.
 * Yon TV vityèl nan navigatè a ki konekte ak vre backend la egzakteman tankou app Fire Stick la:
 * li anrejistre ak yon MAC + Device Key, li li konfigirasyon an, li resevwa kòmand yo, li voye estati li.
 * Li montre sa panel la mete sou ekran an (ticker, chyron, grafik TV, piblisite, mesaj) — li pa jwe vre chanèl yo.
 */
(() => {
  const CSS = `
.ttv{position:fixed;right:18px;bottom:18px;z-index:40;width:min(460px,calc(100vw - 36px));background:var(--surface,#161e32);border:1px solid var(--line2,#2a3554);border-radius:14px;box-shadow:0 18px 48px rgba(0,0,0,.55);display:flex;flex-direction:column;overflow:hidden;color:var(--text,#fff)}
.ttv-h{display:flex;align-items:center;gap:8px;padding:9px 12px;border-bottom:1px solid var(--line,#222c48);font-size:14px}
.ttv-h b{white-space:nowrap}.ttv-h code{font-size:12px;color:#00E5FF;margin-right:auto;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ttv-h button{width:30px;height:30px;border-radius:8px;border:1px solid var(--line2,#2a3554);background:var(--field,#0f1626);color:inherit;cursor:pointer;font-size:14px;flex:none}
.ttv-h button:hover{border-color:#00E5FF}
.ttv-screen{position:relative;width:100%;aspect-ratio:16/9;overflow:hidden;background:#000}
.ttv.min .ttv-screen,.ttv.min .ttv-f{display:none}
.ttv-stage{position:absolute;left:0;top:0;width:960px;height:540px;transform-origin:0 0;overflow:hidden;color:#fff;font-family:inherit;line-height:1.35;user-select:none}
.ttv-stage>div{position:absolute;inset:0}
.ttv-bg{background:radial-gradient(ellipse at 70% 30%,rgba(255,190,110,.35),transparent 55%),linear-gradient(160deg,#16324f 0%,#3b2a5a 45%,#8a4a2c 80%,#c98a3a 100%);display:flex;align-items:center;justify-content:center;flex-direction:column;gap:8px}
.ttv-bg b{font-size:54px;font-weight:800;color:rgba(255,255,255,.14)}.ttv-bg span{font-size:20px;color:rgba(255,255,255,.3)}
.ttv-bg img{max-width:260px;max-height:150px;object-fit:contain;margin-bottom:6px}
.ttv-info{inset:auto!important;left:24px!important;top:24px!important;background:rgba(0,0,0,.78);border-radius:14px;padding:14px 18px;display:flex;gap:16px;align-items:center;max-width:620px}
.ttv-info img{width:84px;height:56px;object-fit:contain}.ttv-info .n{font-size:32px;font-weight:700;color:#00E5FF}.ttv-info .t{font-size:22px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:400px}.ttv-info .c{font-size:15px;color:#9AA3C7}
.ttv-list{background:rgba(8,12,26,.94);display:flex;gap:10px;padding:18px;pointer-events:auto}
.ttv-list .col{display:flex;flex-direction:column;gap:4px;overflow-y:auto;min-height:0}.ttv-list .cats{width:260px;flex:none}.ttv-list .chs{flex:1}
.ttv-list h4{margin:0 0 6px;font-size:14px;letter-spacing:.1em;color:#9AA3C7;text-transform:uppercase;flex:none}
.ttv-list button{flex:none;display:flex;align-items:center;gap:10px;text-align:left;border:0;border-radius:9px;background:#1B2240;color:#fff;font:inherit;font-size:16px;padding:0 12px;height:40px;cursor:pointer;white-space:nowrap;overflow:hidden}
.ttv-list button:hover{background:#2a3566}.ttv-list button.on{background:#3558f6}
.ttv-list button img{width:46px;height:30px;object-fit:contain;flex:none}.ttv-list button i{font-style:normal;color:#00E5FF;min-width:44px;font-variant-numeric:tabular-nums}
.ttv-list button span{overflow:hidden;text-overflow:ellipsis}.ttv-list .more{font-size:13px;color:#9AA3C7;padding:6px 2px;flex:none}
.ttv-screen{cursor:pointer}
.ttv-ov,.ttv-gfx,.ttv-cy{pointer-events:none}.ttv-gfx,.ttv-cy{position:absolute;inset:0}
.ttv [hidden]{display:none!important}
.ttv-tk{position:absolute;left:0;right:0;bottom:0}
.ttv-act{background:#F4F5F9;color:#1b2340;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;text-align:center}
.ttv-act img{height:150px;object-fit:contain}
.ttv-act .ids{display:flex;gap:16px}.ttv-act .id{background:#fff;border:1px solid #d9deea;border-radius:12px;padding:10px 22px;min-width:230px}
.ttv-act .id small{display:block;font-size:12px;letter-spacing:.1em;color:#7a849f}.ttv-act .id b{font-size:26px;font-family:ui-monospace,monospace;letter-spacing:.06em}
.ttv-act p{font-size:20px;margin:0;color:#4a5578}.ttv-act .big{font-size:26px;font-weight:800;color:#1b2340}
.ttv-block{background:#0B0E1A;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;text-align:center;padding:40px}
.ttv-block img{height:110px}.ttv-block h2{font-size:30px;margin:0}.ttv-block p{font-size:18px;color:#9AA3C7;margin:0;max-width:640px}
.ttv-ad{background:#000;display:flex;align-items:center;justify-content:center}
.ttv-ad video,.ttv-ad img.m{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;background:#000}
.ttv-dlg{background:rgba(0,0,0,.6);display:flex;align-items:center;justify-content:center;pointer-events:auto}
.ttv-dlg>div{background:#161e32;border:1px solid #2a3554;border-radius:16px;padding:26px 30px;max-width:620px;min-width:380px;display:flex;flex-direction:column;gap:12px}
.ttv-dlg h3{margin:0;font-size:26px}.ttv-dlg p{margin:0;font-size:20px;color:#d7dcf0;white-space:pre-wrap}
.ttv-dlg img{max-width:100%;max-height:220px;object-fit:contain}
.ttv-dlg button{align-self:flex-end;font-size:18px;font-weight:700;padding:8px 26px;border-radius:10px;border:0;background:#3558f6;color:#fff;cursor:pointer}
.ttv-toast{inset:auto!important;left:50%!important;top:26px!important;transform:translateX(-50%);background:rgba(0,0,0,.82);border-radius:12px;padding:10px 22px;font-size:20px;white-space:nowrap}
.ttv-f{display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:9px 12px;font-size:12.5px;color:var(--dim,#9aa3c7)}
.ttv-f span{flex:1;min-width:150px}.ttv-f button{flex:none}
#ttvBtn.on{border-color:#00E5FF;color:#00E5FF}
@media (max-width:560px){.ttv{right:8px;bottom:8px;width:calc(100vw - 16px)}}
`;
  const KEY = "galaxy_testtv";
  const load = () => { try { return JSON.parse(localStorage.getItem(KEY) || "{}"); } catch { return {}; } };
  const save = (s) => { try { localStorage.setItem(KEY, JSON.stringify(s)); } catch {} };
  const hexb = () => Math.floor(Math.random() * 256).toString(16).padStart(2, "0").toUpperCase();
  // #AARRGGBB (fòma app Android la) → koulè CSS
  const argb = (v, d) => (/^#[0-9a-f]{8}$/i.test(v || "") ? "#" + v.slice(3) + v.slice(1, 3) : v || d);
  const isVideo = (a) => a.type === "video" || /\.(mp4|m3u8|webm|mkv|mov)(\?|$)/i.test(a.url || "");
  const mmss = (n) => { n = Math.max(0, Math.ceil(n)); return Math.floor(n / 60) + ":" + String(n % 60).padStart(2, "0"); };

  function init(D) {
    const { API, t, esc } = D, G = window.GalaxyGfx, FX = window.GalaxyFx;
    const st = document.createElement("style"); st.textContent = CSS; document.head.appendChild(st);
    const S = load();
    if (!S.mac) { S.mac = "02:" + [0, 0, 0, 0, 0].map(hexb).join(":"); S.key = String(Math.floor(100000 + Math.random() * 900000)); save(S); }
    const T = { all: [], chs: [], cats: [], idx: 0, cat: "", listOpen: false, chKey: "", cfg: null, on: false, timers: [], abort: null, brk: null, seen: new Set(), cy: { i: 0, sig: "", timer: null }, lastIdent: 0, lastBreak: Date.now(), cursor: 0, lastMin: "", err: "" };

    const el = document.createElement("div"); el.className = "ttv"; el.hidden = true;
    el.innerHTML = `<div class="ttv-h"><b>📺 Customer TV</b><code></code><button type="button" data-a="min" aria-label="${esc(t("Redui"))}" title="${esc(t("Redui"))}">—</button><button type="button" data-a="off" aria-label="${esc(t("Etenn TV tès la"))}" title="${esc(t("Etenn TV tès la"))}">✕</button></div>
      <div class="ttv-screen"><div class="ttv-stage"><div class="ttv-bg"></div><div class="ttv-ov"><div class="ttv-gfx"></div><div class="ttv-cy"></div><div class="ttv-tk"></div></div><div class="ttv-info" hidden></div><div class="ttv-full" hidden></div><div class="ttv-list" hidden></div><div class="ttv-ad" hidden></div><div class="ttv-scr" hidden></div><div class="ttv-dlg" hidden></div><div class="ttv-toast" hidden></div></div></div>
      <div class="ttv-f"><span></span><button class="btn small" type="button" data-a="chdn" aria-label="CH−">CH−</button><button class="btn small" type="button" data-a="chup" aria-label="CH+">CH+</button><button class="btn small" type="button" data-a="list">☰</button><button class="btn small" type="button" data-a="dev"></button></div>`;
    document.body.appendChild(el);
    const $ = (s) => el.querySelector(s), stage = $(".ttv-stage");
    const scale = () => { const w = $(".ttv-screen").clientWidth; if (w) stage.style.transform = `scale(${w / 960})`; };
    if (window.ResizeObserver) new ResizeObserver(scale).observe($(".ttv-screen"));

    const dev = async (method, path, body, signal) => {
      const r = await fetch(`${API}/devices${path}`, { method, signal, headers: { "Content-Type": "application/json", "X-Device-Key": S.key }, body: body === undefined ? undefined : JSON.stringify(body) });
      const data = await r.json().catch(() => ({}));
      if (!r.ok) throw Object.assign(new Error(data.error || "HTTP " + r.status), { status: r.status });
      return data;
    };
    const M = encodeURIComponent(S.mac);
    const register = () => dev("POST", "/register", { mac: S.mac, deviceKey: S.key, model: "Customer TV (tès nan navigatè)", appVersion: "test" });
    const blocking = () => T.cfg && T.cfg.known && ["blocked", "expired", "maintenance"].includes(T.cfg.status);
    const playing = () => T.cfg && T.cfg.known && T.cfg.status === "active" && T.cfg.playlists.length && !blocking();
    const cur = () => T.chs[T.idx] || null;
    const report = () => dev("POST", `/${M}/status`, { screen: playing() ? "player" : "activation",
      channel: playing() ? (cur() ? { id: cur().id, num: cur().num, name: cur().name, category: cur().categoryName } : { id: 1, num: 1, name: "Customer TV (tès)", category: "Tès" }) : null,
      playlistName: T.cfg?.playlists?.[0]?.name || "", adBreak: !!T.brk, quality: "FHD", appVersion: "test" }).catch(() => {});

    function toast(msg) { const x = $(".ttv-toast"); x.textContent = msg; x.hidden = false; clearTimeout(T.toastT); T.toastT = setTimeout(() => (x.hidden = true), 3500); }
    function dialog(title, msg, img) { const d = $(".ttv-dlg");
      d.innerHTML = `<div><h3>${esc(title)}</h3>${img ? `<img src="${esc(img)}" alt="">` : ""}<p>${esc(msg || "")}</p><button type="button">OK</button></div>`; d.hidden = false;
      d.querySelector("button").onclick = () => { d.hidden = true; messages(); }; }

    // ------------------------------------------------------------ Sa ki sou ekran an
    function render() {
      $(".ttv-h code").textContent = S.mac;
      const scr = $(".ttv-scr"), c = T.cfg;
      if (T.err || !c || !playing()) {
        const blk = blocking();
        scr.className = "ttv-scr " + (blk ? "ttv-block" : "ttv-act"); scr.hidden = false;
        scr.innerHTML = blk
          ? `<img src="logo.png" alt=""><h2>${esc(t(c.status === "blocked" ? "Aparèy sa a bloke" : c.status === "expired" ? "Abònman an ekspire" : "Mentenans"))}</h2><p>${esc(c.status === "maintenance" && c.statusMessage ? c.statusMessage : t("Kontakte founisè ou a."))}</p><p>MAC: ${esc(S.mac)}</p>`
          : `<img src="logo.png" alt=""><div class="ids"><div class="id"><small>MAC ADDRESS</small><b>${esc(S.mac)}</b></div><div class="id"><small>DEVICE KEY</small><b>${esc(S.key)}</b></div></div>
             <p class="big">${esc(T.err || t("Ap tann founisè a aktive aparèy la…"))}</p><p>${esc(T.err ? "" : t("Ajoute yon playlist sou MAC sa a nan paj Aparèy la."))}</p>`;
        overlays(null); closeList(); $(".ttv-info").hidden = true; T.bgSig = null; $(".ttv-f span").textContent = T.err || t("TV tès la ap tann yon playlist.");
      } else {
        scr.hidden = true;
        const ch = cur(), bgSig = ch ? ch.id + "|" + ch.name : "none|" + (T.chErr || "");
        if (bgSig !== T.bgSig) { T.bgSig = bgSig; $(".ttv-bg").innerHTML = ch
          ? `${ch.icon ? `<img src="${esc(ch.icon)}" alt="" onerror="this.remove()">` : ""}<b>${esc(ch.name.slice(0, 26))}</b><span>${esc(ch.num + " · " + (ch.categoryName || c.playlists[0].name || ""))}</span>`
          : `<b>Customer TV</b><span>${esc(T.chErr || c.playlists[0].name || "")} · ${esc(t(T.chErr ? "chanèl tès" : "Ap chaje chanèl yo…"))}</span>`; }
        overlays(T.brk ? null : c);
        $(".ttv-f span").textContent = T.chs.length ? t("{0} chanèl · videyo a pa jwe nan TV tès la", T.chs.length) : t("Li montre sa panel la mete sou ekran an; li pa jwe vre chanèl yo.");
      }
      $('[data-a="dev"]').textContent = t("Louvri nan Aparèy");
    }

    function overlays(c) {
      // Ticker
      const tk = c && c.ticker, box = $(".ttv-tk"), sig = tk ? JSON.stringify(tk) : "";
      if (sig !== T.tkSig) { T.tkSig = sig; box.innerHTML = "";
        if (tk) { const fs = tk.textSize || 20, base = argb(tk.textColor, "#fff"), h = (x) => (FX ? FX.html(x, tk.animateEmoji !== false) : esc(x));
          const items = tk.items && tk.items.length ? tk.items : [{ text: tk.text, color: "" }];
          const one = items.map((x) => `<span style="color:${esc(x.color ? argb(x.color) : base)}">${h(x.text)}</span><span style="color:${esc(base)}">     ${h(tk.separator || "•")}     </span>`).join("");
          const bgA = /^#[0-9a-f]{8}$/i.test(tk.bgColor || "") ? parseInt(tk.bgColor.slice(1, 3), 16) : 255;
          box.innerHTML = `<div class="tkprev${tk.transparent || bgA < 110 ? " shadow" : ""}" style="background:${esc(argb(tk.bgColor, "rgba(124,77,255,.82)"))};font-size:${fs}px;height:${fs * 2}px">
            ${tk.label ? `<div class="lab" style="background:${esc(argb(tk.labelBg, "#E50914"))};color:${esc(argb(tk.labelColor, "#fff"))}">${h(tk.label)}</div>` : ""}
            <div class="run"><div class="track"><span>${one.repeat(3)}</span><span>${one.repeat(3)}</span></div></div>${tk.showClock ? `<div class="clk"${tk.transparent ? ' style="background:transparent"' : ""}></div>` : ""}</div>`;
          const tr = box.querySelector(".track"), w = tr.firstElementChild.offsetWidth;
          if (!w) T.tkSig = null; // dock la kache: rekalkile pita
          tr.style.animationDuration = Math.max(4, w / ((tk.speed || 5) * 24)) + "s"; tr.style.animationDirection = tk.direction === "right" ? "reverse" : "normal";
          if (FX && tk.runner) FX.runner(box.querySelector(".run"), tk.runner, tk.direction, (tk.runner.speed || 6) * 36); } }
      const clk = box.querySelector(".clk"); if (clk && tk) { const h12 = tk.clockFormat === "12"; clk.textContent = new Date().toLocaleTimeString(h12 ? "en-US" : [], { hour: h12 ? "numeric" : "2-digit", minute: "2-digit", hour12: h12 }); }
      const bottom = tk ? (tk.textSize || 20) * 2 + 20 : 30;
      // Grafik TV
      const g = (c && c.graphics) || {}, gs = JSON.stringify([g.bug, g.watermark, g.scoreboard, g.weather, g.countdown, bottom]);
      if (G && gs !== T.gfxSig) { T.gfxSig = gs; $(".ttv-gfx").innerHTML = G.html(g, { bottom }); }
      if (G) G.tick($(".ttv-gfx"));
      // Chyron: youn apre lòt
      const list = (c && c.chyrons) || [], cs = JSON.stringify(list);
      if (cs !== T.cy.sig) { T.cy.sig = cs; clearTimeout(T.cy.timer); $(".ttv-cy").innerHTML = ""; T.cy.i = 0; if (list.length) cyShow(list, bottom); }
      const cur = $(".ttv-cy .chy"); if (cur && cur.className.includes("pos-bottom")) cur.style.bottom = bottom + "px";
      if (!c) { $(".ttv-full").hidden = true; }
    }
    function cyShow(list, bottom) { const c = list[T.cy.i % list.length], box = $(".ttv-cy"); box.innerHTML = D.cyHtml(c, true);
      const e = box.firstElementChild; if (e && c.position.startsWith("bottom")) e.style.bottom = bottom + "px";
      const stay = c.duration > 0 ? c.duration * 1000 : list.length > 1 ? 15000 : 0;
      if (stay) T.cy.timer = setTimeout(() => { box.innerHTML = ""; T.cy.i = (T.cy.i + 1) % list.length; T.cy.timer = setTimeout(() => { if (T.cy.sig === JSON.stringify(list)) cyShow(list, bottom); }, c.duration > 0 ? 20000 : 400); }, stay); }

    // ------------------------------------------------------------ Chanèl playlist la (sèvè a chèche lis la pou nou)
    function applyFilters() {
      const c = T.cfg || {}, hid = new Set(c.hiddenChannels || []), hidC = new Set((c.hiddenCategories || []).map(String)), keep = cur()?.id;
      let list = T.all.filter((x) => !hid.has(x.id) && !hidC.has(x.categoryId));
      if (c.maxChannels > 0) list = list.slice(0, c.maxChannels);
      T.chs = list; const seen = new Map(); for (const x of list) if (!seen.has(x.categoryId)) seen.set(x.categoryId, x.categoryName || "—"); T.cats = [...seen].map(([id, name]) => ({ id, name }));
      const i = list.findIndex((x) => x.id === keep); T.idx = i >= 0 ? i : 0;
    }
    async function loadChannels() {
      const pl = T.cfg?.playlists?.[0]; if (!pl) return; const key = pl.id + "|" + pl.username + "|" + pl.server; if (key === T.chKey) return; T.chKey = key; T.all = []; T.chErr = ""; applyFilters();
      try { const r = await dev("GET", `/${M}/xtream/live`); if (T.chKey !== key) return; T.all = r.channels || []; applyFilters();
        dev("POST", `/${M}/channels`, { channels: T.all.map(({ id, num, name, categoryId, categoryName }) => ({ id, num, name, categoryId, categoryName })) }).catch(() => {});
        if (T.chs.length) play(T.idx, true); }
      catch (err) { if (T.chKey !== key) return; T.chErr = t(err.message); T.chKey = ""; setTimeout(() => { if (T.on && playing() && !T.all.length) loadChannels(); }, 60000); }
      render();
    }
    function play(i, quiet) {
      if (!T.chs.length || T.brk) return; T.idx = (i + T.chs.length) % T.chs.length; const ch = cur(); closeList(); render(); report();
      const info = $(".ttv-info"); info.innerHTML = `${ch.icon ? `<img src="${esc(ch.icon)}" alt="" onerror="this.remove()">` : ""}<div class="n">${ch.num}</div><div><div class="t">${esc(ch.name)}</div><div class="c">${esc(ch.categoryName || "")}</div></div>`;
      info.hidden = false; clearTimeout(T.infoT); T.infoT = setTimeout(() => (info.hidden = true), 4000);
      if (!quiet && T.cfg?.graphics?.ident?.onChannelChange) setTimeout(ident, 300);
    }
    function openList() {
      if (!playing() || T.brk || !T.chs.length) return; T.listOpen = true; T.cat = T.cat && T.cats.some((c) => c.id === T.cat) ? T.cat : cur()?.categoryId || T.cats[0]?.id || "";
      const box = $(".ttv-list"), rows = T.chs.filter((x) => x.categoryId === T.cat), shown = rows.slice(0, 400), id = cur()?.id;
      box.innerHTML = `<div class="col cats"><h4>${esc(t("Kategori"))}</h4>${T.cats.map((c) => `<button type="button" data-cat="${esc(c.id)}"${c.id === T.cat ? ' class="on"' : ""}><span>${esc(c.name)}</span></button>`).join("")}</div>
        <div class="col chs"><h4>${esc(t("{0} chanèl", rows.length))}</h4>${shown.map((x) => `<button type="button" data-ch="${x.id}"${x.id === id ? ' class="on"' : ""}><i>${x.num}</i>${x.icon ? `<img src="${esc(x.icon)}" alt="" loading="lazy" onerror="this.remove()">` : ""}<span>${esc(x.name)}</span></button>`).join("")}${rows.length > shown.length ? `<div class="more">${esc(t("{0} sou {1} afiche", shown.length, rows.length))}</div>` : ""}</div>`;
      box.hidden = false; box.querySelector(".chs .on")?.scrollIntoView({ block: "center" });
    }
    function closeList() { T.listOpen = false; $(".ttv-list").hidden = true; }
    $(".ttv-screen").addEventListener("click", (e) => {
      const cat = e.target.closest("[data-cat]"), ch = e.target.closest("[data-ch]");
      if (cat) { T.cat = cat.dataset.cat; return openList(); }
      if (ch) { const i = T.chs.findIndex((x) => String(x.id) === ch.dataset.ch); if (i >= 0) play(i); return; }
      if (e.target.closest(".ttv-dlg,.ttv-ad")) return;
      T.listOpen ? closeList() : openList();
    });

    function messages() {
      const c = T.cfg; if (!c || !playing() || T.brk || !$(".ttv-dlg").hidden) return;
      const b = c.broadcast; if (b && !T.seen.has("b:" + b.id)) { T.seen.add("b:" + b.id); return dialog((b.level === "urgent" ? "🔴 " : b.level === "warning" ? "⚠ " : "📢 ") + t("Mesaj"), b.message); }
      const p = (c.popups || []).find((x) => !T.seen.has("p:" + x.id)); if (p) { T.seen.add("p:" + p.id); dialog(p.title, p.message, p.imageUrl); }
    }

    // ------------------------------------------------------------ Ident + koupi piblisite
    function ident() { const i = T.cfg?.graphics?.ident; if (!G || !i || !playing() || T.brk) return; T.lastIdent = Date.now();
      const f = $(".ttv-full"); f.innerHTML = G.identHtml(i); f.hidden = false; clearTimeout(T.identT); T.identT = setTimeout(() => (f.hidden = true), i.durationSec * 1000); }
    function startBreak(ads, skip) {
      if (!playing() || T.brk || !ads || !ads.length) return;
      const bm = T.cfg.graphics?.bumper, bum = (id) => ({ id, type: "bumper", durationSec: bm.durationSec });
      const q = [...(bm && (bm.inUrl || bm.inText) ? [bum("bumper-in")] : []), ...ads, ...(bm && (bm.outUrl || bm.outText) ? [bum("bumper-out")] : [])];
      closeList(); $(".ttv-info").hidden = true;
      T.brk = { q, i: -1, el: 0, skip: +skip || 0, bumper: bm, last: Date.now() }; T.lastBreak = Date.now(); $(".ttv-full").hidden = true; overlays(null); nextSpot(); report();
    }
    function nextSpot() {
      const b = T.brk; if (!b) return; const prev = b.q[b.i];
      if (prev && prev.type !== "bumper") dev("POST", `/${M}/ad-events`, { adId: prev.id, event: "complete" }).catch(() => {});
      b.i++; const a = b.q[b.i], box = $(".ttv-ad");
      if (!a) { T.brk = null; T.lastBreak = Date.now(); box.hidden = true; box.innerHTML = ""; render(); report(); return; }
      b.spotEl = 0; b.video = null; box.hidden = false;
      if (a.type === "bumper") { box.innerHTML = G ? G.bumperHtml(b.bumper, a.id === "bumper-out" ? "out" : "in") : ""; return; }
      dev("POST", `/${M}/ad-events`, { adId: a.id, event: "start" }).catch(() => {});
      const spots = b.q.filter((x) => x.type !== "bumper");
      box.innerHTML = `${isVideo(a) ? `<video src="${esc(a.url)}" autoplay muted playsinline></video>` : `<img class="m" src="${esc(a.url)}" alt="">`}
        <div class="brpv-tag"><b>${esc(t("PIBLISITE"))}</b><span data-n="${spots.indexOf(a) + 1}/${spots.length}"></span></div><div class="brpv-bar"><i></i></div>`;
      const m = box.firstElementChild, fail = () => { if (T.brk !== b || b.q[b.i] !== a || b.failed === a) return; b.failed = a; b.video = null;
        m.outerHTML = `<div class="brpv-ph" style="background:linear-gradient(135deg,#2a1552,#0b3b4a)"><div class="big">${isVideo(a) ? "▶" : "🖼"}</div><div class="n">${esc(a.name || (a.url || "").split("/").pop())}</div></div>`; };
      m.addEventListener("error", fail);
      if (isVideo(a)) { b.video = m; m.addEventListener("ended", () => { if (T.brk === b && b.q[b.i] === a) nextSpot(); }); const pr = m.play(); if (pr && pr.catch) pr.catch(fail); }
    }
    function breakTick() {
      const b = T.brk; if (!b) return; const now = Date.now(), dt = (now - b.last) / 1000; b.last = now; b.el += dt; b.spotEl += dt;
      const a = b.q[b.i]; if (!a) return;
      const v = b.video, known = v && isFinite(v.duration) && v.duration > 0, dur = known ? v.duration : Math.max(a.type === "bumper" ? 2 : 3, +a.durationSec || 10), pos = known ? v.currentTime : b.spotEl;
      if (!v && b.spotEl >= dur) return nextSpot();
      const tag = el.querySelector(".ttv-ad [data-n]"), bar = el.querySelector(".ttv-ad .brpv-bar i");
      if (tag) tag.textContent = `${tag.dataset.n}   ·   ${mmss(dur - pos)}` + (b.skip > 0 ? "\n" + (b.skip - b.el > 0 ? t("Ou ka sote nan {0} s", Math.ceil(b.skip - b.el)) : t("Peze OK pou sote ▶")) : "");
      if (bar) bar.style.width = Math.min(100, (pos / dur) * 100) + "%";
    }
    function scheduler() {
      const c = T.cfg, ab = c && c.adBreak; if (!ab || !ab.auto || !playing() || T.brk) return;
      const now = new Date(), hm = String(now.getHours()).padStart(2, "0") + ":" + String(now.getMinutes()).padStart(2, "0");
      const due = (ab.times.includes(hm) && T.lastMin !== hm) || (ab.everyMin > 0 && Date.now() - T.lastBreak >= ab.everyMin * 60000);
      if (!due || !ab.spots.length) return; T.lastMin = hm;
      const n = Math.min(ab.spotsPerBreak, ab.spots.length), pick = []; for (let k = 0; k < n; k++) pick.push(ab.spots[(T.cursor + k) % ab.spots.length]); T.cursor = (T.cursor + n) % ab.spots.length;
      startBreak(pick, ab.skipAfterSec);
    }

    // ------------------------------------------------------------ Koneksyon ak backend la
    async function poll() {
      if (!T.on) return;
      try {
        let c = await dev("GET", `/${M}/config`);
        if (!c.known) { await register(); c = await dev("GET", `/${M}/config`); }
        const was = playing(); T.cfg = c; T.err = "";
        if (!was && playing()) { T.lastBreak = Date.now(); T.lastIdent = Date.now(); report(); }
        if (playing()) { if (T.all.length) applyFilters(); loadChannels(); } else { T.chKey = ""; T.all = []; T.chs = []; }
      } catch (err) { T.err = err.status === 403 || err.status === 409 ? t("Device Key pa matche. Efase TV tès la nan paj Aparèy la, epi relimen l.") : t("Sèvè a pa reponn."); }
      render(); messages();
      clearTimeout(T.pollT); T.pollT = setTimeout(poll, Math.min(15, Math.max(5, T.cfg?.refreshSec || 15)) * 1000);
    }
    async function commands() {
      while (T.on) {
        try {
          const r = await dev("GET", `/${M}/commands?wait=20`, undefined, T.abort.signal);
          for (const c of r.commands || []) {
            if (c.type === "sync" || c.type === "refresh") { clearTimeout(T.pollT); poll(); if (c.type === "refresh") toast(t("Panel la rafrechi TV a")); }
            else if (c.type === "message") dialog(t("Mesaj"), c.text);
            else if (c.type === "adbreak") startBreak(c.ads, c.skipAfterSec);
            else if (c.type === "play") { const i = T.chs.findIndex((x) => x.id === Number(c.channelId)); toast(t("Panel la chanje chanèl la")); if (i >= 0) play(i); }
            else if (c.type === "reload") { toast(t("Panel la rechaje lis chanèl la")); T.chKey = ""; clearTimeout(T.pollT); poll(); }
            else if (c.type === "restart") toast(t("Panel la redemare app la"));
            else if (c.type === "logout") toast(t("Panel la dekonekte TV a"));
          }
        } catch (err) { if (!T.on) return; await new Promise((r) => setTimeout(r, 5000)); }
      }
    }
    function start() {
      if (T.on) return; T.on = true; S.on = true; save(S); el.hidden = false; el.classList.toggle("min", !!S.min); btn()?.classList.add("on"); scale(); render();
      T.abort = new AbortController();
      register().catch(() => {}).then(() => { poll(); commands(); });
      T.timers = [setInterval(() => { if (playing()) { overlays(T.brk ? null : T.cfg); const i = T.cfg.graphics?.ident; if (i && i.everyMin > 0 && !T.brk && Date.now() - T.lastIdent >= i.everyMin * 60000) ident(); } }, 1000),
        setInterval(breakTick, 250), setInterval(scheduler, 20000), setInterval(report, 20000)];
    }
    function stop() {
      T.on = false; S.on = false; save(S); el.hidden = true; btn()?.classList.remove("on");
      T.timers.forEach(clearInterval); T.timers = []; clearTimeout(T.pollT); clearTimeout(T.cy.timer); T.abort?.abort(); T.brk = null; T.chKey = ""; T.all = []; T.chs = []; T.bgSig = null; closeList(); $(".ttv-ad").hidden = true; $(".ttv-ad").innerHTML = "";
    }
    const btn = () => document.getElementById("ttvBtn");
    el.addEventListener("click", (e) => { const a = e.target.closest("[data-a]")?.dataset.a;
      if (a === "off") stop(); if (a === "min") { S.min = !S.min; save(S); el.classList.toggle("min", S.min); scale(); T.tkSig = null; }
      if (a === "chup") play(T.idx + 1); if (a === "chdn") play(T.idx - 1); if (a === "list") (T.listOpen ? closeList() : openList());
      if (a === "dev") D.openDevice(S.mac); });
    return { start, stop, toggle: () => (T.on ? stop() : start()), isOn: () => T.on, wasOn: () => !!S.on, mac: S.mac };
  }
  window.GalaxyTestTv = { init };
})();
