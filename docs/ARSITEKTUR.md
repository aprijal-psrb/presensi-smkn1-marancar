# Review Arsitektur — Presensi SMKN 1 Marancar (v1.0)

Tanggal review: 2026-11-20 · Cabang: `main` · Cakupan baca: 6 berkas inti (`Db.kt`, `SessionServer.kt`, `App.kt`, `StudentScanActivity.kt`, `ImporSiswa.kt`, `Util.kt`).

## 1. Ringkasan arsitektur saat ini

Satu APK, dua peran. HP guru menjalankan **NanoHTTPD (port 8787)** di dalam foreground service dan menjadi satu-satunya "server". HP siswa memindai QR berisi `http://<ip-guru>:<port>/s/<token>` lalu `POST /api/absen`. Tidak ada VPS dan tidak ada Google Sheets — sumber kebenaran adalah SQLite di HP guru, ditulis lebih dulu sebelum ditampilkan/diekspor (`App.kt`).

```
HP GURU (foreground service)                    HP SISWA (±200, ~22/rombel)
┌──────────────────────────────────┐            ┌──────────────────────────────────┐
│ Activity Guru                    │            │ StudentScanActivity              │
│  buka sesi → token 96-bit +      │            │  pindai QR / kode manual 6 huruf │
│  kode 6 huruf → tampilkan QR     │            │  identitas: SharedPreferences    │
│        │                         │            │  outbox: SQLite lokal            │
│        ▼                         │  Wi-Fi /   │        │                         │
│ SQLite presensi.db               │  hotspot   │        ▼                         │
│  sesi · absen (UNIQUE            │  sekolah   │  GET  /api/sesi, /api/roster    │
│  sesi,nisn) · roster             │◄───────────┤  POST /api/absen {token,nisn}   │
│        ▲ semua fungsi            │            │  gagal jaringan → outbox        │
│        │ @Synchronized           │            │  → kirim ulang otomatis         │
│ SessionServer (NanoHTTPD)        │            └──────────────────────────────────┘
│  :8787 (naik +1 s.d. +9 bila     │
│  port sibuk)                     │
└──────────────────────────────────┘
```

Alur data: scan → `GET /api/sesi` (validasi token, tahu kelas) → pilih nama dari roster / pakai identitas tersimpan → `POST /api/absen` → validasi token aktif + NISN terdaftar di kelas → `INSERT ... CONFLICT_IGNORE` (UNIK `sesi,nisn`) → status HADIR/TERLAMBAT dihitung dari jam HP guru (toleransi default 15 menit).

**Yang sudah benar dan layak dipertahankan:** tulis-ke-SQLite-dulu (tahan mati listrik/proses); `UNIQUE(sesi,nisn)` + `CONFLICT_IGNORE` = mustahil dobel; rotasi token saat ganti QR/tutup sesi (QR lama mati); jam server sebagai acuan status terlambat; nama diambil dari roster, bukan ketikan siswa; kolom `absen` denormalisasi (nama/kelas/mapel/guru disalin saat dicatat — aman dari perubahan roster); impor Excel dalam transaksi atomik; token `SecureRandom` 96-bit.

**Asumsi dan ketidakpastian (eksplisit):**
- Activity/layanan guru (pembuatan QR, layar daftar hadir, foreground service), `Net.kt`, model data, manifest, dan `build.gradle` **tidak dibaca** dalam review ini; perilakunya disimpulkan dari konteks dan ditandai asumsi.
- NanoHTTPD dipakai dengan konstruktor bawaan → default `DefaultAsyncRunner`: **satu thread baru per koneksi (paralel di lapisan HTTP)**. Versi pustaka tidak diverifikasi.
- Angka kinerja di bawah adalah **estimasi teoretis, bukan hasil pengukuran**.
- Nama antarmuka jaringan hotspot berbeda antar merek Android (`wlan0` mode AP vs `ap0`/`swlan0`); banyak HP membatasi klien hotspot pada kisaran 8–10 perangkat (belum diuji).

## 2. Temuan

