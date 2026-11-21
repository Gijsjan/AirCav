#!/usr/bin/env bash
# One-command update for a connected phone:
#   git pull -> build APK in container -> adb install (smart upgrade)
#
# Usage:  ./update.sh [serial]        pass serial only if adb sees 2+ devices
# Env:    WIRELESS_PORT=192.168.x.x:port   to prefer wireless over USB
# Needs:  podman; phone via USB (debugging on) or previously-paired wireless

set -euo pipefail
cd "$(dirname "$0")"

TAG=aircav-builder
APK=out/app-debug.apk
SERIAL=()
if [ $# -gt 0 ]; then
    SERIAL=(-s "$1")
fi

echo "==> git pull"
git pull --ff-only

if ! podman image exists "$TAG"; then
    echo "==> building container image (first time, ~10 min)"
    podman build -t "$TAG" -f docker/Dockerfile .
fi

mkdir -p out .android-debug .adb

echo "==> building APK (container)"
podman run --rm \
    -v "$PWD":/work:Z \
    -v "$PWD/.android-debug":/root/.android:Z \
    -e CARGO_TARGET_DIR=/work/rust/target \
    "$TAG"

[ -f "$APK" ] || { echo "ERROR: build produced no APK ($APK)" >&2; exit 1; }

echo "==> locating adb (extracted from the image once)"
ADB=./adb
if [ ! -x "$ADB" ]; then
    podman run --rm "$TAG" cat /opt/android-sdk/platform-tools/adb > "$ADB"
    chmod +x "$ADB"
fi

adb_list() { "$ADB" "${SERIAL[@]}" devices | awk 'NR>1 && $2=="device"{print $1}'; }

if [ -n "${WIRELESS_PORT:-}" ]; then
    echo "==> wireless: connecting to $WIRELESS_PORT"
    "$ADB" connect "$WIRELESS_PORT" >/dev/null 2>&1 || true
elif [ -z "$(adb_list)" ]; then
    echo "==> no USB device; trying wireless"
    read -r -p "Phone IP:port from the Wireless debugging screen: " PORT
    [ -n "$PORT" ] && "$ADB" connect "$PORT" >/dev/null 2>&1 || true
fi

DEV="$(adb_list | head -n1)"
[ -n "$DEV" ] || { echo "ERROR: no device (check cable, USB-debugging prompt on phone)" >&2; exit 1; }

echo "==> installing to $DEV"
# try an in-place update first; fall back to uninstall only when the
# signature truly changed (fresh keystore, i.e. once per install lineage)
if ! "$ADB" "${SERIAL[@]}" install -r "$APK"; then
    echo "==> update refused (signature change); clean install"
    "$ADB" "${SERIAL[@]}" uninstall dev.ruimdetijd.aircav >/dev/null || true
    "$ADB" "${SERIAL[@]}" install "$APK"
fi

echo
echo "Done. AirCav updated on $DEV"
