# Noise Machine

Always-on Android noise-machine / ambient mixer that's fully controllable from a
web browser. Designed for a phone or old Android tablet sitting on a nightstand
or shelf, exposed only over Tailscale.

## What it does

- **Launchpad-style mixer.** Tap a tile and that sound starts. Tap another and
  it plays at the same time. Each tile has its own volume slider; everything
  feeds through a master volume.
- **Sounds:**
  - Procedurally synthesized: white noise, pink noise, brown noise,
    grandfather-clock ticking.
  - Bundled looped recordings (you supply the audio files): rain, ocean, wind,
    fan, fireplace, thunderstorm, forest birds, stream, coffee shop,
    ambient pad.
  - User-uploaded audio (mp3, ogg, wav, m4a, flac, opus) via the web UI.
- **Scenes.** Save the current set of active sounds (and their volumes) under a
  name, then activate the whole scene with one click or from a schedule.
- **Schedule.** Per day-of-week recurring entries that activate a scene at a
  given time. Optional "auto-stop after N minutes". Backed by `AlarmManager`
  with `setExactAndAllowWhileIdle` so it fires even in Doze.
- **Always running.** A foreground service holds a partial wake lock and only
  pauses for transient audio focus loss (i.e. an alarm, phone call). When the
  alarm finishes the noise resumes. The service does not stop on its own — you
  stop it from the web UI.
- **Auto-mute notifications.** While playing, the service can flip the device
  into "alarms only" Do-Not-Disturb so notifications don't blast through your
  ambient bed. Alarms still ring.
- **Auto-set volume.** When playback starts the service can force the music
  stream to a configured percentage of max so the level is predictable.
- **Auto-start on boot.** Re-arms schedules and (optionally) restores the last
  active mixer state after the device reboots.
- **Web UI is the source of truth.** The Android activity itself is just a
  full-screen WebView pointing at `http://127.0.0.1:<port>/`, so the on-device
  controls are identical to remote-over-Tailscale controls.

## Building

You need Android Studio Hedgehog+ or the command-line Android SDK with build
tools 34, plus JDK 17.

```bash
# from the project root
./gradlew :app:assembleRelease
# or for a debuggable build
./gradlew :app:assembleDebug
```

Note: this repo does not include the Gradle wrapper jar. Either:
- open the project in Android Studio (it will fetch the wrapper for you), or
- run `gradle wrapper --gradle-version 8.7` once with a system Gradle to
  generate `gradle/wrapper/gradle-wrapper.jar` and `gradlew`.

## Adding the bundled audio loops

This repo intentionally does not ship audio. Drop seamlessly-looped OGG
Vorbis files into `app/src/main/assets/sounds/` before building, named:

```
rain.ogg ocean.ogg wind.ogg fan.ogg fireplace.ogg
storm.ogg forest.ogg stream.ogg cafe.ogg ambient.ogg
```

Anything missing simply shows up as a disabled "missing audio file" tile.
You can also upload arbitrary audio files at runtime in the **Library** tab.

### Quick-fetch script

`tools/fetch-sounds.sh` will pull the rain loop from Pixabay and an ambient
track from YouTube straight into `app/src/main/assets/sounds/`. Requires
`yt-dlp` and `ffmpeg` on your machine.

```bash
# defaults: Pixabay "Calming Rain Loop" + Scott Buckley "The Long Dark" (CC-BY 4.0)
./tools/fetch-sounds.sh

# override either URL with anything yt-dlp can extract
./tools/fetch-sounds.sh \
  --rain    "https://pixabay.com/sound-effects/nature-calming-rain-loop-398653/" \
  --ambient "https://www.youtube.com/watch?v=<VIDEO_ID>"
```

Always check the source's license before bundling. Pixabay sound effects
are free with no attribution; CC-BY tracks (Scott Buckley, Kevin MacLeod,
etc.) require crediting the creator somewhere user-visible. Don't bundle
anything from a YouTube channel that hasn't explicitly granted download
permission — many ambient channels are full copyrighted releases despite
the "no copyright" titles. Safe sources:

