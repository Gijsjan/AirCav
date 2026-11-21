#!/usr/bin/env bash
set -euo pipefail

# Runs inside the container (see docker/Dockerfile); repo mounted at /work.
cd /work/rust
cargo ndk -t arm64 -t arm build --release -o ../android/app/src/main/jniLibs

cd /work/android
gradle --no-daemon assembleDebug

mkdir -p /work/out
cp app/build/outputs/apk/debug/app-debug.apk /work/out/
echo "APK ready: out/app-debug.apk"
