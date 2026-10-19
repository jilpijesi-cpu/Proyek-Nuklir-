# Perbaikan koneksi AI + peralihan AI otomatis (Oktober 2026)

Masalah awal: error "high demand" (503) dan "exceeded your current quota" (429) langsung menghentikan tugas.

## Yang berubah
- `ApiErrors.kt`: klasifikasi error API (sementara / kuota habis / ditolak), parser `retryDelay`.
- Provider Gemini, OpenAI, Anthropic: error di dalam stream (setelah HTTP 200) kini ikut diulang bila sementara.
- `ModelCall.openStream`: 429 kuota harian / limit 0 tidak diulang; jeda mengikuti `Retry-After`/`retryDelay` (maks 45 dtk).
- `Failover.kt` (logika murni): susunan cadangan lintas penyedia dan model, pelacak status.
- Kartu visual "Mengalihkan AI otomatis" (FailoverView.kt) sudah dihapus. Pergantian AI tetap berjalan otomatis di latar belakang tanpa tampilan apa pun di chat.
- `ModelCall.callModelRetry`: saat sebuah AI macet di tengah kerja, sistem otomatis:
  1. mencoba model lain di penyedia yang sama,
  2. lalu penyedia lain yang API key-nya terdaftar (semua model yang diketahui dari hasil uji, atau diambil langsung dari penyedia bila belum pernah diuji), dari yang terbaik,
  3. bila semua sibuk, menunggu (10/25/45 dtk) dan mengulang sampai 3 putaran,
  4. begitu ada yang berhasil, giliran dilanjutkan otomatis dan AI itu dipakai sampai tugas selesai.
  Model yang kuotanya habis atau ditolak dilewati selama tugas itu. Selama peralihan, percobaan ulang per model dipersingkat (1x).
- Tes: `ApiErrorsTest.kt`, `FailoverTest.kt`.

## Catatan
- Penyedia cadangan harus punya API key tersimpan, URL https (atau lokal), dan model terisi atau daftar model yang bisa diambil.
- Riwayat percakapan yang sama dikirim ke AI cadangan; bila ditolak (mis. 400), sistem lanjut ke kandidat berikutnya.
