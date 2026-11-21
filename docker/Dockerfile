# Build environment for AirCav: Android SDK + NDK + Rust + Gradle.
# Build the APK without installing any of this on your host.
#
#   podman build -t aircav-builder -f docker/Dockerfile .
#   mkdir -p out
#   podman run --rm -v "$PWD":/work -e CARGO_TARGET_DIR=/work/rust/target aircav-builder
#   # APK lands in ./out/app-debug.apk

FROM docker.io/eclipse-temurin:17-jdk-jammy

ENV ANDROID_HOME=/opt/android-sdk \
    CARGO_HOME=/opt/cargo \
    RUSTUP_HOME=/opt/rustup
ENV ANDROID_NDK_VERSION=27.0.12077973
ENV ANDROID_NDK_HOME=${ANDROID_HOME}/ndk/${ANDROID_NDK_VERSION}
ENV PATH=$PATH:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:/opt/gradle/bin:${CARGO_HOME}/bin

RUN apt-get update && apt-get install -y --no-install-recommends \
        unzip curl xz-utils git ca-certificates build-essential pkg-config \
    && rm -rf /var/lib/apt/lists/*

# Android command-line tools + SDK/NDK packages
RUN curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/clt.zip \
    && mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && unzip -q /tmp/clt.zip -d ${ANDROID_HOME}/cmdline-tools \
    && mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm /tmp/clt.zip \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;${ANDROID_NDK_VERSION}" >/dev/null

# Gradle
RUN curl -fsSL https://services.gradle.org/distributions/gradle-8.13-bin.zip -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && mv /opt/gradle-8.13 /opt/gradle \
    && rm /tmp/gradle.zip

# Rust + cargo-ndk
RUN curl -fsSL https://sh.rustup.rs -sSf | sh -s -- -y --default-toolchain 1.100.0 --profile minimal \
    && rustup target add aarch64-linux-android armv7-linux-androideabi \
    && cargo install --locked --version 4.1.2 cargo-ndk

WORKDIR /work
CMD ["/work/docker/build.sh"]
