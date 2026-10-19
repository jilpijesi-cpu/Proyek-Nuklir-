# Threat model singkat AgentApp

Dokumen ini menjelaskan apa yang DIJAMIN dan apa yang TIDAK dijamin. Sumber: kode, SANDBOX.md, dan Laporan_Audit_Menyeluruh_AgentApp.md.

## Kapabilitas berisiko (AndroidManifest)
- `com.termux.permission.RUN_COMMAND`: menjalankan perintah shell di Termux.
- `REQUEST_INSTALL_PACKAGES`: memasang APK (lewat ApkProvider).
- `INTERNET`: memanggil API penyedia model; cleartext hanya untuk localhost/127.0.0.1.
- `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, `POST_NOTIFICATIONS`: agen tetap berjalan saat layar mati.

## Yang dijamin
- Perintah yang bukan SAFE harus disetujui pengguna; dialog menampilkan perintah asli, bukan hanya deskripsi model.
- Mode otonom default MATI dan butuh konfirmasi eksplisit (`autonomous_ack`).
- Di mode sandbox, perintah berjaringan SELALU minta persetujuan (`force = true`): tidak bisa dilewati mode otonom atau "Ya, semua".
- API key disimpan terenkripsi (Android Keystore, AES-GCM). Jika Keystore gagal, key TIDAK disimpan (fail-closed).
- `ApkProvider` hanya melayani file di dalam direktorinya (cek canonical path); komponen internal `exported=false`.
- Data aplikasi tidak ikut backup cloud atau transfer perangkat (`allowBackup=false` + `data_extraction_rules.xml`).

## Yang TIDAK dijamin
- **proot bukan batas keamanan.** Ia tidak memblokir jaringan dan tidak mencegah proses keluar dari workdir di sistem file tamu.
- **Pembatasan workdir dan jaringan adalah analisis teks (heuristik).** Payload yang disandikan (mis. `base64 | sh`), variabel shell, symlink, atau skrip python/node bisa lolos; satu-satunya penahannya adalah dialog persetujuan.
- Perintah yang pengguna setujui berjalan dengan hak Termux penuh.
- Output perintah dan isi percakapan dapat memuat data sensitif; belum ada redaksi otomatis.

## Data yang disimpan (penyimpanan privat aplikasi, `filesDir`)
| File | Isi |
|---|---|
| `history.json` | riwayat percakapan (`history.json.bad` bila rusak) |
| `agent_checkpoint.json` | checkpoint tugas agen |
| `command_log.jsonl` | perintah, status, dan hasil |
| `thumbs/` | thumbnail lampiran gambar |

Retensi: data disimpan sampai pengguna menghapusnya (fitur hapus semua belum ada; hapus lewat Pengaturan Android > Aplikasi > Hapus data).

## Mode Analisis APK
Opt-in, default mati. Menambah instruksi ke model (bukan pembatas teknis) agar reverse engineering
tetap berupa analisis statis: TIDAK membobol lisensi/DRM/anti-tamper, TIDAK mem-patch+distribusi APK
bajakan, TIDAK membuat tool serangan dari analisis malware. Ini instruksi ke model, bukan jaminan
teknis — pengguna yang memaksa lewat prompt tetap bisa mencoba; batas teknis yang nyata tetap
CommandPolicy + persetujuan + isolasi proot/Ubuntu seperti perintah lain.

## Rekomendasi operasional
Jangan aktifkan mode otonom di perangkat berisi data penting; tinjau perintah sebelum menyetujui; pertimbangkan build tanpa installer/Termux bila fitur itu tidak dibutuhkan.
