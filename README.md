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
