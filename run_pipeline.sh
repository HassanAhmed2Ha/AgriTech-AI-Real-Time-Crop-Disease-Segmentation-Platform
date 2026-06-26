#!/bin/bash
set -e

# --- Configuration ---
# Exporting target JDK and Android SDK paths
export JAVA_HOME="/home/hassan/jdk17"
export ANDROID_HOME="/home/hassan/android-sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/tools:$PATH"

echo "=== Build Environment Configured ==="
echo "JAVA_HOME: $JAVA_HOME"
echo "ANDROID_HOME: $ANDROID_HOME"
echo ""

# 1. Compile the APK
echo "=== Step 1: Compiling debug APK via Gradle wrapper ==="
./gradlew assembleDebug

# 2. Install the APK
echo "=== Step 2: Installing APK onto connected device ==="
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Clear older logs
echo "=== Step 3: Clearing Logcat buffers ==="
adb logcat -c

# 4. Monitor Logcat filtering strictly by AGRITECH_PERF
echo "=== Step 4: Monitoring Cloud Inference Latency ==="
adb logcat AGRITECH_PERF:D *:S
