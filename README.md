# Kenya AgroWeather (Android)

Kenya-focused, location-specific weather and El Niño tracking with decision support for precision agriculture
(spraying, irrigation, soil moisture, field work). Kotlin + Jetpack Compose, minSdk 26.

## Build
1. Open the folder in Android Studio (Ladybug or newer, JDK 17) and let it sync, or run `./gradlew assembleDebug`.
2. Run unit tests with `./gradlew testDebugUnitTest` (JVM-only, no emulator needed).

Kenya tailoring: place search limited to Kenya, one-tap quick picks for 17 agricultural towns/counties, Kenyan season labels (long rains, short rains) on the dashboard, El Niño guidance framed for Kenyan seasons, KMD/ICPAC cross-check prompts.

## Precise location (about 5-10 m)
- **Pick on map:** full-screen OpenStreetMap, zoom to level 19; tap to drop the pin. Coordinates are stored to 5 decimals (about 1 m).
- **GPS (high accuracy):** requests a fresh fine-location fix and shows the reported accuracy circle; warns if worse than 10 m. Real-world GPS accuracy depends on the phone and sky view (typically 3-10 m in the open, worse under canopy or indoors).
- **Tap-to-check (Radar tab):** tap anywhere on the map for conditions at that spot, then optionally save it as a farm.
- Point forecasts are requested at 4 decimals (about 11 m) so Open-Meteo applies its terrain-elevation temperature correction for the exact pin. The weather model grid itself is several km wide, so wind/rain do not differ between pins a few metres apart.

## Build the APK in the cloud (GitHub Actions)
1. Create a new GitHub repository and push this folder (`git init && git add . && git commit -m "Kenya AgroWeather" && git branch -M main && git remote add origin <your-repo-url> && git push -u origin main`).
2. Open the repository's **Actions** tab. The "Build Kenya AgroWeather APK" workflow runs on every push (or press **Run workflow**).
3. When it finishes, open the run and download **kenya-agroweather-debug-apk** from the Artifacts section. Unzip it and install `app-debug.apk` on your phone.
4. If the run fails, open the failed step's log (or download the **reports** artifact) and send me the error text.

The debug APK is signed with a debug key: fine for testing, not for Play Store release.

## Cloud component (rain cross-check)
Weather-model rain fields often miss small convective showers, which drive most afternoon rain in Kenya. The app now also reads
**low / mid / high cloud cover and CAPE (storm energy)** from Open-Meteo and cross-checks the model's rain signal (`domain/RainRisk.kt`):
- **Cloud & rain confidence** card and a 24-hour strip on Today. A "!" marks hours where cloud and storm energy favour rain that the model's rain field does not show ("CLOUD ALERT"); "ℹ" marks modelled rain under a clear-looking sky (possible over-forecast).
- Cloud shading on the 48-hour chart, cloud % and warnings in the hourly rows, average cloud and storm-energy notes in the daily rows.
- A "Thunderstorm risk" alert when CAPE is high (>= 1500 J/kg) and cloud is building in the next 24 h.
- Spray advice uses the raised chance, so spraying is held when the model is dry but the sky is loaded.
- **Safety rule:** the cloud check can only add caution. It never lowers the model's rain chance and never changes forecast millimetres (so irrigation/water balance are unaffected).
- **Limits:** the weights are physically motivated heuristics, not fitted to Kenyan rain-gauge data. Calibrate against local gauge records (e.g. KMD stations) before relying on the percentages. Satellite cloud imagery is not included (no verified free tile source).

## Screens
| Tab | What it gives you |
|---|---|
| Today | Alerts, current conditions, and four traffic-light decisions: Spraying, Irrigation, Soil moisture, Field work. Tap a card for the reasoning. 24-hour spray strip. |
| Forecast | Segmented by horizon: 48 hours (chart + hourly rows), 7 days, 14 days (rain vs normal, ET0, irrigation plan, day rows) and Monthly (seasonal outlook with anomalies vs a 2011-2025 baseline). |
| Radar | Animated RainViewer rain radar over OpenStreetMap, centred on the farm; wind and temperature at the farm. |
| El Niño | NOAA ONI status, phase/strength/trend, 12-season chart, Kenya-specific impacts and actions, farm seasonal outlook. |
| Farms | Up to any number of farms: place search, GPS, crop, soil, planting date, optional probe reading, alert toggle. |

## Data sources (all free, no API keys)
- Open-Meteo forecast (hourly + daily, 16 days, soil moisture/temperature, ET0), seasonal (ECMWF, to ~7 months), ERA5 archive (baseline), geocoding
- RainViewer radar composite (past ~2 h + short nowcast)
- NOAA CPC Oceanic Nino Index (oni.ascii.txt)

