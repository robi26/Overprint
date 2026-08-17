# ConnectStats for Android

Android port of [ConnectStats](https://github.com/roznet/connectstats), Brice Rosenzweig’s iOS activity viewer for Garmin Connect. Original product page: [ro-z.net/connectstats](https://ro-z.net/connectstats/).

This is not affiliated with Garmin. It does not record workouts. It displays activities you import or download.

## What you need

### To build the original iOS app

You cannot compile the iOS tree on Windows. On a Mac:

1. Xcode with the **iOS 18.6** SDK (the Podfile pins `platform :ios, '18.6'`).
2. [CocoaPods](https://cocoapods.org/): `pod install` in the cloned repo.
3. Copy `ConnectStats/credentials.sample.json` to `credentials.json` and fill keys:
   - **Garmin Health API** OAuth 1.0 consumer key/secret (official ConnectStats service)
   - **Strava** OAuth client id/secret
   - **Google Maps** API key
   - Optional: Withings, Flurry, App Store ids
4. Open `ConnectStats.xcworkspace` (not the `.xcodeproj`).
5. Optional backend: [connectstats_server](https://github.com/roznet/connectstats_server) (PHP + MySQL) if you want the official Garmin Health API path instead of the Garmin website login.

Related libraries: [FitFileParser](https://github.com/roznet/FitFileParser), [RZUtils](https://github.com/roznet/rzutils).

Without API keys the iOS app can still use **Garmin Connect website** username/password download.

### To build this Android app

| Requirement | Notes |
|---|---|
| **Android Studio** (Otter 3 / 2025.2.3 or newer) | Needed for AGP 9. Older Studio can still sync from the command line. |
| **JDK 25** | Fine for running Gradle 9.1. Android Studio can keep this as the Gradle JDK. |
| **Android SDK 35** | Platform + build-tools; Studio installs these on first Gradle sync |
| **Device or emulator** | API 26+ (Android 8.0) |

The Gradle wrapper is **9.1.0**, which supports running on JDK 25. App code still compiles to **JVM 21** bytecode — that is the newest class-file version Android’s D8/R8 dexer accepts.

Optional accounts:

- **Garmin Connect** — sign in inside the app (Settings) to download from the same website APIs the iOS app uses. Garmin can change this without notice.
- **FIT / GPX / TCX files** — export from Garmin Connect or copy off a watch; no account required.

Official **Garmin Health API** still needs a Garmin developer registration and a server like `connectstats_server`. That path is not wired in this first Android release.

## Open in Android Studio

1. File → Open → `C:\temp\connectStats\ConnectStatsAndroid`
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

## What this port includes

Tabs match the iOS app:

- **Activities** — list, search, sport filter, refresh
- **Detail** — summary, coloured map (HR / speed / power / cadence / elevation / grade), graphs, laps, time in HR and power zones, best rolling splits, stride and kJ
- **Calendar** — month grid with sport-coloured dots
- **Stats** — week / month / YTD / all-time, weekly and monthly distance, histograms, HR vs pace scatter
- **Settings** — metric/imperial, Garmin login, file import, demo data, max HR and FTP

First launch loads **demo activities** around Zürich so the UI is usable immediately.

## What is not a 1:1 clone yet

The iOS project is ~15 years of Objective-C/Swift (700+ sources), SQLite, HealthKit, Withings, Apple Watch, and the ConnectStats PHP backend. Still to port:

- HealthKit / Health Connect weight overlay
- Withings
- Multi-profile Garmin accounts
- Rename-on-Garmin, Google Earth share
- Swim-stroke colouring and full Critical Power plots
- Official Garmin Health API + `connectstats_server`

## Architecture

Kotlin, Jetpack Compose, Room, OkHttp. FIT files are decoded in-app (session / lap / record messages). Garmin website sync reuses a WebView session cookie against the same `connect.garmin.com/modern/proxy/...` endpoints as the iOS `GCWebUrl` helpers.

## License

MIT, same as the original ConnectStats project.
