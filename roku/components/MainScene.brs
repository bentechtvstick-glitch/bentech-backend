' Galaxy TV Stick pou Roku — ekran prensipal la.

sub init()
    m.act = m.top.findNode("act")
    m.browse = m.top.findNode("browse")
    m.cats = m.top.findNode("cats")
    m.chans = m.top.findNode("chans")
    m.video = m.top.findNode("video")
    m.osd = m.top.findNode("osd")
    m.err = m.top.findNode("err")
    m.listMsg = m.top.findNode("listMsg")
    m.clock = m.top.findNode("clock")

    m.pl = invalid          ' playlist panel la bay TV a
    m.plSig = ""
    m.hidCats = {}
    m.hidChans = {}
    m.catList = []          ' [{id, name}]
    m.chList = []           ' chanèl gwoup ki louvri a
    m.loadedCat = ""
    m.playIndex = -1
    m.triedTs = false

    m.panel = CreateObject("roSGNode", "PanelTask")
    m.panel.observeField("mac", "onIds")
    m.panel.observeField("key", "onIds")
    m.panel.observeField("config", "onConfig")
    m.panel.observeField("state", "onPanelState")
    m.panel.control = "RUN"

    m.cats.observeField("itemFocused", "onCatFocused")
    m.chans.observeField("itemSelected", "onChanSelected")
    m.video.observeField("state", "onVideoState")
    m.top.findNode("catTimer").observeField("fire", "onCatTimer")
    m.top.findNode("osdTimer").observeField("fire", "onOsdTimer")
    ct = m.top.findNode("clockTimer")
    ct.observeField("fire", "updateClock")
    ct.control = "start"
    m.top.findNode("statusTimer").observeField("fire", "sendStatus")
    updateClock()
    m.top.setFocus(true)
end sub

' ------------------------------------------------------------ Panel

sub onIds()
    m.top.findNode("macLbl").text = m.panel.mac
    m.top.findNode("keyLbl").text = m.panel.key
end sub

sub onPanelState()
    s = m.panel.state
    if m.pl = invalid
        if s = "offline"
            m.top.findNode("actMsg").text = "Can't reach the panel. Retrying…"
        else if s = "keyconflict"
            m.top.findNode("actMsg").text = "This MAC is already used with another Device Key. Contact customer service."
        else if s = "ok"
            m.top.findNode("actMsg").text = "Waiting for activation. Give the MAC address and Device Key to customer service."
        end if
    end if
end sub

sub onConfig()
    cfg = m.panel.config
    if cfg = invalid then return
    pls = cfg.playlists
    pl = invalid
    if type(pls) = "roArray" and pls.Count() > 0 then pl = pls[0]
    if isTrue(cfg.blocked) or isTrue(cfg.expired) then pl = invalid
    if pl <> invalid and (pl.username = invalid or pl.password = invalid or pl.server = invalid) then pl = invalid

    m.hidCats = {}
    if type(cfg.hiddenCategories) = "roArray"
        for each c in cfg.hiddenCategories
            m.hidCats[toText(c)] = true
        end for
    end if
    m.hidChans = {}
    if type(cfg.hiddenChannels) = "roArray"
        for each c in cfg.hiddenChannels
            m.hidChans[toText(c)] = true
        end for
    end if

    if pl = invalid
        m.pl = invalid
        m.plSig = ""
        stopVideo()
        m.browse.visible = false
        m.act.visible = true
        msg = "Waiting for activation. Give the MAC address and Device Key to customer service."
        if isTrue(cfg.blocked) then msg = "This TV is blocked. Contact customer service."
        if isTrue(cfg.expired) then msg = "Your subscription has expired. Contact customer service to renew."
        m.top.findNode("actMsg").text = msg
        m.top.setFocus(true)
        return
    end if

    server = toText(pl.server)
    while Right(server, 1) = "/"
        server = Left(server, Len(server) - 1)
    end while
    sig = server + "|" + toText(pl.username) + "|" + toText(pl.password)
    m.pl = { server: server, user: toText(pl.username), pass: toText(pl.password), name: toText(pl.name) }
    m.act.visible = false
    m.browse.visible = true
    if sig <> m.plSig
        m.plSig = sig
        m.loadedCat = ""
        loadCategories()
    else
        ' Menm playlist: aplike chanèl / gwoup kache yo san rechaje tout bagay
        renderCats()
    end if
