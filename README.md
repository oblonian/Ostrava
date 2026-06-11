# Ostrava

A Strava-style activity tracker for Android, built with Kotlin and Jetpack Compose.
Track runs, rides, walks and hikes with live GPS, then dig into splits, charts,
personal records and weekly training analytics.

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
- **Splits** — per-km (or per-mile) splits with linear interpolation at split
  boundaries and pace bars
- **Best efforts** — fastest 1k / 5k / 10k / half marathon (runs) or
  5k / 20k / 40k (rides) found with a sliding-window sweep
- **Personal records** — longest distance, longest duration, biggest climb,
  fastest average
- **Training analytics** — weekly distance chart for the last 12 weeks,
  all-time totals, per-sport filtering
- **Weekly distance goal** with progress tracking on the home feed
- **GPX export** — share any activity as a standards-compliant GPX 1.1 file
- **Calorie estimation** (MET-based, using your weight)
- **Heart rate zones** computed from your max HR
- Metric / imperial units, dark theme

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

## Building

Requires JDK 17+ and the Android SDK (compileSdk 35).

```bash
./gradlew assembleDebug   # APK at app/build/outputs/apk/debug/
./gradlew test            # JVM unit tests
```

CI builds a debug APK on every push — grab it from the workflow run's
artifacts.

## Permissions

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` | GPS track recording |
| `FOREGROUND_SERVICE_LOCATION` | Keep recording with the app in background |
| `POST_NOTIFICATIONS` | Live recording notification (Android 13+) |
| `INTERNET` | OpenStreetMap tile downloads |

Map data © OpenStreetMap contributors.
