' Zouti rezo ak idantite TV a (itilize nan Task yo sèlman: roUrlTransfer pa mache sou fil render la).

function panelBase() as string
    return "https://panel.galaxytvstick.net/api"
end function

function appVersion() as string
    return "1.0.1"
end function

' Demann HTTP. Retounen {code, body}. code -1 = pa ka voye, -2 = tan an fini.
function httpReq(url as string, method as string, body as string, headers as object, timeoutMs as integer) as object
    t = CreateObject("roUrlTransfer")
    port = CreateObject("roMessagePort")
    t.SetMessagePort(port)
    t.SetUrl(url)
    t.SetCertificatesFile("common:/certs/ca-bundle.crt")
    t.InitClientCertificates()
    t.RetainBodyOnError(true)
    t.EnableEncodings(true)
    if headers <> invalid
        for each k in headers
            t.AddHeader(k, headers[k])
        end for
    end if
    ok = false
    if method = "POST"
        t.AddHeader("Content-Type", "application/json")
        ok = t.AsyncPostFromString(body)
    else
        ok = t.AsyncGetToString()
    end if
    if not ok then return { code: -1, body: "" }
    msg = wait(timeoutMs, port)
    if type(msg) = "roUrlEvent"
        return { code: msg.GetResponseCode(), body: msg.GetString() }
    end if
    t.AsyncCancel()
    return { code: -2, body: "" }
end function

function urlEsc(s as string) as string
    t = CreateObject("roUrlTransfer")
    return t.Escape(s)
end function

' MAC ak Device Key TV a: kreye yon sèl fwa, epi sove nan memwa Roku a.
function deviceIds() as object
    sec = CreateObject("roRegistrySection", "galaxy")
    if sec.Exists("mac") and sec.Exists("key")
        return { mac: sec.Read("mac"), key: sec.Read("key") }
    end if
    di = CreateObject("roDeviceInfo")
    seed = CreateObject("roByteArray")
    seed.FromAsciiString("galaxy-roku:" + di.GetRandomUUID())
    dg = CreateObject("roEVPDigest")
    dg.Setup("sha256")
    h = UCase(dg.Process(seed))
    mac = ""
    for i = 0 to 5
        if i > 0 then mac = mac + ":"
        mac = mac + Mid(h, i * 2 + 1, 2)
    end for
    b = CreateObject("roByteArray")
    b.FromHexString(Mid(h, 13, 6))
    n = b[0] * 65536 + b[1] * 256 + b[2]
    key = (100000 + (n MOD 900000)).ToStr()
    sec.Write("mac", mac)
    sec.Write("key", key)
    sec.Flush()
    return { mac: mac, key: key }
end function

function panelHeaders(key as string) as object
    return { "X-Device-Key": key, "X-App-Version": appVersion(), "X-App-Brand": "galaxy" }
end function
