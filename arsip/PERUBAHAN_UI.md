# Perubahan AgentApp: UI Monokrom dan Lampiran

## Perubahan
- Menu tombol (+) sekarang hanya berisi `Upload File` dan `Upload Gambar`.
- Upload gambar memakai pemilih dokumen Android dengan filter `image/*`.
- Kedua jenis lampiran disalin ke `Download/ClaudeMythos` agar dapat dirujuk oleh proses AI/Termux.
- Untuk provider API Gemini, OpenAI-compatible, dan Anthropic, gambar juga diperkecil lalu dikirim sebagai input visual; file asli tetap utuh. Provider CLI menerima rujukan path sesuai dukungan CLI tersebut.
- Pesan pengguna, kontrol, panel detail, kode, indikator status, dan warna risiko memakai tema monokrom.
- Latar gradasi, warna-warni sintaks kode, titik berpulsasi, efek shimmer, dan animasi kartu langkah dihilangkan.
- Aktivitas tool kini memperbarui satu baris status; detail perintah/hasil tetap bisa dibuka lewat baris tersebut.
- Build APK, pemeriksaan proyek, dan fitur lain tidak dihapus dari backend; pemanggilannya dilakukan melalui instruksi chat.

## Validasi
- Pemeriksaan statis sumber dan alur pemilihan file/gambar dilakukan.
- Build Android dan unit test Gradle belum dapat dijalankan di lingkungan ini karena arsip sumber tidak menyertakan `gradlew`/Gradle Wrapper dan Gradle serta Android SDK tidak tersedia.


> Catatan: tema monokrom di atas sudah digantikan oleh desain berwarna. Lihat `LAPORAN_FEED_LOGIKA_UI.md`.

---
# Tampilan chat gaya Gemini (1:1 dengan screenshot acuan)
- Latar: gradien putih -> biru muda (`ChatBackground` di `ChatLook.kt`, warna di `Palette.SKY_COLORS`); gambar `chat_bg.png` tidak dipakai lagi.
- Bilah atas: menu dua garis, nama model (kata pertama gelap, sisanya abu-abu) + panah kecil, ikon chat baru (lingkaran putus-putus), avatar bulat.
- Kolom input: pil putih tinggi 76dp tanpa garis tepi, bayangan lembut; isi: `+` | "Minta Claude Mythos" | mikrofon | tombol aksi lingkaran biru muda berisi ikon gelombang.
- Tombol aksi: kosong = biru muda + ikon gelap; ada teks = biru solid + panah putih; bekerja = gelap + kotak henti.
- Gelembung pengguna abu-biru muda tanpa garis tepi; balasan AI tanpa kartu (teks langsung di latar).
- Warna merek diganti dari oranye ke biru (`Palette.BRAND`, `colors.xml`) agar seluruh aksen serasi.
- Belum dikompilasi di sini; jalankan `./gradlew testDebugUnitTest assembleDebug`.

---
# Feed langkah agen: kartu pil, maksimal 3 (`ActivityFeed.kt`)
- Tiap langkah kini berupa kartu pil sendiri (ikon status | judul | catatan seperti "exit=1" | panah), tanpa kepala kartu pembungkus.
- Centang hijau / silang merah polos tanpa lingkaran; berputar saat berjalan. Langkah sukses tidak menampilkan catatan waktu.
- Batas 3 kartu (`MAX_VISIBLE_ROWS`): saat langkah ke-4 masuk, kartu tertua hilang dan yang baru muncul di bawah, dst. Berlaku juga saat riwayat dipulihkan.
- Dihapus: kepala kartu, tombol Hentikan di kartu (pakai tombol henti di kolom input), "Tampilkan N langkah sebelumnya", buka-otomatis untuk langkah gagal, pratinjau satu baris.
- API luar tidak berubah (`addStep`, `addRestored`, `seal`, `FeedRow.finish/setLive/setWaiting/restore`). Tes baru: `hanyaTigaKartuTampilYangTertuaHilang`.
- Belum dikompilasi di sini; jalankan `./gradlew testDebugUnitTest assembleDebug`.

---
# Perbaikan koneksi AI yang mudah putus
- `AgentService`: tambah WakeLock (CPU) + WifiLock selama AI bekerja, permission `WAKE_LOCK`. Sebelumnya hanya layanan latar depan, sehingga saat layar mati/pindah ke Termux koneksi streaming ikut putus.
- `openStream`: header `Connection: close` (hindari koneksi idle basi setelah tool lama seperti build), `readTimeout` 90 dtk -> 180 dtk, percobaan ulang buka koneksi 3 -> 5 (jeda 2/4/8/16 dtk, maks 20), pesan galat akhir lebih jelas.
- Stream putus di tengah jalan: percobaan ulang 2 -> 4.
- Kode HTTP 408 dan 500 kini ikut dicoba ulang (sebelumnya langsung gagal).
- Belum dikompilasi di sini; jalankan `./gradlew testDebugUnitTest assembleDebug`.
