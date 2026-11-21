#!/usr/bin/env bash
# One-command update for a connected phone:
#   git pull -> build APK in container -> adb install (smart upgrade)
#
# Usage:  ./update.sh [serial-or-ip:port]   # pass to target a wireless device
#                                              or pick among several devices
# Env:    WIRELESS_PORT=192.168.x.x:port     # auto-connect to that phone
# Needs:  podman; phone via USB (debugging on) or previously-paired wireless

set -euo pipefail
cd "$(dirname "$0")"

TAG=aircav-builder
APK=out/app-debug.apk
TARGET="${1:-${WIRELESS_PORT:-}}"

echo "==> git pull"
PULL_OUT="$(git pull --ff-only 2>&1 || true)"
echo "$PULL_OUT"
if [ -z "${FORCE:-}" ] && echo "$PULL_OUT" | grep -q "Already up to date"; then
    echo "Nothing to do. (run with FORCE=1 to rebuild and reinstall anyway)"
    exit 0
fi

if ! podman image exists "$TAG"; then
    echo "==> building container image (first time, ~10 min)"
    podman build -t "$TAG" -f docker/Dockerfile .
fi

echo "==> building APK (container)"
mkdir -p out .android-debug
if [ -n "${RELEASE:-}" ]; then
    [ -n "${AIRCAV_KEYSTORE:-}" ] || {
        echo "ERROR: RELEASE=1 needs AIRCAV_KEYSTORE (+ _PASSWORD/_KEY_ALIAS/_KEY_PASSWORD)" >&2
        exit 1
    }
    podman run --rm \
        -v "$PWD":/work:Z \
        -v "$PWD/.android-debug":/root/.android:Z \
        -e CARGO_TARGET_DIR=/work/rust/target \
        -e AIRCAV_KEYSTORE -e AIRCAV_KEYSTORE_PASSWORD -e AIRCAV_KEY_ALIAS -e AIRCAV_KEY_PASSWORD \
        --entrypoint bash "$TAG" -c "cd /work/android && gradle --no-daemon assembleRelease \
            && cp app/build/outputs/apk/release/app-release.apk /work/out/app-release.apk"
    APK=out/app-release.apk
else
    podman run --rm \
        -v "$PWD":/work:Z \
        -v "$PWD/.android-debug":/root/.android:Z \
        -e CARGO_TARGET_DIR=/work/rust/target \
        "$TAG"
fi
[ -f "$APK" ] || { echo "ERROR: build produced no APK ($APK)" >&2; exit 1; }

echo "==> locating adb (extracted from the image once)"
ADB=./adb
if [ ! -x "$ADB" ]; then
    podman run --rm "$TAG" cat /opt/android-sdk/platform-tools/adb > "$ADB"
    chmod +x "$ADB"
fi

# if a target was given (arg or WIRELESS_PORT), connect to it first
if [ -n "$TARGET" ]; then
    echo "==> connecting to $TARGET"
    "$ADB" connect "$TARGET"
fi

adb_list() {
    "$ADB" devices | awk 'NR>1 && $2=="device"{print $1}'
}

find_phone() {
    DEV="$(adb_list | head -n1)"
    [ -n "$DEV" ] && return 0
    # transient states right after plug-in / daemon start: retry a few times
    for _ in 1 2 3 4 5; do
        sleep 2
        DEV="$(adb_list | head -n1)"
        [ -n "$DEV" ] && return 0
    done
    return 1
}

echo "==> looking for the phone"
if ! find_phone; then
    UNAUTH="$("$ADB" devices | awk 'NR>1 && $2=="unauthorized"{print $1}')"
    if [ -n "$UNAUTH" ]; then
        echo "ERROR: phone is visible but unauthorized — accept the 'Allow USB debugging?' prompt on the phone" >&2
    else
        echo "ERROR: no device found." >&2
        echo "  - plug in via USB with USB debugging on, or" >&2
        echo "  - re-run with:  WIRELESS_PORT=<ip>:<port> ./update.sh   (port from the phone's Wireless-debugging screen), or" >&2
        echo "  - re-run with:  ./update.sh <ip>:<port>" >&2
    fi
    exit 1
fi
echo "    found: $DEV"

# use the explicit serial only if the user passed one
SERIAL=()
if [ -n "$TARGET" ]; then
    SERIAL=(-s "$TARGET")
fi

echo "==> installing to $DEV (in-place upgrade; falls back to clean install on signature change)"
if ! "$ADB" "${SERIAL[@]}" install -r "$APK"; then
    echo "==> update refused (signature change); clean install"
    "$ADB" "${SERIAL[@]}" uninstall dev.ruimdetijd.aircav >/dev/null || true
    "$ADB" "${SERIAL[@]}" install "$APK"
fi

echo
echo "Done. AirCav updated on $DEV"
