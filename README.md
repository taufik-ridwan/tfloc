# tf-loc

A minimal Android app that lets you draw a circle on a map and randomly
spoof your GPS location to a point inside it. No Android Studio, no Google
Maps API key — uses OpenStreetMap tiles via osmdroid. Should build to
roughly 2-3 MB.

## Features

- **Set an area** — long-press the map to drop a center point, drag the
  slider to set the radius (100 m – 5 km).
- **Search** — search bar overlaid on the map, backed by OpenStreetMap's
  free Nominatim geocoding API (no key needed). Tapping a result recenters
  the map and sets that as your center point.
- **Saved profiles** — save up to 5 named areas (center + radius) locally
  on-device. Tap a profile chip to load it, long-press to delete it.
- **Enable / Disable** — one button starts/stops the mock-location service.
  The persistent notification also has a **Disable** action so you can turn
  it off without reopening the app (note: if you disable from the
  notification, the in-app button won't update its label until you reopen
  the app — it doesn't poll the service's state).

## How it works

- Tap **Enable Spoofing** once you've set a center + radius — a foreground
  service registers itself as the system's mock `GPS_PROVIDER` /
  `NETWORK_PROVIDER` and feeds it a new random point inside the circle
  every 4 seconds.
- Tap **Disable Spoofing** (in-app or via the notification) to release the
  mock providers and go back to real GPS.

## One-time device setup (required by Android)

Android will not let any app silently override GPS — you must explicitly
authorize it:

1. Enable Developer Options (Settings → About phone → tap "Build number" 7 times).
2. Settings → Developer options → **Select mock location app** → choose "tf-loc".

Without this step the app will show as active but the OS will reject the
location writes (SecurityException), and it'll stop itself.

## Building (no Android Studio needed)

You only need the command-line SDK tools + a JDK, not the full IDE.

1. Install a JDK 17.
2. Download the "command line tools only" package from
   https://developer.android.com/studio#command-line-tools-only and unzip it.
3. Set up the SDK:
   ```bash
   export ANDROID_HOME=$HOME/android-sdk
   mkdir -p $ANDROID_HOME/cmdline-tools
   # move the unzipped 'cmdline-tools' folder into $ANDROID_HOME/cmdline-tools/latest
   yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
   $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   ```
4. From this project folder:
   ```bash
   ./gradlew assembleDebug
   ```
   (first run: `gradle wrapper` if the wrapper jar isn't present — see note below)
5. Install to a connected device/emulator:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Note on the Gradle wrapper

This project doesn't include the `gradle-wrapper.jar` binary (kept out of
the source drop). Generate it once with a local Gradle install:
```bash
gradle wrapper --gradle-version 8.7
```
After that, `./gradlew` works standalone for all future builds.

## Notes / limitations

- Play Store actively flags and rejects pure mock-location apps under its
  deceptive-behavior policy — this is meant as a personal/dev-testing tool,
  sideloaded via `adb install`, not a Play Store submission.
- Many location-verified services (rideshare, delivery, anti-cheat in games)
  detect the mock-location flag on a `Location` object and can flag or
  suspend accounts that use it. Use accordingly.
- Osmdroid pulls map tiles from OpenStreetMap over plain internet — no API
  key, no billing, but does require network access to browse the map.
