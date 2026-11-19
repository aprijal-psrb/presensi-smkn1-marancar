# Panduan Guru — Presensi SMKN 1 Marancar

## A. Sekali saja di awal (5 menit)

1. Pasang aplikasi **Presensi SMKN 1 Marancar** di HP guru (Android 10 ke atas).
2. Buka aplikasi → **Saya Guru** → **Data Siswa** → **Impor dari Excel / CSV**.
3. Pilih berkas daftar siswa. Format: kolom **NISN**, **Nama**, **Kelas** di baris pertama.
   Contoh: `docs/contoh-data-siswa.csv`. Hasil impor akan tampil sebagai daftar kelas + jumlah siswa.
4. **Hubungkan HP siswa ke jaringan yang sama dengan HP guru.**
   - Cara paling andal: nyalakan **Hotspot** di HP guru, siswa menyambung ke hotspot itu.
   - Wi-Fi sekolah juga bisa, asalkan siswa dan guru berada di jaringan yang sama dan
     Wi-Fi tersebut tidak memisahkan antar-perangkat (*client isolation*).

## B. Membuka sesi presensi (setiap hari, ±10 detik)

1. Buka aplikasi → **Saya Guru**.
2. Isi tiga kolom: **Nama guru**, **Mata pelajaran**, **Kelas** (kelas bisa dipilih dari daftar).
3. Tekan **Buka Sesi & Tampilkan QR**. QR langsung muncul — tidak ada isian lain.
4. Minta siswa memindai QR dari layar HP Anda.
5. Di bawah QR terlihat jumlah siswa yang sudah tercatat, dan daftarnya bertambah otomatis.

**Catatan status:** pemindaian dalam **15 menit pertama** tercatat **HADIR**, lewat dari itu **TERLAMBAT**.

## C. Menjaga QR tetap sah (anti titip absen)

- QR hanya berlaku **selama sesi ini terbuka**. Sesi ditutup → QR mati.
- Tombol **Ganti QR** membuat QR baru seketika; QR lama (misalnya yang sempat difoto siswa)
  langsung tidak bisa dipakai.
- Siswa hanya bisa tercatat **sekali** per sesi. Scan berulang diabaikan.
- Nama yang tercatat diambil dari daftar Excel, dan NISN harus cocok dengan kelas sesi tersebut.

## D. Bila ada siswa yang tidak bisa memindai

Di bawah QR ada **Kode manual** (6 huruf). Siswa membuka aplikasi → **Saya Siswa** →
**Kamera tidak bisa? Masukkan kode manual** → isi alamat server (tertulis di bawah QR,
contoh `http://192.168.43.1:8787`) dan kode tersebut.

## E. Menutup sesi

Tekan **Tutup Sesi** → **Tutup**. Data yang sudah masuk tetap tersimpan, QR tidak berlaku lagi.

> Jangan menutup aplikasi dari daftar aplikasi terakhir selama sesi berlangsung. Kalau HP terkunci,
> server tetap jalan (ada notifikasi "Sesi presensi berjalan").

## F. Melihat & mengekspor rekap

1. **Saya Guru** → **Rekap**.
2. Saring sesuai kebutuhan: **Kelas**, **Mapel**, **Tanggal** (atau *Semua tanggal*) → **Tampilkan**.
   Ringkasan sesi dan jumlah hadir/terlambat muncul di layar.
3. Tekan **Ekspor Rekap (CSV)** → pilih WhatsApp / Email / Drive untuk mengirim berkas.
   Berkas CSV bisa dibuka di Excel atau Google Sheets.

## G. Jika ada masalah

| Gejala | Penyebab & solusi |
|---|---|
| Siswa "Tidak bisa menghubungi HP guru" | HP siswa tidak satu jaringan. Nyalakan hotspot guru, sambungkan siswa, coba lagi. |
| Alamat di bawah QR `127.0.0.1` | HP guru belum tersambung ke Wi-Fi/hotspot mana pun. |
| "NISN tidak terdaftar di kelas ..." | NISN siswa belum ada di Excel, atau kelasnya salah. Perbaiki berkas lalu impor ulang. |
| "Sesi presensi sudah ditutup guru" | Sesi sudah ditutup. Buka sesi baru. |
| Data hilang setelah HP mati | Tidak akan terjadi: setiap scan langsung ditulis ke penyimpanan HP guru. |
