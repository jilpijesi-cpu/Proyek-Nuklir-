package com.kita.agent

// Kebijakan perintah untuk MODE SANDBOX. Sengaja TANPA dependensi Android supaya bisa diuji sebagai unit test JVM.
//
// classify() TIDAK diubah. assessCommand() memanggil classify() lalu hanya MENAMBAH pembatasan
// (blokir, tanda jaringan, pemeriksaan workdir). Hasilnya tidak pernah lebih longgar dari classify().
//
// BATAS YANG JUJUR: ini analisis teks (heuristik), bukan penjaga di tingkat sistem operasi. Variabel shell,
// symlink, skrip python/node, atau trik quoting yang tidak terduga bisa lolos dari analisis teks. Karena itu
// lapisan ini dipasangkan dengan isolasi proot-distro (lihat SandboxRunner.kt) dan persetujuan pengguna.

const val DEFAULT_SANDBOX_WORKDIR = "/root/sandbox/work"
const val DEFAULT_GUEST_HOME = "/root"

enum class Decision {
    /** Ada di allowlist, hanya membaca, di dalam workdir, tanpa jaringan. Boleh tanpa dialog (bila autoread aktif). */
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
    val reasons: List<String>
) {
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

// Perintah yang tidak pernah boleh jalan di sandbox (bukan sekadar "berisiko").
private val BLOCK_CMDS = setOf(
    "dd", "fdisk", "sfdisk", "cfdisk", "parted", "mkswap", "swapon", "swapoff", "wipefs", "blkdiscard",
    "mount", "umount", "losetup", "chroot", "shred",
    "shutdown", "reboot", "halt", "poweroff", "init", "telinit",
    "su", "proot", "proot-distro", "am", "pm", "svc", "settings", "cmd",
    "insmod", "rmmod", "modprobe", "setenforce", "iptables"
)

private val NETWORK_CMDS = setOf(
    "curl", "wget", "ssh", "scp", "sftp", "rsync", "nc", "ncat", "netcat", "telnet", "ftp", "tftp",
    "ping", "ping6", "traceroute", "tracepath", "nslookup", "dig", "host", "nmap", "socat", "aria2c",
    "lynx", "w3m", "pip", "pip3", "npm", "npx", "yarn", "pnpm", "gem", "cargo", "bundle", "composer",
    "apt", "apt-get", "aptitude", "apk", "pkg", "gradle", "gradlew", "mvn"
)
private val GIT_NETWORK_SUB = setOf("clone", "fetch", "pull", "push", "ls-remote", "submodule")
private val INTERPRETERS = setOf("node", "nodejs", "perl", "ruby", "php", "lua")
private val SHELL_NAMES = setOf("sh", "bash", "zsh", "dash", "ash", "ksh", "eval", "source", ".")
private val RM_LIKE = setOf("rm", "rmdir", "unlink")
private val PERM_CMDS = setOf("chmod", "chown", "chgrp")

// Perintah yang menulis ke path yang disebut sebagai argumen.
private val WRITERS = setOf(
    "mkdir", "touch", "cp", "mv", "install", "ln", "tee", "truncate",
    "chmod", "chown", "chgrp", "rm", "rmdir", "unlink", "curl", "wget", "rsync", "scp"
)

private val SEGMENT_SPLIT = Regex("""[|&;\n\r(){}]+""")
private val NON_WORD = Regex("[^A-Za-z0-9_.-]+")
private val WHITESPACE = Regex("\\s+")
private val FORK_BOMB = Regex(""":\s*\(\s*\)\s*\{""")
private val DEV_NET = Regex("""/dev/(tcp|udp)/""")
private val URL_RE = Regex("""(?i)\b[a-z][a-z0-9+.\-]*://""")
private val SCP_RE = Regex("""\b[\w.-]+@[\w.-]+:""")
private val REDIRECT_RE = Regex("""\d*>>?\s*([^\s&|;<>()]+)""")

private fun unquote(w: String) = w.replace("\"", "").replace("'", "").replace("\\", "")

private fun isBlockedName(n: String) = n in BLOCK_CMDS || n.startsWith("mkfs") || n.startsWith("termux-")

private fun isRecursiveFlag(a: String) =
    a == "--recursive" || (a.startsWith("-") && !a.startsWith("--") && a.contains('R'))

private fun isFollowFlag(a: String) =
    (a.startsWith("-") && !a.startsWith("--") && (a.contains('f') || a.contains('F'))) || a.startsWith("--follow")

private fun isPathLike(v: String) =
    v == "." || v == ".." || v.startsWith("/") || v.startsWith("~") ||
        v.contains('$') || v.contains('`') || v.split('/').any { it == ".." || it == "." }

/** Argumen yang tampak seperti path. Nilai setelah "=" pada opsi (--output=/x) ikut diperiksa. */
private fun candidatePaths(args: List<String>): List<String> = args.mapNotNull { a ->
    val v = if (a.startsWith("-")) (if (a.contains('=')) a.substringAfter('=') else null) else a
    if (v != null && isPathLike(v)) v else null
}

/**
 * Ubah path menjadi path absolut ternormalisasi di dalam sandbox. Null bila tidak bisa dipastikan
 * (variabel shell, substitusi, ~user). Path relatif dihitung dari [workdir].
 */
internal fun resolvePath(raw: String, workdir: String, guestHome: String): String? {
    var p = raw
    if (p.contains('$') || p.contains('`')) return null
    if (p == "~") p = guestHome
    else if (p.startsWith("~/")) p = guestHome.trimEnd('/') + p.substring(1)
    else if (p.startsWith("~")) return null
    val base = if (p.startsWith("/")) p else workdir.trimEnd('/') + "/" + p
    val out = ArrayList<String>()
    for (part in base.split('/')) {
        when (part) {
            "", "." -> {}
            ".." -> { if (out.isNotEmpty()) out.removeAt(out.size - 1) }
            else -> out.add(part)
        }
    }
    return "/" + out.joinToString("/")
}

internal fun isInside(path: String, workdir: String): Boolean {
    val w = workdir.trimEnd('/')
    return path == w || path.startsWith("$w/")
}

private fun isWriter(name: String, args: List<String>, segRisk: Risk): Boolean =
    segRisk == Risk.DELETE || name in WRITERS ||
        (name == "sed" && args.any { it.startsWith("-i") || it == "--in-place" })

/**
 * Nilai satu perintah untuk mode sandbox.
 * @param workdir path workdir DI DALAM sandbox (guest), bukan path Termux.
 */
fun assessCommand(
    cmd: String,
    workdir: String = DEFAULT_SANDBOX_WORKDIR,
    guestHome: String = DEFAULT_GUEST_HOME
): Assessment {
    val reasons = ArrayList<String>()
    var blocked = false
    var network = false
    var allNamesSafe = true
    var outsideRead = false
    var neverEnds = false
    val wd = workdir.trimEnd('/')

    fun block(msg: String) { blocked = true; reasons.add("DIBLOKIR: $msg") }

    val risk = classify(cmd)

    if (FORK_BOMB.containsMatchIn(cmd)) block("pola fork bomb")
    if (DEV_NET.containsMatchIn(cmd) || URL_RE.containsMatchIn(cmd) || SCP_RE.containsMatchIn(cmd)) {
        network = true
        reasons.add("Menyentuh jaringan (alamat atau URL terdeteksi)")
    }

    // Sama seperti classify(): buang redirect yang tidak menulis file sebelum memecah perintah.
    val c = cmd.replace(Regex("""\d*>&\d+"""), " ").replace(Regex("""\d*>>?\s*/dev/null"""), " ")

    for (m in REDIRECT_RE.findAll(c)) {
        val target = unquote(m.groupValues[1])
        val r = resolvePath(target, workdir, guestHome)
        if (r == null || !isInside(r, workdir)) block("menulis ke luar workdir lewat redirect: $target")
    }

    for (seg in c.split(SEGMENT_SPLIT)) {
        val words = seg.trim().split(WHITESPACE).map { unquote(it) }.filter { it.isNotEmpty() }
        if (words.isEmpty()) continue
        val t = unwrap(words)
        val name = t.firstOrNull()?.substringAfterLast('/') ?: continue
        val args = t.drop(1)
        val segRisk = classify(seg)

        if (name !in SAFE_ALLOWLIST) allNamesSafe = false
        if (name == "tail" && args.any { isFollowFlag(it) }) {
            neverEnds = true
            reasons.add("tail -f tidak berakhir sendiri")
        }

        // Isi shell (bash -c '...') tidak bisa dipastikan: periksa kata-katanya.
        val nested = if (name in SHELL_NAMES) t.flatMap { it.split(NON_WORD) }.filter { it.isNotEmpty() } else emptyList()

        if (isBlockedName(name)) block("perintah '$name' tidak diizinkan di sandbox")
        val nestedBad = nested.firstOrNull { isBlockedName(it) }
        if (nestedBad != null) block("'$nestedBad' di dalam shell tidak diizinkan di sandbox")
        if (name in PERM_CMDS && args.any { isRecursiveFlag(it) }) block("perubahan izin atau pemilik massal (-R) tidak diizinkan")

        val gitSub = if (name == "git") args.firstOrNull { !it.startsWith("-") } else null
        val isNet = name in NETWORK_CMDS || (gitSub != null && gitSub in GIT_NETWORK_SUB) || nested.any { it in NETWORK_CMDS }
        if (isNet) {
            network = true
            reasons.add("Menyentuh jaringan: $name")
        }
        if (name.startsWith("python") || name in INTERPRETERS) {
            reasons.add("'$name' bisa memakai jaringan lewat skrip; tidak bisa dipastikan")
        }

        val paths = candidatePaths(args)
        val outside = paths.filter { p ->
            val r = resolvePath(p, workdir, guestHome)
            r == null || !isInside(r, workdir)
        }
        if (outside.isNotEmpty()) {
            val shown = outside.take(3).joinToString(", ")
            if (name == "cd" || name == "pushd") {
                block("'$name' tidak boleh pindah ke luar workdir ($shown)")
            } else if (isWriter(name, args, segRisk)) {
                block("'$name' menulis atau menghapus di luar workdir ($shown)")
            } else {
                outsideRead = true
                reasons.add("Menyentuh path di luar workdir: $shown")
            }
        }
        // "cd" tanpa tujuan pindah ke home tamu, yaitu di luar workdir; perintah relatif sesudahnya jadi tidak terukur.
        if ((name == "cd" || name == "pushd") && args.none { !it.startsWith("-") }) {
            block("'$name' tanpa tujuan pindah ke luar workdir")
        }
        if (name in RM_LIKE && paths.any { resolvePath(it, workdir, guestHome) == wd }) {
            block("tidak boleh menghapus workdir itu sendiri")
        }
    }

    val decision = when {
        blocked -> Decision.BLOCKED
        risk == Risk.READ && allNamesSafe && !network && !outsideRead && !neverEnds &&
            !cmd.contains('$') && !cmd.contains('`') -> Decision.SAFE
        else -> Decision.NEEDS_APPROVAL
    }
    return Assessment(decision, risk, network, reasons.distinct())
}
