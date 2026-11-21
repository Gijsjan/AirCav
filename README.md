# AirCav

A minimal, private Taskwarrior client for Android.

Named after the air cavalry: the Huey-borne soldiers who were the original
"very mobile warriors" — fast in, mission done, fast out. Under the hood it's
a full [Taskchampion](https://gothenburgbitfactory.org/taskchampion/) replica.

**[How to get this app on your phone → INSTALL.md](INSTALL.md)** — build the Rust
library, assemble the APK, and sideload it over ADB (no Google Play involved).

It is a small, focused alternative to the "Taskwarrior Flutter" app on Google Play:

* List tasks and filter by context (any tag: `+home`, `+work`, ...)
* Add tasks by typing; complete or delete with one tap
* Add tasks by voice (on-device speech recognizer)
* Real **Taskchampion** replica embedded in the app (SQLite on device), full offline support
* Syncs directly to your own `taskchampion-sync-server` over Tailscale — no cloud, no Google Play services, no third party

## Requirements

* A machine (desktop/home server) that runs both:
  * [Tailscale](https://tailscale.com) (the phone must be able to reach the machine's Tailscale IP, e.g. `100.x.y.z`)
  * [taskchampion-sync-server](https://github.com/GothenburgBitFactory/taskchampion-sync-server)
* Android Studio (with Android SDK + NDK), Rust toolchain

## Server setup (one time)

On your desktop/home server:

```
# install the sync server, e.g. via cargo or your distro, then:
taskchampion-sync-server --listen 0.0.0.0:8080 --data-dir ~/syncdata
```

Make sure Tailscale is up on both the server and the phone. From the phone's browser, `http://<server-tailscale-ip>:8080` can be reached. That same address goes into the app settings.

Note: the sync protocol encrypts all task data with your own `encryption_secret` before it leaves this app, so plain HTTP over Tailscale is still end-to-end encrypted from the server's point of view.

## Build the native library (Rust)

The app embeds the real [Taskchampion](https://gothenburgbitfactory.org/taskchampion/) library as a JNI library (see `rust/`). Build it once with the Android NDK:

```
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
cargo install cargo-ndk

cd rust
cargo ndk -t arm64-v8a -t armeabi-v7a build --release \
  -o ../android/app/src/main/jniLibs
```

## Build the app

Open the `android/` folder in Android Studio and press Run, or:

```
cd android
./gradlew assembleDebug
```

The debug APK is not on the Play Store; sideload it (`adb install app/build/outputs/apk/debug/app-debug.apk`).

## Configure

In the app's settings screen:

* **Sync server URL** – e.g. `http://100.x.y.z:8080`
* **Client ID** – pre-generated UUID; use the same ID on all replicas that share one task list
* **Encryption secret** – any long random string; must match the one you use elsewhere (`task config sync.encryption_secret`)

If your desktop Taskwarrior also syncs to this server with the same client ID and secret, tasks created in the app appear on the desktop and vice versa.

## Usage

* Type a line like `buy milk +home` — words starting with `+` or `@` become tags/contexts, the rest becomes the description.
* Tap the mic icon and speak a task — on-device recognition, never uploaded
  (speak tags like "+home" the same way as typing).
* The horizontal filter chips list all tags/contexts present in your tasks; tap one to filter.
* Checkbox completes a task; trash icon deletes (status Deleted) it.
* The cloud button forces a manual sync; every change is also synced automatically if a server URL is configured.

## Test on Linux (no phone needed)

Everything except the Compose UI runs on a normal Linux box, so you can verify
sync before touching Android:

```console
$ cd rust
$ cargo test                                    # replica sync round-trip
$ cargo build                                   # builds aircav-cli + the JNI lib
$ cargo install taskchampion-sync-server
$ taskchampion-sync-server --listen 127.0.0.1:8123 --data-dir /tmp/tcs

# in another terminal, two replicas against the real HTTP server:
$ ./target/debug/aircav-cli /tmp/rep-a add "test task +linux"
$ ./target/debug/aircav-cli /tmp/rep-a sync http://127.0.0.1:8123 <uuid> <secret>
$ ./target/debug/aircav-cli /tmp/rep-b sync http://127.0.0.1:8123 <uuid> <secret>
$ ./target/debug/aircav-cli /tmp/rep-b list             # sees the task, tags, done/remove, resync
```

These are the exact operations the Android app performs; the phone just calls
them through JNI. The end-to-end HTTP test above against a live sync server is
also what your Tailscale setup will do, with `127.0.0.1` replaced by the
tailnet IP.

For the **UI itself**, run the app in the Android emulator on your Linux
machine (Android Studio → Device Manager → create a virtual phone → Run). No
Google account is required, and you can point the emulator's sync URL at
`10.0.2.2` to reach a sync server running on the same Linux host.

## Voice input & privacy

Voice input uses the phone's **on-device speech recognition only**
(`EXTRA_PREFER_OFFLINE`). No Mistral, no LLM, no cloud speech service, and no
fallback: your voice never leaves the phone. Speech becomes text with the same
tag parsing as typed input (`+tag` spoken as "plus tag" works too).

If the device has no offline speech model installed, tapping the mic shows a
message instead of sending audio anywhere. (Most phones can download the
offline model under Settings → System → Languages & input → Speech services.)

Your task list never leaves your devices except as end-to-end-encrypted
blobs to your own sync server.

## Project layout

* `rust/` – JNI cdylib wrapping `taskchampion` 2.x: open/list/add/complete/remove/sync
* `android/` – Kotlin + Jetpack Compose app (no Jetpack internals beyond Compose + Activity/XML manifest)
