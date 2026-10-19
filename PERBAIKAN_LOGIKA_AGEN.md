# Perbaikan logika agen (peningkatan keberhasilan)

1. AgentLoop.kt: galat tak terduga pada satu alat tidak lagi menggugurkan seluruh tugas. Galat dikembalikan
   sebagai hasil gagal ("exit=1 Alat gagal: ...") agar model bisa membaca errornya dan memperbaiki diri.
   Pembatalan oleh pengguna tetap dilempar seperti biasa.
2. TermuxShell.kt: timeout kini mengembalikan "exit=124 Timeout ..." dengan arahan untuk mengecek proses
   dulu. Sebelumnya pesannya tidak dikenali pengaman error berulang.
3. MainActivity.kt (baseSystem): ditambah ATURAN KERJA (rencana singkat, baca error sebelum mengulang,
   cek proses sebelum mengulang perintah timeout, buktikan hasil sebelum menyatakan selesai).
4. AgentGuard.kt: fungsi murni recoverableToolResult dan timeoutResult.
5. Tes baru: AgentRecoveryTest.kt.

Belum dikompilasi di lingkungan ini. Jalankan: ./gradlew testDebugUnitTest assembleDebug

## Tahap pemahaman (TaskBrief.kt)
- Sebelum tugas baru dijalankan, model merumuskan tujuan dan langkah awal (PAHAM:) atau bertanya bila informasi penting kurang (TANYA:).
- PAHAM dimasukkan ke prompt sistem sebagai arahan untuk tugas itu. TANYA dikirim sebagai jawaban dan tugas tidak dijalankan.
- Dapat dimatikan dengan preferensi "understand" = false (belum ada tombol di Pengaturan).
- Tes: TaskBriefTest.kt.

## Sifat default agen (baseSystem)
- Jujur, teliti, hemat (ringkas), hati-hati dengan perintah berisiko, tidak berlebihan dalam bertanya.

## Izin dan dialog (tanpa penolakan otomatis)
- confirm() kini menampilkan dialog persetujuan sungguhan (showApprovalDialog). Area sensitif hanya diberi peringatan merah, tidak ditolak diam-diam.
- "Ya, semua" berlaku untuk sisa tugas. Tanpa jawaban dalam 10 menit dianggap tidak.
- Tool baru ask_user (AskUser.kt): agen bertanya lewat dialog dengan pilihan atau jawaban bebas.
- Mode hanya baca dan opsi autoread tetap pilihan pengguna di Pengaturan.
- Belum dikompilasi: jalankan ./gradlew testDebugUnitTest assembleDebug.

## Koneksi AI
- ProviderOpenAI.kt: IOException di tengah stream tidak lagi ditelan. Sebelumnya jawaban yang terpotong dianggap lengkap, dan percobaan ulang tidak pernah terjadi.
- ModelCall.kt: percobaan ulang stream naik dari 4 ke 5 kali, dengan jeda bertahap (2, 4, 8, ... maks 15 dtk).
- Belum dikompilasi: jalankan ./gradlew testDebugUnitTest assembleDebug.

## Kejujuran agen
- Status laporan baru UNVERIFIED ("Belum terverifikasi"): dipakai bila ada perubahan file yang belum diikuti build/verifikasi yang berhasil. Sebelumnya laporan bisa berbunyi "Berhasil" tanpa bukti.
- Verifikasi APK dihitung: apktool b, apksigner verify, aapt dump, zipalign. Decompile (apktool d) tidak dihitung sebagai verifikasi.
- Prompt sistem: aturan KEJUJURAN, tidak menyatakan berhasil untuk perubahan yang belum dibuktikan.
- Belum dikompilasi: jalankan ./gradlew testDebugUnitTest assembleDebug.

## Penyelarasan aturan (memilih kebebasan agen)
- Prompt: aturan "jangan mengubah ~/agentapp" dihapus. Kebebasan memutuskan cara kerja dan kapan bertanya lewat ask_user ditegaskan.
- Prompt: aturan pembuktian hanya untuk perubahan kode atau file, sesuai pengingat verifikasi di kode.
- Tahap pemahaman tidak lagi menahan tugas. TANYA dicatat sebagai asumsi, dan agen bisa bertanya sendiri lewat ask_user.
- "Ya, semua" tetap berlaku untuk sisa tugas. Dokumentasi dialog diselaraskan. Kode mati allowAllApplies dihapus.
- Belum dikompilasi: jalankan ./gradlew testDebugUnitTest assembleDebug.

## Keyboard setelah kirim
- Setelah pesan dikirim, keyboard tertutup dan fokus kolom input dilepas (hideKeyboard di AgentLoop.kt).
- Belum dikompilasi: jalankan ./gradlew testDebugUnitTest assembleDebug.

## Perbaikan lanjutan (poin 5, 6, 7)
- 5. Dialog: thread agen yang menunggu dialog dilepas saat Activity ditutup atau diputar (releaseWaits di AskUser.kt). Tidak lagi macet 10 menit.
- 6. Koneksi OpenAI: stream yang berakhir tanpa [DONE] atau finish_reason dianggap terputus dan diulang.
- 7. Dokumentasi: laporan lama dipindahkan ke arsip/, DOKUMENTASI.md menjadi sumber utama.
- 9 (struktur ulang ke ViewModel/coroutine) belum dikerjakan: perlu build yang stabil dulu.
- Belum dikompilasi: jalankan ./gradlew testDebugUnitTest assembleDebug.