end sub

' ------------------------------------------------------------ Sèvè IPTV a

sub runXtream(action as string, catId as string)
    t = CreateObject("roSGNode", "XtreamTask")
    t.observeField("result", "onXtream")
    t.job = { server: m.pl.server, user: m.pl.user, pass: m.pl.pass, action: action, catId: catId }
    t.control = "RUN"
    ' Kenbe yon referans: san sa, Roku ka efase Task la anvan li fini
    if action = "get_live_categories" then m.catTask = t else m.chTask = t
end sub

sub loadCategories()
    m.listMsg.text = "Loading channels…"
    m.listMsg.visible = true
    runXtream("get_live_categories", "")
end sub

sub onXtream(ev as object)
    r = ev.getData()
    if r = invalid then return
    if r.action = "get_live_categories"
        m.catList = []
        if type(r.data) = "roArray"
            for each c in r.data
                if c <> invalid and c.category_id <> invalid
                    m.catList.Push({ id: toText(c.category_id), name: toText(c.category_name) })
                end if
            end for
        end if
        renderCats()
        if not r.ok
            m.listMsg.text = "The IPTV server did not answer (code " + r.code.ToStr() + "). Retrying when the panel sends the playlist again."
            m.listMsg.visible = true
        end if
    else if r.action = "get_live_streams"
        if r.catId <> m.loadedCat then return
        m.chList = []
        if type(r.data) = "roArray"
            for each c in r.data
                if c <> invalid and c.stream_id <> invalid
                    id = toText(c.stream_id)
                    if not m.hidChans.DoesExist(id)
                        m.chList.Push({ id: id, name: toText(c.name), num: m.chList.Count() + 1 })
                    end if
                end if
            end for
        end if
        renderChans()
    end if
end sub

function visibleCats() as object
    out = []
    for each c in m.catList
        if not m.hidCats.DoesExist(c.id) then out.Push(c)
    end for
    return out
end function

sub renderCats()
    m.visCats = visibleCats()
    content = CreateObject("roSGNode", "ContentNode")
    for each c in m.visCats
        n = content.CreateChild("ContentNode")
        n.title = c.name
    end for
    m.cats.content = content
    if m.visCats.Count() = 0
        m.listMsg.text = "No channels for this playlist."
        m.listMsg.visible = true
        return
    end if
    m.listMsg.visible = false
    if not m.video.visible then m.cats.setFocus(true)
    m.cats.jumpToItem = 0
    openCat(0)
end sub

sub onCatFocused()
    ' Tann yon ti moman: si kliyan an ap desann vit, pa chaje chak gwoup li pase
    t = m.top.findNode("catTimer")
    t.control = "stop"
    t.control = "start"
end sub

sub onCatTimer()
    openCat(m.cats.itemFocused)
end sub

sub openCat(i as integer)
    if m.visCats = invalid or i < 0 or i >= m.visCats.Count() then return
    id = m.visCats[i].id
    if id = m.loadedCat then return
    m.loadedCat = id
    m.chans.content = CreateObject("roSGNode", "ContentNode")
    m.listMsg.text = "Loading…"
    m.listMsg.visible = true
    runXtream("get_live_streams", id)
end sub

sub renderChans()
    content = CreateObject("roSGNode", "ContentNode")
    for each c in m.chList
        n = content.CreateChild("ContentNode")
        n.title = c.num.ToStr() + "    " + c.name
    end for
    m.chans.content = content
    if m.chList.Count() = 0
        m.listMsg.text = "No channels in this group."
        m.listMsg.visible = true
    else
        m.listMsg.visible = false
    end if
end sub

' ------------------------------------------------------------ Gade chanèl

sub onChanSelected()
    play(m.chans.itemSelected, false)
end sub

