# BenTech TV Stick Backend

Backend API for BenTech TV Stick IPTV service.

## Stack
- Node.js + Express
- LowDB (JSON file database)
- JWT Authentication
- Input validation, pagination, audit logging, error handling

## Endpoints
- GET /api/health — Health check
- GET /api/version — Version info
- POST /api/auth/login — Admin login (JWT)
- POST /api/auth/activate — Device activation
- GET /api/dashboard — Dashboard counts
- CRUD on: customers, devices, channels, programs, banners, popups, tickers, live-events, logs, settings

## Deployment
Hosted on Render with persistent disk for db.json.
## Galaxy TV Stick (app TV a)

`src/galaxy.js` konekte app **Galaxy TV Stick** la ak panel la. Chak aparèy idantifye ak yon **MAC Address** + **Device Key** (6 chif) ki parèt sou ekran TV a.

### Kijan li mache
1. Kliyan an louvri app la → TV a montre MAC + Device Key epi li anrejistre tèt li (`POST /api/devices/register`). Aparèy la parèt nan lis **Devices** la.
2. Admin nan antre MAC la nan fòm **Ajoute Playlist** la → aparèy la parèt → Non playlist, Sèvè, Username, Password → **Submit**.
3. Nan 5 segonn, TV a resevwa playlist la epi li konekte poukont li.

### Sa panel la kontwole sou TV a
| Nan panel la | Sou TV a |
|---|---|
| **Tickers** (status Active) | Tout mesaj aktif yo defile anba ekran an. Chak mesaj ka gen pwòp koulè li (`color`) ak yon orè `start` / `end` (lè panel la; vid = toutan). Panel la gen Edit / Delete pou chak mesaj. Paramèt: `tickerActive` (Active/Inactive), `tickerDirection` (`left`/`right`), `tickerSpeed`, `tickerTextColor`, `tickerBgColor`, `tickerLabel` (egz: 🔴 LIVE), `tickerLabelBg`, `tickerLabelColor`, `tickerSeparator`, `tickerClock`, `tickerClockFormat` (`12` oswa `24`), `tickerLabelOn` (switch etikèt la), `tickerTextSize` (12–40), `tickerBgTransparent`, `tickerLabelBgTransparent`, `tickerAnimEmoji` (imoji anime), `tickerRunnerOn` / `tickerRunner` / `tickerRunnerSpeed` / `tickerRunnerFlip` (animasyon k ap kouri, egz: 🏎️💨) |
| **Banners** (content = tèks oswa URL imaj) | Banner nan kwen anba adwat lè lis chanèl la ouvè |
| **Popups** (active = Yes, ant `start` ak `end`) | Fenèt pop-up (yon fwa pa aparèy) |
| **Live Events** (`channel` = non chanèl Xtream, oswa `streamUrl`) | Kategori "🔴 Evènman an dirèk" nan lis la |
| **Media Ads** (`/api/media-ads`: url, type, placement preroll/corner/fullscreen) | Piblisite anvan chanèl la oswa nan kwen |
| **Settings → emergencyActive / emergencyText** | Mesaj Broadcast sou tout TV yo |
| **Settings → maintenanceMode / maintenanceMessage** | Tout TV yo montre ekran mentenans |
| **Settings → forceRefreshAt** (chanje valè a) | Tout TV yo rechaje chanèl yo |
| **Chyrons** (`/api/chyrons`, status Active/Inactive) | Lower third pwofesyonèl: `headline`, `name`, `title`, `logoUrl`, `sticker` (imoji anime), `position` (bottom/top × left/center/right), `animation` (slide/fade/wipe/none), `duration` (segonn, 0 = toutan), `opacity`, `bgColor`, `textColor`, `accentColor`, `headlineSize`, `nameSize`, `titleSize`, `animateEmoji`. Plizyè chyron aktif pase youn apre lòt (poz 20 s ant yo). Ansyen `chyronActive/chyronTitle` toujou mache |
| **Settings → tickerTextColor / tickerBgColor / tickerSpeed** | Koulè ak vitès ticker la |
| **Device → blocked: true** | TV a bloke |
| **Device → customer** (non kliyan an) | Si kliyan an *Expired* oswa `expiry` pase → TV a montre "abònman ekspire"; *Suspended* → bloke |
| **Device → channelProfile** (egz: "50 Channels Starter") | TV a montre sèlman kantite chanèl profile la |
| **Device → maxChannels / hiddenChannels / hiddenCategories** | Limit ak hide/show chanèl pou aparèy sa a |