| # | Gejala di lapangan | Penyebab teknis | Risiko | Berkas/fungsi |
|---|--------------------|-----------------|--------|---------------|
| T1 | Titip absen lewat NISN teman: siswa A memindai QR, memilih nama teman B, mengetik NISN B → B tercatat HADIR tanpa hadir; satu siswa bisa "menitipkan" beberapa teman sekali jalan | `/api/absen` hanya memvalidasi NISN terdaftar di kelas. NISN bukan rahasia: tercetak di dokumen sekolah, diketahui teman sekelas, dan endpoint `/api/roster` membagikan pasangan nama–NISN seluruh kelas ke siapa pun yang memegang token. Pengecekan NISN di HP siswa (`tanyaNisn`) membandingkan isian dengan roster yang sudah diunduh HP itu sendiri — bukan bukti identitas. Identitas tersimpan plaintext + tombol "Ganti identitas" terbuka. Tidak ada PIN, tidak ada pengikat perangkat | **Tinggi** | `SessionServer.apiRoster/apiAbsen`, `StudentScanActivity.pilihNama/tanyaNisn/konfirmasiGantiIdentitas`, `Util.Identitas` |
| T2 | Jalur isian nama bebas bila roster kelas kosong (fail-open): kelas yang belum diimpor, atau ejaan kelas sesi ≠ ejaan kelas Excel (mis. "XI TKJ 1" vs "XI tkj 1" — perbandingan biner `kelas = ?`) → siswa atau siapa pun di jaringan dapat mengirim nama+NISN bebas dan diterima | `apiAbsen`: bila `cariSiswa()==null` DAN `jumlahSiswaKelas(kelas)==0`, nama dari klien dipakai apa adanya. Dialog `tanyaNamaManual` juga isian bebas. Tidak ada normalisasi nama kelas | **Tinggi** | `SessionServer.apiAbsen`, `Db.cariSiswa/jumlahSiswaKelas`, `StudentScanActivity.tanyaNamaManual` |
| T3 | QR menunjuk IP yang tidak terjangkau siswa: siswa terhubung hotspot guru tapi terus "Tidak bisa menghubungi HP guru"; muncul saat guru ikut Wi-Fi sekolah sambil menyalakan hotspot, atau saat hotspot mati-nyala (IP berubah) dan QR lama masih dipakai | `Jaringan.ipLokal()` mengurutkan `wlan*` **di atas** `ap*/swlan*` → bila dua antarmuka hidup, QR memakai IP `wlan` (subnet Wi-Fi sekolah) yang tidak terjangkau klien hotspot. Alamat QR dibekukan saat dibuat; halaman `/s/` memanggil `ipLokal()` ulang per permintaan, QR statis tidak. Batas klien hotspot (~8–10 di banyak HP) bisa membuat sebagian siswa tak bisa bergabung sama sekali | **Tinggi** | `Util.Jaringan.ipLokal`, `SessionServer.halaman`, activity guru (QR) |
| T4 | Outbox siswa dibuang diam-diam saat sesi ditutup: jaringan buruk saat scan → UI berjanji "aman, akan dikirim otomatis" → guru menutup sesi → kirim ulang ditolak 409 → baris outbox **dihapus tanpa jejak dan tanpa kabar** | `flushAntrian()`: `NetGagal → hapusAntrian(id)` — asumsi `Net.kirim` melempar `NetGagal` untuk semua respons `ok=false`, termasuk 409 `SESI_DITUTUP`/`TIDAK_VALID` dan 500 (galat sementara ikut terbuang). Tidak ada status di UI, tidak ada TTL. URL outbox memuat IP lama — bila IP guru berubah, antrean mengejar alamat mati selamanya | **Tinggi** | `StudentScanActivity.flushAntrian/kirim`, `Db.hapusAntrian` |
| T5 | Aplikasi guru dimatikan paksa di tengah sesi: semua scan siswa mendadak gagal (masuk outbox); setelah HP hidup lagi, sesi lama masih berstatus terbuka ("zombie", `ditutup IS NULL`) — bila server ikut menyala, QR/token lama masih bisa menerima absen tanpa pengawasan | Server hanya hidup selama proses; `sesiAktif()` mengembalikan sesi zombie; tidak ada alur "lanjutkan/tutup?" saat aplikasi menyala kembali (asumsi — layar guru tidak dibaca). Catatan: absen yang **sudah masuk** tetap aman (SQLite-first, autocommit per INSERT) — ini risiko operasional, bukan kehilangan data | Sedang | `App.mulaiServer/hentikanServer`, `Db.sesiAktif/bukaSesi` |
| T6 | Impor Excel menggantikan seluruh roster: setelah impor ulang dengan berkas berbeda, siswa yang tak ada di berkas baru ditolak saat scan; ejaan kelas bergeser → roster kelas "kosong" → memicu jalur T2; tidak ada konfirmasi/pratinjau sebelum menghapus | `Db.imporRoster`: `DELETE FROM roster` lalu insert semua (replace-all). Duplikat `kelas\|nisn` dalam berkas diabaikan senyap. Riwayat `absen` **tidak** rusak — denormalisasi menyelamatkannya | Sedang | `Db.imporRoster`, `ImporSiswa.ubahKeSiswa` |
| T7 | Konkurensi & kinerja saat 200 siswa serentak: lonjakan scan 1–3 menit pertama — jawaban lambat/antre, sebagian timeout di sisi siswa (masuk outbox, masih selamat, tapi bisa terkena T4) | NanoHTTPD bawaan = **thread per koneksi (paralel)**, TAPI semua fungsi `Db` ber-`@Synchronized` pada satu objek → eksekusi basis data **berurutan lewat satu kunci global**. Tiap `POST /api/absen` memegang kunci 4×: `sesiByToken → cariSiswa → catat → hitungStatus`; `hitungStatus` menjalankan `GROUP BY` atas `absen WHERE sesi=?` **tanpa indeks kolom `sesi`** (hanya `idx_absen_waktu`) → pemindaian penuh yang membesar seiring riwayat, dipanggil pada SETIAP absen. Estimasi (bukan ukuran): 1–5 ms per operasi → antrean puncak ±1–2 detik untuk 200 POST — dapat diterima. Tidak ada batas ukuran badan permintaan, rate-limit, maupun jumlah koneksi (backlog socket default Java 50 — asumsi) | Sedang | `Db` (`@Synchronized`, `hitungStatus`, `catat`), `SessionServer.serve`, `App.mulaiServer` (`start(5_000, false)`) |
| T8 | Server terbuka tanpa autentikasi di jaringan sekolah: perangkat apa pun yang melihat QR (diproyeksikan di depan kelas) dapat mengunduh seluruh nama+NISN kelas (`/api/roster`), menandai hadir siswa mana pun (T1), membanjiri server. Header `Access-Control-Allow-Origin: *` memungkinkan halaman web di perangkat yang sama memanggil API via JavaScript; kode 6 huruf (32⁶ ≈ 1,07×10⁹ ≈ 2³⁰ kombinasi) terlihat di layar dan bisa ditebak tanpa batas | Token 96-bit kuat secara kriptografis, tetapi **bukan rahasia** — terlihat di layar kelas dan dipakai bersama; HTTP polos tanpa TLS; tanpa rate-limit; asumsi server mendengar di 0.0.0.0 sehingga juga terjangkau dari Wi-Fi sekolah. Deep link `presensi://absen?u=<URL>` dari halaman web diterima tanpa pemeriksaan host (asumsi activity exported+BROWSABLE — manifest tidak dibaca) | **Tinggi** (integritas + privasi) | `SessionServer.serve/apiRoster/apiAbsen/json`, `StudentScanActivity.keUrl/onCreate` |
| T9 | Data pribadi siswa plaintext: nama+NISN+kehadiran siswa di bawah 18 tahun tersimpan SQLite tanpa enkripsi di HP guru dan HP siswa, terkirim HTTP polos — relevan bagi kewajiban pengendali data menurut UU PDP No. 27/2022 (perlu diverifikasi ke teks resmi sebelum dijadikan dasar kebijakan; **tidak diverifikasi dalam review ini**) | `presensi.db` berkas biasa; identitas siswa di `SharedPreferences` plaintext; seluruh transmisi HTTP; `allowBackup` belum diverifikasi | Sedang | `Db` (presensi.db), `Util.Identitas`, `SessionServer` (HTTP) |
| T10 | Jam & data lain: absen lewat outbox dicatat TERLAMBAT meski scan tepat waktu (jam server saat diterima, bukan saat scan); siswa dengan NISN kehilangan nol depan ditolak (sel xlsx numerik dibaca tanpa format); rekap terpotong diam-diam (`rekap` LIMIT 20000, `ringkasanSesi` LIMIT 300 — terpotong setelah ~17/~6 hari efektif tanpa filter); `onUpgrade` kosong (VERSI=1, tanpa jalur migrasi) | `Db.catat` memakai `System.currentTimeMillis()` server saat menerima; `ImporSiswa.pecahSheet` membaca `<v>` numerik apa adanya; LIMIT tetap tanpa paginasi/peringatan | Sedang–Rendah | `Db.catat/rekap/ringkasanSesi/onUpgrade`, `ImporSiswa` |

