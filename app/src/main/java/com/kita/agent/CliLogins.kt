package com.kita.agent

// Daftar CLI berlangganan yang bisa dipakai lewat login akun (tanpa API key).
// Menambah CLI baru = menambah SATU baris di CLI_LOGINS: preset penyedia, tombol login, dan cek status ikut otomatis.
// Murni Kotlin tanpa Android, bisa di-unit-test (CliLoginTest).

internal class CliLogin(
    val name: String,     // nama di daftar penyedia (Pengaturan)
    val account: String,  // akun untuk login (dipakai di teks tombol dan status)
    val bin: String,      // nama perintah CLI
    val install: String,  // perintah pasang kalau belum ada
    val login: String,    // perintah login (dijalankan di sesi Termux yang terlihat)
    val check: String,    // perintah cek login: exit 0 = sudah masuk
    val run: String,      // templat perintah chat; {prompt} diganti pesan pengguna
    val tip: String = ""  // petunjuk singkat saat tombol login diketuk
)

private const val NODE = "pkg install -y nodejs && "

internal val CLI_LOGINS = listOf(
    CliLogin(
        "Codex (ChatGPT)", "ChatGPT", "codex",
        NODE + "npm install -g @openai/codex", "codex login",
        "test -s ~/.codex/auth.json",
        "codex exec --skip-git-repo-check {prompt} 2>/dev/null"
    ),
    CliLogin(
        "Gemini CLI (Google)", "Google", "gemini",
        NODE + "npm install -g @google/gemini-cli", "gemini",
        "test -s ~/.gemini/oauth_creds.json",
        "gemini -p {prompt} 2>/dev/null",
        "Pilih \"Login with Google\", lalu keluar dari Gemini setelah berhasil"
    ),
    CliLogin(
        "Claude Code (Claude.ai)", "Claude.ai", "claude",
        NODE + "npm install -g @anthropic-ai/claude-code", "claude",
        "test -s ~/.claude/.credentials.json -o -s ~/.claude/credentials.json",
        "claude -p {prompt} 2>/dev/null",
        "Ikuti login di browser, lalu keluar dari Claude setelah berhasil"
    )
)

internal fun cliLoginOf(name: String): CliLogin? = CLI_LOGINS.firstOrNull { it.name == name }
