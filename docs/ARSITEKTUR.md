# Review Arsitektur v1.0 — Presensi SMKN 1 Marancar

Tanggal: 20 Nov 2026. Dasar: commit `e160a95` pada branch `main`.

**Yang dibaca:** `Db.kt`, `SessionServer.kt`, `App.kt`, `StudentScanActivity.kt`, `ImporSiswa.kt`, `Util.kt`.

**Yang tidak dibaca:** `Net.kt`, model `Siswa`/`Sesi`/`Absen`, layar guru, `AndroidManifest.xml`, `build.gradle`. Temuan yang bergantung pada berkas tersebut ditandai **asumsi** dan wajib diverifikasi.

---

## 1. Ringkasan arsitektur saat ini

```
 HP GURU (server)                                   HP SISWA (klien)
+------------------------------------------+       +---------------------------+
| Layar guru: Nama, Mapel, Kelas           |       | Pindai QR (kamera)        |
|  -> Db.bukaSesi: token 96 bit, kode 6    |  QR   |  presensi://absen?u=URL   |
| App.mulaiServer: NanoHTTPD 8787..8796    |------>|                           |
| SessionServer:                           |       | GET  /api/sesi?token=     |
|   GET /s/{token}, /api/sesi, /api/roster |<------| GET  /api/roster?token=   |
|   POST /api/absen                        | HTTP  | POST /api/absen           |
| SQLite presensi.db: sesi, absen, roster  |  LAN  | Outbox (SQLite) bila gagal|
+------------------------------------------+hotspot+---------------------------+
```

Alur:
1. Guru mengisi nama, mapel, dan kelas. `Db.bukaSesi` membuat `token` (12 byte acak, 96 bit, `SecureRandom`) dan `kode` (6 karakter dari 32 huruf/angka). Sesi aktif sebelumnya ditutup otomatis.
2. `App.mulaiServer` menyalakan NanoHTTPD di port 8787. Bila port terpakai, mencoba 8788 sampai 8796.
3. QR berisi `presensi://absen?u=<URL terenkode>`. URL berbentuk `http://<IP>:<port>/s/<token>`.
4. Siswa memindai. Aplikasi memanggil `GET /api/sesi`, lalu `GET /api/roster` bila identitas belum tersimpan, memilih nama, mengetik NISN, dan mengirim `POST /api/absen`.
5. Server menulis ke tabel `absen` dengan `UNIQUE(sesi, nisn)`. Bila jaringan gagal, aplikasi siswa menyimpan POST ke tabel `outbox`.

Kontrak endpoint yang ada di kode:

| Endpoint | Autentikasi | Sukses | Galat |
|---|---|---|---|
| `GET /s/{token}` | token | HTML | HTML pesan, status 200 |
| `GET /api/sesi?token=` atau `?kode=` | token atau kode | `ok`, `sesi`, `tercatat` | 409 `TIDAK_VALID`, 409 `SESI_DITUTUP` |
| `GET /api/roster?token=` | token | `ok`, `kelas`, `siswa[{nisn,nama}]` | 409 tanpa `status` |
| `POST /api/absen` | token atau kode di body | `ok`, `status` (`BARU`/`SUDAH`), `nama`, `tercatat` | 409 `TIDAK_VALID`, 409 `SESI_DITUTUP`, 409 `NISN_TIDAK_COCOK`, 400 NISN/nama kosong, 500 galat |
| `GET /api/ping` | tidak ada | `ok`, `aplikasi` | — |

Yang sudah benar:
- `UNIQUE(sesi, nisn)` dengan `CONFLICT_IGNORE` mencegah absen dobel.
- Token dan kode dibuat dengan `SecureRandom`.
- Ganti QR dan penutupan sesi langsung membatalkan token lama.
- Nama diambil dari roster, bukan dari isian siswa (bila roster ada).
- Absen ditulis ke SQLite sebelum server menjawab. Setiap INSERT berjalan sebagai transaksi sendiri (autocommit).

---