**Port 8787 bentrok (Rendah):** `App.mulaiServer` mencoba 8788…8796 lalu menyerah (-1); QR memakai port aktual (perilaku benar), hanya hint contoh alamat di input kode manual tetap `8787`. Dua guru paralel di jaringan yang sama tidak bentrok (IP berbeda).

## 3. Rekomendasi (berprioritas)

| Prioritas | Apa yang dilakukan | Berkas terkena | Dampak bila tidak dikerjakan |
|---|---|---|---|
| **P0-1** | **Tutup jalur isian bebas (fail-closed).** Bila roster kelas sesi kosong → tolak dengan status baru `ROSTER_KOSONG`, jangan terima nama dari klien; tolak pembukaan sesi untuk kelas yang rosternya kosong; hapus dialog isian nama/NISN bebas; normalisasi nama kelas (trim/spasi ganda) | `SessionServer.apiAbsen`, `Db.bukaSesi`/layar guru, `StudentScanActivity.tanyaNamaManual` | T2: siapa pun di jaringan dapat mencatat NISN+nama fiktif — jaminan "nama dari roster" hangus |
| **P0-2** | **NISN + PIN per siswa (anti titip absen minimal).** PIN 6 digit dicetak wali kelas, disimpan sebagai hash di HP guru; 5 percobaan salah per siswa → kunci sampai guru buka (kebijakan sekolah). Hapus NISN penuh dari `/api/roster` (tampilkan tersamar `••••43`). PIN yang dibagikan siswa ke teman tetap mungkin — diterima sadar atau ditangani aturan sekolah | `Db` (kolom `pin_hash`), `SessionServer.apiRoster/apiAbsen`, `StudentScanActivity.tanyaNisn` | T1: satu HP bisa mencatat seluruh kelas; data kehadiran tidak dapat dipertanggungjawabkan |
| **P0-3** | **QR selalu menunjuk alamat yang hidup.** Saat hotspot aktif, dahulukan antarmuka `ap*/swlan*` (bukan `wlan`); hitung alamat sekali saat sesi dibuka dan simpan di tabel `sesi`; pantau perubahan IP saat QR ditampilkan → perbarui QR (token/kode tetap, tampilkan tombol "Perbarui QR"); tolak start server bila tak ada IP jangkauan; tampilkan IP+nama antarmuka di layar guru | `Util.Jaringan.ipLokal`, `Db.bukaSesi`, activity guru, `SessionServer.halaman` | T3: kelas terhenti "tidak bisa menghubungi HP guru" justru pada mode paling sering dipakai (hotspot) |
| **P0-4** | **Outbox transparan, tidak dibuang senyap.** Hapus baris hanya bila jawaban final sukses (`BARU`/`SUDAH`); `SESI_DITUTUP`/`TIDAK_VALID` → status "gagal" ditampilkan permanen ke siswa ("beri tahu guru untuk absen manual"); 500/timeout → tetap antre dengan jeda naik; layar guru menampilkan penghitung scan tertunda per sesi; pastikan `Net.kirim` tidak melempar `NetGagal` untuk galat sementara | `StudentScanActivity.flushAntrian/kirim/tampilAntri`, `Db.outbox` (kolom status), `Net.kt` | T4: kehadiran hilang senyap sementara UI berjanji "aman" — merusak kepercayaan siswa dan rekap |
| **P0-5** | **Uji lapangan sebelum dipakai harian.** Satu HP guru + ±22 HP siswa per rombel (catat merek/Android, batas klien hotspot), plus 200 POST dalam ~10 detik dari PC di jaringan yang sama. Hasilnya menentukan: satu hotspot cukup / dua hotspot per rombel / Wi-Fi sekolah tanpa isolasi klien | Tidak mengubah kode; alat uji terpisah | T3/T7: sesi gagal di hari pertama tanpa diketahui penyebabnya (angka saat ini estimasi) |
| **P1-1** | Batas laju & permintaan: tolak badan > 32 KB; 10 percobaan kode/menit per IP (balas 429 status `RATE_LIMIT` — kontrak baru); dengarkan hanya antarmuka hotspot/Wi-Fi sekolah, bukan 0.0.0.0; tolak sumber di luar subnet lokal | `SessionServer.serve`, `App.mulaiServer` | T8: tebakan kode tanpa batas; satu perangkat nakal membanjiri HP guru hingga kelas tak bisa absen |
| **P1-2** | Deep link & CORS: tautan luar hanya diterima bila host IP privat RFC 1918 dan port cocok; konfirmasi sebelum kirim; hapus pengiriman otomatis dari `onCreate`; hapus `Access-Control-Allow-Origin: *` dari endpoint roster; verifikasi `exported`/BROWSABLE di manifest | `StudentScanActivity.keUrl/onCreate/prosesIsi`, `SessionServer.json`, `AndroidManifest.xml` | T8: NISN+nama terkirim ke host sembarang via tautan web |
| **P1-3** | Waktu scan: kirim `waktu_scan` dari HP siswa; server simpan `waktu_scan` + `waktu_terima`, status dihitung dari `waktu_scan`; tandai bila selisih > 5 menit (ambang diuji) | `SessionServer.apiAbsen`, `Db.catat`, `StudentScanActivity.kirim` | Siswa tepat waktu tercatat TERLAMBAT karena outbox (T10) |
| **P1-4** | Impor roster dengan pratinjau: ringkasan sebelum menerapkan (+n baru, −n hilang, kelas berganti ejaan); konfirmasi bila >10% siswa akan hilang; impor per kelas (hanya kelas yang ada di berkas); simpan riwayat impor; NISN wajib 10 digit TEXT (tolak + daftar baris salah) | `Db.imporRoster`, `ImporSiswa`, UI impor | T6: satu berkas Excel salah → siswa tak bisa absen / memicu T2, tanpa jalan kembali |
| **P1-5** | Cadangan terjadwal: ekspor otomatis CSV/JSON (`absen`+`sesi`) tiap sesi ditutup atau tiap hari ke folder Download via SAF; tampilkan tanggal cadangan terakhir di layar guru; ingatkan guru di akhir hari | Activity rekap/guru, `Db` | HP guru hilang/rusak = seluruh riwayat kehadiran sekolah hilang |
| **P1-6** | Pemulihan sesi setelah mati paksa: saat aplikasi guru menyala dan ada sesi zombie, tawarkan "Lanjutkan (server ikut nyala, token tetap) / Tutup sesi"; pastikan foreground service + WakeLock parsial selama sesi aktif; uji 45 menit layar mati | `App.mulaiServer`, activity guru, `Db.sesiAktif` | T5: sesi zombie menerima absen tanpa pengawasan, atau scan siswa menggantung tanpa penjelasan |
| **P1-7** | Kunci mode guru dengan PIN lokal (hash); migrasi DB: isi `onUpgrade` langkah per versi + tabel `meta(versi)` | Layar guru, `App`, `Db.onUpgrade` | Siapa pun yang memegang HP guru bisa buka sesi/lihat data (batasan sadar); pembaruan berikutnya bisa merusak data lama |
| **P1-8** | Paginasi rekap/ringkasan + tampilkan jumlah total | `Db.rekap/ringkasanSesi`, layar rekap | Laporan terpotong diam-diam setelah beberapa hari efektif (T10) |
| **P2-1** | Indeks basis data: `CREATE INDEX idx_absen_sesi ON absen(sesi);` dan `idx_sesi_token ON sesi(token)` via `onUpgrade` (VERSI 2) | `Db` | `hitungStatus` per absen melambat sepanjang semester — belum kritis pada ±200 siswa |
| **P2-2** | Enkripsi & minimisasi data anak: SQLCipher/enkripsi berkas DB; identitas siswa keluar dari SharedPreferences plaintext; simpan hanya yang perlu; susun formulir persetujuan wali (UU PDP No. 27/2022 — verifikasi teks resmi dulu) | `Db`, `Util.Identitas` | Pencurian HP guru = kebocoran data pribadi anak (T9) |
| **P2-3** | Transport aman opsional: HTTPS self-signed per HP guru dengan **sidik jari sertifikat dibenamkan di QR** — aplikasi siswa memverifikasi sidik jari (bukan CA), menghapus risiko penyadapan/permintaan palsu di Wi-Fi sekolah | `SessionServer`, pembuat QR, `Net` | Lalu lintas nama+NISN tetap terbaca di jaringan (T9) — diterima sadar untuk v1.0 |
| **P2-4** | Kebersihan kecil: escape HTML pada `guru/mapel/kelas` di `bingkai`; ganti `e.message` dengan pesan galat umum; hint port manual dari port aktif; bungkus `bukaSesi` dalam satu transaksi; parser CSV menangani tanda kutip; deteksi header CSV/Excel lebih ketat (sinonim terbatas, cocok persis) | `SessionServer.bingkai/serve`, `StudentScanActivity.tanyaKodeManual`, `Db.bukaSesi`, `ImporSiswa` | Perbaikan kosmetik; masing-masing dampak kecil |

