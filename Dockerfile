# MemoryLens Android Build Container
# Builds the debug APK inside Docker — no Android Studio required.
#
# Usage:
#   docker build -t memorylens .
#   docker run --rm -v "$PWD/output":/workspace/output memorylens
#
# The signed debug APK lands at: output/app-debug.apk
# Install on a connected Android phone:
#   adb install output/app-debug.apk

FROM eclipse-temurin:21-jdk-jammy

# ── System dependencies ───────────────────────────────────────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
        wget \
        unzip \
        git \
        curl \
    && rm -rf /var/lib/apt/lists/*

# ── Android SDK ───────────────────────────────────────────────────────────────
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# Download Android command-line tools (2024 stable release)
RUN mkdir -p $ANDROID_HOME/cmdline-tools \
    && wget -q "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
            -O /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d $ANDROID_HOME/cmdline-tools \
    && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
    && rm /tmp/cmdline-tools.zip

# Accept licenses, then install the SDK packages used by this project
RUN yes | sdkmanager --licenses > /dev/null 2>&1 \
    && sdkmanager \
        "platforms;android-34" \
        "build-tools;34.0.0" \
        "platform-tools" \
    && rm -rf $ANDROID_HOME/.temp

# ── Project ───────────────────────────────────────────────────────────────────
WORKDIR /workspace

COPY . .

RUN chmod +x gradlew

# Pre-download all Gradle dependencies (cached in the image layer)
RUN ./gradlew dependencies --no-daemon -q || true

# ── Build ─────────────────────────────────────────────────────────────────────
RUN ./gradlew assembleDebug --no-daemon

# Copy the APK to a predictable output path
RUN mkdir -p /workspace/output \
    && cp app/build/outputs/apk/debug/app-debug.apk /workspace/output/memorylens-debug.apk

CMD ["sh", "-c", \
     "cp /workspace/output/memorylens-debug.apk /workspace/output/ && \
      echo '✓ APK ready at /workspace/output/memorylens-debug.apk'"]