## 2. Temuan

Risiko: **T** = Tinggi, **S** = Sedang, **R** = Rendah.

### Risiko Tinggi

| No | Gejala di lapangan | Penyebab teknis | Berkas / fungsi |
|---|---|---|---|
| T1 | Satu siswa bisa mencatat hadir untuk teman sekelasnya. Foto QR dari dalam ruangan juga cukup untuk dikirim ke teman. | `apiRoster` mengembalikan NISN dan nama seluruh kelas. Satu-satunya syarat adalah token, yang ada di QR. `tanyaNisn` membandingkan NISN yang diketik dengan nilai dari server, jadi cek itu selalu lolos. `apiAbsen` hanya mengecek NISN ada di roster. NISN tidak rahasia: tercantum di rapor dan dokumen sekolah serta diketahui teman sekelas. Tidak ada PIN, tidak ada ikatan perangkat. Pengenal ini hanya cukup untuk mencegah salah ketik, bukan untuk membuktikan identitas. | `SessionServer.apiRoster`, `apiAbsen`; `StudentScanActivity.pilihNama`, `tanyaNisn` |
| T2 | Siswa melihat "Tersimpan di HP, akan dikirim otomatis", lalu baris outbox hilang saat sesi sudah ditutup atau QR sudah diganti. Kehadiran tidak tercatat dan siswa tidak diberi tahu. | `flushAntrian` menghapus baris pada setiap `NetGagal`. **Asumsi:** `Net.kirim` melempar `NetGagal` untuk semua respons `ok=false`, termasuk 409 `SESI_DITUTUP`, 409 `TIDAK_VALID`, dan 500. Kasus 500 (misalnya penyimpanan penuh) juga menghapus baris. Komentar kode menyebut "ditolak permanen", tetapi kondisi itu tidak dibedakan dari galat sementara. | `StudentScanActivity.flushAntrian`; `Db.hapusAntrian` |
| T3 | Siswa memindai QR, lalu muncul "Tidak bisa menghubungi HP guru". | `Jaringan.ipLokal` mengurutkan antarmuka `wlan*` paling depan. Bila HP guru tersambung ke Wi-Fi sekolah (`wlan0`) sambil menyalakan hotspot (`ap0`, `swlan0`, `wlan1`, atau `rndis`), IP yang dipakai adalah IP Wi-Fi sekolah. Siswa di hotspot tidak bisa mencapainya. `halaman()` menghitung IP ulang setiap kali dibuka, sedangkan QR di layar sudah dicetak dengan IP lama. Bila hotspot mati-nyala, IP gateway bisa berubah (bergantung merek HP; belum diuji). QR lama lalu menunjuk alamat yang salah. | `Util.kt` `Jaringan.ipLokal`; `SessionServer.halaman` |
| T4 | Sebagian siswa tidak bisa masuk hotspot saat satu rombel (±30–36 siswa) hadir. | Banyak HP Android membatasi jumlah klien hotspot pada kisaran 8–10 (nilai pasti bergantung merek dan versi; belum diuji di repo). Tidak ada kode yang mengatur atau mendeteksi batas ini. | Keputusan hotspot; `App.mulaiServer` |
| T5 | Sebagian HP siswa menampilkan "Tidak bisa menghubungi" meski sudah tersambung ke hotspot guru. | **Dugaan, belum dibuktikan.** Hotspot guru tidak punya internet. Android dapat menganggap Wi-Fi itu tanpa internet dan memakai data seluler sebagai jaringan default. Permintaan ke `192.168.x.x` lalu gagal karena jalur itu tidak menuju hotspot. Aplikasi siswa tidak mengikat koneksi ke Wi-Fi. | `StudentScanActivity.proses`, `kirim`, `flushAntrian`; `Net.kt` (asumsi) |
| T6 | Setelah impor ulang Excel, rombel lain tidak bisa absen dengan verifikasi NISN, atau nama diisi bebas oleh siswa. | `Db.imporRoster` menghapus **seluruh** tabel `roster`, lalu menulis ulang dari berkas. Berkas yang hanya berisi satu rombel menghapus rombel lain. Di `apiAbsen`, bila `jumlahSiswaKelas` = 0, pengecekan NISN dilewati dan nama diambil dari HP siswa. Riwayat absen tidak ikut hilang karena tabel `absen` menyimpan salinan nama dan kelas. Tidak ada pratinjau atau riwayat impor. | `Db.imporRoster`; `SessionServer.apiAbsen`; `ImporSiswa.ubahKeSiswa` |
| T7 | Data kehadiran hilang bila data aplikasi dihapus, HP guru rusak, atau HP diganti. | Semua data hanya di SQLite lokal HP guru. Tidak ada ekspor atau cadangan. Status `allowBackup` belum diverifikasi (manifest tidak dibaca). | `App.kt`; keputusan "data hanya di HP guru" |