- [Pixabay sound effects](https://pixabay.com/sound-effects/) — CC0-equivalent
- [Scott Buckley](https://www.scottbuckley.com.au/library/) — CC-BY 4.0
- [Kevin MacLeod / Incompetech](https://incompetech.com/music/royalty-free/) — CC-BY 4.0
- [Freesound.org](https://freesound.org) — mix; check each file
- [Free Music Archive](https://freemusicarchive.org/) — mix; check each file

## Installing & permissions

1. Install the APK (`adb install app/build/outputs/apk/debug/app-debug.apk`).
2. Open the app once. It will request **ignore battery optimizations** —
   approve. Without this Android will eventually doze the service.
3. In Android settings → **Apps → Noise Machine → Notifications →
   Notification access** (a.k.a. Notification policy access), grant access.
   Required for DND control. The web UI's Settings tab shows a warning while
   this is missing.
4. On Android 12+, in Apps → Noise Machine → **Alarms & reminders**, enable
   it. Required for exact, wake-up alarms.
5. The first time playback starts, you may also need to grant
   `POST_NOTIFICATIONS` for the persistent foreground notification.

## Connecting over Tailscale

1. Install the Tailscale app on the noise-machine Android device, sign in,
   and note the device's tailnet IP (e.g. `100.64.0.5`) or magicDNS name.
2. From any other tailnet device, open `http://<tailnet-ip>:8378` (or whatever
   port you set on the Settings tab).
3. The web server binds to `0.0.0.0`, so it's reachable on any interface that
   the Android device exposes — Tailscale, the local Wi-Fi, USB tethering,
   etc. If you only want it reachable over Tailscale, use Tailscale's MagicDNS
   and set up a stricter firewall on your network.

## Architecture

```
   ┌─────────────┐   tap / drag    ┌──────────────┐
   │  Web UI     │ ──────────────▶ │  HTTP /api   │
   │ (any browser)│ ◀── SSE state ─│  (NanoHTTPD) │
   └─────────────┘                 └──────┬───────┘
                                          │
                                  ┌───────▼────────────────────────┐
                                  │  NoiseService (foreground,     │
                                  │   wake-locked, mediaPlayback)  │
                                  ├────────────┬───────────────────┤
                                  │ Procedural │   FileVoice (×N)  │
                                  │   Mixer    │  (MediaPlayer per │
                                  │ (single    │   active loop)    │
                                  │  AudioTrack│                   │
                                  │  PCM-FLOAT)│                   │
                                  └─────┬──────┴────────┬──────────┘
                                        │               │
                                  ┌─────▼───────────────▼─────────┐
                                  │ Android STREAM_MUSIC (mixed)  │
                                  └───────────────────────────────┘
```

`AlarmManager` → `ScheduleReceiver` → `NoiseService.onStartCommand` activates
or stops scenes at scheduled times. After firing each entry, the service
re-arms the next occurrence so the schedule survives indefinitely.

## File layout

- `app/src/main/java/com/noisemachine/`
  - `audio/` — procedural voices, mixer, file voice, sound catalog
  - `state/` — persistent JSON settings, schedule manager
  - `net/` — embedded HTTP server
  - `NoiseService.kt`, `MainActivity.kt`, `BootReceiver.kt`,
    `ScheduleReceiver.kt`
- `app/src/main/assets/`
  - `web/index.html`, `web/static/style.css`, `web/static/app.js`
  - `sounds/` — drop your looped audio files here
- `app/src/main/AndroidManifest.xml` — permissions, foreground service type,
  receivers

## Notes on alarms taking priority

The service requests `AUDIOFOCUS_GAIN` with `USAGE_MEDIA`. When the system
fires an alarm clock, the alarm app requests its own audio focus, which sends
us `AUDIOFOCUS_LOSS_TRANSIENT`. The service immediately pauses every voice;
when focus is regained (alarm dismissed), playback resumes. Combined with
DND set to `INTERRUPTION_FILTER_ALARMS`, the alarm always wins.
