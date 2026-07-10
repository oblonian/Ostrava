# Ostrava

A Strava-style activity tracker for **Android** (Kotlin + Jetpack Compose) and
**iOS** (Swift + SwiftUI). Track runs, rides, walks and hikes with live GPS,
then dig into splits, charts, personal records and weekly training analytics.
Both apps share the same feature set and data formats (GPX, JSON backup).

## Features

**Recording**
- Live GPS tracking for Run / Ride / Walk / Hike via a foreground service
  (keeps recording with the screen off, live stats in the notification)
- Live map (OpenStreetMap via osmdroid — no API key required), distance,
  moving time, pace/speed and elevation gain
- GPS accuracy indicator and noise filtering (accuracy gating, minimum
  displacement, elevation hysteresis)

**Pro features**
- **Auto-pause** — the clock stops when you stop, with per-sport thresholds
- **Audio cues** — spoken split announcements (time + last-km pace) via TTS
- **Interval workouts** — warmup / N×(work+rest) / cooldown with voice and
  vibration guidance and a live phase banner
- **Bluetooth heart rate** — standard BLE HR straps; live bpm while recording,
  avg/max stored per activity
- **Segments** — save any route as a segment; future activities covering it
  are timed automatically and ranked against your best
- **Splits** — per-km (or per-mile) splits with linear interpolation at split
  boundaries and pace bars
- **Best efforts** — fastest 1k / 5k / 10k / half marathon (runs) or
  5k / 20k / 40k (rides) found with a sliding-window sweep
- **Personal records** — longest distance, longest duration, biggest climb,
  fastest average
- **Training analytics** — weekly/monthly distance charts, streaks,
  training-load ramp warning, all-time totals, per-sport filtering
- **Weekly distance goal** with progress tracking on the home feed
- **Route thumbnails** on every feed card, drawn straight from the GPS track
- **Share image** — rendered summary card (route + stats) for social media
- **GPX export & import**, plus full **JSON backup/restore** of all data
- **Calorie estimation** (MET-based, using your weight)
- **Heart rate zones** computed from your max HR
- Perceived-effort rating on save, 3-2-1 start countdown, keep-screen-on,
  discard confirmation, first-run onboarding
- Metric / imperial units, dark theme incl. dark map tiles

## Architecture

- **UI**: Jetpack Compose + Material 3, Navigation Compose, MVVM with
  `StateFlow`
- **Tracking**: foreground `Service` + FusedLocationProvider; state shared with
  the UI through a process-wide `TrackingStateHolder`
- **Persistence**: Room (activities + GPS track points), DataStore preferences
  for settings
- **Maps**: osmdroid (OpenStreetMap tiles)
- **DI**: lightweight manual container (`AppContainer`)

```
app/src/main/java/com/ostrava/app/
├── data/          Room database, DAOs, repositories, DataStore settings
├── di/            AppContainer
├── domain/        Models, split/best-effort/calorie calculations, formatters
├── export/        GPX export + share
├── tracking/      Foreground tracking service + shared recording state
└── ui/            Compose screens: feed, record, detail, stats, profile
```

## iOS app

`ios/` contains the native SwiftUI variant (iOS 17+): CoreLocation tracking
with auto-pause and background recording, MapKit route maps, SwiftData
persistence, AVSpeech voice cues, CoreBluetooth heart-rate sensors, interval
workouts, segments, Swift Charts analytics, GPX import/export, JSON
backup/restore and a share-image renderer.

```bash
cd ios
brew install xcodegen
xcodegen generate      # produces Ostrava.xcodeproj
open Ostrava.xcodeproj # set your signing team, then run on a device
```

Running on a physical iPhone requires signing with your Apple ID in Xcode
(Signing & Capabilities → Team). CI builds the app for the iOS Simulator on
every push and uploads it as the `ostrava-ios-simulator` artifact.

## Building (Android)

Requires JDK 17+ and the Android SDK (compileSdk 35).

```bash
./gradlew assembleDebug   # APK at app/build/outputs/apk/debug/
./gradlew test            # JVM unit tests
```

CI builds a debug APK on every push — grab it from the workflow run's
artifacts or the `apk-dist` branch.

## Permissions

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` | GPS track recording |
| `FOREGROUND_SERVICE_LOCATION` | Keep recording with the app in background |
| `POST_NOTIFICATIONS` | Live recording notification (Android 13+) |
| `INTERNET` | OpenStreetMap tile downloads |

Map data © OpenStreetMap contributors.
