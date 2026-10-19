// Samakan versi plugin dengan proyek ~/kita yang sudah berhasil build di Termux
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

// Pastikan file gradlew selalu memiliki izin eksekusi (0755 / rwxr-xr-x)
rootProject.file("gradlew").takeIf { it.isFile }?.setExecutable(true, false)