Lè yo nan panel la (Live Events, Popups, expiry) li nan zòn lè `PANEL_TZ` (pa defo `America/New_York`).

### Endpoint
**App TV a** (pwoteje pa MAC + header `X-Device-Key`):
- `POST /api/devices/register`
- `GET  /api/devices/:mac/config`
- `POST /api/devices/:mac/channels`

**Panel admin nan** (JWT obligatwa, `Authorization: Bearer <token>`):
- `GET    /api/galaxy/devices` — tout aparèy Galaxy yo ak playlist yo (san modpas)
- `GET    /api/galaxy/devices/lookup?mac=` — chèche yon aparèy pa MAC
- `PATCH  /api/galaxy/devices/:mac` — `deviceName`, `customer`, `channelProfile`, `blocked`, `maxChannels`, `hiddenChannels`, `hiddenCategories`, `resetDeviceKey`
- `DELETE /api/galaxy/devices/:mac`
- `GET    /api/galaxy/devices/:mac/channels` — lis chanèl kliyan an ak eta hide/show
- `GET|POST /api/galaxy/devices/:mac/playlists`, `PUT|DELETE /api/galaxy/devices/:mac/playlists/:id`

### Varyab anviwònman
| Varyab | Sa l fè |
|---|---|
| `DB_FILE` | Kote db.json sove. Mete `/data/db.json` ak yon disk Render (plan peye). Premye fwa li kopye `./db.json` la. |
| `PANEL_TZ` | Zòn lè panel la (pa defo `America/New_York`) |
| `PROTECT_API` | `true` = tout route panel yo (customers, devices, tickers…) mande JWT. Rekòmande pou pwodiksyon. Route app TV a rete ouvè. |

## Galaxy Control — panel an dirèk (`/galaxy`)

Backend la sèvi yon panel konplè nan **`https://<sèvè-ou>/galaxy/`** (fichye nan `public/galaxy/`). Konekte ak menm `ADMIN_USER` / `ADMIN_PASS` la.

- **An dirèk**: tout TV Galaxy yo, sa chak kliyan ap gade (chanèl, pwogram, kalite 4K/8K, depi konbyen tan), mete ajou chak 5 segonn.
- **Aparèy & Playlist**: chèche pa MAC, wè TV kliyan an, ajoute/retire playlist, bloke, lye ak yon kliyan.
- **Kontwòl a distans** (`src/galaxy-live.js`): chanje chanèl, voye yon mesaj sou ekran an, rechaje, redemare app la, dekonekte playlist. App la montre yon notis sou TV a chak fwa.
- **Chanèl, Mesaj, Evènman, Piblisite, Sistèm**: menm done ak koleksyon ki deja egziste yo (tickers, banners, popups, live-events, media-ads, settings).
- **Lang**: Kreyòl, English, Français, Español (`public/galaxy/i18n.js`). Pou ajoute yon lang, kopye blòk `en` an.

Nouvo endpoint:
- App: `POST /api/devices/:mac/status`, `GET /api/devices/:mac/commands?wait=20` (long-poll)
- Panel (JWT): `GET /api/galaxy/live`, `GET /api/galaxy/devices/:mac/live`, `POST /api/galaxy/devices/:mac/commands` (`message`, `play`, `reload`, `restart`, `logout`, `refresh`)

Estati an dirèk ak kòmand yo rete an memwa (yo pa chaje db.json). Si ou gen plizyè instans sèvè, yo dwe pataje yon Redis pou sa — sou yon sèl instans Render, pa gen anyen pou fè.

## Panel konplè

Panel `/galaxy` la gen paj sa yo: **Dashboard, Live TV, Devices, Channels, Customers, Providers, Channel Profiles, Live Events, Broadcast, Media Ads, Ticker, Chyron, Banners, Pop-ups, Categories, Countries, Regions, Languages, Admin Users, Audit Logs, System**.

