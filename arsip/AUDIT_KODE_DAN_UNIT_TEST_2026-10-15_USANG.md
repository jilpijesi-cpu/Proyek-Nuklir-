> **USANG.** Audit 15 Okt 2026 atas versi lama. Temuan mode otonom default, fallback API key plaintext, dan cleartext global sudah diperbaiki di versi terbaru. Rujukan terkini: Laporan_Audit_Menyeluruh_AgentApp.md (18 Okt 2026).

# Laporan Audit Kode dan Unit Test AgentApp

Tanggal audit: 15 Oktober 2026  
Proyek: `com.kita.agent` / AgentApp `1.1-perbaikan-agen`  
Metode: inspeksi statis Kotlin/Gradle/Manifest, peninjauan unit test, dan percobaan build.

## Ringkasan eksekutif

- Proyek memiliki **49 file Kotlin produksi**, **12 kelas unit test**, dan **86 metode beranotasi `@Test`**.
- Logika murni untuk klasifikasi perintah, guard keberhasilan, progres aktivitas, laporan akhir, payload gambar, dan beberapa utilitas sudah diuji dengan cukup baik.
- **Test belum berhasil dieksekusi** karena sandbox tidak memiliki Android SDK. Percobaan pertama juga menemukan `gradlew` dari arsip tidak memiliki bit eksekusi; izin lokal sudah diperbaiki.
- Belum ada `androidTest`, sehingga alur Android/Termux/network/lifecycle belum diuji otomatis.
- Dua temuan prioritas tinggi perlu ditangani sebelum distribusi luas:
  1. Mode otonom aktif secara default dan melewati semua dialog persetujuan.
  2. Kegagalan Android Keystore menyebabkan API key disimpan sebagai plaintext.

## Status verifikasi

Perintah yang dicoba:

```bash
./gradlew testDebugUnitTest assembleDebug --stacktrace
```

Hasil akhir:

```text
SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable
or by setting the sdk.dir path in local.properties.
```

Android SDK 34 tidak ditemukan di lokasi umum sandbox. Dengan demikian, laporan ini adalah **audit statis**, bukan klaim bahwa seluruh test lulus.

## Temuan prioritas tinggi

### H-01 — Mode otonom default melewati seluruh persetujuan

**Lokasi:** `app/src/main/java/com/kita/agent/AgentLoop.kt:14`, `ToolExecution.kt:46`, `AskUser.kt:21`

```kotlin
internal fun MainActivity.isAutonomous(): Boolean = prefs.getBoolean("autonomous", true)
```

```kotlin
if (allowAll || isAutonomous()) { ... return true }
```

Dampak:

- Perintah shell, penulisan file, dan tindakan berisiko dapat berjalan tanpa dialog ketika preferensi belum pernah disetel.
- `ask_user` juga tidak menunggu pengguna dalam mode ini dan mengembalikan keputusan otomatis.
- Manifest mengizinkan komunikasi Termux dan pemasangan APK, sehingga konsekuensi tindakan otonom cukup besar.

Konteks: perilaku ini memang didokumentasikan sebagai fitur, tetapi default aktif membuat aplikasi baru langsung berada pada mode dengan perlindungan paling rendah. Ini sebaiknya diperlakukan sebagai keputusan keamanan produk, bukan sekadar preferensi UI.

**Rekomendasi:** ubah default menjadi `false`, tampilkan onboarding yang menjelaskan mode otonom, atau minta opt-in eksplisit sebelum pertama kali mengaktifkannya. Tambahkan test yang memastikan instalasi baru tidak melewati `confirm()`.

### H-02 — Fallback Keystore menyimpan API key plaintext

**Lokasi:** `app/src/main/java/com/kita/agent/Secret.kt:33-43`

```kotlin
/** ... Kalau Keystore gagal, simpan apa adanya ... */
...
} catch (e: Exception) {
    plain
}
```

Jika Android Keystore gagal, `encrypt()` mengembalikan key asli. Kode pemanggil kemudian dapat menyimpan nilai itu ke `SharedPreferences`. Hal ini bertentangan dengan komentar kelas yang menjanjikan penyimpanan terenkripsi dan dapat mengekspos kredensial pada backup, root/debug inspection, atau akses file perangkat.

