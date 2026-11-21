# Installing AirCav on your phone

AirCav is not on Google Play. You build it yourself and sideload the APK.
Total one-time setup takes about 30–45 minutes.

## What you need

* A computer with:
  * [Android Studio](https://developer.android.com/studio) (installs the Android SDK and NDK for you)
  * [Rust](https://rustup.rs) (stable toolchain)
  * USB cable (or Wi-Fi ADB) for your phone
* A phone running Android 8.0 (API 26) or newer
* Already done: your `taskchampion-sync-server` reachable over Tailscale

## 1. Build the native library (once)

The app embeds the real Taskchampion library; this step cross-compiles it for
phone CPUs.

```console
$ rustup target add aarch64-linux-android armv7-linux-androideabi
$ cargo install cargo-ndk
```

Point cargo-ndk at the NDK (find the path in Android Studio under
*Settings → Languages & Frameworks → Android SDK → NDK*, typically
`~/Android/Sdk/ndk/<version>`):

```console
$ export ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.0.12077973
```

Then, from the repo root:

```console
$ cd rust
$ cargo ndk -t arm64-v8a -t armeabi-v7a build --release -o ../android/app/src/main/jniLibs
```

This writes `libaircav.so` into `android/app/src/main/jniLibs/`,
where Gradle picks it up automatically.

> Only `arm64` is needed for any phone bought in the last ~10 years; `arm`
> (32-bit) is included in case you have an older device. Drop `-t arm` if you
> don't want it.

## 2. Build the APK

**Option A — Android Studio (easiest):**

1. Open the `android/` folder in Android Studio.
2. Wait for Gradle sync to finish.
3. `Build → Build App Bundle(s) / APK(s) → Build APK(s)`.
4. The APK appears under `android/app/build/outputs/apk/debug/app-debug.apk`.

**Option B — command line:**

```console
$ cd android
$ ./gradlew assembleDebug
```

## 3. Put your phone into developer mode

This is needed once so you can install apps yourself ("sideloading"):

1. Open **Settings → About phone** and tap **Build number** 7 times.
2. Back in Settings, open **Developer options** and toggle on **USB debugging** (you
   may also want **Wireless debugging** if you don't want to use a cable).

## 4. Install the APK

**With the phone connected by USB** — accept the "allow USB debugging" pop-up on your phone:

```console
$ adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Or in Android Studio, just select your phone as the run target and press ▶ Run.

**Without a cable (on the same Wi-Fi network/Tailnet):**

```console
$ adb connect <phone-tailscale-ip>:5555   # then install as above
```

You can also copy `app-debug.apk` to the phone any way you like (Tailscale
Drop, Syncthing, cloud drive, file manager) and tap it on the phone to install
(it may ask you to allow installing from that source).

To reinstall after building a new version, rerun the same `adb install -r` command.

## 5. Grant mic permission + configure the app

1. On first use, the app asks for microphone permission — allow it (only used
   when you tap the mic button).
2. Open **Settings (⚙ in the app)** and fill in:
   * **Sync server URL** — e.g. `http://100.x.y.z:8080` (your server's Tailscale IP/port)
   * **Client ID / Encryption secret** — prefilled with generated values;
     use the same values on your desktop Taskwarrior to share one task list
   * **Mistral API key** — from [console.mistral.ai](https://console.mistral.ai);
     required for the voice-input button (Voxtral)
3. Tap **Save & sync** — a "Synced" toast confirms it works.

## 6. Update / rebuild later

Any time you pull changes:

```console
$ cd android && ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If the Rust library changed: rerun step 1 first.

## Troubleshooting

| Problem | Fix |
|---|---|
| `cargo ndk` fails with NDK errors | Re-check `ANDROID_NDK_HOME` points to the exact NDK version folder |
| Gradle sync fails: "failed to find NDK" | Open the project once in Android Studio, let it install SDK 34 + NDK |
| App opens but list stays empty | No tasks yet; type `buy milk +errands` below to test |
| "sync failed" toast | Check server URL, that `taskchampion-sync-server` is running, and that you can open the URL in the phone's web browser while Tailscale is up |
| Mic says 'Set a Mistral API key' | Add your key in the app settings (from console.mistral.ai) |

## Option C — build inside a container (no host installs)

If you don't want the JDK, Android SDK, NDK, and Rust toolchain on your host at
all, build in Podman/Docker. You only need `podman` (or `docker`) itself.

```console
$ git clone https://github.com/Gijsjan/AirCav.git && cd AirCav
$ ./update.sh                                           # pull + build + install in one go
$ mkdir -p out
$ mkdir -p .android-debug   # persists the debug keystore so APK signatures stay stable
$ podman run --rm -v "$PWD":/work:Z -v "$PWD/.android-debug":/root/.android \
    -e CARGO_TARGET_DIR=/work/rust/target aircav-builder
$ ls out/app-debug.apk                                      # your APK
```

The build cache persists in `rust/target` on your checkout, so subsequent runs
are much faster.

**Installing on the phone without host adb** — enable *Wireless debugging* in
the phone's Developer options (same screen as USB debugging), then:

```console
$ podman run --rm -it -v "$PWD":/work --network host aircav-builder adb connect <phone-ip>:<port>
$ podman run --rm -v "$PWD":/work:Z --network host aircav-builder adb install -r /work/out/app-debug.apk
```

`adb` is already inside the image (`platform-tools`). With rootless Podman,
`--network host` lets the container reach your LAN; with Docker just drop it
and use the phone's IP directly. (Classic `adb install` over USB from the
container is also possible with `--device /dev/bus/usb` but device passthrough
is fiddly — wireless is the easy path.)

**Debug keystore / signature note.** The debug signing key lives in the
image-internal `$HOME/.android` — without the `.android-debug` volume mount
above, every container build generates a fresh key, and `adb install -r`
fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. If you ever hit that:
`adb uninstall dev.ruimdetijd.aircav`, then install again.
| "voice failed" toast | Check the API key, Mistral quota, and network; the temp WAV is deleted after each attempt |

## Release builds (recommended for daily use)

Debug APKs are debuggable: anyone with ADB access to the phone can read the
app's files (`run-as`) and attach a debugger. Build a signed release instead:

```console
$ keytool -genkey -v -keystore ~/aircav-release.jks -keyalg RSA \
    -keysize 2048 -validity 10000 -alias aircav
$ export AIRCAV_KEYSTORE=~/aircav-release.jks
$ export AIRCAV_KEYSTORE_PASSWORD=... AIRCAV_KEY_ALIAS=aircav AIRCAV_KEY_PASSWORD=...
$ RELEASE=1 ./update.sh          # out/app-release.apk (R8-minified, ~10x smaller dex)
```

The keystore lives outside the repo (never commit it). Keep it: all future
release updates must be signed with the same key or Android refuses the
upgrade. Release builds disable `run-as`; use the app's settings dialog
"Export voice log" button instead.

**Note before your first release install:** server-side, switch to
`tailscale serve --bg --https=443 http://127.0.0.1:8080` and set the app's sync
URL to `https://<machine>.<tailnet>.ts.net` — release builds no longer allow
plain `http://` on purpose (see README, Server setup).

## Parser unit tests

```console
$ podman run --rm -v "$PWD":/work:Z --entrypoint bash aircav-builder \
    -c "cd /work/android && gradle --no-daemon test"
```

Covers the spoken/typed input grammar (plain adds, `task add ...`,
`task <n> delete|done` with real Voxtral punctuation, project normalization,
and the regression that verb-first text must stay a plain task).
