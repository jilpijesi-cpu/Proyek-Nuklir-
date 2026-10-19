package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test logika murni UI aktivitas AI: kartu, progres nyata, teks tengah, laporan akhir. Murni JVM. */
class ActivityModelTest {

    // ---------------------------------------------------------------- Jenis aktivitas

    @Test
    fun jenisAktivitasDariPerintah() {
        assertEquals(ActivityKind.BUILD, activityKindOf("./gradlew assembleDebug"))
        assertEquals(ActivityKind.BUILD, activityKindOf("cd app && ./gradlew --no-daemon assembleDebug 2>&1 | tail -20"))
        assertEquals(ActivityKind.BUILD, activityKindOf("gradle build"))
        assertEquals(ActivityKind.INSTALL, activityKindOf("pkg install -y openjdk-17"))
        assertEquals(ActivityKind.INSTALL, activityKindOf("pip install requests"))
        assertEquals(ActivityKind.INSTALL, activityKindOf("npm ci"))
        assertEquals(ActivityKind.DOWNLOAD, activityKindOf("curl -L -o a.zip https://x.y/a.zip"))
        assertEquals(ActivityKind.DOWNLOAD, activityKindOf("wget https://x.y/a.zip"))
        assertEquals(ActivityKind.DOWNLOAD, activityKindOf("git clone https://github.com/a/b"))
        assertEquals(ActivityKind.DOWNLOAD, activityKindOf("pkg update"))
    }

    @Test
    fun perintahBiasaBukanAktivitasProgres() {
        assertEquals(ActivityKind.OTHER, activityKindOf("ls -la"))
        assertEquals(ActivityKind.OTHER, activityKindOf("cat app/build.gradle.kts"))
        assertEquals(ActivityKind.OTHER, activityKindOf("grep -rn curl src/")) // kata "curl" hanya argumen
        assertEquals(ActivityKind.OTHER, activityKindOf("echo gradle assembleDebug"))
        assertEquals(ActivityKind.OTHER, activityKindOfDetail("app/Foo.kt\n\nisi")) // tulis file
        assertEquals(ActivityKind.BUILD, activityKindOfDetail("$ ./gradlew assembleDebug"))
    }

    // ---------------------------------------------------------------- Progres nyata

    @Test
    fun persenDiambilDariKeluaranNyata() {
        assertEquals(45, parseProgress("Receiving objects:  45% (9/20)", ActivityKind.DOWNLOAD))
        assertEquals(100, parseProgress("Resolving deltas: 100% (5/5), done.", ActivityKind.DOWNLOAD))
        assertEquals(37, parseProgress("###########                 37.4%", ActivityKind.DOWNLOAD))
        assertEquals(12, parseProgress(" 12%[==>        ] 1.2M  2.3MB/s", ActivityKind.DOWNLOAD))
        assertEquals(63, parseProgress("[ 63%] Building CXX object foo.o", ActivityKind.BUILD))
        assertEquals(80, parseProgress("<=======> 80% EXECUTING [12s]", ActivityKind.BUILD))
    }

    @Test
    fun yangDiambilPenandaTerakhir() {
        val t = "10%\n20%\n55%\nbaris biasa tanpa angka\n"
        assertEquals(55, parseProgress(t, ActivityKind.INSTALL))
        // curl memperbarui baris dengan carriage return
        assertEquals(90, parseProgress("  5%\r 40%\r 90%", ActivityKind.DOWNLOAD))
    }

    @Test
    fun tabelProgresCurlDibaca() {
        val t = "  % Total    % Received % Xferd  Average Speed   Time    Time     Time  Current\n" +
            "                                 Dload  Upload   Total   Spent    Left  Speed\n" +
            " 42 1256k   42  530k    0     0  1043k      0  0:00:01  0:00:00  0:00:01 1043k\n"
        assertEquals(42, parseProgress(t, ActivityKind.DOWNLOAD))
    }

    @Test
    fun pecahanUkuranDanKurungSikuHanyaUntukJenisYangSesuai() {
        assertEquals(35, parseProgress("   ━━━━━━ 1.2/3.4 MB 5.0 MB/s", ActivityKind.INSTALL))
        assertEquals(3, parseProgress("[12/340] Compiling foo.cpp", ActivityKind.BUILD))
        assertNull(parseProgress("[12/340] Compiling foo.cpp", ActivityKind.DOWNLOAD))
        assertNull(parseProgress("1.2/3.4 MB", ActivityKind.BUILD))
    }