### Risiko Sedang

| No | Gejala di lapangan | Penyebab teknis | Berkas / fungsi |
|---|---|---|---|
| S1 | Pertanyaan: paralel atau berurutan? Dampak 200 siswa scan hampir bersamaan? | **Paralel di lapisan HTTP.** **Asumsi:** NanoHTTPD 2.x dengan `DefaultAsyncRunner` memakai satu thread per koneksi tanpa batas. **Berurutan di lapisan data.** Semua method `Db` memakai `@Synchronized` pada satu objek, jadi INSERT dan SELECT antre. Estimasi (belum diukur): bila satu INSERT memakan 1–5 ms, 200 POST selesai dalam orde ratusan milidetik. Batas nyata ada di backlog socket (default Java 50, asumsi) dan timeout baca 5 detik. Lonjakan 200 koneksi bisa ditolak atau timeout. Siswa menerima galat, lalu masuk outbox, dan terkena T2 bila galatnya 500. | `App.mulaiServer` (`start(5_000, false)`); semua fungsi `Db` |
| S2 | Server bisa dipakai siapa pun di jaringan yang sama. Tebakan kode 6 huruf dapat dicoba tanpa batas. | Tidak ada autentikasi di luar token. Tidak ada rate limit. Ruang kode: 32^6 = 1.073.741.824. Estimasi: 500 percobaan/detik selama 45 menit = 1,35 × 10^6 percobaan, sekitar 0,13% dari ruang kode. Token 96 bit tidak praktis ditebak. **Asumsi:** server mendengar di semua antarmuka (0.0.0.0), sehingga juga terjangkau dari Wi-Fi sekolah bila HP guru tersambung ke sana. | `SessionServer.apiAbsen`, `apiSesi`; `App.mulaiServer` |
| S3 | Tautan `presensi://absen?u=http://...` dari halaman web langsung mengirim NISN dan nama siswa ke host itu. | `keUrl` hanya memeriksa skema. `onCreate` menerima extra `url` tanpa pemeriksaan. `proses` mengirim tanpa konfirmasi, asalkan identitas sudah tersimpan. **Asumsi:** activity diekspor dengan kategori BROWSABLE (manifest belum diverifikasi). | `StudentScanActivity.keUrl`, `onCreate`, `prosesIsi` |
| S4 | Setelah aplikasi guru dimatikan paksa, QR mati. Baris sesi tetap `ditutup = NULL`, sehingga sesi lama tetap tercatat aktif. | `Db` menutup sesi hanya lewat `tutupSesi`. Tidak ada pemulihan saat aplikasi dibuka. Absen yang sudah diterima aman karena autocommit. Siswa yang gagal kirim tetap ada di outbox. Bila sesi dilanjutkan, absen itu tercatat dengan waktu terima (lihat S7). Apakah server hidup kembali otomatis bergantung pada layar guru (tidak dibaca). | `App.kt`; `Db.bukaSesi`, `tutupSesi`, `sesiAktif` |
| S5 | Layar guru mati lalu absen gagal, atau server berhenti di tengah sesi. | Tidak ada WakeLock di `App.mulaiServer`. Layanan latar (foreground service, sesuai keputusan) belum terlihat di berkas yang dibaca. Doze dan pembatasan baterai bisa menghentikan server. | `App.mulaiServer`; layanan guru (tidak dibaca) |
| S6 | Siswa yang absen dengan outbox dicatat "TERLAMBAT" padahal memindai tepat waktu. | `Db.catat` memakai `System.currentTimeMillis()` saat server menerima, bukan saat siswa memindai. Outbox yang dikirim 10 menit kemudian ikut terkena. | `SessionServer.apiAbsen`; `Db.catat` |
| S7 | Ini nomor S7 dan sudah dipakai untuk waktu terlambat; lihat S6. | — | — |
| S8 | Siswa dengan NISN yang tersimpan tanpa nol di depan tidak bisa lolos cek NISN di HP. | Sel angka di xlsx dibaca sebagai teks `<v>` tanpa format, sehingga nol depan hilang. `tanyaNisn` membandingkan dengan nilai roster. Tidak ada validasi panjang atau format NISN. | `ImporSiswa.pecahSheet`, `ubahKeSiswa`; `StudentScanActivity.tanyaNisn` |
| S9 | Kelas "XI TKJ 1" dan "XI tkj 1" dianggap dua rombel. Sesi dengan nama yang tidak persis sama tidak punya roster, lalu lolos tanpa cek NISN. | `rosterKelas`, `cariSiswa`, `jumlahSiswaKelas` memakai `kelas = ?` (perbandingan biner, peka huruf dan spasi). Tidak ada normalisasi. | `Db.cariSiswa`, `rosterKelas`, `jumlahSiswaKelas` |
| S10 | Siapa pun bisa membuka mode guru, menjalankan server palsu, dan membagikan QR-nya. Layar "Tercatat ✓" di HP siswa bisa dipakai sebagai bukti palsu. | Satu APK dengan dua peran tanpa PIN atau kunci. Data palsu hanya di HP pembuat server palsu. Daftar hadir resmi tetap di HP guru asli. | Layar guru (tidak dibaca); keputusan dua peran |
| S11 | Rekap dan ringkasan tidak lengkap tanpa peringatan. | `Db.rekap` membatasi 20.000 baris. `ringkasanSesi` membatasi 300 sesi. Estimasi: satu rombel dengan 6 jam pelajaran/hari selama 200 hari ≈ 43.200 baris per tahun, dan sekolah ≈ 10.800 sesi per tahun (9 rombel). Angka 200 hari efektif adalah asumsi. | `Db.rekap`, `ringkasanSesi` |
| S12 | Versi aplikasi berikutnya bisa merusak data lama. | `onUpgrade` kosong dan `VERSI = 1`. Belum ada jalur migrasi. | `Db.kt` |

