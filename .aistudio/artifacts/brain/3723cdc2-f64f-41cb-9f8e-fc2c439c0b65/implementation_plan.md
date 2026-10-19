# Penyederhanaan Halaman Pengaturan AgentApp 2.6

Merancang ulang lembar Pengaturan menjadi antarmuka satu kolom yang minimalis, rapi, dan nyaman dioperasikan di layar HP Android dengan mengelompokkan seluruh kontrol ke dalam 4 bagian terstruktur (**AI & Model**, **Eksekusi**, **Aplikasi**, dan **Lanjutan**) tanpa menghilangkan fungsi maupun kompatibilitas penyimpanan yang sudah ada.

## User Review & Critical Decisions

> [!IMPORTANT]
> Keputusan desain berikut telah dikonfirmasi berdasarkan preferensi Anda pada tahap klarifikasi awal:

- **Keputusan Tata Letak Awal (`Confirmed`)**: Menampilkan opsi-opsi utama dari bagian **AI & Model**, **Eksekusi**, dan **Aplikasi** secara langsung saat lembar Pengaturan dibuka, sementara konfigurasi teknis yang jarang digunakan disembunyikan di dalam bagian **Lanjutan** yang dapat dibuka-tutup (*collapsible*).
- **Keputusan Cakupan Bagian Aplikasi (`Confirmed`)**: Menampilkan pengaturan perilaku antarmuka & asisten yang sudah ada di sistem (**Susun kode dulu, izin saat build** serta **Pahami tugas sebelum bertindak**) dengan label dan deskripsi singkat yang jelas tanpa menambah opsi kosmetik yang belum diperlukan.
- **Keputusan Perlindungan Pengaturan Berisiko (`Confirmed`)**: Menerapkan dialog peringatan dan konfirmasi eksplisit sebelum mengaktifkan 4 pengaturan berisiko:
  1. **Mode Otonom** (`autonomous` — jalankan perintah & pasang APK tanpa bertanya)
  2. **Mode Sandbox** (`sandbox` — isolasi perintah di dalam `proot-distro` Ubuntu)
  3. **Setujui otomatis perintah baca** (`autoread` — jalankan `ls`, `cat`, `grep`, dll. tanpa dialog)
  4. **Mode Analisis APK** (`apk_re` — batasi agen hanya untuk dekompilasi & analisis statis)

---

## 1. Overview & Core Concept