## Decision logic (see `domain/`)
- Spray: wind 3-15 km/h, gusts < 30, no rain within 3 h, daylight, T < 28-32 C, Delta-T 2-8 (Stull wet-bulb). Windows >= 2 h.
- Irrigation: FAO-56 root-zone water balance. Dr += Kc x ET0 - effective rain; irrigate when Dr >= p x TAW. Crop Kc/stage lengths/root depth per crop; soil FC/WP per soil type.
- Soil start point: probe reading (valid 3 days) > model soil layers (thickness-weighted 0-27 cm) > assumption. The UI states which was used.
- Alerts (WorkManager, ~3 h): downpour, heavy rain, 3-day flood risk, gusts, heat, frost, dry week, irrigation due. De-duplicated per event.
- Monthly: seasonal ensemble daily values aggregated per month; anomaly vs ERA5 baseline.

## Honest limitations
- Radar measures rain, not wind or temperature. Wind/temperature here come from the weather model, which assimilates radar, satellite and station data. Radar coverage over East Africa is patchy.
- Seasonal and El Nino guidance shifts odds; it is not a local guarantee. Always cross-check KMD / ICPAC.
- Crop and soil parameters are FAO-56 defaults; calibrate with local data (field capacity, wilting point, Kc) before relying on volumes.
- Open-Meteo's free tier is for non-commercial use; check their terms before commercial distribution. OSM tiles have a usage policy: use a commercial tile provider at scale.
- Not yet verified: this project was developed in a sandbox without the Android SDK, so the Android/Compose layers have not been compiled or run. The pure-Kotlin core (models, parsers, domain, repository) is covered by 52 JVM tests. Expect to fix a few compile-time nits on first sync.


## Comparing weather models side by side

Forecast tab -> **Models**. The app asks several independent models (ECMWF IFS, GFS, ICON, UK Met Office) for the
same pin and shows, per day, each model's rain total, max temperature and peak wind, how many models call the day wet
(1 mm or more), the min-max spread, and a verdict: all dry / all wet / models disagree.

- Each model is requested separately, so one the service rejects shows as "Unavailable" instead of breaking the rest.
- Model ids come from Open-Meteo's example URLs (`ecmwf_ifs`, `ncep_gfs_seamless`, `dwd_icon_seamless`, `ukmo_seamless`);
  edit `WeatherModel.DEFAULTS` to add others. The response parser accepts both `<variable>_<model>` and plain column names.
- A model that does not cover a whole day is left out of that day rather than counted as dry.
- This is comparison only: the Today/spray/alert logic still uses the primary forecast with the cloud cross-check.


## More sources, charts and screen sizes

**Compared in the Models tab:** four Open-Meteo models (ECMWF IFS, GFS, ICON, UK Met Office), **MET Norway**
(api.met.no, free, no key) and **Pirate Weather** (free key, entered in the app and kept only on the phone).
Set your own contact in `OkHttpFetcher.USER_AGENT`: MET Norway rejects anonymous or generic agents.
Charts: cumulative rain per model over 7 days (where lines fan out, models disagree), temperature / wind / cloud lines
over 72 h, and grouped daily-rain bars. Colour-blind-safe colours plus different dash patterns.

**Meteosat:** Radar tab -> satellite chips (infrared cloud, cloud-top height) from EUMETSAT's public EUMETView WMS,
layers `msg_iodc:ir108` and `msg_iodc:cth`. Raw Meteosat files need an EUMETSAT account and heavy processing, so they are not used.

**Not included:** TAHMO (needs a key and secret from DKUT and its endpoints are not publicly documented) and the FASTA
API (documentation not public). Both are observation/nowcast sources that would slot into the same comparison once you have access.

**Screen sizes:** bottom bar on phones, side rail from 600 dp wide (tablets, landscape, foldables), content centred with a
readable maximum width, chip rows scroll sideways, the map grows on large screens, and the activity is resizable (split-screen).

## TAHMO ground-station check

Forecast tab -> Models -> "TAHMO credentials". Enter the API key name and secret/token you got from TAHMO / DKUT; they are
stored only on the phone and sent only to `datahub.tahmo.org` as HTTP Basic credentials in the Authorization header
(never in a URL, cache key or error message). The app finds the TAHMO station nearest your pin, reads its quality-checked
rain (`pr`) for yesterday and today so far, and shows each model's forecast for the same period with the error.

Built from TAHMO's own API v2 Python examples (github.com/TAHMO/API-V2-Python-examples). Unverified assumptions:
station coordinates are read from `location.latitude/longitude` (or top-level `latitude/longitude`), and each `pr` record
is treated as mm for its interval. Check both against your first real response.
