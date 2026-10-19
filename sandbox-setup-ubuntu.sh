#!/data/data/com.termux/files/usr/bin/bash
# Pasang dan periksa sandbox Ubuntu (proot-distro) untuk AgentApp. Jalankan di Termux:  bash sandbox-setup-ubuntu.sh
# Skrip ini TIDAK menjalankan termux-setup-storage dan tidak memberi akses ke penyimpanan bersama.
set -u

DISTRO=ubuntu
ROOTFS="$PREFIX/var/lib/proot-distro/installed-rootfs/$DISTRO"
FAIL=0

echo "== 1/3 Pasang proot-distro dan Ubuntu"
pkg install -y proot-distro
if [ -d "$ROOTFS" ]; then
  echo "Ubuntu sudah terpasang."
else
  proot-distro install "$DISTRO" || { echo "GAGAL: Ubuntu tidak terpasang."; exit 1; }
fi

echo "== 2/3 Pemeriksaan isolasi (semua harus LULUS)"
check_hidden() {  # $1 = path yang TIDAK boleh terlihat dari dalam sandbox
  out=$(proot-distro login "$DISTRO" --isolated -- /bin/sh -c "ls -A '$1' 2>/dev/null | head -n 1" 2>/dev/null)
  if [ -z "$out" ]; then
    echo "LULUS  $1 tidak terlihat"
  else
    echo "GAGAL  $1 TERLIHAT dari dalam sandbox: JANGAN pakai mode sandbox"
    FAIL=1
  fi
}
check_hidden /sdcard
check_hidden /storage
check_hidden /data/data/com.termux/files/home

echo "== 3/3 Pemeriksaan lingkungan bersih (tidak boleh ada token/API key)"
leak=$(env -i PATH="$PREFIX/bin" "$PREFIX/bin/proot-distro" login "$DISTRO" --isolated -- /bin/sh -c 'env' 2>/dev/null | grep -Ei 'key|token|secret|password' || true)
if [ -z "$leak" ]; then
  echo "LULUS  tidak ada variabel mirip rahasia"
else
  echo "GAGAL  variabel mencurigakan terlihat:"; echo "$leak" | sed 's/=.*/=<disembunyikan>/'
  FAIL=1
fi

echo "== Opsional: alat Analisis APK (jadx, apktool, aapt2) di dalam Ubuntu"
if [ "${INSTALL_RE_TOOLS:-0}" = "1" ]; then
  proot-distro login "$DISTRO" -- /bin/sh -c "apt-get update -y && apt-get install -y --no-install-recommends openjdk-17-jre-headless unzip wget aapt" || echo "GAGAL memasang paket dasar; cek koneksi."
  proot-distro login "$DISTRO" -- /bin/sh -c '
    set -e
    mkdir -p /opt/re && cd /opt/re
    [ -f jadx.zip ] || wget -q https://github.com/skylot/jadx/releases/latest/download/jadx-1.5.0.zip -O jadx.zip
    [ -d jadx ] || unzip -q jadx.zip -d jadx
    ln -sf /opt/re/jadx/bin/jadx /usr/local/bin/jadx
    [ -f apktool.jar ] || wget -q https://github.com/iBotPeaches/Apktool/releases/latest/download/apktool.jar -O apktool.jar
    printf "#!/bin/sh\nexec java -jar /opt/re/apktool.jar \"\$@\"\n" > /usr/local/bin/apktool
    chmod +x /usr/local/bin/apktool
  ' || echo "GAGAL memasang jadx/apktool; versi di URL mungkin berubah, cek manual."
  echo "Alat RE terpasang di dalam Ubuntu (tidak terlihat dari Termux utama). Pakai lewat run_command: jadx -d out app.apk"
else
  echo "Dilewati. Jalankan dengan INSTALL_RE_TOOLS=1 bash sandbox-setup-ubuntu.sh untuk memasang jadx/apktool/aapt."
fi

echo
if [ "$FAIL" -eq 0 ]; then
  echo "Selesai: semua pemeriksaan lulus. Ingat: proot BUKAN batas keamanan kuat (lihat SANDBOX.md)."
else
  echo "Ada pemeriksaan yang GAGAL. Jangan aktifkan Mode sandbox sebelum diperbaiki."
  exit 2
fi
