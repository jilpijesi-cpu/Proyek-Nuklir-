package com.kita.agent

import java.io.File

// Kebijakan perintah untuk MODE SANDBOX, RUANG KERJA, dan PERBAIKI DIRI.
// Sengaja TANPA dependensi Android supaya bisa diuji sebagai unit test JVM.
//
// classify() TIDAK diubah. assessCommand() memanggil classify() lalu hanya MENAMBAH pembatasan
// (blokir, tanda jaringan, pemeriksaan workdir/ruang kerja, dan perlindungan file terlindung).
// Hasilnya tidak pernah lebih longgar dari classify().

const val DEFAULT_SANDBOX_WORKDIR = "/root/sandbox/work"
const val DEFAULT_GUEST_HOME = "/root"

enum class Decision {
    /** Ada di allowlist, hanya membaca, di dalam workdir, tanpa jaringan, tanpa menyentuh file terlindung. */
    SAFE,
    /** Harus lewat dialog persetujuan. */
    NEEDS_APPROVAL,
    /** Tidak dijalankan sama sekali dan tidak ditawarkan ke pengguna. */
    BLOCKED
}

data class Assessment(
    val decision: Decision,
    val risk: Risk,
    val network: Boolean,
    val reasons: List<String>,
    val protectedFiles: List<String> = emptyList()
) {
    /** True bila perintah menyentuh file terlindung yang wajib lewat dialog diff khusus tanpa "Ya, semua". */
    val requiresProtectedApproval: Boolean
        get() = protectedFiles.isNotEmpty()

    /** Teks peringatan untuk dialog persetujuan; null bila tidak ada yang perlu disorot. */
    fun warning(): String? = if (reasons.isEmpty()) null else reasons.joinToString("\n")
}

/** Persetujuan boleh dilewati ("Ya, semua" / mode otonom) kecuali [force]. */
internal fun bypassesApproval(allowAll: Boolean, autonomous: Boolean, force: Boolean): Boolean =
    !force && (allowAll || autonomous)

// Allowlist sengaja himpunan bagian dari perintah baca di classify(): yang tidak dikenal classify() tidak pernah SAFE.
private val SAFE_ALLOWLIST = setOf(
    "ls", "cat", "head", "tail", "grep", "egrep", "fgrep", "find", "pwd", "echo", "wc", "stat",
    "du", "df", "which", "file", "tree", "diff", "cut", "basename", "dirname"
)

// Perintah yang tidak pernah boleh jalan (bukan sekadar "berisiko").
private val BLOCK_CMDS = setOf(
    "dd", "fdisk", "sfdisk", "cfdisk", "parted", "mkswap", "swapon", "swapoff", "wipefs", "blkdiscard",
    "mount", "umount", "losetup", "chroot", "shred",
    "shutdown", "reboot", "halt", "poweroff", "init", "telinit",
    "su", "proot", "proot-distro", "am", "pm", "svc", "settings", "cmd",
    "insmod", "rmmod", "modprobe", "setenforce", "iptables",
    "keytool", "jarsigner"
)

private val NETWORK_CMDS = setOf(
    "curl", "wget", "ssh", "scp", "sftp", "rsync", "nc", "ncat", "netcat", "telnet", "ftp", "tftp",
    "ping", "ping6", "traceroute", "tracepath", "nslookup", "dig", "host", "nmap", "socat", "aria2c",
    "lynx", "w3m", "pip", "pip3", "npm", "npx", "yarn", "pnpm", "gem", "cargo", "bundle", "composer",
    "apt", "apt-get", "aptitude", "apk", "pkg", "gradle", "gradlew", "mvn"
)
private val GIT_NETWORK_SUB = setOf("clone", "fetch", "pull", "push", "ls-remote", "submodule")
private val GIT_FILE_MUTATING_SUB = setOf("checkout", "apply", "restore", "reset", "revert", "rm", "mv", "clean", "stash", "am")
private val INTERPRETERS = setOf("node", "nodejs", "perl", "ruby", "php", "lua")
private val SHELL_NAMES = setOf("sh", "bash", "zsh", "dash", "ash", "ksh", "eval", "source", ".")
private val RM_LIKE = setOf("rm", "rmdir", "unlink")
private val PERM_CMDS = setOf("chmod", "chown", "chgrp")

// Perintah yang menulis ke path yang disebut sebagai argumen.
private val WRITERS = setOf(
    "mkdir", "touch", "cp", "mv", "install", "ln", "tee", "truncate",
    "chmod", "chown", "chgrp", "rm", "rmdir", "unlink", "curl", "wget", "rsync", "scp", "patch"
)

