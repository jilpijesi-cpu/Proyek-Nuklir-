# Perbaikan UI aktivitas AI

Berkas diubah: ActivityFeed.kt (ditulis ulang), ActivityZone.kt, ActivityModel.kt (baru), MainActivity.kt, AgentLoop.kt,
ToolExecution.kt, StatusBar.kt, ChatMessages.kt, ChatControls.kt, ActivityFeedTest.kt, ActivityModelTest.kt (baru), STRUKTUR.md.

- Kartu: maks 2, tinggi wadah tetap; kartu baru naik dari bawah, yang lama bergeser ke atas, yang tertua keluar ke atas (420 ms).
  Baris kartu yang keluar tetap hidup, jadi tugasnya terus berjalan dan hasilnya tetap dicatat.
- Progres: persen + bar hanya dari keluaran perintah (git/wget/curl/pip/apt/cmake/ninja dsb). Tanpa angka nyata: bar tak-tentu
  tanpa persen. Perintah selain unduh/instal/build tidak punya bar.
- Teks tengah: satu baris dari status AI sebenarnya (fase + judul langkah), teks sama tidak diulang, minimal 900 ms antar ganti.
- Laporan akhir: ringkasan, perubahan (file ditulis, build/instal/unduh, APK siap), status Berhasil/Gagal/Dihentikan.
- Riwayat lama dipulihkan sebagai daftar satu baris di chat (bukan kartu bergerak).

Uji: ./gradlew testDebugUnitTest assembleDebug (belum bisa dijalankan di lingkungan pengembangan ini, lihat balasan).
