/*
 * Galaxy TV Stick pou TV LG (webOS) — vèsyon tès.
 * Aktivasyon ak panel la (MAC + Device Key), gwoup ak chanèl (panel la chèche yo kay founisè a),
 * gade chanèl an dirèk plen ekran. Ekri an JavaScript ansyen (ES5) pou l mache sou TV LG ki gen plizyè ane.
 */
(function () {
  "use strict";

  var PANEL = "https://panel.galaxytvstick.net/api";
  var VERSION = "1.0.3";
  var $ = function (id) { return document.getElementById(id); };

  // ------------------------------------------------------------ Idantite TV a (yon sèl fwa, sove sou TV a)
  function ids() {
    var mac = null, key = null;
    try { mac = localStorage.getItem("gx_mac"); key = localStorage.getItem("gx_key"); } catch (e) {}
    if (mac && key) return { mac: mac, key: key };
    var b = [], i;
    for (i = 0; i < 9; i++) b.push(Math.floor(Math.random() * 256));
    var hex = function (n) { return (n < 16 ? "0" : "") + n.toString(16).toUpperCase(); };
    mac = [hex(b[0]), hex(b[1]), hex(b[2]), hex(b[3]), hex(b[4]), hex(b[5])].join(":");
    key = String(100000 + ((b[6] * 65536 + b[7] * 256 + b[8]) % 900000));
    try { localStorage.setItem("gx_mac", mac); localStorage.setItem("gx_key", key); } catch (e2) {}
    return { mac: mac, key: key };
  }
  var me = ids();

  function req(method, path, body, cb, timeoutMs) {
    var x = new XMLHttpRequest();
    x.open(method, PANEL + path, true);
    x.timeout = timeoutMs || 20000;
    x.setRequestHeader("X-Device-Key", me.key);
    x.setRequestHeader("X-App-Version", VERSION);
    x.setRequestHeader("X-App-Brand", "galaxy");
    if (body) x.setRequestHeader("Content-Type", "application/json");
    x.onload = function () {
      var data = null;
      try { data = JSON.parse(x.responseText); } catch (e) {}
      cb(x.status, data);
    };
    x.onerror = function () { cb(-1, null); };
    x.ontimeout = function () { cb(-2, null); };
    x.send(body ? JSON.stringify(body) : null);
  }

  // ------------------------------------------------------------ Eta
  var state = {
    registered: false,
    plSig: "",
    live: null,      // {server,user,pass}
    cats: [],        // [{id,name}]
    chs: [],         // tout chanèl yo
    byCat: {},       // id gwoup → [chanèl]
    focus: "cats",   // cats | chans | video
    catI: 0,
    chI: 0,
    playList: [],
    playI: -1,
    triedTs: false
  };

  $("macLbl").textContent = me.mac;
  $("keyLbl").textContent = me.key;

  function show(screen) {
    $("act").className = screen === "act" ? "" : "hidden";
    $("browse").className = screen === "browse" ? "" : "hidden";
    var v = screen === "video";
    $("video").className = v ? "" : "hidden";
    if (!v) { $("osd").className = "hidden"; $("err").className = "hidden"; }
  }

  // ------------------------------------------------------------ Panel la
  function register(done) {
    req("POST", "/devices/register", {
      deviceId: me.mac, mac: me.mac, deviceKey: me.key,
      model: "LG webOS TV", androidVersion: "webOS", appVersion: VERSION
    }, function (code) {
      if (code >= 200 && code < 300) state.registered = true;
      else if (code === 409) $("actMsg").textContent = "This MAC is already used with another Device Key. Contact customer service.";
      done();
    });
  }

  function poll() {
    var go = function () {
      req("GET", "/devices/" + encodeURIComponent(me.mac) + "/config", null, function (code, cfg) {
        if (code === 200 && cfg) onConfig(cfg);
        else if (!state.live) $("actMsg").textContent = "Can't reach the panel. Retrying…";
        setTimeout(poll, 15000);
      });
    };
    if (!state.registered) register(go); else go();
  }

  function onConfig(cfg) {
    var pls = cfg.playlists || [];
    var st = String(cfg.status || "");
    var ok = pls.length > 0 && st !== "blocked" && st !== "expired" && st !== "maintenance";
    if (!ok) {
      state.plSig = ""; state.live = null;
      stopVideo(); show("act");
      var msg = "Waiting for activation. Give the MAC address and Device Key to customer service.";
      if (st === "blocked") msg = "This TV is blocked. Contact customer service.";
      if (st === "expired") msg = "Your subscription has expired. Contact customer service to renew.";
      if (st === "maintenance") msg = cfg.statusMessage || "Service is under maintenance. Please try again later.";
      $("actMsg").textContent = msg;
      return;
    }
    // Rechaje lis la sèlman si playlist la oswa chanèl/gwoup kache yo chanje
    var sig = JSON.stringify([pls[0].server, pls[0].username, cfg.hiddenChannels, cfg.hiddenCategories, cfg.categoryNames, cfg.channelNames, cfg.channelNumbering]);
    if (sig === state.plSig) return;
    state.plSig = sig;
    loadChannels();
  }

  function loadChannels() {
    if (state.focus !== "video") show("browse");
    $("listMsg").textContent = "Loading channels…";
    req("GET", "/devices/" + encodeURIComponent(me.mac) + "/live", null, function (code, d) {
      if (code !== 200 || !d) {
        $("listMsg").textContent = "Could not load the channels (" + (d && d.error ? d.error : "code " + code) + "). Retrying…";
        state.plSig = "";
        return;
      }
      state.live = d.live;
      state.cats = d.cats || [];
      state.chs = d.chs || [];
      state.byCat = {};
      for (var i = 0; i < state.chs.length; i++) {
        var c = state.chs[i];
        (state.byCat[c.c] = state.byCat[c.c] || []).push(c);
      }
      state.cats = state.cats.filter(function (c) { return state.byCat[c.id]; });
      $("listMsg").textContent = state.cats.length ? "" : "No channels for this playlist.";
      if (state.catI >= state.cats.length) state.catI = 0;
      state.chI = 0;
      renderCats(); renderChans();
    }, 60000);
  }

  // ------------------------------------------------------------ Lis yo (yon fenèt 11 liy ki deplase ak remòt la)
  var ROWS = 11;
  function windowStart(i, n) { return Math.max(0, Math.min(i - 5, n - ROWS)); }
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (m) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[m]; }); }

  function renderCats() {
    var el = $("cats"), h = "", n = state.cats.length, s = windowStart(state.catI, n);
    for (var i = s; i < Math.min(n, s + ROWS); i++) h += '<div class="row' + (i === state.catI ? " sel" : "") + '"><span class="t">' + esc(state.cats[i].name) + "</span></div>";
    el.innerHTML = h;
    el.className = "list" + (state.focus === "cats" ? " focus" : "");
  }

  function curList() { var c = state.cats[state.catI]; return c ? state.byCat[c.id] || [] : []; }

  function renderChans() {
    var el = $("chans"), list = curList(), h = "", n = list.length, s = windowStart(state.chI, n);
    for (var i = s; i < Math.min(n, s + ROWS); i++) {
      var c = list[i];
      h += '<div class="row' + (i === state.chI ? " sel" : "") + '"><span class="n">' + c.n + "</span>" +
        (c.icon ? '<img src="' + esc(c.icon) + '" alt="" onerror="this.style.visibility=\'hidden\'">' : "") +
        '<span class="t">' + esc(c.name) + "</span></div>";
    }
    el.innerHTML = h;
    el.className = "list" + (state.focus === "chans" ? " focus" : "");
  }

  // ------------------------------------------------------------ Gade chanèl
  var video = $("video"), osdT = null;

  /*
   * Fason pou jwe chanèl la, youn apre lòt (sou sèvè playlist la, epi sou sèvè videyo founisè a si l diferan):
   *  1. HLS (.m3u8) ak lektè TV a, ak tip la endike (webOS bezwen l)
   *  2. HLS ak hls.js (si TV a pa li HLS li menm)
   *  3. MPEG-TS (.ts) ak mpegts.js (menm fòma ak Fire Stick yo)
   *  4. MPEG-TS ak lektè TV a
   * Si youn pa bay imaj nan 12 s oswa li bay yon erè, app la eseye pwochen an, epi l ekri rezon yo.
   */
  var tries = [], tryI = 0, stallT = null, lastErr = "", token = 0, hls = null, mp = null;
  function candidates(c) {
    var L = state.live, bases = [L.server], out = [], i;
    if (L.alt && L.alt !== L.server) bases.push(L.alt);
    var tsFirst = (L.formats || []).length && (L.formats || []).indexOf("m3u8") < 0;
    for (i = 0; i < bases.length; i++) {
      var u = bases[i] + "/live/" + encodeURIComponent(L.user) + "/" + encodeURIComponent(L.pass) + "/" + c.id, alt = i ? " alt" : "";
      var hlsTries = [{ mode: "native", url: u + ".m3u8", type: "application/vnd.apple.mpegurl", label: "m3u8" + alt },
                      { mode: "hlsjs", url: u + ".m3u8", label: "hls.js" + alt }];
      var tsTries = [{ mode: "mpegts", url: u + ".ts", label: "mpegts.js" + alt },
                     { mode: "native", url: u + ".ts", type: "video/mp2t", label: "ts" + alt }];
      out = out.concat(tsFirst ? tsTries.concat(hlsTries) : hlsTries.concat(tsTries));
    }
    return out;
  }

  function play(i) {
    var list = state.playList;
    if (!state.live || i < 0 || i >= list.length) return;
    state.playI = i;
    tries = candidates(list[i]); tryI = 0; lastErr = "";
    $("err").className = "hidden";
    state.focus = "video";
    show("video");
    startTry();
    showOsd();
    sendStatus();
  }

  function clearEngines() {
    if (hls) { try { hls.destroy(); } catch (e) {} hls = null; }
    if (mp) { try { mp.pause(); mp.unload(); mp.detachMediaElement(); mp.destroy(); } catch (e2) {} mp = null; }
    try { video.pause(); } catch (e3) {}
    while (video.firstChild) video.removeChild(video.firstChild);
    video.removeAttribute("src");
    try { video.load(); } catch (e4) {}
  }

  function startTry() {
    clearTimeout(stallT);
    clearEngines();
    var t = tries[tryI]; if (!t) return;
    var my = ++token;
    var fail = function (why) { if (my === token) nextTry(why); };
    if (t.mode === "hlsjs") {
      if (!(window.Hls && window.Hls.isSupported())) { fail("not available"); return; }
      hls = new window.Hls({ enableWorker: true, lowLatencyMode: false });
      hls.on(window.Hls.Events.ERROR, function (ev, d) {
        if (d && d.fatal) fail(d.type + " " + d.details + (d.response && d.response.code ? " HTTP " + d.response.code : ""));
      });
      hls.loadSource(t.url);
      hls.attachMedia(video);
    } else if (t.mode === "mpegts") {
      if (!(window.mpegts && window.mpegts.getFeatureList().mseLivePlayback)) { fail("not available"); return; }
      mp = window.mpegts.createPlayer({ type: "mpegts", isLive: true, url: t.url }, { enableWorker: true, lazyLoad: false, liveBufferLatencyChasing: false });
      mp.on(window.mpegts.Events.ERROR, function (type, detail, info) {
        fail(type + " " + detail + (info && info.code ? " HTTP " + info.code : "") + (info && info.msg ? " " + info.msg : ""));
      });
      mp.attachMediaElement(video);
      mp.load();
    } else {
      var src = document.createElement("source");
      src.src = t.url; src.type = t.type;
      src.addEventListener("error", function () { fail("source error"); });
      video.appendChild(src);
      try { video.load(); } catch (e0) {}
    }
    try { var pr = video.play(); if (pr && pr.catch) pr.catch(function () {}); } catch (e) {}
    stallT = setTimeout(function () { fail("no picture after 12 s"); }, 12000);
  }

  function nextTry(why) {
    if (state.focus !== "video") return;
    clearTimeout(stallT);
    lastErr += (lastErr ? " · " : "") + (tries[tryI] ? tries[tryI].label : "?") + ": " + why;
    tryI++;
    if (tryI < tries.length) { startTry(); return; }
    clearEngines();
    $("err").innerHTML = "This channel is not available right now. Try another channel.<br><span style=\"font-size:22px;color:#C9CFEA\">" + esc(lastErr) + "</span>";
    $("err").className = "";
  }

  video.addEventListener("error", function () {
    if (hls || mp || !tries[tryI] || video.firstChild) return; // hls.js / mpegts.js / <source> bay pwòp erè yo
    var e = video.error, code = e ? e.code : 0;
    var names = { 1: "aborted", 2: "network", 3: "decode", 4: "format not supported" };
    nextTry("error " + code + " " + (names[code] || ""));
  });
  video.addEventListener("playing", function () { clearTimeout(stallT); $("err").className = "hidden"; });

  function stopVideo() {
    clearTimeout(stallT);
    token++;
    clearEngines();
  }

  function showOsd() {
    var c = state.playList[state.playI]; if (!c) return;
    $("osdNum").textContent = c.n; $("osdName").textContent = c.name;
    $("osd").className = "";
    clearTimeout(osdT); osdT = setTimeout(function () { $("osd").className = "hidden"; }, 4000);
  }

  function sendStatus() {
    var body = { screen: "home", appVersion: VERSION };
    if (state.focus === "video" && state.playI >= 0) {
      var c = state.playList[state.playI], cat = state.cats[state.catI];
      body.screen = "player";
      body.channel = { id: c.id, num: c.n, name: c.name, category: cat ? cat.name : "" };
    }
    req("POST", "/devices/" + encodeURIComponent(me.mac) + "/status", body, function () {});
  }
  setInterval(sendStatus, 60000);

  function backToList() {
    stopVideo();
    state.focus = "chans";
    show("browse"); renderCats(); renderChans();
    sendStatus();
  }

  // ------------------------------------------------------------ Remòt la
  document.addEventListener("keydown", function (e) {
    var k = e.keyCode;
    var BACK = k === 461 || k === 8 || k === 27, UP = k === 38, DOWN = k === 40, LEFT = k === 37, RIGHT = k === 39, OK = k === 13;
    if (state.focus === "video") {
      if (BACK) { backToList(); e.preventDefault(); return; }
      var n = state.playList.length;
      if ((UP || DOWN) && n) { play((state.playI + (UP ? 1 : -1) + n) % n); e.preventDefault(); return; }
      if (OK) { showOsd(); e.preventDefault(); }
      return;
    }
    if ($("browse").className === "hidden") { if (BACK) { e.preventDefault(); window.close(); } return; }
    if (state.focus === "cats") {
      if (UP && state.catI > 0) { state.catI--; state.chI = 0; }
      else if (DOWN && state.catI < state.cats.length - 1) { state.catI++; state.chI = 0; }
      else if ((RIGHT || OK) && curList().length) state.focus = "chans";
      else if (BACK) { e.preventDefault(); window.close(); return; } // fèmen app la (tankou lòt app LG yo)
      else return;
    } else {
      var list = curList();
      if (UP && state.chI > 0) state.chI--;
      else if (DOWN && state.chI < list.length - 1) state.chI++;
      else if (LEFT || BACK) state.focus = "cats";
      else if (OK && list.length) { state.playList = list; play(state.chI); e.preventDefault(); return; }
      else return;
    }
    e.preventDefault();
    renderCats(); renderChans();
  });

  // ------------------------------------------------------------ Lè a
  function clock() {
    var d = new Date(), h = d.getHours(), m = d.getMinutes(), ap = h >= 12 ? "PM" : "AM";
    h = h % 12 || 12;
    $("clock").textContent = h + ":" + (m < 10 ? "0" : "") + m + " " + ap;
  }
  clock(); setInterval(clock, 20000);

  show("act");
  poll();
})();
