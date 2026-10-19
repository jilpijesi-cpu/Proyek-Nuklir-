package com.kita.agent

// Menyalin lampiran dari Download/ClaudeMythos ke workdir sandbox SEBELUM agen berjalan.
// Sandbox sengaja tidak melihat /sdcard, jadi penyalinan dilakukan dari shell Termux utama
// ke folder rootfs tamu. Hanya file yang dilampirkan pengguna yang disalin; tidak ada mount baru.

internal object SandboxStage {

    /** Folder asal yang diizinkan. Mencegah penyalinan file lain atas nama lampiran. */
    const val ALLOWED_SRC_PREFIX = "/sdcard/Download/ClaudeMythos/"

    /** Lokasi rootfs distro dilihat dari sisi Termux (host). */
    fun rootfsDir(c: SandboxConfig): String =
        "${c.termuxPrefix}/var/lib/proot-distro/installed-rootfs/${c.distro}"

    /** Path file setelah disalin, dilihat dari DALAM sandbox. */
    fun guestPath(c: SandboxConfig, name: String): String =
        "${c.workdir.trimEnd('/')}/$name"

    /** Nama file tujuan. Null bila nama tidak aman (kosong, '.', '..', atau mengandung '/'). */
    fun safeName(name: String): String? =
        name.takeIf { it.isNotBlank() && it != "." && it != ".." && !it.contains('/') }

    /**
     * Perintah untuk shell Termux yang menyalin [srcPath] ke workdir tamu.
     * Null bila sumber di luar folder lampiran atau nama tujuan tidak aman.
     */
    fun copyCommand(srcPath: String, c: SandboxConfig): String? {
        if (!srcPath.startsWith(ALLOWED_SRC_PREFIX)) return null
        if (srcPath.split('/').any { it == ".." || it == "." }) return null
        val name = safeName(srcPath.substringAfterLast('/')) ?: return null
        val dir = rootfsDir(c) + c.workdir
        val dest = rootfsDir(c) + guestPath(c, name)
        return "mkdir -p ${SandboxCommand.shq(dir)} && cp ${SandboxCommand.shq(srcPath)} ${SandboxCommand.shq(dest)}"
    }
}