### Risiko Rendah

| No | Gejala di lapangan | Penyebab teknis | Berkas / fungsi |
|---|---|---|---|
| R1 | Di HP guru, sebagian siswa tidak bisa memakai hint manual port 8787 saat server memakai 8788. | QR memakai port aktif, tetapi `tanyaKodeManual` menulis contoh tetap `8787`. Bentrok port di HP guru jarang terjadi. | `App.mulaiServer`; `StudentScanActivity.tanyaKodeManual` |
| R2 | Kolom Excel salah terbaca, misalnya "Nama Ayah" terpilih sebagai nama siswa. | Deteksi memakai `contains("nama")` dan mengambil kolom pertama yang cocok. | `ImporSiswa.ubahKeSiswa` |
| R3 | Nama di CSV dengan koma terpotong. | `pecahCsv` memisah pada koma tanpa memperhatikan tanda kutip. | `ImporSiswa.pecahCsv` |
| R4 | Nama guru atau mapel yang berisi HTML tampil apa adanya di halaman siswa. Pesan galat internal ikut terkirim. CORS `*` berlaku pada semua JSON. | `bingkai` tidak melakukan escape. `serve` mengirim `e.message`. `json` menambah `Access-Control-Allow-Origin: *`. | `SessionServer.bingkai`, `serve`, `json` |
| R5 | Kondisi balapan kecil: absen masuk ke sesi yang baru ditutup dalam milidetik. `bukaSesi` juga tidak memakai transaksi, sehingga crash di tengah bisa menyisakan dua sesi terbuka. | `apiAbsen` membaca sesi lalu mencatat dalam dua langkah. `bukaSesi` menjalankan UPDATE dan INSERT terpisah. | `SessionServer.apiAbsen`; `Db.bukaSesi` |

