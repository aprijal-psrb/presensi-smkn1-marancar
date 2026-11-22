# Kunci Tanda Tangan APK — Status & Cara Merawat

Terakhir diperbarui: 22 November 2026.

## Ringkasan

| | Kunci lama (v1.0–v1.1) | Kunci baru (v1.2 ke atas) |
|---|---|---|
| Lokasi | dulu di repo `app/keystore/presensi.p12.b64` | GitHub Secrets (tidak di repo) |
| Password | tertulis di `app/build.gradle.kts` | hanya di GitHub Secrets |
| Status | **BOCOR — jangan dipakai lagi** | aman |
| SHA-256 | `E7:CD:90:47:E9:A8:D2:C3:85:47:20:0D:43:81:B8:5B:45:A2:11:D1:A5:CC:01:DB:87:80:1A:6B:84:8B:C1:94` | `6F:D2:5D:05:D9:5A:95:AE:D1:DC:F1:FE:40:1B:C3:F3:7F:E6:96:1B:8D:61:BF:E7:C1:CB:E6:E7:FA:5D:24:BB` |
| Berlaku sampai | 2056 | 2056 |

## Kenapa kunci lama diganti

File kunci (`presensi.p12`) dan password-nya pernah ikut ter-commit ke repositori
publik. Siapa pun bisa mengunduhnya, membuka kunci privat, lalu membuat APK yang
ditandatangani atas nama aplikasi sekolah ini. Karena itu kunci lama dianggap
**mati** dan seluruh riwayat git dibersihkan (force-push) pada 22 November 2026.

Catatan: mengganti password saja tidak cukup — file `.p12`-nya sendiri sudah publik,
jadi kunci privatnya sudah di tangan orang lain.

## Akibat bagi pengguna

Android hanya mau memasang pembaruan bila tanda tangannya sama. Karena kunci
berganti, APK v1.2 **tidak bisa menimpa** aplikasi v1.0–v1.1.

Langkah migrasi untuk guru (sekali saja):

1. Buka aplikasi lama → menu **Rekap** → **Ekspor CSV**, simpan/kirim berkasnya.
2. **Uninstall** aplikasi Presensi.
3. Pasang APK v1.2 dari halaman Release.
4. Impor ulang daftar siswa (Excel/CSV) lewat menu daftar siswa.

Data absensi lama tetap terbaca di Excel/Google Sheets dari CSV yang diekspor.

## Di mana kunci baru disimpan

Empat nilai berikut ada di **Settings → Secrets and variables → Actions** repositori:

| Nama secret | Isi |
|---|---|
| `KEYSTORE_B64` | isi file `presensi.p12` yang di-encode base64 (satu baris) |
| `KEYSTORE_PASSWORD` | password keystore |
| `KEY_ALIAS` | `presensi` |
| `KEY_PASSWORD` | password kunci (sama dengan keystore) |

Workflow `.github/workflows/build.yml` men-decode kunci dari `KEYSTORE_B64` ke
`app/keystore/presensi.p12`, memakainya untuk menandatangani APK, lalu menghapusnya
lagi dari runner.

## Simpan salinan luring!

GitHub Secrets tidak bisa dibaca ulang. **Simpan salinan file `presensi.p12` beserta
password-nya di tempat aman** (mis. Google Drive akun sekolah + flashdisk kantor,
bukan repo dan bukan grup WhatsApp). Bila file ini hilang, aplikasi tidak bisa
diperbarui lagi dan harus dibuat kunci baru dari nol — yang berarti semua pengguna
harus uninstall sekali lagi.

## Aturan yang tidak boleh dilanggar

- Jangan pernah menaruh `*.p12`, `*.jks`, atau password kunci di dalam repo —
  `.gitignore` sudah memblokirnya, jangan diakali dengan `git add -f`.
- Jangan menaruh password di kode, skrip, dokumentasi, atau screenshot.
- Bila kunci dicurigai bocor lagi: buat kunci baru, ganti secrets, naikkan
  `versionCode`, dan catat di berkas ini.

## Membangun APK sendiri (tanpa CI)

```bash
export KEYSTORE_PASSWORD='...'      # dari tempat penyimpanan aman
export KEY_ALIAS='presensi'
export KEY_PASSWORD='...'
cp /path/aman/presensi.p12 app/keystore/presensi.p12
gradle :app:assembleRelease
```

Bila `KEYSTORE_PASSWORD` kosong, build tetap jalan tetapi menghasilkan APK
**tanpa tanda tangan** — jangan dibagikan ke guru.