private val SEGMENT_SPLIT = Regex("""[|&;\n\r(){}`]+""")
private val NON_WORD = Regex("[^A-Za-z0-9_.-]+")
private val WHITESPACE = Regex("\\s+")
private val FORK_BOMB = Regex(""":\s*\(\s*\)\s*\{""")
private val DEV_NET = Regex("""/dev/(tcp|udp)/""")
private val URL_RE = Regex("""(?i)\b[a-z][a-z0-9+.\-]*://""")
private val SCP_RE = Regex("""\b[\w.-]+@[\w.-]+:""")
private val REDIRECT_RE = Regex("""\d*>>?\s*([^\s&|;<>()]+)""")
private val RAW_HOME_TOKEN_RE = Regex("(^|[\\s\"'=])(~|\\\$HOME|\\\$\\{HOME\\})(/|\\s|$|[\"'])")
private val SIGNING_OR_APPID_RE = Regex("""\b(applicationId|signingConfigs|storePassword|keyPassword|keyAlias|storeFile)\b""")

private fun unquote(w: String) = w.replace("\"", "").replace("'", "").replace("\\", "")

private fun isBlockedName(n: String): Boolean {
    val l = n.lowercase()
    return l in BLOCK_CMDS || l.startsWith("mkfs") || l.startsWith("termux-")
}

private fun isRecursiveFlag(a: String) =
    a == "--recursive" || (a.startsWith("-") && !a.startsWith("--") && a.contains('R'))

private fun isFollowFlag(a: String) =
    (a.startsWith("-") && !a.startsWith("--") && (a.contains('f') || a.contains('F'))) || a.startsWith("--follow")

private fun isPathLike(v: String) =
    v == "." || v == ".." || v.startsWith("/") || v.startsWith("~") || v.contains('/') ||
        v.contains('$') || v.contains('`') || v.split('/').any { it == ".." || it == "." } ||
        touchesForbiddenKitaDir(v) || isProtectedFile(v)

private fun isExistingSymlinkOrFile(v: String, workdir: String): Boolean {
    if (v.isEmpty() || v.startsWith("-")) return false
    return try {
        val f = File(workdir, v)
        f.exists() || java.nio.file.Files.isSymbolicLink(f.toPath())
    } catch (_: Exception) {
        false
    }
}

/** Argumen yang tampak seperti path. Nilai setelah "=" pada opsi (--output=/x) atau flag pendek melekat (-f/etc/passwd) ikut diperiksa. */
private fun candidatePaths(args: List<String>, workdir: String = ""): List<String> = args.mapNotNull { a ->
    val v = when {
        !a.startsWith("-") -> a
        a.contains('=') -> a.substringAfter('=')
        !a.startsWith("--") && a.length > 2 && isPathLike(a.substring(2)) -> a.substring(2)
        else -> null
    }
    if (v != null && (isPathLike(v) || (workdir.isNotEmpty() && isExistingSymlinkOrFile(v, workdir)))) v else null
}

/**
 * Ubah path menjadi canonical path absolut di dalam workdir/ruang kerja menggunakan [File.canonicalPath].
 * Null bila tidak bisa dipastikan (variabel shell, substitusi, ~user).
 * Path relatif dihitung dari canonical path [workdir].
 */
internal fun resolvePath(raw: String, workdir: String, guestHome: String): String? {
    var p = raw.trim()
    if (p.isEmpty() || p.contains('$') || p.contains('`')) return null
    if (p == "~") p = guestHome
    else if (p.startsWith("~/")) p = guestHome.trimEnd('/') + p.substring(1)
    else if (p.startsWith("~")) return null
    return try {
        val wsCanon = File(workdir).canonicalPath.trimEnd('/')
        val target = if (p.startsWith("/")) File(p) else File("$wsCanon/$p")
        target.canonicalPath
    } catch (_: Exception) {
        null
    }
}

/**
 * Periksa apakah [path] berada di dalam [workdir] memakai resolusi canonical path.
 */
internal fun isInside(path: String, workdir: String): Boolean {
    return try {
        val w = File(workdir).canonicalPath.trimEnd('/')
        val p = File(path).canonicalPath.trimEnd('/')
        if (w.isEmpty()) p == "/" else p == w || p.startsWith("$w/")
    } catch (_: Exception) {
        false
    }
}

private fun isWriter(name: String, args: List<String>, segRisk: Risk): Boolean =
    segRisk == Risk.DELETE || name in WRITERS ||
        (name == "sed" && args.any { it.startsWith("-i") || it == "--in-place" }) ||
        (name == "tree" && args.any { it == "-o" || it.startsWith("-o") || it.startsWith("--output") })

private fun isProtectedFileMutator(name: String, gitSub: String?, args: List<String>, segRisk: Risk): Boolean =
    isWriter(name, args, segRisk) ||
        name == "sed" ||
        name == "tee" ||
        name == "patch" ||
        (name == "git" && gitSub != null && gitSub in GIT_FILE_MUTATING_SUB)