## 4. Jalur evolusi (tanpa meninggalkan v1.0)

v1.0 tetap bekerja offline di kelas. Evolusi menuju dashboard wakasek tidak perlu mengubah peran HP guru:

1. **Fase A (termurah):** ekspor terjadwal (P1-5); guru menyerahkan CSV/JSON ke wakasek untuk dilihat di Excel. Tanpa server baru.
2. **Fase B (v1.x):** setiap absen punya `id_absen` (UUID) + `diubah` (timestamp) + `perangkat_id` — keputusan skema yang harus diambil **sekarang** karena menentukan migrasi. Satu komputer sekolah (PC/minipc) menjalankan *collector* sederhana; HP guru mengunggah **delta** (JSON: sesi + baris `absen` baru sejak unggahan terakhir) saat terhubung Wi-Fi sekolah, dengan UPSERT berdasarkan `id_absen` (idempoten, tidak menggandakan data). HP guru tetap sumber kebenaran saat kelas berjalan; unggahan best-effort dengan antrean ulang (pola outbox).
3. **Fase C (v2):** dashboard wakasek membaca collector. Baru setelah itu dipikirkan login pusat, verifikasi GPS, verifikasi wajah, Play Integrity — keputusan desain terpisah, jangan dicampurkan ke v1.0 (Play Integrity membutuhkan backend verifikasi; v1.0 tidak punya).