- **What It Does**: Menyajikan lembar Pengaturan (*bottom sheet*) yang ringkas dan bebas dari duplikasi atau istilah debug membingungkan. Pengguna dapat memilih penyedia AI, memasukkan API key dengan aman (dilengkapi tombol tampil/sembunyikan), melihat status kesiapan koneksi serta mengujinya langsung, mengatur izin eksekusi Termux/sandbox dengan perlindungan konfirmasi, dan menyesuaikan perilaku kerja asisten AI.
- **Target Audience / Persona**: Pengguna AgentApp 2.6 di perangkat Android yang membutuhkan akses cepat ke pengaturan harian di layar sempit tanpa harus menggulir daftar opsi teknis yang panjang.
- **Key Value**: Mengurangi beban visual di layar utama Pengaturan, mencegah pengaktifan mode berisiko secara tidak sengaja melalui dialog konfirmasi langsung, dan mempermudah verifikasi koneksi AI sebelum memulai percakapan.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Konfigurasi AI & Uji Koneksi**: Pengguna membuka Pengaturan -> memilih **Penyedia AI** dari dropdown (atau menempelkan API key sehingga penyedia terdeteksi otomatis) -> mengetuk tombol **Tampil/Sembunyi** di samping kolom API key bila ingin memeriksa karakter key -> melihat ringkasan model/status koneksi saat ini -> mengetuk tombol **Uji koneksi** untuk memverifikasi API key dan kesiapan model secara langsung, atau memilih **Pilih model** untuk mengganti model aktif.
  2. **Pengaturan Eksekusi & Konfirmasi Risiko**: Pengguna meninjau bagian **Eksekusi** (seperti tombol pintas **Siapkan Termux**, **Mode hanya baca**, **Setujui otomatis perintah baca**, dan **Mode sandbox**). Saat pengguna menyalakan sakelar berisiko (**Mode Sandbox**, **Setujui otomatis perintah baca**, **Mode Otonom**, atau **Mode Analisis APK**), dialog konfirmasi muncul menjelaskan konsekuensinya. Jika pengguna memilih **Batal**, sakelar otomatis kembali ke posisi mati.
  3. **Perilaku Aplikasi & Opsi Lanjutan**: Pengguna mengatur perilaku kerja asisten di bagian **Aplikasi** (**Susun kode dulu, izin saat build** dan **Pahami tugas sebelum bertindak**). Jika membutuhkan pengaturan teknis (seperti **Folder kerja**, **Alamat API / Base URL**, **Nama model manual**, **Mode otonom**, **Mode Analisis APK**, **Instruksi tambahan**, **Catatan perintah**, atau **Salin laporan masalah**), pengguna cukup mengetuk baris **Lanjutan** untuk membuka seluruh kontrol tersebut.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: *Clean Utilitarian Mobile Sheet* — mempertahankan bahasa desain asli aplikasi yang bersih, ringan, dan responsif di atas lembar putih bersudut membulat (`28dp` top radius).
  - *Color Palette & Mood*: Menggunakan token palet aplikasi secara konsisten:
    - `--surface` / Latar lembar: `#FFFFFF` (`Color.WHITE`)
    - `--field` / Latar kontrol & input: `#F3F5F9` (`Palette.FIELD`)
    - `--primary` / Aksen utama & tombol aktif: `#0A74D0` (`Palette.BRAND`)
    - `--text-primary` / Judul & label utama: `#1F1F1F` (`Palette.INK`)
    - `--text-secondary` / Deskripsi singkat & petunjuk: `#6B7280` (`Palette.INK_SOFT`)
    - `--divider` / Pemisah antarbagian: `#E8EAEF` (`Palette.DIVIDER`)
    - `--status-ok` / `--status-warn` / `--status-err`: `#16A34A` (`Palette.GREEN`), `#F59E0B` (`Palette.AMBER`), `#EF4444` (`Palette.RED`)
  - *Typography & Hierarchy*:
    - Judul Lembar: `20sp` Medium (`sans-serif-medium`)
    - Header Bagian (AI & Model, Eksekusi, Aplikasi, Lanjutan): `13sp` Medium berwarna aksen/sekunder tegas dengan jarak atas konsisten
    - Label Opsi & Sakelar: `15sp` Regular/Medium (`Palette.INK`)
    - Deskripsi Singkat Opsi: `12.5sp` Regular (`Palette.INK_SOFT`)
  - *Component Styling & Layout*: Satu kolom vertikal dengan jarak tepi `20dp`, jarak antar-item `8dp–12dp`, tinggi area sentuh minimum `48dp` untuk semua kontrol interaktif, dan pemisah tipis `1dp` antarbagian.

- **Interactive Feedback & Motion**:
  - Efek riak sentuhan (*ripple*) mengikuti sudut membulat pada setiap baris tautan, tombol, dan header akordeon **Lanjutan**.
  - Indikator status koneksi AI berubah secara real-time saat tombol **Uji koneksi** ditekan (`Menguji koneksi...` -> `Terhubung ✓` dengan warna hijau atau pesan penyebab kegagalan yang ringkas dengan warna merah/amber tanpa pernah membocorkan nilai API key).

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Pengelompokan 4 Bagian dengan Lanjutan Tertutup secara Default**
  - *Chosen Approach*: Menampilkan 3 bagian utama (**AI & Model**, **Eksekusi**, **Aplikasi**) langsung di tampilan awal dan menempatkan bagian ke-4 (**Lanjutan**) sebagai panel yang tertutup secara default (kecuali penyedia AI yang dipilih mewajibkan pengisian Base URL kustom/CLI).
  - *Why*: Menjaga lembar pengaturan tetap pendek, mudah dipindai di layar HP, dan menghindarkan pengguna harian dari opsi teknis seperti URL endpoint, folder kerja, atau mode berisiko tinggi.
  - *Alternatives Considered*: Menampilkan keempat bagian terbuka sekaligus (membuat halaman terlalu panjang di layar kecil) atau menutup keempat bagian dalam akordeon (menambah ketukan ekstra untuk mengganti penyedia/API key).