---

## 3. Rekomendasi

### P0: wajib sebelum dipakai harian

| No | Yang dilakukan | Berkas | Dampak bila tidak dikerjakan |
|---|---|---|---|
| R0-1 | Ganti verifikasi NISN dengan **NISN + PIN per siswa**. PIN dicetak di kartu oleh wali kelas dan disimpan sebagai hash di HP guru. Batasi 5 percobaan salah per siswa per sesi. Hapus NISN dari respons `/api/roster`. Pemberian PIN harus dilakukan sebelum sekolah memakai sistem, karena skema roster bergantung padanya. | `SessionServer.apiRoster`, `apiAbsen`; `StudentScanActivity.tanyaNisn`; `Db` (kolom `pin_hash`) | T1: satu HP bisa mencatat seluruh kelas. |
| R0-2 | Outbox hanya menghapus baris bila hasilnya final dan sukses (`BARU`/`SUDAH`). Untuk `SESI_DITUTUP` dan `TIDAK_VALID`, pindahkan baris ke status "gagal" dan tampilkan ke siswa: "Belum tercatat, sesi sudah ditutup." Untuk 500 dan timeout, tetap antre dengan jeda naik. Pastikan `Net.kt` tidak melempar `NetGagal` untuk galat sementara. | `StudentScanActivity.flushAntrian`, `kirim`; `Db` (kolom `status`, `alasan`) | T2: kehadiran hilang diam-diam sementara siswa mengira sudah aman. |
| R0-3 | Hitung alamat hotspot sekali saat sesi dibuka, lalu simpan di tabel `sesi` (kolom `alamat`). Pilih antarmuka hotspot secara eksplisit dan tampilkan IP beserta nama antarmuka di layar guru sebelum QR. Sediakan tombol "Perbarui QR". Tolak start server bila tidak ada IP hotspot. | `Util.kt` `Jaringan.ipLokal`; `SessionServer.halaman`; `Db.bukaSesi` | T3: QR menunjuk alamat yang salah saat guru memakai Wi-Fi sekolah. |
| R0-4 | Uji lapangan dengan satu HP guru dan 36 HP siswa. Catat merek, versi Android, dan batas klien hotspot. Uji juga 200 POST dalam 10 detik. Hasilnya menentukan: (a) satu hotspot cukup, (b) dua hotspot per rombel, atau (c) Wi-Fi sekolah tanpa isolasi klien. | Tidak ada kode. Tambahkan pencatatan waktu di `App.mulaiServer` dan `apiAbsen` untuk uji. | T4 dan S1: sesi gagal di hari pertama tanpa diketahui penyebabnya. |
| R0-5 | Di HP siswa, minta jaringan Wi-Fi dengan `ConnectivityManager.requestNetwork(TRANSPORT_WIFI)`. Kirim semua permintaan ke IP lokal lewat `Network.socketFactory`. Uji dengan data seluler aktif dan hotspot tanpa internet. Kalau dugaan T5 tidak terbukti, rekomendasi ini bisa diturunkan ke P1. | `StudentScanActivity.proses`, `kirim`, `flushAntrian`; `Net.kt` | T5: sebagian siswa gagal absen, dan penyebabnya sulit dilacak. |

### P1: penting, sebelum semester berikutnya

