package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test classify(): penilai risiko yang menentukan perintah mana boleh jalan otomatis.
 * Murni JVM (tanpa Android): jalankan dengan  ./gradlew testDebugUnitTest
 */
class ClassifyTest {

    private fun assertAll(expected: Risk, cmds: List<String>) {
        for (c in cmds) assertEquals("classify(${c.replace("\n", "\\n")})", expected, classify(c))
    }

    /** Untuk kasus di mana yang penting hanya: tidak boleh dianggap READ. */
    private fun assertAtLeast(min: Risk, cmds: List<String>) {
        for (c in cmds) {
            val r = classify(c)
            assertTrue("classify(${c.replace("\n", "\\n")}) = $r, seharusnya minimal $min", r.ordinal >= min.ordinal)
        }
    }

    @Test
    fun urutanRisikoDipakaiPenilai() {
        // classify() membandingkan lewat ordinal: READ < WRITE < DELETE
        assertTrue(Risk.READ.ordinal < Risk.WRITE.ordinal)
        assertTrue(Risk.WRITE.ordinal < Risk.DELETE.ordinal)
    }

    @Test
    fun perintahBacaSaja_READ() = assertAll(
        Risk.READ,
        listOf(
            "ls -la",
            "cat a.txt",
            "pwd",
            "echo hi",
            "head -n 5 a | cut -d, -f1",
            "ls | grep foo",
            "grep -r foo . | head -20",
            "find . -name '*.kt'",
            "unzip -l a.zip",
            "unzip -p a.zip dir/file.kt",
            "tree -L 2",
            "ls && echo ok",
            "",
            "   "
        )
    )

    @Test
    fun redirectYangTidakMenulisFile_tetapREAD() = assertAll(
        Risk.READ,
        listOf(
            "ls 2>&1",
            "ls > /dev/null",
            "ls 2>/dev/null",
            "cat a >/dev/null 2>&1",
            "echo hi >&2"
        )
    )

    @Test
    fun redirectKeFile_WRITE() = assertAll(
        Risk.WRITE,
        listOf(
            "echo hi > out.txt",
            "cat a >> b",
            "ls 2> err.log",
            "echo a>b"
        )
    )

    @Test
    fun perintahMengubahAtauTidakDikenal_WRITE() = assertAll(
        Risk.WRITE,
        listOf(
            "mkdir x",
            "touch f",
            "cp a b",
            "mv a b",
            "sed -i s/a/b/ f",
            "chmod +x f",
            "./gradlew assembleDebug",
            "curl http://x",
            "git status",
            "python3 a.py",
            "LS"
        )
    )

    @Test
    fun perintahMenghapusAtauMenimpa_DELETE() = assertAll(
        Risk.DELETE,
        listOf(
            "rm -rf build",
            "rmdir x",
            "/bin/rm x",
            "unlink x",
            "truncate -s 0 f",
            "dd if=a of=b",
            "shred f",
            "find . -delete",
            "find . -name x -delete",
            "unzip -o a.zip"
        )
    )

    @Test
    fun rantaiPerintah_diambilYangTerburuk() = assertAll(
        Risk.DELETE,
        listOf(
            "ls; rm x",
            "ls && rm x",
            "ls || rm x",
            "ls & rm x",
            "ls\nrm x",
            "(rm x)",
            "{ rm x; }",
            "ls -l;rm x",
            "ls|rm x",
            "cat a.txt && rm a.txt"
        )
    )

    @Test
    fun pembungkus_sudoEnvNohup_naikJadiWRITE() = assertAll(
        Risk.WRITE,
        listOf(
            "sudo ls",
            "env ls",
            "nohup ls"
        )
    )

    @Test
    fun pembungkus_yangMembungkusPenghapus_DELETE() = assertAll(
        Risk.DELETE,
        listOf(
            "sudo rm -rf x",
            "xargs rm",
            "ls | xargs rm"
        )
    )

    @Test
    fun shellDenganIsiTidakPasti_WRITE() = assertAll(
        Risk.WRITE,
        listOf(
            "sh -c ls",
            "eval ls",
            "source env.sh",
            ". ./env.sh",
            "zsh -c 'echo hi'"
        )
    )

    @Test
    fun shellYangIsinyaMenghapus_DELETE() = assertAll(
        Risk.DELETE,
        listOf(
            "bash -c 'rm x'"
        )
    )

    @Test
    fun opsiBerbahayPadaPerintahBaca_WRITE() = assertAll(
        Risk.WRITE,
        listOf(
            "find . -exec cat {} \\;",
            "find . -fprint out.txt",
            "tree -o out.txt",
            "tree --output=out.txt"
        )
    )

    @Test
    fun substitusiPerintah_tidakPernahREAD() = assertAtLeast(
        Risk.WRITE,
        listOf(
            "echo \$(ls)",
            "echo `ls`",
            "diff <(ls) <(ls)",
            "cat x > >(tee y)"
        )
    )

    @Test
    fun penghapusDiBalikPembungkusMenjadiDELETE() = assertAll(
        Risk.DELETE,
        listOf(
            "timeout 5 rm x",
            "nice -n 5 rm x",
            "find . -exec rm {} \\;"
        )
    )
}
