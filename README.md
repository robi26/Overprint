# Overprint

Overprint is an Android app for viewing endurance activities: maps, heatmaps, calendars, and training stats. It does not record workouts. Import FIT / GPX / TCX files or download from Garmin Connect.

Not affiliated with Garmin.

## Requirements

| Requirement | Notes |
|---|---|
| **Android Studio** (Otter 3 / 2025.2.3 or newer) | Needed for AGP 9. Older Studio can still sync from the command line. |
| **JDK 25** | Fine for running Gradle 9.1. Android Studio can keep this as the Gradle JDK. |
| **Android SDK 35** | Platform + build-tools; Studio installs these on first Gradle sync |
| **Device or emulator** | API 26+ (Android 8.0) |

The Gradle wrapper is **9.1.0**, which supports running on JDK 25. App code still compiles to **JVM 21** bytecode — that is the newest class-file version Android’s D8/R8 dexer accepts.

Optional accounts:

- **Garmin Connect** — sign in in Settings with email and password to download activities. Garmin can change these APIs without notice.
- **FIT / GPX / TCX files** — export from Garmin Connect or copy off a watch; no account required.

## Open in Android Studio

1. File → Open → this project folder
2. Let Gradle sync (first time downloads the Android Gradle Plugin and dependencies).
3. Run the `app` configuration on an emulator or phone.

From a machine that already has the SDK:

```bat
gradlew.bat assembleDebug
gradlew.bat installDebug
```

If `local.properties` is missing, Android Studio writes `sdk.dir=...` automatically. You can also create it:

```
sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

## What’s included

- **Activities** — list, search, sport filter, Garmin refresh
- **Detail** — summary, street map with metric-coloured track, graphs, laps, HR and power zones, best rolling splits
- **Calendar** — month grid with sport-coloured dots
- **Stats** — week / month / YTD / all-time, distance trends, histograms, HR vs pace scatter, year filter
- **Health** — Garmin daily totals and all-day curves; older days whose detail charts Garmin has offloaded can be reloaded per day and are then stored locally
- **Heatmap** — all GPS tracks on OpenStreetMap, layer menu (streets / dark / none, heat, tracks), zoom filters the list
- **Settings** — metric/imperial, appearance, Garmin login, file import, demo data, max HR and FTP

First launch loads **demo activities** around Zürich so the UI is usable immediately.

## Architecture

Kotlin, Jetpack Compose, Room, OkHttp. FIT files are decoded in-app (session / lap / record messages). Garmin sync signs in through Garmin SSO and downloads activity files.

## License

MIT. See `LICENSE`.
