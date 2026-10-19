# Aplikasi ini tanpa pustaka pihak ketiga dan tanpa refleksi: aturan bawaan AGP (proguard-android-optimize.txt)
# sudah cukup. Activity, Service, dan Provider di AndroidManifest dijaga otomatis.

# Nomor baris tetap ada di stack trace; petakan kembali dengan app/build/outputs/mapping/release/mapping.txt
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Nama konstanta enum disimpan ke disk (catatan perintah: Risk, ApprovalMode) dan dibaca lagi lewat valueOf().
# Jangan sampai ikut diacak antar-rilis.
-keepclassmembers enum com.kita.agent.** { *; }
