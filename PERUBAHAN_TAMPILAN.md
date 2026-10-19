# Perubahan tampilan v2.2 (modern, sederhana, tajam)

Tujuan: layar lebih bersih, satu cara untuk tiap hal, mudah dioperasikan satu tangan.
Logika agen, penyedia model, dan keamanan tidak diubah.

## Chat
- Latar putih bersih (dulu gradasi biru berawan). Teks AI 16,5sp, spasi baris 1,35, warna tinta tunggal.
- Hapus kilau biru berjalan pada teks yang sedang mengalir, dan hapus kata tebal/kode yang berwarna biru.
  Tebal = tebal biasa; kode inline = abu lembut.
- Hapus header "AI Agent - status" di tiap balasan (mengulang baris aktivitas). Status hanya di SATU pil abu di atas jawaban.
- Pil status tanpa awalan "AI Agent"; hasil akhir ringkas: "Berhasil - n langkah".
- Gelembung pengguna lebih rapat; chip saran putih bergaris halus (tidak lagi biru).

## Header dan input
- Header: pil model di kiri, chat baru + pengaturan (ikon roda gigi) di kanan. Ikon menu tiga garis (yang dulu membuka Pengaturan) dan avatar dihapus.
- Input: satu tombol aksi saja (mikrofon saat kosong, panah kirim saat ada teks, kotak saat berjalan). Tombol mikrofon ganda dihapus.
- Layar kosong kini punya sapaan dan tiga contoh perintah (mengisi kolom input, tidak langsung menjalankan).

## Pengaturan, model, izin
- Pengaturan jadi lembar bawah: Penyedia AI, API key, Simpan. Menu pendukung berupa baris tautan. Opsi lanjutan memakai saklar dengan keterangan satu baris.
- Pemilih model jadi lembar bawah dengan daftar bersih, centang pada model aktif, penanda "sibuk".
- Dialog izin: penjelasan, label risiko berwarna lembut, perintah di kotak abu. Tombol "Jalankan", "Tolak", "Ya, semua".
- Dialog sistem: sudut 28dp, tombol tanpa huruf kapital semua.

## File
- Baru: Ui.kt (pembantu bentuk, tombol, lembar bawah).
- Diubah: Palette, MainActivity, ChatMessages, Markdown, MessageMenu, ActivityZone, AgentStepList, StatusBar,
  SettingsDialog, ModelPicker, ApprovalDialog, ChatControls, ModelCall/ToolExecution (hanya teks tombol), styles/colors/dialog_bg.
- Belum dikompilasi di lingkungan penyusunan: jalankan WITH_TESTS=1 bash build-termux.sh.

## AI bekerja mandiri (v2.3)
- Prompt sistem ditulis ulang: dari sekitar 30 aturan kaku menjadi satu paragraf pendek. Isinya hanya fakta lingkungan
  (Termux, folder kerja, alat, folder lampiran) dan kebiasaan yang meningkatkan mutu kerja (pahami tujuan, baca dulu,
  belajar dari kegagalan, periksa hasil, lapor jujur). Keputusan bertindak atau menjawab ada di AI.
- Dihapus: pesan dorongan tersembunyi "Kamu belum menjalankan langkah apa pun..." (AgentLoop).
- Tahap "Memahami permintaan" kini mati secara bawaan (pengaturan "understand" = false); hemat satu panggilan model per pesan.
- Tetap ada: dialog persetujuan, mode hanya baca/sandbox/otonom, pengingat verifikasi build (maks 2 kali).

## Baris status live + terminal live (v2.5)
Tujuan: satu baris status yang bersih seperti di aplikasi Claude, dan kode/perintah AI bisa dilihat langsung saat diketuk.