**Rekomendasi:** fail closed: kembalikan error/`Result`, jangan simpan key, dan minta pengguna memasukkan ulang. Tambahkan test untuk kegagalan Keystore atau abstraksikan provider kripto agar bisa diuji di JVM. Pertimbangkan `EncryptedSharedPreferences`/Android Keystore-backed storage untuk seluruh siklus hidup key.

## Temuan prioritas sedang

### M-01 — Klasifikasi beberapa penghapus melalui wrapper masih hanya WRITE

**Lokasi:** `Classify.kt:41-47, 51-58`; test `ClassifyTest.kt:190-204`

Test secara eksplisit mendokumentasikan gap berikut sebagai hanya minimal `WRITE`:

- `timeout 5 rm x`
- `nice -n 5 rm x`
- `find . -exec rm {} \;`

Padahal perintah tersebut dapat menghapus data. Label `WRITE` menurunkan kejelasan peringatan risiko dan dapat memengaruhi UI/prompt. Dalam mode non-otonom perintah tetap meminta persetujuan, tetapi pengguna tidak mendapat klasifikasi `DELETE` yang paling tepat. Dalam mode otonom, seluruh klasifikasi tidak menjadi penghalang, sehingga isu ini memperbesar dampak H-01.

**Rekomendasi:** parser perlu memahami opsi wrapper (`timeout`, `nice`, dan sejenisnya) tanpa menganggap opsi sebagai nama perintah, serta menelusuri isi `find -exec`/`xargs`. Ubah test menjadi `Risk.DELETE` setelah implementasi diperbaiki. Untuk shell arbitrer, pendekatan paling aman adalah `WRITE` minimal dan selalu meminta persetujuan, atau gunakan parser/allowlist yang lebih ketat.

### M-02 — `usesCleartextTraffic="true"` memperluas permukaan jaringan

**Lokasi:** `app/src/main/AndroidManifest.xml:19`

Aplikasi mengaktifkan cleartext traffic secara global, walaupun `ModelProbe.checkUrl()` memblokir HTTP ke host publik dan preset HTTP yang terlihat adalah Ollama localhost.

Risiko:

- Perlindungan URL dapat berubah/terlewati bila ada jalur network baru yang tidak memanggil `checkUrl()`.
- Konfigurasi global memberi izin HTTP ke seluruh komponen jaringan aplikasi.

**Rekomendasi:** nonaktifkan cleartext global dan gunakan `networkSecurityConfig` yang sangat terbatas hanya jika dukungan localhost benar-benar diperlukan. Tambahkan test untuk seluruh jalur HTTP provider, bukan hanya fungsi helper.

### M-03 — Komponen kritis belum memiliki test integrasi/instrumentasi

Tidak ada direktori `app/src/androidTest`. Area tanpa test langsung yang signifikan:

- `MainActivity` lifecycle, rotasi, pemulihan state, dan penghentian worker.
- `AgentLoop` dengan callback streaming dan pembatalan.
- `TermuxShell`/`RUN_COMMAND`, broadcast receiver, timeout, kill process tree.
- `ApkInstall` dan `FileProvider`/installer flow.
- HTTP provider OpenAI, Anthropic, Gemini, retry, parsing SSE, dan stream terputus.
- `Secret` pada Android Keystore nyata.
- dialog persetujuan, mode otonom, dan `ask_user`.

**Rekomendasi:** tambahkan test JVM untuk parser/provider dengan fake transport, lalu test instrumentasi minimal pada emulator/perangkat untuk Keystore, lifecycle, broadcast, FileProvider, dan Termux bridge. Tambahkan contract test untuk tiap provider.

### M-04 — Build reproducibility dari arsip belum baik

Arsip yang diunggah mengekstrak `gradlew` tanpa izin eksekusi sehingga perintah dokumentasi gagal dengan exit code 126. Bit eksekusi diperbaiki hanya di workspace audit.

**Rekomendasi:** pastikan mode file `gradlew` adalah `100755` sebelum membuat ZIP/commit, lalu verifikasi dengan:

```bash
stat -c '%A %a %n' gradlew
./gradlew --version
```

Workflow GitHub menggunakan `gradle` global, sehingga tidak mendeteksi masalah wrapper ini.

## Temuan prioritas rendah / maintainability