| No | Yang dilakukan | Berkas | Dampak bila tidak dikerjakan |
|---|---|---|---|
| R1-1 | Dengarkan hanya di antarmuka hotspot atau Wi-Fi sekolah, bukan 0.0.0.0. Tolak permintaan yang alamat sumbernya di luar subnet lokal. | `App.mulaiServer`; `SessionServer.serve` | S2: server terbuka di jaringan sekolah. |
| R1-2 | Batasi percobaan kode 6 huruf: 10 per menit per IP. Balas 429 dengan status `RATE_LIMIT` (kontrak baru). | `SessionServer.apiAbsen`, `apiSesi` | S2: tebakan kode tanpa batas. |
| R1-3 | Tautan dari luar (deep link, extra `url`) hanya diterima bila host adalah IP RFC 1918 dan port cocok. Tampilkan konfirmasi sebelum mengirim. Hapus pengiriman otomatis dari `onCreate`. Verifikasi `exported` dan BROWSABLE di manifest. | `StudentScanActivity.keUrl`, `onCreate`, `prosesIsi`; `AndroidManifest.xml` | S3: NISN dan nama terkirim ke host sembarang. |
| R1-4 | Impor per kelas dengan pratinjau: tampilkan jumlah siswa per kelas, minta konfirmasi, lalu ganti hanya kelas yang ada di berkas. Simpan riwayat impor (waktu, jumlah, hash berkas). Normalisasi nama kelas (trim, spasi ganda, huruf besar). | `Db.imporRoster`; `ImporSiswa.ubahKeSiswa` | T6, S9: roster hilang dan kelas tidak cocok. |
| R1-5 | Tolak pembukaan sesi untuk kelas yang rosternya kosong. Hapus cabang fallback yang menerima NISN apa pun. | `SessionServer.apiAbsen`; `Db.bukaSesi` atau layar guru | T6: absen tanpa verifikasi. |
| R1-6 | Simpan NISN sebagai TEXT. Saat impor, tolak baris yang NISN-nya bukan 10 digit dan tampilkan daftarnya. NISN umumnya 10 digit (verifikasi dengan data sekolah). | `ImporSiswa.pecahSheet`, `ubahKeSiswa`; `StudentScanActivity.tanyaNisn` | S8: siswa tidak bisa absen karena nol depan hilang. |
| R1-7 | Kirim `waktu_scan` dari HP siswa. Server menyimpan `waktu_terima` dan `waktu_scan`. Status dihitung dari `waktu_scan`. Tandai bila selisihnya lebih dari 5 menit (ambang perlu diuji). | `SessionServer.apiAbsen`; `Db.catat`; `StudentScanActivity.kirim` | S6: siswa tepat waktu tercatat terlambat. |
| R1-8 | Isi `onUpgrade` dengan langkah per versi. Tambah tabel `meta(versi)`. Uji migrasi dari versi 1 sebelum rilis berikutnya. | `Db.kt` | S12: data lama rusak saat pembaruan. |
| R1-9 | Tambahkan "Ekspor cadangan": SQLite dan CSV rekap ke folder Download lewat SAF. Ingatkan guru di akhir hari. | `Db.kt` (fungsi ekspor); layar guru | T7: kehilangan data tanpa jalan pulih. |
| R1-10 | Kunci mode guru dengan PIN lokal (disimpan sebagai hash). | Layar guru; `App.kt` | S10: server palsu dan bukti "Tercatat" palsu. |
| R1-11 | Uji 45 menit dengan layar mati. Pastikan layanan foreground dengan notifikasi, WakeLock parsial selama sesi, dan pengecualian pembatasan baterai. | `App.mulaiServer`; layanan guru | S5: absen gagal di tengah sesi. |
| R1-12 | Saat aplikasi guru dibuka, cek sesi yang masih terbuka. Tawarkan "lanjutkan" atau "tutup", dan hidupkan server lagi. | `App.kt`; `Db.sesiAktif` | S4: sesi tertinggal dan QR mati tanpa diketahui guru. |
| R1-13 | Ganti `LIMIT` tetap pada rekap dan ringkasan dengan paginasi. Tampilkan jumlah total. | `Db.rekap`, `ringkasanSesi` | S11: laporan terpotong tanpa peringatan. |