Prinsip semua fase: aplikasi kelas **tidak boleh bergantung** pada ketersediaan collector; collector hanya menyalin, tidak memutuskan.

Keputusan skema yang harus diambil sekarang karena menentukan evolusi: PIN per siswa (P0-2), `id_absen`+`diubah`+`perangkat_id` (Fase B), `waktu_scan` (P1-3).

## 5. Batasan yang diterima sadar (v1.0)

- Data kehadiran hanya tersimpan di HP guru; HP hilang/rusak/diganti = hilang (dimitigasi murah oleh P1-5).
- Tidak ada login guru: siapa pun yang memegang HP guru dapat membuka sesi dan melihat data.
- Tidak ada cadangan otomatis ke luar HP.
- HTTP tanpa TLS di jaringan lokal sekolah; token/kode terlihat di layar kelas.
- Anti-curang mengandalkan kewaspadaan guru (daftar hadir di layar) + NISN (dan PIN bila P0-2 dikerjakan); tanpa GPS, tanpa biometrik, tanpa Play Integrity — APK modifikasi/root/emulator tidak terdeteksi.
- Outbox siswa best-effort: tidak menjamin kehadiran masuk bila sesi ditutup lebih dulu.
- Semua jam mengandalkan jam HP (server = HP guru), tidak disinkronkan ke sumber waktu otoritatif.
- Absen hanya bisa dari jaringan yang sama dengan HP guru: titip absen dari luar sekolah tidak mungkin, dari dalam ruangan tetap mungkin (T1).

## 6. Ringkasan untuk pemilik proyek

1. **T1** — `/api/roster` membagikan nama+NISN seluruh kelas dan NISN bukan rahasia, sehingga satu HP bisa mencatat kehadiran teman-temannya.
2. **T4** — outbox siswa menghapus kehadiran yang gagal dikirim saat sesi sudah ditutup, tanpa memberi tahu siswa yang diberi janji "aman dan akan dikirim otomatis".
3. **T3** — QR memakai IP Wi-Fi bila guru online di Wi-Fi sekolah sambil menyalakan hotspot, dan IP bisa berubah saat hotspot mati-nyala, sehingga QR menunjuk alamat mati.

Rekomendasi: **P0 = 5, P1 = 8, P2 = 4**. Dokumen terunggah di `docs/ARSITEKTUR.md` pada branch `main` (commit "docs: review arsitektur v1.0").

---
*Review oleh Arsitek Sistem & Keamanan. Disusun dari pembacaan 6 berkas inti; asumsi dan ketidakpastian tercantum di Bagian 1. Rekomendasi berbentuk keputusan desain, bukan implementasi.*
