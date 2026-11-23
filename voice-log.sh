#!/usr/bin/env bash
# Print AirCav's voice log from a connected phone:
#   adb shell run-as dev.ruimdetijd.aircav cat files/voice-log.jsonl
#
# Usage:  ./voice-log.sh [serial-or-ip:port]   # same device argument as update.sh
# Env:    WIRELESS_PORT=192.168.x.x:port       # auto-connect to that phone
# Needs:  the app installed as a *debug* build (run-as does not work on release)
#
# Tip:  ./voice-log.sh | tail -n 20       # last 20 entries
#       ./voice-log.sh > voice-log.jsonl  # save a copy

set -euo pipefail
cd "$(dirname "$0")"

TAG=aircav-builder
PKG=dev.ruimdetijd.aircav
TARGET="${1:-${WIRELESS_PORT:-}}"

echo "==> locating adb (extracted from the image once)"
ADB=./adb
if [ ! -x "$ADB" ]; then
    podman run --rm "$TAG" cat /opt/android-sdk/platform-tools/adb > "$ADB"
    chmod +x "$ADB"
fi

if [ -n "$TARGET" ]; then
    echo "==> connecting to $TARGET"
    "$ADB" connect "$TARGET"
fi

adb_list() {
    "$ADB" devices | awk 'NR>1 && $2=="device"{print $1}'
}

DEV="$(adb_list | head -n1)"
if [ -z "$DEV" ]; then
    # transient states right after plug-in / daemon start: retry a few times
    for _ in 1 2 3 4 5; do
        sleep 2
        DEV="$(adb_list | head -n1)"
        [ -n "$DEV" ] && break
    done
fi
if [ -z "$DEV" ]; then
    UNAUTH="$("$ADB" devices | awk 'NR>1 && $2=="unauthorized"{print $1}')"
    if [ -n "$UNAUTH" ]; then
        echo "ERROR: phone is visible but unauthorized — accept the 'Allow USB debugging?' prompt on the phone" >&2
    else
        echo "ERROR: no device found (plug in USB or pass an ip:port like update.sh)" >&2
    fi
    exit 1
fi
echo "==> found: $DEV"

exec "$ADB" -s "$DEV" shell run-as "$PKG" cat files/voice-log.jsonl
