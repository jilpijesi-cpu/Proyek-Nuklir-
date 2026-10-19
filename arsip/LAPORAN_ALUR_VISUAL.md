# AgentApp — alur visual satu baris

Perubahan:
- Status pekerjaan tetap berada pada satu baris elegan.
- Teks status berganti perlahan setiap 2,2 detik sesuai fase yang diketahui: mengirim instruksi, menunggu respons, menganalisis langkah, menulis jawaban, atau menjalankan tindakan.
- Detail perintah aktual tetap dapat dibuka melalui chevron status; baris ringkas tidak dipenuhi log.
- Rotasi berhenti ketika status disembunyikan atau tugas selesai.
- Tidak menambahkan baris status baru untuk setiap langkah, sehingga layar chat tetap bersih.

Catatan:
- Ini adalah perubahan kode sumber. APK belum dibangun atau diuji di perangkat pada lingkungan ini.
- Baris status menjelaskan fase yang diketahui; ia bukan persentase kemajuan palsu. Laporan akhir tetap bergantung pada jawaban AI dan hasil tool yang sebenarnya.