    @Test
    fun tanpaDataNyataHasilnyaNullBukanKarangan() {
        // Gradle tanpa konsol interaktif tidak mencetak persen.
        val gradle = "> Task :app:preBuild UP-TO-DATE\n> Task :app:compileDebugKotlin\n> Task :app:mergeDebugResources\n"
        assertNull(parseProgress(gradle, ActivityKind.BUILD))
        assertNull(parseProgress("", ActivityKind.DOWNLOAD))
        assertNull(parseProgress("Get:1 http://x stable/main arm64 foo [1,234 kB]", ActivityKind.INSTALL))
    }

    @Test
    fun angkaBukanProgresDiabaikan() {
        assertNull(parseProgress("(Reading database ... 85%)", ActivityKind.INSTALL)) // dpkg membaca database, bukan instalasi
        assertNull(parseProgress("/dev/sda1  50G  25G  25G  50% /", ActivityKind.DOWNLOAD).takeIf { false }) // df hanya bila jenis OTHER
        assertNull(parseProgress("ratio 250%", ActivityKind.DOWNLOAD)) // di luar 0..100
        assertNull(parseProgress("progress 50%", ActivityKind.OTHER)) // jenis OTHER tidak pernah punya progres
    }

    @Test
    fun barHanyaMemakaiPersenNyataAtauSelesai() {
        // Berjalan tanpa data nyata -> tak-tentu, tanpa angka
        assertEquals(BarMode.INDETERMINATE, barSpec(ActivityKind.BUILD, StepState.RUNNING, null).mode)
        // Berjalan dengan data nyata -> lebar = persen nyata
        val s = barSpec(ActivityKind.DOWNLOAD, StepState.RUNNING, 45)
        assertEquals(BarMode.DETERMINATE, s.mode)
        assertEquals(0.45f, s.fraction, 0.0001f)
        assertFalse(s.complete)
        // Selesai OK -> penuh
        val ok = barSpec(ActivityKind.INSTALL, StepState.OK, null)
        assertEquals(1f, ok.fraction, 0.0001f)
        assertTrue(ok.complete)
        // Gagal: bar berhenti di persen nyata terakhir; tanpa data -> disembunyikan (tidak ada angka palsu)
        assertEquals(0.3f, barSpec(ActivityKind.BUILD, StepState.FAIL, 30).fraction, 0.0001f)
        assertEquals(BarMode.HIDDEN, barSpec(ActivityKind.BUILD, StepState.FAIL, null).mode)
        // Langkah biasa tidak punya bar
        assertEquals(BarMode.HIDDEN, barSpec(ActivityKind.OTHER, StepState.RUNNING, 50).mode)
        assertEquals(BarMode.HIDDEN, barSpec(ActivityKind.BUILD, StepState.DENIED, 50).mode)
    }

    // ---------------------------------------------------------------- Teks tengah

