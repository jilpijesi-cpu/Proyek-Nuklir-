#!/data/data/com.termux/files/usr/bin/bash
# Build APK AgentApp langsung di Termux (aarch64).
# Pemakaian: bash build-termux.sh          (lewati tes unit, cepat)
#            WITH_TESTS=1 bash build-termux.sh   (jalankan tes unit juga)
set -e
cd "$(dirname "$0")"

SDK="$HOME/android-sdk"
export ANDROID_HOME="$SDK"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

echo "== 1/5 Paket Termux"
pkg update -y
pkg install -y openjdk-17 gradle git aapt2 unzip wget

echo "== 2/5 Android SDK"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK/cmdline-tools"
  cd "$SDK"
  wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdtools.zip
  unzip -q cmdtools.zip -d tmp
  rm -rf cmdline-tools/latest
  mv tmp/cmdline-tools cmdline-tools/latest
  rm -rf tmp cmdtools.zip
  cd - > /dev/null
fi
yes | sdkmanager --licenses > /dev/null || true
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

echo "== 3/5 Konfigurasi proyek"
echo "sdk.dir=$SDK" > local.properties
if [ ! -f gradle.properties ]; then
  cat > gradle.properties << PROPS
android.useAndroidX=true
org.gradle.jvmargs=-Xmx1536m
android.aapt2FromMavenOverride=$PREFIX/bin/aapt2
PROPS
fi
if [ ! -x ./gradlew ]; then
  gradle wrapper --gradle-version 8.9
  chmod +x gradlew
fi

echo "== 4/5 Build APK debug"
if [ "${WITH_TESTS:-0}" = "1" ]; then
  ./gradlew testDebugUnitTest --no-daemon
  ./gradlew assembleDebug --no-daemon
else
  ./gradlew assembleDebug -x test --no-daemon
fi

echo "== 5/5 Salin APK"
APK="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
  cp "$APK" "$HOME/storage/downloads/agentapp-1.1.apk" 2>/dev/null \
    && echo "APK disalin ke Download/agentapp-1.1.apk" \
    || echo "Gagal menyalin ke Download. Jalankan: termux-setup-storage"
  echo "Selesai. APK: $(pwd)/$APK"
else
  echo "APK tidak ditemukan. Lihat pesan error di atas."
  exit 1
fi
