# Perbaikan alur interaksi AI

- Status awal kini menunjukkan `Mengirim instruksi ke AI`.
- Jika model belum mengirim respons setelah 1,8 detik, status berubah menjadi `Menunggu respons model AI`.
- Saat putaran berikutnya dimulai, status berubah menjadi `Menganalisis hasil langkah`.
- Saat token jawaban mulai diterima, status berubah menjadi `Menulis jawaban`.
- Tombol saran generik `Lanjutkan` tidak lagi muncul setelah setiap jawaban. Tombol saran hanya ditampilkan jika ada tindakan kontekstual, misalnya memperbaiki error, build ulang, atau menjawab pertanyaan AI.
- Tidak ada klaim progres palsu; status hanya menunjukkan keadaan aplikasi yang diketahui.
