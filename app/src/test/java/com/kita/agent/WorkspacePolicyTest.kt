package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkspacePolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // =========================================================================
    // 1. Validasi Nama Ruang Kerja (^[A-Za-z0-9_-]{1,32}$)
    // =========================================================================

    @Test
    fun validateWorkspaceName_acceptsValidNamesIncludingDefault() {
        assertTrue(validateWorkspaceName("default").valid)
        assertTrue(validateWorkspaceName("projek_1").valid)
        assertTrue(validateWorkspaceName("kita-app-2026").valid)
        assertTrue(validateWorkspaceName("A").valid)
        assertTrue(validateWorkspaceName("a".repeat(32)).valid)
    }

    @Test
    fun validateWorkspaceName_rejectsSpacesDotsSlashesAndDoubleDots() {
        assertFalse(validateWorkspaceName("").valid)
        assertFalse(validateWorkspaceName("ruang kerja").valid)
        assertFalse(validateWorkspaceName(" hidden").valid)
        assertFalse(validateWorkspaceName("my.project").valid)
        assertFalse(validateWorkspaceName(".git").valid)
        assertFalse(validateWorkspaceName("..").valid)
        assertFalse(validateWorkspaceName("a..b").valid)
        assertFalse(validateWorkspaceName("sub/dir").valid)
        assertFalse(validateWorkspaceName("sub\\dir").valid)
        assertFalse(validateWorkspaceName("../kita").valid)
        assertFalse(validateWorkspaceName("a".repeat(33)).valid)
    }

    // =========================================================================
    // 2. Perintah Pembuatan Ruang Kerja & Pemeriksaan Exit Code Termux
    // =========================================================================

    @Test
    fun buildCreateWorkspaceCommand_targetsWorkspacesDirectoryAndRunsGitInit() {
        val cmd = buildCreateWorkspaceCommand("default", "/data/data/com.termux/files/home")
        assertTrue(cmd.contains("/data/data/com.termux/files/home/workspaces/default"))
        assertTrue(cmd.contains("mkdir -p"))
        assertTrue(cmd.contains("git init"))
    }

    @Test
    fun didWorkspaceCommandSucceed_checksExitCodeStrictly() {
        assertTrue(didWorkspaceCommandSucceed("exit=0\nInitialized empty Git repository"))
        assertFalse(didWorkspaceCommandSucceed("exit=1\nmkdir: permission denied"))
        assertFalse(didWorkspaceCommandSucceed("exit=127\ngit: not found"))
        assertFalse(didWorkspaceCommandSucceed("Gagal memanggil Termux"))
    }

    // =========================================================================
    // 3. Resolusi Canonical Path & Pengecekan Batas Ruang Kerja
    // =========================================================================

    @Test
    fun isPathInsideWorkspace_allowsRelativeAndCanonicalPathsInsideWorkspace() {
        val home = tmp.newFolder("home")
        val ws = File(home, "workspaces/default").apply { mkdirs() }
        File(ws, "src/main").mkdirs()
        val f = File(ws, "src/main/App.kt").apply { writeText("class App") }

        assertTrue(isPathInsideWorkspace("src/main/App.kt", ws, home))
        assertTrue(isPathInsideWorkspace("./src/main/../main/App.kt", ws, home))
        assertTrue(isPathInsideWorkspace(f.canonicalPath, ws, home))
    }

    @Test
    fun isPathInsideWorkspace_blocksParentTraversalAndOutsideAbsolutePaths() {
        val home = tmp.newFolder("home")
        val ws = File(home, "workspaces/default").apply { mkdirs() }
        val otherWs = File(home, "workspaces/other").apply { mkdirs() }

        assertFalse(isPathInsideWorkspace("..", ws, home))
        assertFalse(isPathInsideWorkspace("../other/file.txt", ws, home))
        assertFalse(isPathInsideWorkspace("src/../../outside.txt", ws, home))
        assertFalse(isPathInsideWorkspace("/etc/hosts", ws, home))
        assertFalse(isPathInsideWorkspace(otherWs.canonicalPath + "/a.txt", ws, home))
    }

    @Test
    fun isPathInsideWorkspace_blocksRawTildeAndHomeReferences() {
        val home = tmp.newFolder("home")
        val ws = File(home, "workspaces/default").apply { mkdirs() }

        assertFalse(isPathInsideWorkspace("~", ws, home))
        assertFalse(isPathInsideWorkspace("~/workspaces/default/a.txt", ws, home))
        assertFalse(isPathInsideWorkspace("\$HOME/workspaces/default/a.txt", ws, home))
        assertFalse(isPathInsideWorkspace("\${HOME}/file.txt", ws, home))
        assertNull(resolveCanonicalWorkspacePath("~/a.txt", ws, home))
        assertNull(resolveCanonicalWorkspacePath("\$HOME/a.txt", ws, home))
    }

    @Test
    fun isPathInsideWorkspace_blocksAccessToKitaDirAndEnvOrKeystore() {
        val home = tmp.newFolder("home")
        val kitaDir = File(home, "kita").apply { mkdirs() }
        val ws = File(home, "workspaces/default").apply { mkdirs() }

        assertFalse(isPathInsideWorkspace("~/kita", ws, home))
        assertFalse(isPathInsideWorkspace("~/kita/MainActivity.kt", ws, home))
        assertFalse(isPathInsideWorkspace(kitaDir.canonicalPath + "/MainActivity.kt", ws, home))
        assertFalse(isPathInsideWorkspace(".env", ws, home))
        assertFalse(isPathInsideWorkspace(".env.local", ws, home))
        assertFalse(isPathInsideWorkspace("config/.env", ws, home))
        assertFalse(isPathInsideWorkspace("debug.keystore", ws, home))
        assertFalse(isPathInsideWorkspace("keystore.properties", ws, home))
        assertFalse(isPathInsideWorkspace("app/release.jks", ws, home))
    }

    @Test
    fun isPathInsideWorkspace_blocksSymlinkPointingOutsideWorkspace() {
        val home = tmp.newFolder("home")
        val ws = File(home, "workspaces/default").apply { mkdirs() }
        val outsideSecret = File(home, "outside_secret.txt").apply { writeText("secret") }
        val linkInsideWs = File(ws, "link_out.txt")

        try {
            Files.createSymbolicLink(linkInsideWs.toPath(), outsideSecret.toPath())
        } catch (_: Exception) {
            return
        }

        assertFalse(
            "Symlink di dalam ruang kerja yang mengarah ke luar harus ditolak oleh canonical path",
            isPathInsideWorkspace("link_out.txt", ws, home)
        )
    }

    // =========================================================================
    // 4. Penilaian Kebijakan Perintah Ruang Kerja (CommandPolicy.kt)
    // =========================================================================

    @Test
    fun assessWorkspaceCommand_blocksEscapingWorkspaceCdKitaEnvAndKeystore() {
        val home = tmp.newFolder("home")
        val kita = File(home, "kita").apply { mkdirs() }
        val ws = File(home, "workspaces/default").apply { mkdirs() }

        // Perintah baca normal di dalam ruang kerja diizinkan
        assertEquals(
            Decision.SAFE,
            assessWorkspaceCommand("ls -la src", ws.canonicalPath, home.canonicalPath).decision
        )
        assertEquals(
            Decision.SAFE,
            assessWorkspaceCommand("cat src/App.kt", ws.canonicalPath, home.canonicalPath).decision
        )

        // cd tanpa argumen, cd keluar, cd ~/kita -> BLOCKED
        assertEquals(
            Decision.BLOCKED,
            assessWorkspaceCommand("cd", ws.canonicalPath, home.canonicalPath).decision
        )
        assertEquals(
            Decision.BLOCKED,
            assessWorkspaceCommand("cd ..", ws.canonicalPath, home.canonicalPath).decision
        )
        assertEquals(
            Decision.BLOCKED,
            assessWorkspaceCommand("cd ~/kita", ws.canonicalPath, home.canonicalPath).decision
        )
        assertEquals(
            Decision.BLOCKED,
            assessWorkspaceCommand("cat ${kita.canonicalPath}/secret.txt", ws.canonicalPath, home.canonicalPath).decision
        )

        // ~ atau $HOME mentah -> BLOCKED
        assertEquals(
            Decision.BLOCKED,
            assessWorkspaceCommand("ls ~", ws.canonicalPath, home.canonicalPath).decision
        )
        assertEquals(
            Decision.BLOCKED,
            assessWorkspaceCommand("cat \$HOME/.bashrc", ws.canonicalPath, home.canonicalPath).decision
        )

        // Path keluar "../" -> BLOCKED
        assertEquals(
            Decision.BLOCKED,
            assessWorkspaceCommand("cat ../other/file.txt", ws.canonicalPath, home.canonicalPath).decision
        )

        // Akses .env dan keystore -> BLOCKED
        assertEquals(
            Decision.BLOCKED,
            assessWorkspaceCommand("cat .env", ws.canonicalPath, home.canonicalPath).decision
        )
        assertEquals(
            Decision.BLOCKED,
            assessWorkspaceCommand("cp debug.keystore /tmp/a", ws.canonicalPath, home.canonicalPath).decision
        )
    }
}