/**
 * Nilai perintah khusus dengan pembatas ketat Ruang Kerja (`strictWorkspace = true`).
 */
fun assessWorkspaceCommand(
    cmd: String,
    workspaceDir: String,
    termuxHome: String = "/data/data/com.termux/files/home"
): Assessment = assessCommand(
    cmd = cmd,
    workdir = workspaceDir,
    guestHome = termuxHome,
    strictWorkspace = true
)

/**
 * Nilai satu perintah untuk mode sandbox, ruang kerja, dan perlindungan file terlindung.
 * @param workdir path workdir / ruang kerja aktif.
 * @param guestHome path home (tamu sandbox atau home Termux).
 * @param strictWorkspace bila true (otomatis true untuk folder ruang kerja ~/workspaces/<nama>),
 *        segala path yang keluar dari ruang kerja (absolut di luar folder, "../", symlink yang
 *        mengarah keluar via canonical path, cd ke luar, ~ atau $HOME mentah) dinilai BLOCKED.
 */
fun assessCommand(
    cmd: String,
    workdir: String = DEFAULT_SANDBOX_WORKDIR,
    guestHome: String = DEFAULT_GUEST_HOME,
    strictWorkspace: Boolean = workdir.contains("/workspaces/") || workdir.endsWith("/workspaces")
): Assessment {
    val reasons = ArrayList<String>()
    val protectedTouched = ArrayList<String>()
    var blocked = false
    var network = false
    var allNamesSafe = true
    var outsideRead = false
    var neverEnds = false
    val wd = try { File(workdir).canonicalPath.trimEnd('/') } catch (_: Exception) { workdir.trimEnd('/') }

    fun block(msg: String) { blocked = true; reasons.add("DIBLOKIR: $msg") }

    val risk = classify(cmd)

    if (FORK_BOMB.containsMatchIn(cmd)) block("pola fork bomb")
    if (DEV_NET.containsMatchIn(cmd) || URL_RE.containsMatchIn(cmd) || SCP_RE.containsMatchIn(cmd)) {
        network = true
        reasons.add("Menyentuh jaringan (alamat atau URL terdeteksi)")
    }

    // Di mode ruang kerja ketat, penggunaan ~ atau $HOME mentah dinilai BLOCKED.
    if (strictWorkspace && RAW_HOME_TOKEN_RE.containsMatchIn(cmd)) {
        block("penggunaan ~ atau \$HOME mentah tidak diizinkan di ruang kerja")
    }

    // Sama seperti classify(): buang redirect yang tidak menulis file sebelum memecah perintah.
    val c = cmd.replace(Regex("""\d*>&\d+"""), " ").replace(Regex("""\d*>>?\s*/dev/null"""), " ")

    for (m in REDIRECT_RE.findAll(c)) {
        val target = unquote(m.groupValues[1])
        if (touchesForbiddenKitaDir(target, guestHome)) {
            block("akses ke ~/kita dilarang: $target")
        }
        when (classifyProtectedPath(target)) {
            ProtectedFileVerdict.BLOCKED ->
                block("akses ke file .env atau keystore dilarang: $target")
            ProtectedFileVerdict.NEEDS_SPECIAL_APPROVAL -> {
                allNamesSafe = false
                protectedTouched.add(target)
                reasons.add("PERINGATAN FILE TERLINDUNG: menulis ke '$target' wajib persetujuan khusus")
            }
            ProtectedFileVerdict.UNPROTECTED -> {}
        }
        val r = resolvePath(target, workdir, guestHome)
        if (r == null || !isInside(r, workdir)) {
            block("menulis ke luar workdir lewat redirect: $target")
        }
    }
    val cWithoutRedirects = REDIRECT_RE.replace(c, " ")

    for (seg in cWithoutRedirects.split(SEGMENT_SPLIT)) {
        val words = seg.trim().split(WHITESPACE).map { unquote(it) }.filter { it.isNotEmpty() }
        if (words.isEmpty()) continue
        val t = unwrap(words)
        val rawName = t.firstOrNull()?.substringAfterLast('/') ?: continue
        val name = rawName.lowercase()
        val args = t.drop(1)
        val segRisk = classify(seg)

        if (rawName !in SAFE_ALLOWLIST) allNamesSafe = false
        if (name == "tail" && args.any { isFollowFlag(it) }) {
            neverEnds = true
            reasons.add("tail -f tidak berakhir sendiri")
        }

        // Isi shell (bash -c '...') tidak bisa dipastikan: periksa kata-katanya.
        val nested = if (name in SHELL_NAMES) t.flatMap { it.split(NON_WORD) }.map { it.lowercase() }.filter { it.isNotEmpty() } else emptyList()

        if (isBlockedName(name)) block("perintah '$rawName' tidak diizinkan")
        val nestedBad = nested.firstOrNull { isBlockedName(it) }
        if (nestedBad != null) block("'$nestedBad' di dalam shell tidak diizinkan")
        if (name in PERM_CMDS && args.any { isRecursiveFlag(it) }) block("perubahan izin atau pemilik massal (-R) tidak diizinkan")
        if (name == "apksigner" && args.any { it.equals("sign", ignoreCase = true) }) {
            block("agen tidak boleh mengakses kunci signing untuk menandatangani APK secara langsung")
        }

        val gitSub = if (name == "git") args.firstOrNull { !it.startsWith("-") }?.lowercase() else null
        val isNet = name in NETWORK_CMDS || (gitSub != null && gitSub in GIT_NETWORK_SUB) || nested.any { it in NETWORK_CMDS }
        if (isNet) {
            network = true
            reasons.add("Menyentuh jaringan: $rawName")
        }
        if (name.startsWith("python") || name in INTERPRETERS) {
            reasons.add("'$rawName' bisa memakai jaringan lewat skrip; tidak bisa dipastikan")
        }

        // Larang modifikasi langsung terhadap applicationId atau signingConfigs lewat perintah tulis/sed
        val isMutator = isProtectedFileMutator(name, gitSub, args, segRisk)
        if ((isMutator || seg.contains(">") || cmd.contains(">")) &&
            (SIGNING_OR_APPID_RE.containsMatchIn(seg) || SIGNING_OR_APPID_RE.containsMatchIn(cmd))
        ) {
            block("agen dilarang mengubah applicationId atau konfigurasi kunci signing")
        }

        // Periksa setiap argumen terhadap ~/kita, .env, keystore*, dan daftar File Terlindung
        for (arg in args) {
            val cleanArg = if (arg.startsWith("-") && arg.contains('=')) arg.substringAfter('=') else arg
            if (touchesForbiddenKitaDir(cleanArg, guestHome)) {
                block("akses ke folder ~/kita dilarang ($cleanArg)")
            }
            when (classifyProtectedPath(cleanArg)) {
                ProtectedFileVerdict.BLOCKED ->
                    block("akses ke file .env atau keystore dilarang ($cleanArg)")
                ProtectedFileVerdict.NEEDS_SPECIAL_APPROVAL -> {
                    if (isMutator) {
                        allNamesSafe = false
                        protectedTouched.add(cleanArg)
                        reasons.add("PERINGATAN FILE TERLINDUNG: '$rawName' memodifikasi/menghapus file terlindung ($cleanArg)")
                    }
                }
                ProtectedFileVerdict.UNPROTECTED -> {}
            }
        }

        val paths = if (name == "echo") emptyList() else candidatePaths(args, workdir)
        val outside = paths.filter { p ->
            if (strictWorkspace && isRawHomeReference(p)) {
                true
            } else {
                val r = resolvePath(p, workdir, guestHome)
                r == null || !isInside(r, workdir)
            }
        }
        if (outside.isNotEmpty()) {
            val shown = outside.take(3).joinToString(", ")
            if (name == "cd" || name == "pushd") {
                block("'$rawName' tidak boleh pindah ke luar workdir ($shown)")
            } else if (strictWorkspace) {
                block("path keluar dari ruang kerja ($shown)")
            } else if (isWriter(name, args, segRisk)) {
                block("'$rawName' menulis atau menghapus di luar workdir ($shown)")
            } else {
                outsideRead = true
                reasons.add("Menyentuh path di luar workdir: $shown")
            }
        }
        // "cd" tanpa tujuan pindah ke home, yaitu di luar workdir; perintah relatif sesudahnya jadi tidak terukur.
        if ((name == "cd" || name == "pushd") && args.none { !it.startsWith("-") }) {
            block("'$rawName' tanpa tujuan pindah ke luar workdir")
        }
        if (name in RM_LIKE && paths.any { resolvePath(it, workdir, guestHome) == wd }) {
            block("tidak boleh menghapus workdir itu sendiri")
        }
    }

    val decision = when {
        blocked -> Decision.BLOCKED
        protectedTouched.isNotEmpty() -> Decision.NEEDS_APPROVAL
        risk == Risk.READ && allNamesSafe && !network && !outsideRead && !neverEnds &&
            !cmd.contains('$') && !cmd.contains('`') -> Decision.SAFE
        else -> Decision.NEEDS_APPROVAL
    }
    return Assessment(
        decision = decision,
        risk = risk,
        network = network,
        reasons = reasons.distinct(),
        protectedFiles = protectedTouched.distinct()
    )
}
