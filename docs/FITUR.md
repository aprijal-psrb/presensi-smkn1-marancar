# Daftar Fitur — Presensi SMKN 1 Marancar v1.0

Status per 19 November 2026. APK: Release `v1.0.2` (10,7 MB, Android 10+).

## Sudah dikerjakan

| # | Fitur | Keterangan |
|---|---|---|
| 1 | Dua peran dalam satu APK | Layar awal: **Saya Guru** / **Saya Siswa** |
| 2 | Layar guru hanya 3 isian | Nama guru, Mapel, Kelas → tombol → QR tampil |
| 3 | QR langsung tampil | Dibuat di HP, tanpa menunggu server luar |
| 4 | Isian terakhir diingat | Sesi berikutnya tinggal tekan tombol |
| 5 | QR terikat sesi | Token unik per sesi, mati saat sesi ditutup |
| 6 | Ganti QR sewaktu-waktu | Token baru; QR lama langsung ditolak server |
| 7 | Kode manual 6 huruf | Cadangan bila kamera siswa bermasalah |
| 8 | Daftar hadir langsung | Bertambah otomatis tiap 1,5 detik di layar guru |
| 9 | Anti dobel | `UNIQUE(sesi, nisn)` — scan kedua diabaikan |
| 10 | Validasi NISN per kelas | NISN di luar kelas sesi ditolak |
| 11 | Nama dari daftar Excel | Siswa tidak mengetik nama sendiri |
| 12 | Impor Excel/CSV sekali | Pembaca `.xlsx` bawaan, tanpa pustaka tambahan |
| 13 | Daftar siswa per kelas | 9 rombel / ±200 siswa, tampil otomatis |
| 14 | Status HADIR / TERLAMBAT | Lewat 15 menit dari sesi dibuka = TERLAMBAT |
| 15 | Data tercatat lengkap | Nama, NISN, kelas, mapel, guru, tanggal, jam, status |
| 16 | Tahan jaringan putus | HP siswa punya antrean `outbox`, kirim ulang otomatis |
| 17 | Tahan aplikasi tertutup | Semua data ditulis ke SQLite sebelum ditampilkan |
| 18 | Server tetap hidup saat layar mati | Foreground service + notifikasi |
| 19 | Rekap per kelas/mapel/tanggal | Saringan gabungan + ringkasan sesi |
| 20 | Ekspor CSV | Bisa dibuka di Excel / Google Sheets, dibagikan via WhatsApp/email |
| 21 | Tanpa VPS / langganan | HP guru menjadi server selama sesi terbuka |
| 22 | Build otomatis | GitHub Actions → APK bertanda tangan di setiap rilis |

## Belum dikerjakan (usulan tahap berikutnya)

| # | Fitur | Alasan belum |
|---|---|---|
| 1 | Sinkronisasi ke Google Sheets | Butuh Web App Apps Script milik sekolah + URL di pengaturan |
| 2 | Server pusat / dashboard wakasek | Perlu VPS; v1.0 sengaja tanpa infrastruktur |
| 3 | Rekap gabungan antar-HP guru | Tiap HP guru menyimpan datanya sendiri; belum ada penggabungan |
| 4 | QR berputar otomatis tiap 30 detik | v1.0 memakai tombol **Ganti QR** manual |
| 5 | Login guru (PIN/akun) | Belum ada autentikasi; siapa pun bisa membuka sesi |
| 6 | Foto selfie saat scan | Perlu kamera depan + penyimpanan tambahan |
| 7 | Notifikasi ke orang tua | Perlu gateway SMS/WA berbayar |
| 8 | Rekap per siswa (persentase kehadiran) | Data sudah ada, tampilan belum dibuat |
| 9 | Impor langsung dari Google Sheets | v1.0 hanya file `.xlsx` / `.csv` |
| 10 | Mode luring penuh (tanpa jaringan sama sekali) | Butuh QR berisi data sesi + sinkronisasi dua arah |

## Catatan teknis penting

- **Ketergantungan jaringan:** HP siswa harus satu jaringan dengan HP guru (hotspot guru paling andal).
  Bila Wi-Fi sekolah memakai *client isolation*, scan tidak akan sampai.
- **Kunci tanda tangan** ada di repo (`app/keystore/presensi.p12.b64`). Simpan baik-baik: APK versi
  berikutnya harus ditandatangani kunci yang sama agar bisa memperbarui aplikasi tanpa uninstall.
- **Data hanya di HP guru.** Belum ada cadangan otomatis; ekspor CSV secara berkala disarankan.