### L-01 — Thread manual dan `Thread.sleep` masih tersebar

**Lokasi:** `AgentLoop.kt`, `TermuxShell.kt`, `ModelCall.kt`, `LiveTail.kt`, `ApkInstall.kt`, `TermuxFiles.kt`

Pendekatan ini bekerja secara konseptual, tetapi memperbesar risiko thread bocor, callback ke Activity yang sudah dihancurkan, dan pembatalan yang tidak konsisten. Dokumentasi proyek juga mengakui `MainActivity.kt` masih besar dan belum dipindahkan ke ViewModel/coroutine.

**Rekomendasi:** setelah build stabil, migrasikan pekerjaan ke coroutine/`ViewModel`, gunakan lifecycle scope, dan buat abstraction untuk cancellation/timeouts. Jangan menjadikan refactor besar ini prasyarat untuk memperbaiki H-01/H-02.

### L-02 — Error Keystore ditelan tanpa observability

Selain fallback plaintext, semua exception kripto dikembalikan sebagai string kosong saat decrypt. Pengguna tidak dapat membedakan key kosong dari key yang hilang/rusak.

**Rekomendasi:** bedakan `missing`, `invalid`, dan `keystore-unavailable`; hapus ciphertext yang tidak dapat dibuka dan tampilkan instruksi input ulang tanpa mencatat isi key.

## Evaluasi unit test

### Yang sudah baik

- `ClassifyTest`: banyak kasus command chain, redirect, shell wrapper, perintah delete, dan command substitution.
- `ActivityModelTest`: laporan sukses/gagal, build ulang setelah gagal, progres, ringkasan, dan reset log.
- `AgentGuardTest` + `AgentRecoveryTest`: error berulang, timeout, hasil tool yang dapat dipulihkan, dan verifikasi build.
- `TaskBriefTest`, `KeyDetectTest`, `CliLoginTest`, `BestModelTest`, `ImagePayloadTest`, `PaletteTest`, dan `ActionIconsTest` mencakup helper/domain logic yang relatif mudah diregresikan.
- Sebagian besar logika domain sengaja dipisah dari Android sehingga dapat diuji sebagai JVM unit test.

### Kekurangan test

- 86 `@Test` tersebar pada 12 kelas, tetapi seluruhnya berada di `src/test`; belum ada `src/androidTest`.
- Belum ada bukti test lulus karena Android SDK tidak tersedia pada lingkungan audit.
- Belum ada test untuk default preference, khususnya `autonomous=true`, `autoread`, `readonly`, dan migrasi key.
- Belum ada test untuk kegagalan Keystore dan jaminan bahwa plaintext tidak disimpan.
- Test klasifikasi saat ini mengunci beberapa celah sebagai `WRITE`, bukan perilaku target `DELETE`.
- Tidak ada test concurrency untuk latch, receiver registration/unregistration, timeout, atau Activity yang ditutup saat worker berjalan.

## Urutan perbaikan yang disarankan

1. **Segera:** ubah mode otonom menjadi opt-in/default `false`, atau tambahkan onboarding/guard eksplisit.
2. **Segera:** hilangkan fallback plaintext pada `Secret.encrypt()` dan tambahkan jalur error yang dapat dipahami pengguna.
3. **Berikutnya:** perbaiki parser risiko wrapper dan `find -exec`; ubah regression test ke `DELETE`.
4. **Berikutnya:** matikan cleartext global dan buat pengecualian localhost yang sempit bila diperlukan.
5. Siapkan Android SDK 34, jalankan:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```
6. Tambahkan test provider/network dan instrumentation untuk Termux, Keystore, lifecycle, serta pemasangan APK.
7. Pastikan `gradlew` tersimpan sebagai executable di repositori/arsip dan perbarui workflow agar memakai wrapper:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug --no-daemon
   ```

## Kesimpulan

Fondasi logika murni dan unit test proyek cukup baik untuk iterasi awal, tetapi verifikasi belum dapat disebut lulus dan area runtime Android belum terlindungi test. Sebelum APK dibagikan ke pengguna, **H-01 dan H-02 harus diselesaikan**, kemudian build/test perlu dijalankan pada lingkungan dengan Android SDK dan minimal satu perangkat/emulator.
