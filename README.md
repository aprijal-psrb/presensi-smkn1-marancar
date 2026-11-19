# Presensi SMKN 1 Marancar

Aplikasi presensi siswa berbasis QR code untuk **SMK Negeri 1 Marancar** (Android native, Kotlin).
Satu aplikasi, dua peran: **guru** (pembuat QR) dan **siswa** (pemindai).

## Alur inti (3 langkah)

1. Guru isi **Nama guru + Mapel + Kelas** → tekan satu tombol → **QR langsung tampil**.
2. Siswa memindai QR itu dengan HP-nya sendiri.
3. Kehadiran siswa **langsung tercatat** di HP guru (nama, NISN, kelas, mapel, guru, tanggal, jam, status).

## Cara mendapatkan APK

1. Buka tab **Releases** di repositori ini.
2. Unduh `Presensi-SMKN1-Marancar-v1.0.apk`.
3. Buka di HP Android 10+ → izinkan *Instal dari sumber tidak dikenal* → pasang.

Setiap `push` ke `main` akan otomatis membangun APK baru lewat GitHub Actions.

## Arsitektur singkat

```
HP GURU                                   HP SISWA
┌──────────────────────────┐             ┌──────────────────────┐
│ Aplikasi Presensi        │             │ Aplikasi Presensi    │
│  • SQLite (sesi, absen)  │◀── HTTP ────│  • kamera QR         │
│  • Server NanoHTTPD :8787│   POST      │  • antrean outbox    │
│  • QR: /s/<token>        │  /api/absen │  (tahan jaringan     │
└──────────────────────────┘             │   putus)             │
                                          └──────────────────────┘
```

- **Tanpa VPS, tanpa Google Sheets, tanpa langganan.** HP guru menjadi server selama sesi terbuka
  (server dijaga *foreground service*, jadi tetap hidup walau layar mati).
- **Anti curang:** token QR unik per sesi, mati saat sesi ditutup atau guru menekan *Ganti QR*;
  NISN harus terdaftar di kelas sesi tersebut; nama diambil dari daftar Excel, bukan diketik siswa.
- **Anti dobel:** `UNIQUE(sesi, nisn)` di SQLite — scan kedua diabaikan.
- **Anti hilang:** data ditulis ke SQLite dulu; di HP siswa ada tabel `outbox` yang mengirim ulang
  otomatis bila jaringan putus.

## Struktur folder

```
app/src/main/java/id/sch/smkn1marancar/presensi/
├── core/
│   ├── App.kt            # Application + pengelola server
│   ├── Db.kt             # SQLite: sesi, absen, roster, outbox
│   ├── Model.kt          # data class Sesi / Absen / Siswa
│   ├── SessionServer.kt  # HTTP server di HP guru (endpoint /s, /api/*)
│   ├── SessionService.kt # foreground service penjaga server
│   ├── Net.kt            # klien HTTP untuk HP siswa
│   ├── ImporSiswa.kt     # pembaca .xlsx / .csv tanpa pustaka tambahan
│   ├── Ekspor.kt         # ekspor rekap ke CSV
│   └── Util.kt           # waktu, IP lokal, QR, preferensi
└── ui/
    ├── MainActivity.kt        # pilih peran guru / siswa
    ├── TeacherHomeActivity.kt # 3 isian → QR
    ├── QrSessionActivity.kt   # QR + daftar hadir langsung
    ├── RosterActivity.kt      # impor Excel
    ├── ReportActivity.kt      # rekap + ekspor
    └── StudentScanActivity.kt # scan + antrean offline
```

## Membangun sendiri (opsional)

```bash
# butuh JDK 17 + Android SDK 34
gradle :app:assembleRelease
```

Kunci tanda tangan ada di `app/keystore/presensi.p12.b64` (khusus aplikasi sekolah ini,
bukan kunci rahasia). Workflow CI men-decode-nya sebelum menandatangani APK.

## Dokumen

- [`docs/PANDUAN-GURU.md`](docs/PANDUAN-GURU.md) — satu halaman untuk guru
- [`docs/PANDUAN-SISWA.md`](docs/PANDUAN-SISWA.md) — satu halaman untuk siswa
- [`docs/FITUR.md`](docs/FITUR.md) — fitur yang sudah & belum dikerjakan
- [`docs/contoh-data-siswa.csv`](docs/contoh-data-siswa.csv) — contoh format impor
