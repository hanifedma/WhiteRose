# White Rose

A Wear OS app that buzzes your wrist **once every minute, on the minute**, and does nothing else.

It runs in the background, never takes audio focus, never opens a screen, and never interferes
with other apps. The only trace it leaves is a silent, minimum-priority notification — which
Android requires for any app that wants to keep running in the background.

Built for the **Samsung Galaxy Watch 7 (40 mm)**, Wear OS 5 / One UI Watch 6.

---

## What it does

| | |
|---|---|
| **Cadence** | Fixed at 60 seconds, anchored to the wall clock — an alert lands at :00 of every minute, and never drifts |
| **Alert by** | Vibration, a Casio-style beep, or both together |
| **Strength** | 5 levels (Whisper → Maximum), mapped to real motor amplitude and beep volume |
| **Pattern** | Single / Double / Triple / Long |
| **Length** | Short / Medium / Long pulse duration |

Strength, Pattern and Length are remembered **per output**. A long, slow buzz and a short, sharp
beep are both reasonable choices, and neither should overwrite the other — so switching *Alert
by* restores whatever that output was last set to. In **Both** mode the two appear as separate
labelled groups, and each fires with its own settings.
| **Quiet hours** | Optional window (e.g. 23:00–07:00) where it stays silent but stays armed |
| **On restart** | Comes back by itself after the watch is powered off and on — **on by default** |
| **Exact timing** | Optional wake lock for "never misses, uses more battery" |
| **Ignore DND** | On by default, so Do Not Disturb doesn't silently stop it |

Changing any of these fires the alert immediately, so you feel or hear the change as you make it.

### The beep

The tone is **4096 Hz** — not arbitrary. A Casio digital watch divides its 32.768 kHz quartz
crystal by 8, which lands exactly there, so it is the pitch of the button-press beep on an F-91W.
Because those watches drive a piezo element with a square wave, the tone is bright and thin
rather than round, which is reproduced here by adding the 3rd and 5th harmonics. The envelope is
a hard gate with ~1 ms edges: the abrupt start and stop are what make it read as a *click*
rather than a tone.

It is synthesised at playback rather than shipped as an audio file, which means Pattern and
Length apply to sound exactly as they do to vibration — a "Double" beep lands on the same edges
as a "Double" buzz, and in **Both** mode the two are measured firing 6 ms apart. It also means
the APK carries no audio asset.

The beep plays with `USAGE_ALARM` (or `USAGE_NOTIFICATION` when *Ignore DND* is off) and
deliberately **does not take audio focus** — a once-a-minute chirp should mix over your music,
not pause it. If the relevant volume is at zero, or the watch has no speaker, the app says so
in a row under *Alert by* instead of failing silently.

## Installing on the watch

You need the PC and the watch on the **same Wi-Fi network**.

### 1. Turn on developer mode (on the watch)

1. **Settings → About watch → Software info**
2. Tap **Software version** 7 times, until it says developer mode is on
3. Go back to **Settings → Developer options**
4. Turn on **ADB debugging**
5. Turn on **Debug over Wi-Fi** (also called Wireless debugging)

### 2. Understand the two ports

This is the step that catches everyone. Wireless debugging uses **two different ports**, and
they are not interchangeable:

| Port | Where the watch shows it | Lifetime |
|---|---|---|
| **Pairing port** | Only inside the *Pair new device* popup, next to the 6-digit code | One-shot — closes the instant pairing finishes |
| **Connect port** | On the main *Wireless debugging* screen, under *IP address & Port* | Until debugging is toggled off or the watch reboots |

`adb pair` establishes trust. It does **not** attach the device. If you pair and then go
straight to `adb install`, you get `no devices/emulators found` — pairing succeeded, but nothing
is connected yet.

### 3. Pair, connect, install (on this PC)

Make sure `adb` is the SDK one. Mixing binaries causes
`adb server version (41) doesn't match this client (39)`:

```bash
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"   # add to ~/.bashrc to make it stick
adb version                                            # expect 1.0.41 or newer
```

Pairing is only needed the **first** time for a given watch:

```bash
adb pair 10.96.100.123:39731     # PAIRING port, from the popup; enter the 6-digit code
```

Then connect, using the port from the **main** Wireless debugging screen:

```bash
adb connect 10.96.100.123:35341  # CONNECT port — a different number
adb devices                      # must show "device", not "offline"
cd ~/AndroidStudioProjects/WhiteRoseApp
adb install -r app/build/outputs/apk/release/app-release.apk
```

Every later session is just the `adb connect` line — the pairing is remembered. But re-read the
connect port each time, because it changes whenever wireless debugging is toggled or the watch
restarts.

**If `adb devices` says `offline`**, or `adb install` says `more than one device`, drop the stale
entries and reconnect:

```bash
adb disconnect                   # drops all network devices
adb connect 10.96.100.123:35341
```

Both the watch and the PC must be on the same network, and some networks (guest or campus Wi-Fi
with client isolation) block device-to-device traffic entirely. `ping <watch-ip>` is the quick
way to tell whether the watch is reachable at all.

### 4. First run (on the watch)

1. Open **White Rose** from the app list
2. Tap the rose in the middle — the ring lights up and the countdown starts
3. Allow **notifications** when asked (the app runs without it, but you lose the status chip)
4. Scroll down to **Battery** and tap **Tap to allow** → choose **Allow / Don't optimise**

### 5. One extra Samsung step — important

One UI puts idle apps to sleep, which will kill the pulse. On the **watch**:

**Settings → Battery → Background usage limits** → make sure **White Rose** is *not* in
*Sleeping apps* or *Deep sleeping apps*. If it is, remove it.

That, plus the battery step above, is what keeps it buzzing overnight.

## Rebuilding after a change

```bash
cd ~/AndroidStudioProjects/WhiteRoseApp
JAVA_HOME=~/android-studio/jbr ./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Signing

The APK is signed with `keystore/whiterose.jks`, and the credentials live in
`keystore.properties`. **Both are gitignored and neither is in this repository.**

That key is what lets a new build install *over* the one already on your watch. Keep a private
backup of it somewhere that is not a public repo — if it is lost, the next build is signed by a
different key and the watch will refuse the update until you uninstall the app first.

Without `keystore.properties` the project still builds; the release APK just comes out unsigned.
To make a fresh key:

```bash
keytool -genkeypair -v -keystore keystore/whiterose.jks -storetype PKCS12 \
  -alias whiterose -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=White Rose, O=White Rose"
```

then write `storeFile`, `storePassword`, `keyAlias` and `keyPassword` into `keystore.properties`.

## How it stays reliable

Two independent paths lead to the same buzz, and a per-minute claim makes sure only one of them
can actually fire in any given minute:

1. **An exact alarm** (`setExactAndAllowWhileIdle`, `RTC_WAKEUP`) scheduled for the next :00.
   Each tick arms the next one, so the chain is self-healing. This is what works when the
   process has been swapped out and the screen has been off for hours.
2. **A timer inside the foreground service**, which is what covers the case where the alarm is
   deferred.

The app holds the `USE_EXACT_ALARM` permission, so the alarms are genuinely exact rather than
batched.

`BootReceiver` re-arms everything on `BOOT_COMPLETED` (and Samsung's `QUICKBOOT_POWERON`), on
app update, and whenever the clock or time zone changes.

### Measured on a Wear OS emulator

| Check | Result |
|---|---|
| Accuracy | Alerts land 1–8 ms after the minute boundary, never twice in a minute |
| Alert modes | Vibrate buzzes only; Beep beeps only; Both fire 6 ms apart on the same tick |
| Per-output settings | Vibration held at Long/Long while Beep stayed Double/Medium; switching back and forth restored each |
| Deep Doze, no battery exemption | 4 buzzes in 4 minutes — survives |
| Deep Doze, battery exempt | 4 buzzes in 4 minutes — survives |
| Reboot | Service and alarm back up unattended; buzzing resumed at the first minute after boot |
| Switching off | Service stopped, alarm cancelled, zero buzzes afterwards |
| Settings after process death | Strength, pattern and length all survive |

## The two settings people ask about

### Exact timing

**Leave it off.** It holds a partial wake lock so the CPU never sleeps, which guarantees the
cadence but runs the processor 24/7 — by far the most expensive thing this app can do.

You almost certainly do not need it. The exact alarm alone was measured delivering every single
minute even in forced deep Doze, so this is insurance against an unusually aggressive power
manager, not a normal setting. Turn it on only if you actually catch minutes going missing, and
turn it back off afterwards. It automatically releases during quiet hours, since nothing is
going to buzz then anyway.

### Battery

Android's battery optimiser is allowed to defer background work. This button asks to be exempt
from it.

Tapping it opens the best screen the watch actually has — on Wear that is usually the app's own
settings page, where the battery and permission options live. It is **not** a one-tap switch on
every watch. If the watch has no such screen at all, the row says *Set it in watch Settings*
instead of pretending to work.

Two honest caveats:

- The app already survives Doze without this, so it is belt-and-braces.
- On a Galaxy Watch the setting that actually matters is Samsung's own, which is separate from
  Android's: **Settings → Battery → Background usage limits**, where White Rose must not be
  listed under *Sleeping apps* or *Deep sleeping apps*.

## Battery cost

The dominant cost is the vibration motor itself, and that is inherent to the feature: roughly
1,440 buzzes a day, about 240 ms each at the default pattern — call it six minutes of motor per
day. Lower **Strength** and a shorter **Length** genuinely reduce it; nothing in software can.

Around that, the app is built to stay out of the way:

- **One wakeup per minute, not two.** The in-service timer only runs in *Exact timing* mode.
  Otherwise the exact alarm alone drives everything, so there is no duplicated tick or duplicate
  alarm re-arm each minute.
- **No polling while you are not looking.** The countdown ring and the permission checks are
  tied to window focus, so they stop the moment the screen goes dark instead of running on
  against a black display.
- **No wake lock unless asked for**, and none at all during quiet hours.
- **No network, no wakeful services, no background scanning.** The app has no `INTERNET`
  permission at all.
- The ongoing notification sits on an `IMPORTANCE_MIN` channel and is only redrawn when
  something actually changes, not every minute.

## Project layout

```
app/src/main/java/com/whiterose/minute/
  core/
    PulseScheduler.kt   exact alarms, anchored to wall-clock minutes
    Pulser.kt           the one place a buzz can happen; dedupes per minute
    PulseService.kt     foreground service, silent notification, optional wake lock
    PulseReceiver.kt    alarm tick -> buzz -> re-arm
    BootReceiver.kt     restart after boot / update / clock change
    Alerter.kt          one entry point for "announce this minute"
    Haptics.kt          strength, pattern and length -> VibrationEffect
    Beeper.kt           the same settings -> synthesised 4096 Hz PCM, played via AudioTrack
  data/Settings.kt      SharedPreferences-backed settings exposed as a StateFlow
  ui/                   Wear Compose: countdown ring + single scrolling settings list
```