## Perbaikan dari rekaman layar
- Classify.kt: cek versi (java -version, gradle -v, android --version, dll.) tidak lagi dianggap menulis file. Sebelumnya semua perintah yang tidak dikenal diberi label "Mengubah atau membuat file".
- Keyboard: ditutup lagi setelah dialog persetujuan atau dialog ask_user ditutup, karena fokus kembali ke kolom input.
- Nama produk "Claude Mythos" diganti "AgentApp" di prompt, UI, notifikasi, dan label aplikasi (AndroidManifest).
- Belum bisa diperbaiki dari video: langkah yang tampak macet setelah YA ditekan. Perlu log dari perangkat.
- Tombol "YA, SEMUA" sudah dikirim dari kode. Jika tidak muncul di perangkat, kemungkinan APK yang diuji berasal dari build sebelumnya.
- Belum dikompilasi: jalankan ./gradlew testDebugUnitTest assembleDebug.

## Versi build
- versionCode 1 -> 2 dan versionName "1.0" -> "1.1-perbaikan-agen" agar APK hasil perbaikan bisa dibedakan dari APK lama.
- Cara cek: Pengaturan Android > Aplikasi > AgentApp > Versi.

## Tugas berhenti terlalu cepat
- Penyebab: model menyatakan rencana (dari tahap pemahaman) tetapi tidak memanggil tool, lalu loop menganggap tugas selesai.
- Perbaikan: jika belum ada langkah tool sama sekali, loop memberi satu kesempatan lagi dengan pengingat yang membiarkan agen memutuskan sendiri.
- Belum dikompilasi: jalankan ./gradlew testDebugUnitTest assembleDebug.

## Mode otonom (default aktif)
- Preferensi "autonomous" (default true). Saat aktif: tidak ada dialog persetujuan, ask_user tidak menunggu pengguna dan agen memutuskan sendiri.
- Prompt sistem menambahkan instruksi MODE OTONOM: kerjakan sampai tujuan tercapai, coba pendekatan lain bila gagal, laporkan jujur di akhir.
- Tetap berlaku: tombol Berhenti, log aktivitas, status laporan jujur (Berhasil, Belum terverifikasi, Gagal).
- Untuk mematikan: set preferensi autonomous = false (belum ada tombol di Pengaturan).
- Belum dikompilasi: jalankan ./gradlew testDebugUnitTest assembleDebug.

## Build di GitHub Actions
- .github/workflows/build-apk.yml: menjalankan tes unit lalu membangun APK debug di server GitHub (JDK 17, Android SDK 34, Gradle 8.9).
- Hasil APK tersedia sebagai artifact "agentapp-debug" di tab Actions.
- Cara pakai: buat repositori GitHub, upload isi folder proyek (build.gradle.kts di root), lalu buka tab Actions.

## Build di Termux (satu perintah)
- build-termux.sh: memasang paket, Android SDK 34, membuat konfigurasi dan Gradle wrapper, lalu build APK debug dan menyalinnya ke Download/agentapp-1.1.apk.
- Pemakaian: bash build-termux.sh (lewati tes). WITH_TESTS=1 bash build-termux.sh (dengan tes).

## Pembersihan kode tidak terpakai
- Dihapus: kindFor (AgentLoop.kt), detailText (ActivityFeed.kt), konstanta warna TERRACOTTA/PURPLE/ICON (Palette.kt), clearLine (ActivityZone.kt), kelas FlowBar (Views.kt), serta logoTile, suggestions, dan cards yang tidak pernah ditampilkan (MainActivity.kt).
- Dipertahankan: ApkProvider, karena dipakai AndroidManifest.xml.
- Verifikasi: keseimbangan kurung tiap berkas tetap, dan analisis referensi tidak menemukan sisa kode tidak terpakai.
- Belum dikompilasi: jalankan ./gradlew testDebugUnitTest assembleDebug atau bash build-termux.sh.

## Hapus fungsi yang hanya dipakai tes
- Dihapus dari ActivityModel.kt: hiddenCount, previewOf, lastLine, feedSummary, feedHeadline (beserta kelas Headline).
- Tes yang memakainya dihapus dari ActivityFeedTest.kt. Tes MAX_VISIBLE_ROWS dipertahankan.
- Belum dikompilasi: jalankan WITH_TESTS=1 bash build-termux.sh.

## Mode sandbox (proot-distro)
- File baru: CommandPolicy.kt, CommandRunner.kt, SandboxRunner.kt, SandboxIntegration.kt, SANDBOX.md; tes: CommandPolicyTest.kt, SandboxRunnerTest.kt.
- Diubah (kecil): Classify.kt (unwrap private -> internal; classify() tidak berubah), ToolExecution.kt (hook sandbox, confirm(force)),
  CommandLog.kt (kolom tambahan berdefault), ContextSummary.kt (kalimat prompt), SettingsDialog.kt (satu kotak centang).
- Default mati. Belum dikompilasi: jalankan ./gradlew testDebugUnitTest assembleDebug atau bash build-termux.sh.
