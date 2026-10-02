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