### P2: penyempurnaan

| No | Yang dilakukan | Berkas | Dampak bila tidak dikerjakan |
|---|---|---|---|
| R2-1 | Escape HTML pada `guru`, `mapel`, dan `kelas`. Ganti `e.message` dengan pesan galat umum. | `SessionServer.bingkai`, `serve` | R4 |
| R2-2 | Hapus `Access-Control-Allow-Origin: *` dari endpoint yang memuat roster. | `SessionServer.json` | R4 |
| R2-3 | Hint port manual diambil dari port yang aktif, bukan tetap 8787. | `StudentScanActivity.tanyaKodeManual` | R1 |
| R2-4 | Bungkus `bukaSesi` dalam satu transaksi. Baca ulang sesi di dalam kunci saat mencatat. | `Db.bukaSesi`, `catat`; `SessionServer.apiAbsen` | R5 |
| R2-5 | Parser CSV menangani tanda kutip. Deteksi header memakai daftar sinonim yang terbatas dan cocok persis. | `ImporSiswa.pecahCsv`, `ubahKeSiswa` | R2, R3 |

Play Integrity tidak dipakai di v1.0. Verdict-nya hanya bisa dibaca dengan memanggil server Google atau backend sendiri, dan v1.0 tidak punya backend. Lihat Batasan.

---

## 4. Jalur evolusi

Keputusan tanpa VPS dan tanpa Google Sheets tetap berlaku sampai ada kebutuhan pusat. Jalurnya bertahap:

- **Tahap 0 (v1.0):** semua data di HP guru. Sesi berjalan langsung ke HP guru.
- **Tahap 1 (v1.x):** setiap absen punya `id_absen` (UUID) dan `diubah` (timestamp). Tambah `perangkat_id`. Ekspor sesi sebagai JSON dan impor dengan UPSERT berdasarkan `id_absen`, sehingga impor ulang tidak menggandakan data.
- **Tahap 2 (v2):** HP guru mengirim batch sesi ke server pusat sekolah secara berkala, misalnya setiap 5 menit saat ada internet. Dashboard wakasek membaca server pusat. Aliran data searah dari HP guru ke pusat, sehingga HP guru tetap sumber saat sesi berjalan.

Hal yang harus diputuskan sekarang karena menentukan skema: PIN per siswa (R0-1), `id_absen` dan `diubah` (Tahap 1), dan kolom `waktu_scan` (R1-7).

---

## 5. Batasan yang diterima sadar

- Data hanya di HP guru. Data hilang bila aplikasi dihapus, HP rusak, atau HP diganti. Mitigasi: R1-9.
- Tidak ada cadangan otomatis.
- Tidak ada login guru. Mitigasi parsial: R1-10.
- Tidak ada Play Integrity. Verifikasinya butuh backend. APK modifikasi, root, dan emulator tidak terdeteksi.
- Tidak ada verifikasi wajah dan GPS. Identitas siswa dipercaya dari NISN dan PIN (setelah R0-1).
- Absen hanya bisa dilakukan dalam satu jaringan dengan HP guru. Ini juga yang membatasi titip absen dari luar ruangan.

---

## 6. Ringkasan untuk pembaca

- Tiga temuan paling berbahaya:
  1. T1: roster memuat NISN seluruh kelas, dan NISN bukan rahasia, sehingga satu HP bisa mencatat kehadiran teman.
  2. T2: outbox menghapus kehadiran yang gagal dikirim saat sesi sudah ditutup, tanpa memberi tahu siswa.
  3. T3 dan T4: alamat QR bisa salah, dan hotspot mungkin tidak menampung satu rombel.
- Jumlah rekomendasi: P0 = 5, P1 = 13, P2 = 5.
- Dokumen ini sudah diunggah ke `docs/ARSITEKTUR.md` di branch `main`.
