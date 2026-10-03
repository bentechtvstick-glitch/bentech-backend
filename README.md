# Galaxy TV Stick 📺🪐

App IPTV pou **Fire Stick / Android TV**, ekri an **Kotlin** ak **Media3 (ExoPlayer)**, ki sipòte **Xtream Codes** ak videyo jiska **8K**, epi ki kontwole pa panel **bentech-admin** ou a.

---

## ✨ Sa app la fè

| Fonksyon | Detay |
|---|---|
| **Aktivasyon ak MAC** | App la montre yon **MAC Address** + **Device Key**. Admin nan antre MAC la nan panel la, ajoute playlist la (non, sèvè, username, password), epi TV a konekte poukont li |
| **Style TiviMate** | App la ouvri dirèk sou TV a plen ekran; OK = lis chanèl sou videyo a ak meni **Gid TV • Playlist • Paramèt**; BACK = "Ou vle soti?" |
| **Sèvis kliyan a distans** | Panel la wè sa TV a ap gade an dirèk epi li ka chanje chanèl, voye mesaj, rechaje, redemare oswa dekonekte. TV a montre yon notis chak fwa |
| **Plizyè lang** | Kreyòl (pa defo), English, Français, Español — selon lang aparèy la, oswa **Paramèt → 🌐 Lang** |
| Plizyè playlist | Chak playlist parèt ak non li; chanje nan meni **Playlist** la |
| Lis chanèl | Kategori, 🔴 Evènman, ★ Favori (kenbe OK sou yon chanèl), Tout chanèl |
| Player 8K | Pa gen limit rezolisyon, dekodè fallback, gwo buffer, HLS (.m3u8) ak otomatik MPEG‑TS (.ts) si HLS pa mache |
| Badge kalite | Montre 8K / 4K / FHD / HD ak rezolisyon + codec (HEVC, AV1…) |
| **Lis chanèl sou videyo a** | Tankou TiviMate: peze **OK** oswa **◀** pandan w ap gade, kategori + chanèl + pwogram k ap pase parèt sou videyo a |
| **Gid TV (EPG)** | Griy chanèl × lè (2 èdtan ki deplase ak ◀ ▶), detay pwogram, peze OK pou gade chanèl la. Soti nan `xmltv.php` Xtream |
| EPG nan lis yo | Pwogram k ap pase kounye a + ba pwogresyon sou chak chanèl, "Kounye a / Apre" nan enfo player a |
| Relanse dènye chanèl | Lè app la ouvri, li jwe dènye chanèl ou t ap gade a otomatikman |
| Remòt | ▲▼ oswa CH+/CH− pou chanje chanèl, nimewo 0‑9 pou ale dirèk sou yon chanèl, ▶/INFO pou enfo, GUIDE pou gid la |
| **MAC / Device Key** | MAC la estab: li soti nan ANDROID_ID aparèy la, paske Android pa kite app yo li vrè MAC la depi Android 6 |
| **Ticker style chèn TV** | Etikèt agoch (🔴 LIVE…), koulè pou chak mesaj, separatè, lè adwat, direksyon ← / →, Active/Inactive; tout imoji yo parèt gras a font imoji ki anndan app la (EmojiCompat) |
| **Chyron pwofesyonèl** | Gwo tit + non + tit, 6 pozisyon, animasyon slide/fade/wipe, dire, opasite, koulè, sticker anime; plizyè chyron pase youn apre lòt (`ChyronView`) |
| **Animasyon** | Drapo flote, machin kouri, balon vire, kè bat… nan ticker a ak chyron an, ak yon animasyon k ap kouri sou ticker a (egz: 🏎️💨) (`EmojiFx`) |
| **Grafik TV** | Logo bug, watermark (MAC kliyan an), scoreboard, meteyo, countdown, channel ident ak bumper anvan/apre piblisite (`ui/GraphicsView.kt`) |
| **🎬 Films ak 🎞 Séries (VOD)** | Tankou TiviMate Premium: **▶ Kontinye gade**, **★ Favori** (kenbe OK sou yon afich), Tout, kategori sèvè a, **rechèch**, afich ak nòt; paj detay (rezime, aktè, dire, **Kontinye / Depi kòmansman**); seri ak sezon, epizòd, ba pwogrè, **epizòd swivan otomatik** |
| **Player VOD** | Ba tan ak kontwòl (OK), ◀ −10 s / ▶ +30 s, ⏪ ⏩ sou remòt la, pozisyon an sove chak 10 s |
| **⏪ Catch-up** | Nan **Gid TV** a, ale nan tan ki pase (◀, oswa ⏪ ⏩ pou 2 è alafwa): pwogram ki gen ⏪ jwe depi kòmansman (achiv sèvè a, jiska 7 jou). Sou yon chanèl live, ⏪ **rekòmanse pwogram k ap pase a** |
| **📺 Koupi piblisite** | Tankou chèn TV: spot yo pase plen ekran youn apre lòt (1/3, 2/3…) pandan live a, epi TV a tounen sou chanèl la. Panel la lanse yo kounye a oswa otomatikman (chak X minit / lè fiks); kliyan an pa ka chanje chanèl pandan piblisite a |
| **Panel** | Ticker, Chyron/Lower third, Banner, Pop‑up, Broadcast, Media Ads (preroll videyo/imaj + ad nan kwen), Live Events |
| **Kontwòl chanèl** | Hide/Show chanèl oswa kategori pa aparèy, limit kantite chanèl pa kliyan, bloke/aktive aparèy |