    @Test
    fun teksDirapikanSatuBaris() {
        assertEquals("Membaca MainActivity.kt", tidyLine("  membaca MainActivity.kt.  "))
        assertEquals("Membangun proyek", tidyLine("membangun\nproyek"))
        assertEquals("Menulis jawaban", tidyLine("- menulis   jawaban"))
        assertEquals("", tidyLine("   "))
        val long = tidyLine("a".repeat(200))
        assertEquals(72, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun teksSamaTidakDitampilkanUlang() {
        val g = TextGate(900)
        assertEquals("Mengirim instruksi ke AI", g.submit("Mengirim instruksi ke AI", 0))
        assertNull(g.submit("Mengirim instruksi ke AI", 100))
        assertNull(g.submit("Mengirim instruksi ke AI", 5_000)) // sama = tetap diabaikan walau sudah lama
        assertNull(g.nextDelay(5_000))
    }

    @Test
    fun perubahanBeruntunDitahanDanYangTerbaruMenang() {
        val g = TextGate(900)
        assertEquals("A", g.submit("A", 0))
        assertNull(g.submit("B", 300))         // terlalu cepat -> ditahan
        assertNull(g.submit("C", 500))         // yang terbaru menggantikan B
        assertEquals(400L, g.nextDelay(500))   // boleh tampil pada t=900
        assertNull(g.release(800))             // belum waktunya
        assertEquals("C", g.release(900))      // B tidak pernah tampil: tidak ada kedipan
        assertEquals("C", g.shown)
        assertNull(g.nextDelay(1_000))
    }

    @Test
    fun kembaliKeTeksSemulaMembatalkanTahanan() {
        val g = TextGate(900)
        assertEquals("A", g.submit("A", 0))
        assertNull(g.submit("B", 200))
        assertNull(g.submit("A", 400)) // kembali ke yang sedang tampil -> tidak perlu ganti
        assertNull(g.nextDelay(400))
        assertNull(g.release(1_000))
    }

    @Test
    fun teksBerubahLangsungBilaSudahCukupLama() {
        val g = TextGate(900)
        assertEquals("A", g.submit("A", 0))
        assertEquals("B", g.submit("B", 1_000))
    }

    @Test
    fun resetMenampilkanTeksPertamaSegera() {
        val g = TextGate(900)
        g.submit("A", 0)
        g.reset()
        assertEquals("A", g.submit("A", 10))
    }

    // ---------------------------------------------------------------- Laporan akhir

    private fun rec(tool: String, title: String, target: String, state: StepState, kind: ActivityKind = ActivityKind.OTHER) =
        StepRecord(tool, title, target, kind, state, 1000)

    private fun logOf(vararg steps: StepRecord, text: String = "", outcome: RunOutcome = RunOutcome.COMPLETED, note: String = "", apk: String? = null): RunLog {
        val l = RunLog()
        l.reset(0)
        steps.forEach { l.record(it) }
        l.finalText = text
        l.outcome = outcome
        l.note = note
        l.apkPath = apk
        return l
    }

    @Test
    fun jawabanBiasaTanpaLangkahTidakMenghasilkanLaporan() {
        assertNull(buildReport(logOf(text = "Halo"), 1000))
    }

    @Test
    fun laporanBerhasilMemuatRingkasanPerubahanDanStatus() {
        val l = logOf(
            rec("run_command", "Membaca kode", "ls", StepState.OK),
            rec("write_file", "Memperbaiki ActivityFeed", "app/src/ActivityFeed.kt", StepState.OK),
            rec("write_file", "Memperbaiki Model", "app/src/ActivityModel.kt", StepState.OK),
            rec("run_command", "Membangun proyek", "./gradlew assembleDebug", StepState.OK, ActivityKind.BUILD),
            text = "**Selesai.** Kartu aktivitas kini maksimal dua.\n\n```kotlin\nval x = 1\n```",
            apk = "/sdcard/app-debug.apk"
        )
        val r = buildReport(l, 65_000)!!
        assertEquals(ReportStatus.SUCCESS, r.status)
        assertEquals("Berhasil", r.title)
        assertTrue(r.summary, r.summary.startsWith("Selesai."))
        assertFalse(r.summary.contains("**"))
        assertFalse(r.summary.contains("val x"))
        assertTrue(r.changes.toString(), r.changes.contains("Mengubah 2 file: ActivityFeed.kt, ActivityModel.kt"))
        assertTrue(r.changes.contains("Build berhasil"))
        assertTrue(r.changes.contains("APK siap dipasang"))
        assertEquals("4 langkah · 1 mnt 5 dtk", r.stats)
    }

    @Test
    fun buildGagalMenjadiLaporanGagal() {
        val l = logOf(
            rec("write_file", "Menulis", "a/B.kt", StepState.OK),
            rec("run_command", "Membangun proyek", "./gradlew assembleDebug", StepState.FAIL, ActivityKind.BUILD),
            text = "Build gagal karena tipe tidak cocok."
        )
        val r = buildReport(l, 5_000)!!
        assertEquals(ReportStatus.FAILED, r.status)
        assertEquals("Build gagal", r.title)
        assertTrue(r.changes.contains("Build gagal"))
        assertTrue(r.changes.contains("1 langkah gagal"))
    }

    @Test
    fun langkahGagalYangSudahDiatasiTetapBerhasilTapiDicatat() {
        val l = logOf(
            rec("run_command", "Build", "./gradlew assembleDebug", StepState.FAIL, ActivityKind.BUILD),
            rec("write_file", "Perbaiki", "A.kt", StepState.OK),
            rec("run_command", "Build ulang", "./gradlew assembleDebug", StepState.OK, ActivityKind.BUILD),
            text = "Sudah beres."
        )
        val r = buildReport(l, 1_000)!!
        assertEquals(ReportStatus.SUCCESS, r.status)
        assertTrue(r.changes.contains("1 langkah sempat gagal"))
        assertTrue(r.changes.contains("Build berhasil"))
    }

    @Test
    fun langkahTerakhirGagalTanpaPenjelasanMenyebutLangkahnya() {
        val l = logOf(rec("run_command", "Menjalankan tes", "./gradlew test", StepState.FAIL))
        val r = buildReport(l, 1_000)!!
        assertEquals(ReportStatus.FAILED, r.status)
        assertTrue(r.summary, r.summary.contains("Menjalankan tes"))
    }

    @Test
    fun dihentikanGalatDanBatasPutaran() {
        val s = rec("run_command", "Membaca", "ls", StepState.OK)
        val stop = buildReport(logOf(s, outcome = RunOutcome.CANCELLED), 1_000)!!
        assertEquals(ReportStatus.STOPPED, stop.status)
        assertEquals("Dihentikan", stop.title)
        assertEquals("Tugas dihentikan setelah 1 langkah", stop.summary)

        val err = buildReport(logOf(s, outcome = RunOutcome.ERROR, note = "Error: koneksi putus"), 1_000)!!
        assertEquals(ReportStatus.FAILED, err.status)
        assertEquals("Error: koneksi putus", err.summary)

        val lim = buildReport(logOf(s, outcome = RunOutcome.INCOMPLETE, note = "Berhenti: AI mengulang langkah yang sama."), 1_000)!!
        assertEquals(ReportStatus.FAILED, lim.status)
        assertEquals("Tidak selesai", lim.title)

        // galat tanpa satu pun langkah tetap dilaporkan
        assertNotNull(buildReport(logOf(outcome = RunOutcome.ERROR, note = "Error: x"), 10))
        assertEquals("Tugas dihentikan sebelum langkah pertama", buildReport(logOf(outcome = RunOutcome.CANCELLED), 10)!!.summary)
    }

    @Test
    fun instalasiDanUnduhanIkutDilaporkan() {
        val l = logOf(
            rec("run_command", "Mengunduh", "curl -O https://x/y", StepState.OK, ActivityKind.DOWNLOAD),
            rec("run_command", "Memasang", "pkg install -y foo", StepState.FAIL, ActivityKind.INSTALL),
            text = "Gagal memasang."
        )
        val r = buildReport(l, 1)!!
        assertTrue(r.changes.contains("Unduhan berhasil"))
        assertTrue(r.changes.contains("Instalasi gagal"))
    }

    @Test
    fun tanpaPerubahanFileDinyatakanApaAdanya() {
        val r = buildReport(logOf(rec("run_command", "Membaca", "ls", StepState.OK), text = "Ini isinya."), 1)!!
        assertEquals(listOf("Tidak ada file yang diubah"), r.changes)
    }

    @Test
    fun ringkasanDariJawabanAkhirDirapikan() {
        assertEquals("", summaryFromAnswer("```\nkode\n```"))
        assertEquals("Perbaikan selesai", summaryFromAnswer("## Perbaikan selesai"))
        val long = summaryFromAnswer("x".repeat(400))
        assertEquals(150, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun logPutaranDiresetUntukTugasBaru() {
        val l = logOf(rec("run_command", "a", "ls", StepState.OK), text = "t", outcome = RunOutcome.ERROR, note = "n", apk = "x")
        l.reset(5)
        assertTrue(l.steps.isEmpty())
        assertEquals("", l.finalText)
        assertEquals(RunOutcome.COMPLETED, l.outcome)
        assertNull(l.apkPath)
        assertEquals(5L, l.startedAt)
    }
}