- **Devices** montre tout aparèy yo (menm kolòn ak panel BenTech la). Yon aparèy DVC-… ki pa gen MAC ka **konekte** ak TV Galaxy li a (Add Provider → tape MAC la → Konekte).
- **⚡ Teste playlist la**: nan fòm Ajoute Playlist la (oswa sou yon playlist ki deja sove), backend la kontakte sèvè Xtream la an vre epi l montre si kont lan mache: estati, dat ekspirasyon, koneksyon (aktif / maks), kantite chanèl live ak kategori.
  - `POST /api/galaxy/xtream/test` `{ server, username, password }` ak `POST /api/galaxy/devices/:mac/playlists/:id/test` (JWT admin).
  - Kèk sèvè IPTV bloke adrès sèvè cloud yo (Render). Si tès la echwe men TV a konekte byen, se sa.

## 🚀 Mete panel la live (Render)

1. Mete kontni dosye sa a nan repo GitHub **bentech-backend** ou a (ranplase ansyen fichye yo), epi fè commit/push. Render ap redeplwaye poukont li.
2. Nan Render → **Environment**, mete:
   - `ADMIN_USER` ak `ADMIN_PASS` (login panel la — pa kite `changeme`!)
   - `JWT_SECRET` (yon long tèks o aza)
   - `PROTECT_API=true`
   - Si w gen yon **Disk** Render: `DB_FILE=/data/db.json` (san disk, done yo ka pèdi lè Render redemare).
3. Louvri **https://bentech-backend.onrender.com/galaxy** epi konekte.
4. Enstale APK a sou Fire Stick la → li montre MAC la → nan panel la: **Devices** → chwazi MAC la → **Ajoute Playlist** → **⚡ Teste playlist la** → **Submit**. TV a konekte nan kèk segonn.

### 😀 Imoji ak senbòl
Chak chan tèks nan panel la (ticker, broadcast, chyron, banner, pop-up, evènman, mesaj remòt) gen yon bouton **🙂**: 1 851 imoji (Emoji 15.1), senbòl chèn TV (🔴 LIVE, ⚡ BREAKING NEWS, 📺, ★, ▶…), senbòl tèks ak drapo (🇭🇹…). App TV a gen font imoji a anndan l, kidonk menm ansyen Fire Stick yo montre yo byen.

### 🎬 Animasyon imoji (ticker + chyron)
Drapo yo flote, machin/avyon kouri, balon vire, kè/🔥 bat, 🔴/🚨 kliyote, 📢/🔔 souke, moun/bèt sote. Menm règ yo nan panel la (`public/galaxy/fx.js`) ak nan app TV a (`ui/EmojiFx.kt`).

> Paj **Abònman / Subscriptions** la retire nan panel la. Endpoint `/api/subscriptions` la toujou la pou done ki egziste deja.

### 📺 Koupi piblisite (tankou chèn TV)
Paj **Media Ads** → **Koupi piblisite**: spot videyo/imaj (`mediaAds` ak `placement: "break"`, chan `name`, `order`) pase plen ekran pandan kliyan an ap gade live, epi TV a tounen sou chanèl la.

**Preview an dirèk**: bouton **▶ Gade koupi a** jwe koupi a nan panel la jan kliyan an ap wè l (etikèt PIBLISITE 1/3, kont a rebou, "sote"), san l pa voye anyen bay TV yo. 👁 sou yon spot = gade sèl spot sa a; **👁 Teste nan preview a** = teste yon URL anvan w ajoute l. Lyen `.m3u8` ak `http://` pa jwe nan navigatè a (yo jwe sou TV a).
- **▶ Pase piblisite kounye a**: `POST /api/galaxy/adbreak` `{ mac?, adIds? }` (JWT) — yon TV oswa tout TV ki online.
- **Otomatik**: paramèt `adBreakAuto`, `adBreakEveryMin` (0 = sèlman lè fiks), `adBreakSpots` (spot pa koupi), `adBreakTimes` ("19:00, 20:30", lè TV a), `adBreakSkipSec` (0 = kliyan an pa ka sote).
- **Kontè**: TV a voye `POST /api/devices/:mac/ad-events` `{ adId, event: "start"|"complete" }`; panel la li `GET /api/galaxy/ad-stats`.



## ⚡ Kapasite ak rapidite

