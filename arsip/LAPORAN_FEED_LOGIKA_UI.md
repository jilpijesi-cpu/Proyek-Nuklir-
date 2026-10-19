# Laporan: Activity Feed, logika inti, dan tampilan

## 1. Live Agent Activity Feed (baru: `ActivityFeed.kt`)
Akar masalah lama: aktivitas hanya satu baris status yang ditimpa tiap langkah, lalu hilang saat selesai
atau saat aplikasi dibuka ulang. Sekarang:
- Satu kartu per rangkaian langkah; tiap langkah = baris dengan ikon berwarna per jenis aksi, judul,
  pratinjau perintah, dan penghitung waktu NYATA (jam perangkat).
- Status langkah: berjalan, menunggu izin (otomatis saat dialog persetujuan terbuka), selesai, gagal,
  ditolak, dihentikan. Langkah gagal langsung terbuka menampilkan galat.
- Interaktif: ketuk baris = buka/tutup detail (perintah + keluaran, atau diff berwarna untuk tulis file);
  tekan lama detail = salin; ketuk kepala kartu = ciutkan/bentangkan.
- Kepala kartu: titik berdenyut + pita kemajuan saat bekerja, ringkasan "N langkah - durasi".
- Riwayat dipulihkan: langkah alat kini muncul kembali (diciutkan) saat aplikasi dibuka ulang.
- Teks status karangan ("Memeriksa hasil - ...") dihapus; baris status hanya menampilkan fase nyata + lama tunggu.

## 2. Perbaikan logika inti (akar masalah)
- Status "Menunggu respons model AI" tidak pernah muncul (judulnya langsung ditimpa loop). Kini dijadwalkan per putaran.
- Loop agen tanpa batas: ditambah batas 60 putaran dan deteksi panggilan alat identik berulang (3x).
- Giliran model dengan panggilan alat tanpa hasil tertinggal di riwayat saat error -> permintaan berikutnya ditolak API.
  Sekarang dibersihkan (`trimDangling`) juga pada error biasa, dan riwayat dijaga tetap selang-seling.
- File BARU dihitung sebagai risiko DELETE ("menimpa data") karena `readOld` mengembalikan "" -> kini WRITE.
- Gemini: blokir/finishReason (mis. SAFETY) sebelumnya hanya jadi "(tidak ada jawaban)"; kini galat yang jelas.
- Label risiko READ memakai teks putih di atas abu-abu muda (tak terbaca); kini warna kontras otomatis (`onColor`).
- Status sementara (persetujuan, hitung mundur, ringkasan) dipulihkan lewat `StatusSnap`, bukan tiga variabel terpisah.
- Teks status tidak lagi mengklaim "Claude Mythos" bila penyedia lain yang dipakai.

## 3. Tampilan
- Palet berwarna (`Palette.kt`): merek indigo-violet + aksen per aksi; sebelumnya satu biru untuk semua.
- Bilah atas, pemilih model berbentuk pil, gelembung pengguna bergradasi, kartu balasan AI, kode bertema gelap
  dengan sintaks berwarna, diff hijau/merah, chip saran, tombol kirim bergradasi, bidang input dengan fokus berwarna.
- Layar kosong dengan kartu mulai-cepat yang bisa diketuk.
- Tema aplikasi (`styles.xml`): dialog membulat dan aksen merek di seluruh kontrol bawaan.
- Responsif: kolom isi maksimum 760dp di tablet/lanskap (dihitung ulang saat konfigurasi berubah); layar kosong bisa digulir.

## Validasi
Kode ini TIDAK dikompilasi di lingkungan ini (tidak ada Gradle/Android SDK/kompiler Kotlin, dan arsip tidak memuat gradlew).
Yang dilakukan: telaah manual dan pemeriksaan keseimbangan tanda kurung. Jalankan:
`./gradlew testDebugUnitTest assembleDebug`
Tes baru/diperbarui: `PaletteTest`, `ActivityFeedTest`.

## Belum dikerjakan
Mode gelap (banyak warna masih tertanam di dialog Pengaturan/Pemilih Model), dan dialog Pengaturan hanya mendapat tema, bukan tata letak baru.

---
# Pembaruan: penyederhanaan Live Agent Activity Feed (`ActivityFeed.kt`)

Tujuan: real-time, informatif, elegan, interaktif, tetapi dengan elemen visual jauh lebih sedikit.

## Sebelum -> sesudah
- Satu kartu besar per langkah (radius 24dp, judul 18sp, 6 elemen per baris: ikon aksi, judul, lencana, status, tombol Hentikan, panah)
  -> SATU kartu tipis (radius 16dp) berisi baris-baris rata dengan garis pemisah tipis. Tiap baris: ikon status | judul | waktu | panah.
- Feed diciutkan sejak awal (pengguna tidak melihat apa pun saat agen bekerja)
  -> terbuka saat bekerja; otomatis diciutkan saat selesai bila bersih (tidak ada langkah gagal) dan pengguna belum membukanya sendiri.
- Titik berdenyut + animator terpisah -> ikon status yang sama dengan baris (berputar saat bekerja, centang/silang saat selesai).
- Tombol Hentikan per baris -> satu tombol di kepala kartu.

## Real-time
- Waktu berjalan per langkah dan total berdetak tiap detik (jam perangkat).
- Baris pratinjau satu baris menampilkan keluaran terakhir perintah yang sedang berjalan (tanpa perlu membuka detail).
- Bila kartu diciutkan saat bekerja, ringkasan menampilkan judul langkah yang sedang berjalan.
- Langkah selesai kini menampilkan durasinya (dulu kosong).
- Saat bekerja hanya 4 langkah terbaru yang tampil; sisanya dilipat ("Tampilkan N langkah sebelumnya").

## Interaktif
- Ketuk baris = buka/tutup detail (perintah + keluaran; diff untuk tulis file); tekan lama detail = salin.
- Langkah gagal terbuka otomatis. Ketuk kepala kartu = ciutkan/bentangkan. Langkah baru muncul dengan transisi halus.

## Kode
- API ke luar tidak berubah (`addStep`, `addRestored`, `seal`, `setCollapsed`, `FeedRow.finish/setLive/setWaiting/restore`),
  jadi `MainActivity`, `ToolExecution`, `AgentLoop`, `ChatHistory` tidak perlu diubah.
- Logika tampilan dipisah menjadi fungsi murni yang dapat diuji: `lastLine`, `noteFor`, `feedSummary`, `feedHeadline`
  (tes ditambahkan di `ActivityFeedTest`).
- Dihapus: `ObjectAnimator` denyut, tombol Hentikan per baris, ikon aksi per baris (warna aksen kini ada di ikon status saat berjalan).

## Validasi
Belum dikompilasi/dijalankan di lingkungan ini (tidak ada kompiler Kotlin/Gradle/Android SDK). Hanya telaah manual dan pemeriksaan
keseimbangan kurung. Jalankan: `./gradlew testDebugUnitTest assembleDebug`, lalu coba satu tugas agen yang memakai beberapa langkah.