- Pil abu + daftar langkah lama diganti `LiveStatusLine` (LiveStatusLine.kt): ikon, teks berkilau, panah.
  - Ikon berganti sesuai kerja AI: titik oranye mengorbit saat berpikir; saat menjalankan langkah ikon mengikuti jenisnya
    (baca, cari, tulis, build = roda gigi berputar, arsip, unduh, uji, ...). Ikon baru muncul dengan efek membal.
  - Teks berkilau abu-ke-gelap, naik-memudar saat berganti, dipotong "..." sebelum panah. Tahan 0,7 detik agar tidak berkedip (TextGate).
  - Teks langkah kini memakai kalimat `deskripsi` yang ditulis AI sendiri (mis. "Memeriksa isi arsip ZIP"), bukan judul generik.
  - Saat selesai: ikon centang hijau / silang merah / dll, teks diam ("Berhasil - 3 langkah"). Panah tetap bisa dibuka untuk meninjau.
- Ketuk baris -> terminal gelap terbuka dengan animasi tinggi + pudar (`LiveTerminal`):
  - Perintah diberi prompt `$` dan diwarnai (perintah cyan, opsi oranye, string hijau, operator ungu, variabel kuning).
  - Keluaran mengalir baris demi baris (efek ketik) dan diwarnai: `> Task` redup, `BUILD SUCCESSFUL` hijau, error merah, warning kuning,
    angka oranye, path ungu. Kursor hijau berkedip selama perintah jalan.
  - Tulis file: path + isi bernomor dengan warna kode; setelah selesai tampil diff (+ hijau, - merah).
  - Deretan chip langkah (geser samping): ketuk untuk melihat langkah lain; ketuk chip terakhir untuk kembali mengikuti live.
  - Tombol Salin (salin isi terminal), Besar/Kecil (tinggi 250 / 460 dp), penghitung waktu berjalan, tombol Setuju/Tolak saat menunggu izin.
  - Tekan lama judul terminal -> panel komputer lengkap (seperti sebelumnya).
  - Gulir otomatis mengikuti keluaran terbaru; bila pengguna menggulir ke atas, tidak ditarik turun.
- Pembacaan keluaran live (`LiveTail`) dipercepat dari 2,5 dtk ke 1,5 dtk (build tetap 5 dtk).
- Baru: LiveStatusLine.kt (tampilan), TerminalLex.kt (pewarnaan murni, teruji), test TerminalLexTest.kt.
- Diubah: ActivityZone.kt (memakai LiveStatusLine), AgentLoop.kt (`stepTitleFor`), LiveTail.kt (interval).
- Belum dikompilasi di lingkungan penyusunan: jalankan WITH_TESTS=1 bash build-termux.sh.


## Tampilan messenger biru (v2.6)
Tujuan: chat sama seperti screenshot acuan, seluruh aplikasi diselaraskan. Logika agen/penyedia/failover tidak diubah.