- **Ekriti gwoupe** (`src/perf.js`): chanjman yo rete nan memwa epi `db.json` ekri an gwoup (~150 ms apre), san espas, nan yon fichye tanporè ki ranplase ansyen an. Yon demann pa tann disk la ankò. Lè Render fèmen sèvè a (SIGTERM), dènye chanjman yo ekri anvan l soti.
- **Repons konprese (gzip)**: gwo repons JSON yo ak fichye panel la (HTML/JS) konprese; panel la resevwa `304` si anyen pa chanje.
- **Endèks aparèy**: sèvè a jwenn yon TV pa MAC san l pa pase sou tout lis la.
- **Gwo playlist**: limit demann JSON la monte a 12 MB (yon lis plizyè milye chanèl pase).
- **Lis yo**: `GET /api/<koleksyon>?limit=` aksepte jiska 5000, ak `?q=` pou chèche sou sèvè a. Panel la chaje tout eleman yo, montre 100 premye yo ak bouton **Montre plis**, epi li pa retouche paj la si anyen pa chanje.
- **Audit log**: sèvè a kenbe dènye 5000 aksyon yo; `GET /api/audit-logs?limit=` (1000 pa defo).
- **Paj Sistèm → Kapasite ak rapidite**: `GET /api/galaxy/system` (gwosè baz done a, tan ekriti, memwa, aparèy, chanèl).

Limit: baz done a se yon sèl fichye JSON. Li bon pou plizyè milye aparèy; si fichye a pwoche 50 MB, li lè pou pase sou yon vre baz done (PostgreSQL).

## 🎬 Grafik TV (abiyaj chèn nan)

Paj **Grafik TV** nan panel la, ak Preview an dirèk. Tout sove otomatikman nan `settings.gfx`; TV yo resevwa yo nan `graphics` (`GET /api/devices/:mac/config`).

| Grafik | Sa l fè |
|---|---|
| **Logo bug** | Ti logo (imaj oswa tèks) nan yon kwen, toutan. Gwosè, opasite, 6 pozisyon. |
| **Watermark** | Tèks pal sou imaj la. `{mac}`, `{customer}`, `{device}` ranplase pou chak TV. Ka chanje kwen chak 30 s. |
| **Scoreboard** | Konpetisyon, 2 ekip ak koulè, gòl (bouton +/−), tan/peryòd. |
| **Meteyo** | Vil + tanperati + ikòn. Otomatik (Open-Meteo, san kle, rafrechi chak 20 min) oswa alamen. Tès: `GET /api/galaxy/weather?city=`. "Ki vil": menm vil pou tout TV, oswa **kote chak kliyan ye** — vil aparèy la (`city`), sinon vil kliyan an (`customers.city`), sinon vil adrès IP TV a (ipwho.is, kenbe 24 è), sinon vil jeneral la. |
| **Countdown** | Konte jiska yon dat/lè (zòn lè panel la), ak yon mesaj lè l fini. |
| **Channel ident** | Logo/non chèn nan nan mitan ekran an pou kèk segonn, chak X minit ak/oswa lè kliyan an chanje chanèl. |
| **Bumper** | Ekran (tèks oswa imaj) anvan ak apre chak koupi piblisite. |

Grafik yo parèt sèlman pandan kliyan an ap gade yon chanèl live, e yo kache pandan piblisite. Kòd: `src/graphics.js`, `public/galaxy/gfx.js`.

## 📺 Customer TV (TV tès nan panel la)

Bouton **📺 Customer TV** anlè panel la limen yon TV vityèl nan navigatè a (`public/galaxy/testtv.js`). Li konekte ak backend la egzakteman tankou app Fire Stick la (MAC + Device Key, config, kòmand, estati), kidonk li parèt nan paj Aparèy ak TV an dirèk.

- San playlist: li montre ekran aktivasyon an ak MAC/Device Key li. Ajoute yon playlist sou MAC sa a.
- Ak playlist: li chaje vre lis chanèl la (`GET /api/devices/:mac/xtream/live` — se sèvè a ki rele sèvè IPTV a), ak kategori, logo, CH+/CH−.
- Li montre ticker, chyron, Grafik TV, koupi piblisite ak bumper, pop-up ak mesaj panel la voye.
- Li **pa jwe videyo** chanèl yo (navigatè a pa ka li flux IPTV yo); se pou verifye sa panel la mete sou ekran an.