> ⚠️ **Sou 8K:** app la pa limite kalite a, men 8K ap jwe sèlman si (1) stream nan li menm se 8K, epi (2) aparèy la gen yon dekodè 8K. Pifò Fire Stick (menm 4K Max) kanpe nan **4K**. App la montre sou ekran an ki maksimòm aparèy la sipòte.

---

## 🤖 APK a san Android Studio (GitHub Actions)

1. Mete dosye `GalaxyTvStick` la nan yon repo GitHub (egz: `galaxy-tv-stick`), ak dosye `.github` la ladan l.
2. GitHub ap konstwi APK a poukont li apre chak push (onglet **Actions** → **Build APK**; oswa **Run workflow**).
3. Lè l fini (≈ 5–8 minit), klike sou travay la → anba **Artifacts** → **GalaxyTvStick-apk** → telechaje zip la → `app-debug.apk` anndan l.
4. Mete l sou Fire Stick la ak app **Downloader** oswa `adb install`.

## 🛠️ Kijan pou w fè APK a

1. Enstale **Android Studio** (vèsyon ki soti 2024 oswa pi nouvo).
2. **File → Open** → chwazi dosye `GalaxyTvStick`.
3. Si Android Studio mande pou kreye *Gradle wrapper* la, di **OK** (li pral itilize Gradle 8.7).
4. Tann Gradle fin sync.
5. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
6. APK a ap nan `app/build/outputs/apk/debug/app-debug.apk`.

### Mete l sou Fire Stick
- Aktive **Developer options → Apps from Unknown Sources** sou Fire Stick la.
- Opsyon 1: Mete APK a sou yon lyen epi telechaje l ak app **Downloader**.
- Opsyon 2: `adb connect IP_FIRESTICK` epi `adb install app-debug.apk`.

### Paramèt nan `app/build.gradle.kts`
| Paramèt | Sa l fè |
|---|---|
| `PANEL_URL` | Adrès backend panel la |
| `DEFAULT_SERVER` | Sèvè Xtream pa defo si admin nan pa mete sèvè nan playlist la |
| `ALLOW_MANUAL_LOGIN` | `false` = se panel la sèl ki ka ajoute playlist (tankou IBO); `true` = kliyan an ka antre pwòp kont li tou |

### Chanje adrès panel la
Nan `app/build.gradle.kts`:
```kotlin
buildConfigField("String", "PANEL_URL", "\"https://bentech-backend.onrender.com/api\"")
```

---

## 🧩 Panel BenTech la

Backend la (**bentech-backend**) gen tout sa app la bezwen nan `src/galaxy.js` — gade README backend la pou lis konplè endpoint yo ak kisa chak paj panel la kontwole sou TV a (Tickers, Banners, Popups, Live Events, Media Ads, Settings, Channel Profiles, Customers).

| Fichye isit la | Kote pou mete l |
|---|---|
| `panel-integration/bentech-admin/src/components/AddPlaylistForm.jsx` | Nan **bentech-admin**: fòm **Ajoute Playlist** (MAC → aparèy la parèt → Non playlist, Sèvè, Username, Password → **Cancel / Submit**) |

### Règ sekirite app la swiv
- Sèlman desizyon admin nan bloke yon TV: **blocked**, **expired** (kliyan an ekspire), **maintenance**.
- Si sèvè a pèdi done li yo (egz: Render redemare san disk), TV yo **pa dekonekte**: yo re-anrejistre tèt yo epi yo remete playlist yo t ap itilize a.
- Si admin nan **efase** yon aparèy, li pa retounen otomatikman ak ansyen playlist li.
- **MAC** ak **Device Key** soti nan ID aparèy la: yo pa chanje si kliyan an re-enstale app la.

## 📁 Estrikti pwojè a

```
app/src/main/java/com/galaxytvstick/app/
├── data/
│   ├── Models.kt        # Account, Category, Channel
│   ├── XtreamApi.kt     # Login, kategori, chanèl, URL stream
│   ├── Panel.kt         # PanelConfig + PanelApi (bentech-backend)
│   ├── EpgRepository.kt # Telechaje + li gid XMLTV la
│   └── Prefs.kt         # Kont, favori, MAC, Device Key
├── ui/
│   ├── LoginActivity.kt
│   ├── MainActivity.kt      # Kategori + chanèl + live events
│   ├── PlayerActivity.kt    # Player 8K, zapping, lis sou videyo, preroll ads
│   ├── EpgActivity.kt       # Gid TV an griy
│   ├── ChannelLists.kt      # Kategori Favori / Tout
│   ├── OverlayController.kt # Ticker, chyron, banner, ads, pop-up, broadcast, blokaj
│   ├── GraphicsView.kt      # Grafik TV: logo bug, watermark, scoreboard, meteyo, countdown, ident, bumper
│   ├── TickerView.kt
│   └── Adapters.kt
└── util/DeviceCaps.kt   # Tcheke si aparèy la sipòte 8K/4K
```