- **Decision 2: Penyembunyian API Key & Uji Koneksi Langsung tanpa Kebocoran Log**
  - *Chosen Approach*: Kolom API key menggunakan mode password tersembunyi secara default dengan tombol teks/ikon **Tampil/Sembunyi** di sebelah kanan dalam satu wadah input yang rapi, disertai baris status koneksi ringkas dan tombol **Uji koneksi**.
  - *Why*: Memudahkan pengguna memastikan tidak ada salah tempel karakter pada API key tanpa mengorbankan privasi layar, serta memungkinkan pengecekan validitas API key sebelum menyimpan pengaturan. Nilai API key tetap dienkripsi via Android Keystore (`Secret.encrypt`) dan tidak pernah ditulis ke log atau laporan.
  - *Alternatives Considered*: Menampilkan API key secara terbuka saat diketik (berisiko terlihat orang lain) atau hanya menguji koneksi setelah dialog ditutup (kurang informatif bila API key salah).

- **Decision 3: Konfirmasi Langsung saat Sakelar Berisiko Diaktifkan**
  - *Chosen Approach*: Menampilkan `AlertDialog` peringatan saat pengguna mengubah sakelar **Mode Otonom**, **Mode Sandbox**, **Setujui otomatis perintah baca**, atau **Mode Analisis APK** dari posisi mati ke aktif.
  - *Why*: Memberikan kejelasan risiko tepat pada saat pengguna menyentuh kontrol terkait, bukan baru muncul setelah tombol Simpan ditekan, sehingga pengguna sadar penuh akan perubahan izin eksekusi.
  - *Alternatives Considered*: Menunda seluruh dialog konfirmasi hingga tombol Simpan ditekan (membingungkan bila pengguna mengaktifkan lebih dari satu opsi berisiko sekaligus).

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Lembar Pengaturan (showSheet)                    │
├─────────────────────────────────────────────────────────────────────────┤
│  1. BAGIAN AI & MODEL                                                   │
│     ├── Dropdown Penyedia AI (Gemini, OpenAI, Anthropic, CLI, dll.)     │
│     ├── Baris API Key [ Input Tersembunyi (•••) | Tombol Tampil/Sembunyi]│
│     │   └── Deteksi Penyedia Otomatis (KeyDetect) & Peringatan Keystore │
│     ├── Baris Model & Status Koneksi Ringkas                            │
│     │   └── [ Indikator Status / Model Aktif ] + [ Tombol Uji Koneksi ] │
│     └── Panel Login CLI (khusus penyedia berbasis CLI)                  │
├─────────────────────────────────────────────────────────────────────────┤
│  2. BAGIAN EKSEKUSI                                                     │
│     ├── Sakelar: Mode hanya baca (readonly)                             │
│     ├── Sakelar: Setujui otomatis perintah baca (autoread) [Konfirmasi] │
│     ├── Sakelar: Mode sandbox proot-distro (sandbox)       [Konfirmasi] │
│     └── Tautan Cepat: Siapkan Termux (showSetup)                        │
├─────────────────────────────────────────────────────────────────────────┤
│  3. BAGIAN APLIKASI                                                     │
│     ├── Sakelar: Susun kode dulu, izin saat build (draft)               │
│     └── Sakelar: Pahami tugas sebelum bertindak (understand)            │
├─────────────────────────────────────────────────────────────────────────┤
│  4. BAGIAN LANJUTAN (Buka / Tutup ▾)                                    │
│     ├── Kolom Nama Model Manual & Alamat API / Base URL                 │
│     ├── Kolom Folder Kerja di Termux (workdir)                          │
│     ├── Sakelar: Mode otonom tanpa persetujuan (autonomous)[Konfirmasi] │
│     ├── Sakelar: Mode Analisis APK (apk_re)                [Konfirmasi] │
│     ├── Kolom Instruksi Tambahan Sistem (extra)                         │
│     └── Tautan Diagnostik: Catatan perintah & Salin laporan masalah     │
├─────────────────────────────────────────────────────────────────────────┤
│  FOOTER: [ Tombol Batal ]                     [ Tombol Simpan ]         │
└─────────────────────────────────────────────────────────────────────────┘
          │                                              │
          ▼                                              ▼