- Latar: gradasi biru tua ke biru langit (warna diambil dari latar myBCA) di `ChatBackground`; `chat_bg.png` (tak terpakai, 2,6 MB) dihapus.
- Bilah atas pucat: panah kembali, tiga bola oranye + "Menunggu perintah Anda..." / "Sedang bekerja...", telepon (suara), caret (pilih model), menu tiga titik (Chat baru, Model AI, Pengaturan).
- Gelembung pengguna: navy gelap berekor di kanan atas, teks putih, jam + centang ganda biru. Gelembung AI: putih berekor di kiri atas, jam abu. (`BubbleDrawable` di ChatLook.kt)
- Riwayat lama yang dipulihkan tampil tanpa jam (waktu aslinya memang tidak tersimpan).
- Status AI: tiga bola oranye mengilap yang memantul bergantian (`drawBalls`, dipakai DotsView/OrbitDotsView) + teks gelap; ikon hasil di lingkaran putih.
- Daftar langkah dalam gelembung putih (`StepChecklist.kt`) dari langkah nyata: centang hijau (selesai), roda gigi biru berputar (berjalan), lingkaran hijau (menunggu izin), silang merah (gagal).
- Kotak kode: judul gelap (ikon file, nama file/bahasa, label bahasa di kanan; ketuk = salin), nomor baris, kode berwarna. Bila fence berisi nama file (```kotlin Nama.kt) tampil kartu file (ukuran, tombol unduh ke folder Download, Android 10+).
- Kolom input putih bulat: wajah (contoh perintah), "Pesan...", penjepit (unggah file/gambar), garis, mikrofon/kirim/berhenti.
- Layar kosong, catatan, tombol Pasang APK, dan "Catatan perintah" disesuaikan dengan latar biru. Warna merek jadi biru #0A74D0.
- Belum dikompilasi di lingkungan penyusunan: jalankan WITH_TESTS=1 bash build-termux.sh.

### Baris status live sesuai video acuan (v2.6b)
- Baris status kembali ke gaya video: strip putih gading (#F9F9F6) selebar chat, tiga titik terakota kecil yang mengorbit (`OrbitDotsView`), teks abu terang dengan pita gelap menyapu ke kanan (2 detik per putaran, `ShimmerText.shimmerMs`), dipotong "..." sebelum panah di kanan.
- Pergantian teks: pudar-silang singkat dengan geser naik tipis; ketuk baris membuka terminal live (tidak berubah).
- Tiga bola oranye mengilap tetap dipakai di bilah atas.

### Perbaikan dari rekaman layar (v2.6c)
- Gelembung AI kosong yang tertinggal di atas baris status dihapus (gelembung baru disembunyikan sampai ada isi; hilang bila balasan kosong).
- Jam di gelembung pengguna tidak lagi terpotong ("17.26" utuh).
- Bola oranye di bilah atas: cahaya tidak lagi terpotong kotak, bola terpisah rapi; judul bilah atas dipersempit agar "Menunggu perintah Anda..." muat.
- Teks percobaan ulang dipersingkat: "Server sibuk (429) · ulang 12 dtk (1/3)" agar hitungan mundur tidak terpotong.

### Bola oranye bilah atas (v2.6d)
- Bola lebih kecil (jari-jari ~4 dp) dan bergerak pelan ke segala arah dengan gerak Lissajous yang mulus (satu putaran 7 detik, tanpa lompatan); cahaya tetap di dalam kotak.

## Pembersihan kode (v2.6e)
Tujuan: membuang sisa kode hasil pengeditan yang tidak lagi dipakai aplikasi. Perilaku aplikasi tidak berubah.

- Dihapus dari aplikasi: `CardQueue`, `slotFor`, `CARD_ANIM_MS`, `MAX_VISIBLE_ROWS` (ActivityModel), `appendOutput` (AgentStepModels),
  `nextFallbackModel`, `buildFallbackPlan`, `FallbackStep` (ApiErrors), `FailoverTracker`, `FoRow`, `FoRowSnap`, `FoSnapshot`, `foNote`
  (Failover; peralihan AI kini memakai `buildChain`), `classifyError` (pakai `parseToolResult(...).errorKind`),
  `decorateAgentBubble` dan `refreshAgentBubbleHeader` (fungsi kosong), warna `CODE_BG`, `CHIP`, `STEP_IDLE`, properti `colors` dan
  parameter `dotColor` pada `DotsView`, parameter `content` pada `addCopyRow`, satu import tak terpakai di Markdown.kt.
- Tes yang hanya menguji kode di atas ikut dihapus (ActivityModelTest, ActivityFeedTest, ApiErrorsTest, FailoverTest); tes lain disesuaikan.
- Sengaja dipertahankan: `ApkProvider` (terdaftar di AndroidManifest) dan `putEncryptedValue` (diuji SecretStorageTest).
- Belum dikompilasi di lingkungan penyusunan: jalankan WITH_TESTS=1 bash build-termux.sh.

## Mode "Susun kode dulu, izin saat build" (v2.7)
Alur: pengguna melampirkan file, AI membacanya langsung (tanpa izin), memahami perintah, menyusun semua kode, baru minta izin saat build.

- Baru: pengaturan `draft` (bawaan AKTIF) di Pengaturan lanjutan, baris "Susun kode dulu, izin saat build".
- `write_file` di dalam folder kerja jalan tanpa dialog; cadangan, tombol urungkan, dan catatan perintah tetap ada (mode log: "Otomatis (susun kode)").
- Tetap minta persetujuan: tulis ke area sensitif, folder sistem Termux, atau di luar folder kerja; semua `run_command` yang bukan baca-saja (termasuk build).
- Mode hanya baca dan mode sandbox tetap menang atas mode ini. Prompt sistem ikut memberi tahu AI urutan kerjanya.
- Membaca lampiran tidak butuh perubahan: perintah baca sudah otomatis lewat "Setujui otomatis perintah baca".
- Belum dikompilasi di lingkungan penyusunan: jalankan WITH_TESTS=1 bash build-termux.sh.

## Perbaikan streaming balasan (v2.7a)
Dari rekaman layar: gelembung balasan macet sempit dan terpotong ("<th inki ng> ..."), tag `<thinking>`/`<response>` tampil mentah,
dan AI bertanya lewat dialog berulang-ulang walau sudah dilewati.

- ChatMessages.kt: efek ketik ditulis ulang. Tiap tik hanya membatalkan tik-nya sendiri (bukan semua callback, yang dulu ikut membunuh
  penutup gelembung), makin tertinggal makin cepat, dan gelembung SELALU difinalisasi saat stream selesai. Balasan baru tidak lagi
  menimpa gelembung yang sedang ditutup, dan percobaan ulang koneksi menggambar ulang gelembung dari awal.
- TextClean.kt (baru): `stripModelTags` membuang blok `<thinking>`, membuka bungkus `<response>`, dan memotong awalan tag yang baru setengah datang;
  isi blok kode tidak disentuh. Dipakai saat streaming dan saat menggambar pesan (riwayat lama ikut bersih). Tes: TextCleanTest.
- AskUser.kt: setelah pengguna menekan "Lewati", pertanyaan berikutnya pada tugas yang sama tidak lagi memunculkan dialog;
  AI diberi tahu untuk memutuskan sendiri dengan asumsi wajar.

## Baris status tanpa pengurung (v2.7b)
Mengikuti video acuan: baris status AI ("Membaca ApiErrors.kt >") kini polos, tanpa kartu, latar, atau garis.

- LiveStatusLine.kt: latar kartu bulat dihapus (`background = null`); ikon + teks + panah langsung di atas latar chat biru.
- Warna menyesuaikan latar biru: teks putih redup dengan pita putih terang yang menyapu (shimmer), panah putih lembut, titik tetap oranye.
- Ikon kerja selalu tiga titik oranye yang mengorbit pada elips mendatar (depan lebih besar, belakang lebih kecil), seperti acuan.
  Ikon hasil (centang, silang, stop, jam) tetap dipakai hanya di akhir tugas.
- Teks berganti dengan pudar-silang di tempat yang sama (teks lama memudar sambil teks baru muncul), tanpa bergeser.
- Panah menempel di ujung teks; teks panjang dipotong dengan "…" dan panah tetap terlihat.
- Font tipis (sans-serif-light) 16sp. Daftar "Langkah yang dikerjakan" (gelembung putih di bawahnya) tidak diubah.

## Balasan AI tanpa kotak (v2.7c)
Dari tangkapan layar: gelembung putih balasan AI macet sempit dan teksnya berhenti di tengah ("Hal / o! S / aya / sia").

- Gelembung putih balasan AI dihapus. Teks tampil langsung di atas latar biru, selebar layar, jadi lebarnya tidak lagi bergantung pada isi teks
  (penyebab gelembung menyempit hilang dari rancangan).
- Teks putih 16sp, jarak baris 1,35, tanpa pemenggalan suku kata, dan strategi pemenggalan sederhana supaya baris yang sudah tampil tidak
  bergeser saat huruf baru muncul. Gaya yang sama dipakai saat mengetik dan setelah selesai, jadi tidak ada lompatan tampilan di akhir.
- Efek ketik tetap: teks muncul huruf demi huruf; makin tertinggal makin cepat.
- Pengaman: kegagalan menggambar satu langkah tidak lagi menghentikan efek ketik, dan bila tik terhenti, teks lengkap dipaksa tampil 4 detik
  setelah stream selesai.
- Jam balasan dan butir daftar memakai putih lembut agar terbaca di atas biru. Kartu kode, kartu file, dan daftar langkah tidak diubah.

## Gelembung pesan pengguna putih (v2.7d)
- Gelembung pesan pengguna kini putih (sebelumnya navy gelap). Teks memakai warna tinta gelap, jam abu, centang ganda biru tetap.
- Perubahan di Palette.kt (`BUBBLE_ME`, `TIME_ME`) dan ChatMessages.kt (warna teks pesan pengguna).

## Baris status tetap hidup (v2.7e)
- LiveStatusLine.kt: setelah AI selesai atau berhenti, titik oranye tetap mengorbit dan teks tetap berkilau (sebelumnya berhenti dan diganti ikon
  centang/silang/stop/jam). Hasil akhir kini terbaca dari teksnya ("Berhasil · 3 langkah", dst.); panah tetap bisa dibuka untuk meninjau langkah.
- `LiveStatusLine.finish(text)` kini hanya menerima teks; `ActivityZone.showReport` tidak lagi memilih ikon hasil.

## Tiga bola di bilah atas melayang acak (v2.7f)
- Views.kt (`DotsView`, `drawBalls`, `ballCenter`): gerak tiga bola oranye di bilah atas diganti dari pola Lissajous kecil yang berulang tiap 7 detik
  menjadi gerak acak halus. Tiap sumbu memakai dua gelombang sinus berfrekuensi tak sebanding, sehingga pola nyaris tak pernah berulang.
- Bola menjelajah hampir seluruh area ikon, condong ke jalurnya masing-masing agar tidak menumpuk, dan ukurannya sedikit "bernapas".
  Waktu memakai jam sistem, jadi gerak tetap mulus saat layar kembali dibuka.
- Tes baru BallsTest: selalu di dalam area, tanpa lompatan, tidak berulang dalam waktu dekat, dan tiap bola punya jalur berbeda.

## Lampiran tampil di chat (v2.8)
Sebelumnya pesan dengan lampiran hanya menampilkan teks "File terlampir di <path>". Kini:

- Foto/gambar tampil sebagai gambar mini di gelembung pesan (sudut membulat, tinggi mengikuti rasio gambar). Ketuk untuk melihat layar penuh.
- File lain tampil sebagai kartu: ikon file, nama, jenis, dan ukuran.
- Baris "Gambar/File terlampir di <path>" tetap dikirim ke AI, tetapi tidak lagi ditampilkan di gelembung. Bila pengguna hanya mengirim lampiran,
  kalimat bawaan ("Periksa lampiran ...") juga disembunyikan.
- Gambar mini dibuat saat lampiran dipilih (FileImport.kt `saveThumb`) dari Uri asli dan disimpan di penyimpanan privat (`files/thumbs`), dengan
  orientasi EXIF diterapkan agar foto kamera tidak miring. Jadi tampil juga setelah aplikasi dibuka ulang (riwayat ikut menampilkan lampiran),
  dan tidak bergantung izin baca Download. Bila gambar mini tidak ada, dipakai file asli; bila keduanya tidak ada, jatuh ke kartu file.
- File baru: AttachmentText.kt (pemisah teks dan lampiran, murni Kotlin), ChatAttachments.kt (tampilan). Tes: AttachmentTextTest.

## Perbaikan dari rekaman layar (v2.8a)
- LiveStatusLine.kt: teks status yang menumpuk/terbaca kacau setelah menekan berhenti. Bila animasi pudar-silang dibatalkan di tengah jalan,
  teks lama sempat menempel di layar; kini disetel ke transparan.
- SandboxIntegration.kt: bila Mode sandbox menyala tetapi Debian belum terpasang, chat menampilkan satu catatan berisi dua jalan keluar
  (matikan Mode sandbox, atau `pkg install proot-distro && proot-distro install debian`).

## Riwayat langkah dipulihkan seperti tampilan live (v2.8b)
- ActivityFeed.kt `restoreFeed`: setelah aplikasi dibuka ulang, langkah tidak lagi tampil sebagai baris merah/abu polos yang sulit dibaca di atas biru
  dengan nama generik ("Menjalankan pemeriksaan", "Memvalidasi hasil"). Kini memakai kartu putih "Langkah yang dikerjakan" yang sama dengan saat
  tugas berjalan (ikon centang hijau atau silang merah), dan judulnya memakai kalimat yang ditulis AI sendiri (`stepTitleFor`), mis. "Melihat daftar file".
- Kartu menampilkan maksimal 6 langkah terakhir dan "+N langkah sebelumnya" bila lebih.

## Daftar langkah tanpa kotak dan tata letak pemberitahuan (v2.9)
- StepChecklist.kt: kotak putih "Langkah yang dikerjakan" dihapus. Daftar langsung di atas latar chat: judul kecil putih lembut, tiap langkah satu baris
  (ikon 20dp + teks putih 15sp). Ikon disesuaikan agar terbaca di atas biru: centang hijau muda, silang merah muda, roda gigi oranye saat berjalan.
- Semua elemen pemberitahuan AI kini rata kiri pada satu garis (14dp dari tepi isi chat): teks balasan, baris status, daftar langkah,
  catatan sistem (mis. "Dihentikan."), dan chip saran. Catatan sistem sedikit lebih besar dengan jarak baris lebih lega.
- Daftar langkah hasil pemulihan riwayat (restoreFeed) memakai tampilan yang sama.
- Warna tak terpakai dihapus dari Palette.kt: `BUBBLE_AI`, `STEP_DONE`, `STEP_BUSY`.

## Teks baris status lebih jelas dan elegan (v2.9a)
Dari video acuan: teks baris status ("Membaca ApkProvider.kt >") terbaca samar. Penyebabnya: huruf tipis (light) berwarna putih 70% di atas
gradasi biru; di area biru muda kontrasnya hanya sekitar 1,7:1. Selain itu teks lama dan baru sempat bertumpuk saat berganti, dan
pemotongan "…" jatuh di tengah kata. Hanya tampilan baris status yang diubah; logika agen tidak disentuh.

- Huruf: dari sans-serif-light 16sp menjadi sans-serif-medium 15,5sp dengan jarak antarhuruf +1,2% (tegas tetapi tetap halus).
- Warna: dasar teks putih 85% (dulu 70%), kilau putih penuh, panah 90%. Kontras naik dan kilau tetap terlihat.
- Bayangan tipis biru tua (30%, turun sekitar 0,75dp) di bawah teks agar tepi huruf tajam di atas biru muda.
  Digambar sebagai lintasan kedua di `ShimmerText.onDraw` (`haloColor`/`haloDy`, bawaan mati, jadi pemakai lain tidak berubah);
  `setShadowLayer` sengaja tidak dipakai karena bayangannya ikut shader kilau.
- Pergantian teks: teks lama hilang 110 ms, teks baru muncul menyusul (tunda 60 ms, 230 ms), tetap di tempat yang sama tanpa bergeser.
  Dua teks tidak lagi bertumpuk menjadi bayangan kabur.
- Teks yang kepanjangan dipotong di batas kata ("Memeriksa isi…", bukan "Memeriksa is…") memakai lebar teks sebenarnya.
  Tanda baca yang menggantung sebelum "…" dibuang. Logika murninya di `fitWords` (TextFit.kt).
- Nama file di dalam teks (mis. `ApkProvider.kt`) tampil dengan huruf monospasi 94% agar mudah dikenali (`fileTokenRanges`).
  Untuk mematikan: ganti isi `styled()` di LiveStatusLine.kt menjadi `return t`.
- Baru: TextFit.kt, TextFitTest.kt. Diubah: LiveStatusLine.kt (`Live.LINE_*`, `label`, `oldLabel`, `swapText`), Views.kt (`ShimmerText`).
- Belum dikompilasi di lingkungan penyusunan: jalankan WITH_TESTS=1 bash build-termux.sh.
