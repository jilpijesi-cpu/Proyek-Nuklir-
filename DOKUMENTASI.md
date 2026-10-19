# AgentApp: dokumentasi utama

Dokumen ini adalah sumber kebenaran untuk kondisi aplikasi saat ini. Laporan lama (perubahan UI dan
laporan sebelumnya) dipindahkan ke folder `arsip/` dan tidak lagi berlaku jika bertentangan dengan dokumen ini.
Riwayat perubahan terbaru ada di `PERBAIKAN_LOGIKA_AGEN.md`. Struktur kode ada di `STRUKTUR.md`.

## Build dan tes
- Jalankan `./gradlew testDebugUnitTest assembleDebug`.
- Status: belum pernah dikompilasi di lingkungan pengembangan. Build pertama wajib dilakukan sebelum fitur baru.

## Perilaku agen saat ini
- Tidak ada penolakan otomatis. Tindakan berisiko ditampilkan sebagai dialog persetujuan.
- "Ya, semua" melewati dialog untuk sisa tugas.
- Agen bisa bertanya ke pengguna lewat tool `ask_user`. Tanpa jawaban dalam 10 menit dianggap tidak menjawab.
- Dialog yang sedang ditunggu dilepas saat Activity ditutup atau diputar.
- Tahap pemahaman (`TaskBrief.kt`) mencatat tujuan dan asumsi. Tahap ini tidak menahan tugas.
- Laporan akhir: "Berhasil", "Belum terverifikasi" (perubahan belum dibuktikan build atau verifikasi), "Gagal", atau "Dihentikan".
- Mode hanya baca dan opsi "autoread" tetap menjadi pilihan pengguna di Pengaturan.

## Koneksi
- Percobaan ulang saat gagal membuka koneksi: 5 kali, jeda 2, 4, 8, 16 detik (maks 20).
- Percobaan ulang saat stream terputus: 5 kali, jeda bertahap (maks 15 detik).
- Stream OpenAI yang berakhir tanpa `[DONE]` atau `finish_reason` dianggap terputus dan diulang.
- Belum ada: lanjut otomatis setelah percobaan habis. Pengguna perlu mengirim "Lanjutkan".

## Diketahui belum selesai
- Pemindahan ke ViewModel atau coroutine belum dilakukan. `MainActivity.kt` masih besar dan memakai status global.
- Dua sistem tampilan langkah masih berjalan berdampingan: `ActivityFeed` dan `InlineActivityBlock`. `InlineActivityBlock` belum dipasang ke chat.
- Belum diuji di perangkat nyata.
