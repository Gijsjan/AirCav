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

On your desktop/home server — hardened:

```
# bind only to localhost; tailscale serve provides TLS + tailnet-only reachability
taskchampion-sync-server --listen 127.0.0.1:8080 --data-dir ~/syncdata \
    --allow-client-id <your-client-id-uuid>

# publish it on your tailnet with a valid HTTPS cert:
tailscale serve --bg --https=443 http://127.0.0.1:8080
```

Use `https://<machine>.<tailnet>.ts.net` as the sync URL in the app (check
`tailscale status` / `tailscale cert` for the exact name). Do NOT use the raw
`http://100.x.y.z` form: when Tailscale is off, the 100.64.0.0/10 range belongs
to carrier-grade NAT, so requests could reach a random carrier host (task data
stays encrypted, but your client ID would leak).

Server flags worth knowing:
 * `--allow-client-id <uuid>` (repeatable): only these client IDs may sync —
   nobody else can create a replica or push junk versions on your server
 * `--no-create-clients`: refuse unknown clients instead of auto-registering
 * binding to `127.0.0.1` keeps the server off your LAN entirely

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
* Tap the mic, speak, tap again — transcribed by Voxtral and applied as a
  taskwarrior-style command:
  - `task add buy milk` — add (or plain text without the `task` prefix)
  - `task 23 delete` / `task 23 done` — operate on a task by number
    (commands need the `task` prefix; unprefixed text is always an add)
  Say `project nde` in the task text to set the project (context chips).
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

## Contexts

Taskwarrior contexts are *filters in `~/.taskrc`* (e.g. `context.nde=project:nde`),
and that config does not sync with tasks. AirCav lets you define them in
Settings instead: one per line in the form `name=project`, e.g.

```
nde=nde
codere=rdt.codere
home=home
```

Each defined context becomes a filter chip matching tasks with that project.
If no contexts are defined, the chips fall back to the `+tags` on your pending
tasks (and typed input `+tag` sets a tag; Taskwarrior shows tags as `+name`).

## Voice log

Every voice attempt is appended to `files/voice-log.jsonl` inside the app's
private storage: one JSON line with a timestamp, the raw transcript, the
parsed task (description, tags, project), or the error if it failed. Inspect
it from your computer:

```console
$ ./adb shell run-as dev.ruimdetijd.aircav cat files/voice-log.jsonl
$ ./adb shell run-as dev.ruimdetijd.aircav sh -c 'echo > files/voice-log.jsonl'   # clear
```

(The app is debug-signed, so `adb shell run-as` works.)

## Voice input & privacy

Voice input uses **Mistral only**: the app records audio in its own process
(max 60 s), sends the WAV file to the Mistral API (Voxtral transcription), and
the transcript is structured into description + tags **locally** — no LLM, no
system speech service (which on most phones means Google). If your phone's
voice entry would otherwise leak, this replaces it entirely.

Set your Mistral API key in the app's settings; the mic button prompts for it
if missing. The key is stored AES/GCM-encrypted in the Android Keystore.

Your task list never leaves your devices except as end-to-end-encrypted
blobs to your own sync server. Voice audio goes to Mistral's transcription
endpoint only; the raw PCM and the WAV are deleted from the app cache right
after every attempt (success or failure), and the voice log keeps only the
last 500 entries.

## Project layout

* `rust/` – JNI cdylib wrapping `taskchampion` 2.x: open/list/add/complete/remove/sync
* `android/` – Kotlin + Jetpack Compose app (no Jetpack internals beyond Compose + Activity/XML manifest)