sub play(i as integer, asTs as boolean)
    if i < 0 or i >= m.chList.Count() or m.pl = invalid then return
    m.playIndex = i
    m.triedTs = asTs
    c = m.chList[i]
    ext = ".m3u8"
    fmt = "hls"
    if asTs
        ext = ".ts"
        fmt = "ts"
    end if
    node = CreateObject("roSGNode", "ContentNode")
    node.url = m.pl.server + "/live/" + m.pl.user + "/" + m.pl.pass + "/" + c.id + ext
    node.streamFormat = fmt
    node.title = c.name
    node.live = true
    m.err.visible = false
    m.video.content = node
    m.video.visible = true
    m.browse.visible = false
    m.video.control = "play"
    m.video.setFocus(true)
    showOsd()
    sendStatus()
    m.top.findNode("statusTimer").control = "start"
end sub

sub onVideoState()
    s = m.video.state
    if s = "error"
        ' Kèk sèvè pa bay .m3u8: eseye .ts yon fwa
        if not m.triedTs
            play(m.playIndex, true)
            return
        end if
        m.err.text = "This channel is not available right now. Try another channel."
        m.err.visible = true
    else if s = "playing"
        m.err.visible = false
    end if
end sub

sub stopVideo()
    if m.video.visible
        m.video.control = "stop"
        m.video.visible = false
    end if
    m.osd.visible = false
    m.err.visible = false
    m.top.findNode("statusTimer").control = "stop"
end sub

sub showOsd()
    if m.playIndex < 0 then return
    c = m.chList[m.playIndex]
    m.top.findNode("osdNum").text = c.num.ToStr()
    m.top.findNode("osdName").text = c.name
    m.osd.visible = true
    t = m.top.findNode("osdTimer")
    t.control = "stop"
    t.control = "start"
end sub

sub onOsdTimer()
    m.osd.visible = false
end sub

sub sendStatus()
    body = { screen: "home", playlistName: "", appVersion: "1.0.1" }
    if m.pl <> invalid then body.playlistName = m.pl.name
    if m.video.visible and m.playIndex >= 0
        c = m.chList[m.playIndex]
        catName = ""
        if m.visCats <> invalid
            for each k in m.visCats
                if k.id = m.loadedCat then catName = k.name
            end for
        end if
        body.screen = "player"
        body.channel = { id: Val(c.id), num: c.num, name: c.name, category: catName }
    end if
    t = CreateObject("roSGNode", "StatusTask")
    t.body = body
    t.control = "RUN"
    m.statusTask = t
end sub

sub updateClock()
    d = CreateObject("roDateTime")
    d.ToLocalTime()
    h = d.GetHours()
    mi = d.GetMinutes()
    ap = "AM"
    if h >= 12 then ap = "PM"
    h = h MOD 12
    if h = 0 then h = 12
    mm = mi.ToStr()
    if mi < 10 then mm = "0" + mm
    m.clock.text = h.ToStr() + ":" + mm + " " + ap
end sub

' ------------------------------------------------------------ Remòt la

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if m.video.visible
        if key = "back"
            stopVideo()
            m.browse.visible = true
            m.chans.setFocus(true)
            sendStatus()
            return true
        else if key = "up" or key = "down"
            n = m.chList.Count()
            if n = 0 then return true
            d = 1
            if key = "down" then d = -1
            play((m.playIndex + d + n) MOD n, false)
            return true
        else if key = "OK" or key = "info"
            showOsd()
            return true
        end if
        return false
    end if
    if m.browse.visible
        if key = "right" and m.cats.hasFocus()
            if m.chList.Count() > 0 then m.chans.setFocus(true)
            return true
        else if key = "left" and m.chans.hasFocus()
            m.cats.setFocus(true)
            return true
        end if
    end if
    return false
end function

' ------------------------------------------------------------ Zouti

' Nenpòt valè JSON → tèks (sèvè IPTV yo konn voye nimewo oswa tèks pou menm chan an)
function toText(v as dynamic) as string
    if v = invalid then return ""
    t = type(v)
    if t = "String" or t = "roString" then return v
    if t = "Integer" or t = "roInt" or t = "LongInteger" or t = "roLongInteger" then return v.ToStr()
    if t = "Float" or t = "roFloat" or t = "Double" or t = "roDouble"
        if v = Int(v) then return Int(v).ToStr()
        return v.ToStr()
    end if
    if t = "Boolean" or t = "roBoolean"
        if v then return "true"
        return "false"
    end if
    return ""
end function

function isTrue(v as dynamic) as boolean
    t = type(v)
    if t = "Boolean" or t = "roBoolean" then return v
    return false
end function
