# Mode sandbox (proot-distro)

Opt-in lewat Pengaturan > Lanjutan > "Mode sandbox". Default mati: perilaku lama tidak berubah.

## Yang dilakukan
- run_command berjalan di dalam proot-distro (default Ubuntu), bukan di shell Termux utama.
- Workdir tamu: /root/sandbox/work (dibuat otomatis). Tanpa --bind, tanpa --shared-tmp, tanpa --termux-home,
  tanpa termux-setup-storage. Pemanggilan memakai --isolated.
- Environment dibersihkan: `env -i` hanya meneruskan PATH, HOME, TMPDIR, PREFIX, TERM, LANG, ANDROID_ROOT, ANDROID_DATA.
- Batas waktu (default 120 dtk, build 1800 dtk) lewat `timeout -s KILL` pada seluruh grup proses.
  Batas keluaran (default 20000 byte) lewat `head -c`; melewati batas diberi penanda "dipotong".
- CommandPolicy.assessCommand() (di atas classify(), classify() tidak diubah):
  SAFE (allowlist, baca-saja, di dalam workdir, tanpa jaringan), NEEDS_APPROVAL, BLOCKED.
- BLOCKED (tidak pernah dijalankan): dd, mkfs*, fdisk, mount, shred, su, proot*, termux-*, am/pm, reboot dst.;
  chmod/chown/chgrp -R; fork bomb; rm/unlink/truncate/find -delete atau penulisan (mkdir, cp, mv, tee, redirect, ...)
  ke luar workdir; rm workdir itu sendiri; cd keluar workdir.
- Jaringan (curl, wget, git clone/push/pull, pip, npm, apt, gradle, ssh, URL, /dev/tcp, ...) ditandai dan SELALU
  butuh persetujuan: tidak bisa dilewati mode otonom atau "Ya, semua" (force = true).
- write_file ditolak saat sandbox aktif (ia menulis ke Termux utama). Model diarahkan memakai run_command.
- Setiap eksekusi, penolakan, dan blokir dicatat di CommandLog (kolom baru: exitCode, decision, sandbox, network).

## Batas yang jujur
proot bukan sandbox keamanan seperti container/VM. Ia tidak memblokir jaringan dan tidak mencegah proses keluar
dari workdir di dalam sistem file tamu. Pembatasan workdir dan jaringan di sini adalah analisis teks (heuristik):
payload yang disandikan (mis. base64 | sh), variabel shell, atau skrip python/node bisa lolos dari analisis dan
hanya tertahan oleh dialog persetujuan serta isolasi proot.

## Pasang di Termux (sekali)
    pkg install -y proot-distro
    proot-distro install ubuntu

## Cara mencoba di perangkat (tanpa perintah destruktif)
1. Cek dukungan opsi:  proot-distro login ubuntu --help   (pastikan ada --isolated)
2. Cek isolasi:        proot-distro login ubuntu --isolated -- ls /sdcard /data/data/com.termux/files/home
   Harus gagal atau kosong. Kalau isinya terlihat, JANGAN pakai mode ini.
3. Cek env bersih:     env -i PATH=$PREFIX/bin proot-distro login ubuntu --isolated -- env
4. Nyalakan "Mode sandbox" di Pengaturan, lalu minta agen: "jalankan pwd dan ls" -> jalan, tanpa dialog bila autoread aktif.
5. "buat file a.txt berisi halo di workdir" -> dialog persetujuan; file muncul di /root/sandbox/work (di dalam Ubuntu).
6. "jalankan sleep 300" -> berhenti di batas 120 dtk dengan pesan Timeout.
7. "tampilkan 100000 baris dengan yes | head -n 100000" -> keluaran dipotong dengan penanda.
8. "jalankan curl https://example.com" -> dialog dengan peringatan jaringan, tanpa tombol "Ya, semua".
9. Minta agen menjalankan `dd if=/dev/zero of=x bs=1 count=1` (hanya diuji untuk DIBLOKIR; tidak akan jalan) -> "Diblokir oleh sandbox".
10. Buka layar catatan perintah: entri baru punya kolom exit code, keputusan, sandbox, jaringan (tampilannya belum diubah).

## Pengerasan maksimal (Ubuntu, di perangkat)
Urutan dari yang paling berpengaruh. Proot tetap bukan batas keamanan; lapisan 1-3 berada DI LUAR proot dan itulah yang membuat isolasi lebih nyata.

1. **Pisahkan perangkat/profil.** Pasang Termux dan AgentApp di profil terpisah (profil kerja lewat Shelter/Island, atau Private Space di Android 15+) atau di HP cadangan tanpa data pribadi. Tujuannya: bila ada yang lolos, tidak ada data penting di sekitarnya.
2. **Blokir jaringan Termux di tingkat Android.** Pakai firewall per-aplikasi berbasis VPN lokal (mis. NetGuard atau RethinkDNS): Termux diblokir secara default, dibuka sementara hanya saat Anda menyetujui perintah berjaringan.
3. **Tanpa rahasia.** Jangan jalankan termux-setup-storage; jangan simpan kunci SSH, token git, atau API key di home Termux. Kunci API AgentApp tetap di Android Keystore dan tidak diteruskan ke sandbox (env -i).
4. **Jalankan `bash sandbox-setup-ubuntu.sh`** sekali. Skrip memasang Ubuntu dan memeriksa bahwa /sdcard, /storage, dan home Termux tidak terlihat, serta tidak ada variabel mirip rahasia.
5. **Mode otonom tetap mati; persetujuan tetap hidup.** Baca perintah asli sebelum menyetujui.
6. **Workdir sekali pakai.** Hapus isi /root/sandbox/work setelah tugas, atau reset distro: `proot-distro reset ubuntu`.

## Mode Analisis APK (reverse engineering statis)
Opt-in di Pengaturan, default mati. Saat aktif, system prompt menambahkan `APK_RE_SYSTEM_PROMPT`
(lihat `ApkReMode.kt`) yang membatasi AI pada analisis statis: dekompilasi (jadx, apktool d), periksa
manifest/signature (aapt2, apksigner verify), cari string/URL. AI diinstruksikan menolak membobol
lisensi/DRM/anti-tamper, mem-patch lalu mendistribusikan APK bajakan, atau membuat tool serangan dari
analisis malware — berapa pun diminta. Ini bukan filter teknis seperti CommandPolicy, melainkan instruksi
ke model; perintah yang akhirnya dijalankan tetap lewat alur persetujuan dan CommandPolicy yang sama.

Alat jadx/apktool/aapt dipasang di DALAM Ubuntu sandbox (lihat `sandbox-setup-ubuntu.sh`,
`INSTALL_RE_TOOLS=1`), bukan di Termux utama, supaya hasil dekompilasi tidak bercampur dengan data lain
dan mudah dibuang (`proot-distro reset ubuntu`). Nyalakan Mode sandbox + Mode Analisis APK bersamaan untuk
hasil paling rapi.

Tanggung jawab legal tetap pada pengguna: hanya analisis APK yang Anda miliki haknya (milik sendiri, atau
izin eksplisit untuk riset/interoperabilitas/keamanan).