┌──────────────────────────────┐              ┌───────────────────────────┐
│ Uji Koneksi (ModelProbe)     │              │ Penyimpanan & Enkripsi    │
│ • fetchModels() / probe()    │              │ • SharedPreferences "cfg" │
│ • cliLoggedIn() untuk CLI    │              │ • Secret.encrypt Keystore │
└──────────────────────────────┘              └───────────────────────────┘
```

- **Data Model & State**:
  - Seluruh kunci `SharedPreferences` (`"cfg"`) dipertahankan 100% agar kompatibel dengan logika agen yang sudah berjalan:
    - `prov`: Penyedia AI aktif.
    - `p_<prov>_key`: API key terenkripsi AES-GCM lewat Android Keystore (`Secret.encrypt` / `Secret.decrypt`).
    - `p_<prov>_model`: Nama model yang dipilih untuk penyedia terkait.
    - `p_<prov>_base`: Alamat endpoint API / template perintah CLI.
    - `autoread` (`Boolean`, default `true`): Persetujuan otomatis perintah baca-saja.
    - `draft` (`Boolean`, default `true`): Mode susun kode dulu, minta izin saat build.
    - `understand` (`Boolean`, default `false`): Merumuskan pemahaman tugas (`understandTask`) sebelum menjalankan alat.
    - `readonly` (`Boolean`, default `false`): Mode hanya baca.
    - `autonomous` & `autonomous_ack` (`Boolean`, default `false`): Mode otonom dan status persetujuan risikonya.
    - `sandbox` (`Boolean`, default `false`): Isolasi perintah di dalam `proot-distro`.
    - `apk_re` (`Boolean`, default `false`): Mode analisis statis APK.
    - `workdir` (`String`, default `"kita"`): Direktori kerja di Termux.
    - `extra` (`String`, default `""`): Instruksi tambahan pada prompt sistem.

- **Interactive Component & State Mapping**:
  - **Tampil/Sembunyi API Key**: Mengetuk tombol di sebelah kanan kolom API key mengubah `inputType` antara `TYPE_TEXT_VARIATION_PASSWORD` dan `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` dengan mempertahankan posisi kursor serta jenis huruf tanpa memicu ulang deteksi penyedia secara keliru (`loading = true` selama transisi tampilan).
  - **Uji Koneksi AI**: Mengetuk tombol **Uji koneksi** menjalankan pemeriksaan di thread latar menggunakan konfigurasi yang sedang terisi di form (penyedia, base URL, model, dan API key yang sudah dinormalisasi). Untuk penyedia HTTP/API, sistem memanggil pemeriksaan daftar model / probe ringan dan memperbarui teks status (`Terhubung ✓ ...` atau pesan error singkat tanpa menyertakan API key). Untuk penyedia CLI, sistem memeriksa status login CLI (`cliLoggedIn`).
  - **Guard Sakelar Berisiko**: Fungsi pembantu untuk sakelar berisiko menangkap perubahan ke status aktif (`isChecked == true` saat pengguna mengetuk) dan menampilkan `AlertDialog` konfirmasi spesifik. Jika pengguna membatalkan atau menutup dialog, sakelar dikembalikan ke `false`; jika pengguna mengonfirmasi, sakelar tetap `true` (dan untuk `autonomous`, menandai konfirmasi agar tersimpan saat menekan **Simpan**).
  - **Penyimpanan Akhir (`Simpan`)**: Menormalisasi dan mengenkripsi API key ke Android Keystore, menyimpan seluruh nilai form dan sakelar ke `SharedPreferences`, memperbarui label model di layar utama (`updateModelLabel()`), dan menjalankan penyiapan model otomatis (`autoSetupModels()`) bila diperlukan.
