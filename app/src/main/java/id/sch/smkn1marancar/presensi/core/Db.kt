package id.sch.smkn1marancar.presensi.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Penyimpanan lokal (SQLite) — dipakai di HP guru maupun HP siswa.
 *
 * Tabel:
 *  - sesi    : sesi presensi yang dibuka guru (berisi token QR)
 *  - absen   : kehadiran siswa, UNIQUE(sesi, nisn) => mustahil tercatat dua kali
 *  - roster  : daftar siswa hasil impor Excel
 *  - outbox  : antrean scan di HP siswa yang belum berhasil terkirim
 */
class Db(context: Context) : SQLiteOpenHelper(context, NAMA_BERKAS, null, VERSI) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sesi (
                 _id       TEXT PRIMARY KEY,
                 guru      TEXT NOT NULL,
                 mapel     TEXT NOT NULL,
                 kelas     TEXT NOT NULL,
                 token     TEXT NOT NULL,
                 kode      TEXT NOT NULL,
                 dibuka    INTEGER NOT NULL,
                 ditutup   INTEGER,
                 toleransi INTEGER NOT NULL DEFAULT 15)"""
        )
        db.execSQL(
            """CREATE TABLE absen (
                 _id   INTEGER PRIMARY KEY AUTOINCREMENT,
                 sesi  TEXT NOT NULL,
                 nisn  TEXT NOT NULL,
                 nama  TEXT NOT NULL,
                 kelas TEXT NOT NULL,
                 mapel TEXT NOT NULL,
                 guru  TEXT NOT NULL,
                 waktu INTEGER NOT NULL,
                 status TEXT NOT NULL,
                 UNIQUE(sesi, nisn))"""
        )
        db.execSQL("CREATE INDEX idx_absen_waktu ON absen(waktu)")
        db.execSQL(
            """CREATE TABLE roster (
                 _id   INTEGER PRIMARY KEY AUTOINCREMENT,
                 nisn  TEXT NOT NULL,
                 nama  TEXT NOT NULL,
                 kelas TEXT NOT NULL,
                 UNIQUE(kelas, nisn))"""
        )
        db.execSQL(
            """CREATE TABLE outbox (
                 _id    INTEGER PRIMARY KEY AUTOINCREMENT,
                 url    TEXT NOT NULL,
                 isi    TEXT NOT NULL,
                 dibuat INTEGER NOT NULL,
                 coba   INTEGER NOT NULL DEFAULT 0)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, lama: Int, baru: Int) {
        // Versi pertama: belum ada migrasi.
    }

    // ---------------------------------------------------------------- sesi

    /**
     * Buka sesi baru. Sesi lain yang masih terbuka otomatis ditutup,
     * sehingga QR lama langsung mati (tidak bisa dipakai di sesi lain).
     */
    @Synchronized
    fun bukaSesi(guru: String, mapel: String, kelas: String, toleransiMenit: Int = 15): Sesi {
        val sekarang = System.currentTimeMillis()
        writableDatabase.execSQL(
            "UPDATE sesi SET ditutup = ? WHERE ditutup IS NULL",
            arrayOf<Any>(sekarang)
        )
        val sesi = Sesi(
            id = Acak.hex(8),
            guru = guru.trim(),
            mapel = mapel.trim(),
            kelas = kelas.trim(),
            token = Acak.hex(12),
            kode = Acak.kode(6),
            dibukaPada = sekarang,
            ditutupPada = null,
            toleransiMenit = toleransiMenit
        )
        writableDatabase.insertOrThrow("sesi", null, isiSesi(sesi))
        return sesi
    }

    /** Ganti QR: token & kode baru, token lama langsung tidak berlaku. */
    @Synchronized
    fun gantiToken(sesiId: String): Sesi? {
        val sesi = sesiById(sesiId) ?: return null
        val baru = sesi.copy(token = Acak.hex(12), kode = Acak.kode(6))
        writableDatabase.update("sesi", isiSesi(baru), "_id = ?", arrayOf(sesiId))
        return baru
    }

    @Synchronized
    fun tutupSesi(sesiId: String) {
        writableDatabase.execSQL(
            "UPDATE sesi SET ditutup = ? WHERE _id = ? AND ditutup IS NULL",
            arrayOf<Any>(System.currentTimeMillis(), sesiId)
        )
    }

    @Synchronized
    fun sesiById(id: String): Sesi? =
        bacaSesi("SELECT * FROM sesi WHERE _id = ?", arrayOf(id))

    @Synchronized
    fun sesiByToken(token: String): Sesi? {
        if (token.isBlank()) return null
        return bacaSesi("SELECT * FROM sesi WHERE token = ?", arrayOf(token))
    }

    @Synchronized
    fun sesiByKode(kode: String): Sesi? {
        if (kode.isBlank()) return null
        return bacaSesi(
            "SELECT * FROM sesi WHERE kode = ? ORDER BY dibuka DESC LIMIT 1",
            arrayOf(kode.uppercase())
        )
    }

    @Synchronized
    fun sesiAktif(): Sesi? = bacaSesi(
        "SELECT * FROM sesi WHERE ditutup IS NULL ORDER BY dibuka DESC LIMIT 1", null
    )

    @Synchronized
    fun semuaSesi(limit: Int = 200): List<Sesi> {
        val hasil = mutableListOf<Sesi>()
        readableDatabase.rawQuery(
            "SELECT * FROM sesi ORDER BY dibuka DESC LIMIT ?", arrayOf(limit.toString())
        ).use { c -> while (c.moveToNext()) hasil.add(c.keSesi()) }
        return hasil
    }

    // --------------------------------------------------------------- absen

    /**
     * Catat kehadiran. Mengembalikan true bila BENAR-BENAR baru.
     * UNIQUE(sesi, nisn) membuat scan kedua dari siswa yang sama diabaikan,
     * sehingga tidak mungkin dobel walau siswa menekan tombol berkali-kali.
     */
    @Synchronized
    fun catat(sesi: Sesi, nisn: String, nama: String, waktu: Long): Boolean {
        val batasTerlambat = sesi.dibukaPada + sesi.toleransiMenit * 60_000L
        val status = if (waktu > batasTerlambat) "TERLAMBAT" else "HADIR"

        val cv = ContentValues().apply {
            put("sesi", sesi.id)
            put("nisn", nisn)
            put("nama", nama)
            put("kelas", sesi.kelas)
            put("mapel", sesi.mapel)
            put("guru", sesi.guru)
            put("waktu", waktu)
            put("status", status)
        }
        val id = writableDatabase.insertWithOnConflict(
            "absen", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        )
        return id != -1L
    }

    @Synchronized
    fun daftarAbsen(sesiId: String): List<Absen> {
        val hasil = mutableListOf<Absen>()
        readableDatabase.rawQuery(
            "SELECT * FROM absen WHERE sesi = ? ORDER BY waktu ASC", arrayOf(sesiId)
        ).use { c -> while (c.moveToNext()) hasil.add(c.keAbsen()) }
        return hasil
    }

    @Synchronized
    fun hitungStatus(sesiId: String): Pair<Int, Int> {
        var hadir = 0
        var terlambat = 0
        readableDatabase.rawQuery(
            "SELECT status, COUNT(*) FROM absen WHERE sesi = ? GROUP BY status", arrayOf(sesiId)
        ).use { c ->
            while (c.moveToNext()) {
                val jumlah = c.getInt(1)
                if (c.getString(0) == "TERLAMBAT") terlambat += jumlah else hadir += jumlah
            }
        }
        return hadir to terlambat
    }

    // -------------------------------------------------------------- roster

    /** Impor ulang daftar siswa (menggantikan data lama). */
    @Synchronized
    fun imporRoster(daftar: List<Siswa>): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("roster", null, null)
            for (s in daftar) {
                val cv = ContentValues().apply {
                    put("nisn", s.nisn)
                    put("nama", s.nama)
                    put("kelas", s.kelas)
                }
                db.insertWithOnConflict("roster", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return jumlahSiswa()
    }

    @Synchronized
    fun jumlahSiswa(): Int = hitung("SELECT COUNT(*) FROM roster")

    @Synchronized
    fun jumlahSiswaKelas(kelas: String): Int =
        hitung("SELECT COUNT(*) FROM roster WHERE kelas = ?", arrayOf(kelas))

    @Synchronized
    fun rosterKelas(kelas: String): List<Siswa> {
        val hasil = mutableListOf<Siswa>()
        readableDatabase.rawQuery(
            "SELECT nisn, nama, kelas FROM roster WHERE kelas = ? ORDER BY nama ASC",
            arrayOf(kelas)
        ).use { c ->
            while (c.moveToNext()) hasil.add(Siswa(c.getString(0), c.getString(1), c.getString(2)))
        }
        return hasil
    }

    @Synchronized
    fun cariSiswa(kelas: String, nisn: String): Siswa? {
        readableDatabase.rawQuery(
            "SELECT nisn, nama, kelas FROM roster WHERE kelas = ? AND nisn = ? LIMIT 1",
            arrayOf(kelas, nisn)
        ).use { c ->
            return if (c.moveToNext()) Siswa(c.getString(0), c.getString(1), c.getString(2)) else null
        }
    }

    @Synchronized
    fun daftarKelas(): List<String> {
        val hasil = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT kelas FROM roster ORDER BY kelas ASC", null
        ).use { c -> while (c.moveToNext()) hasil.add(c.getString(0)) }
        return hasil
    }

    @Synchronized
    fun daftarMapel(): List<String> {
        val hasil = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT mapel FROM sesi ORDER BY mapel ASC", null
        ).use { c -> while (c.moveToNext()) hasil.add(c.getString(0)) }
        return hasil
    }

    // ---------------------------------------------------------------- rekap

    /** Rekap kehadiran dengan saringan kelas / mapel / rentang tanggal. */
    @Synchronized
    fun rekap(kelas: String?, mapel: String?, dari: Long?, sampai: Long?): List<Absen> {
        val syarat = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (!kelas.isNullOrBlank()) {
            syarat.add("kelas = ?"); args.add(kelas)
        }
        if (!mapel.isNullOrBlank()) {
            syarat.add("mapel = ?"); args.add(mapel)
        }
        if (dari != null) {
            syarat.add("waktu >= ?"); args.add(dari.toString())
        }
        if (sampai != null) {
            syarat.add("waktu <= ?"); args.add(sampai.toString())
        }
        val where = if (syarat.isEmpty()) "" else "WHERE " + syarat.joinToString(" AND ")

        val hasil = mutableListOf<Absen>()
        readableDatabase.rawQuery(
            "SELECT * FROM absen $where ORDER BY waktu DESC LIMIT 20000", args.toTypedArray()
        ).use { c -> while (c.moveToNext()) hasil.add(c.keAbsen()) }
        return hasil
    }

    /** Ringkasan sesi (untuk layar Rekap). */
    @Synchronized
    fun ringkasanSesi(kelas: String?, mapel: String?, dari: Long?, sampai: Long?): List<RingkasanSesi> {
        val syarat = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (!kelas.isNullOrBlank()) {
            syarat.add("s.kelas = ?"); args.add(kelas)
        }
        if (!mapel.isNullOrBlank()) {
            syarat.add("s.mapel = ?"); args.add(mapel)
        }
        if (dari != null) {
            syarat.add("s.dibuka >= ?"); args.add(dari.toString())
        }
        if (sampai != null) {
            syarat.add("s.dibuka <= ?"); args.add(sampai.toString())
        }
        val where = if (syarat.isEmpty()) "" else "WHERE " + syarat.joinToString(" AND ")

        val hasil = mutableListOf<RingkasanSesi>()
        readableDatabase.rawQuery(
            "SELECT s.*, " +
                "(SELECT COUNT(*) FROM absen a WHERE a.sesi = s._id AND a.status = 'HADIR'), " +
                "(SELECT COUNT(*) FROM absen a WHERE a.sesi = s._id AND a.status = 'TERLAMBAT') " +
                "FROM sesi s $where ORDER BY s.dibuka DESC LIMIT 300",
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                hasil.add(RingkasanSesi(c.keSesi(), c.getInt(9), c.getInt(10)))
            }
        }
        return hasil
    }

    // --------------------------------------------------------------- outbox

    @Synchronized
    fun antrikan(url: String, isi: String) {
        val cv = ContentValues().apply {
            put("url", url); put("isi", isi); put("dibuat", System.currentTimeMillis())
        }
        writableDatabase.insert("outbox", null, cv)
    }

    @Synchronized
    fun daftarAntrian(): List<Triple<Long, String, String>> {
        val hasil = mutableListOf<Triple<Long, String, String>>()
        readableDatabase.rawQuery(
            "SELECT _id, url, isi FROM outbox ORDER BY _id ASC LIMIT 100", null
        ).use { c ->
            while (c.moveToNext()) hasil.add(Triple(c.getLong(0), c.getString(1), c.getString(2)))
        }
        return hasil
    }

    @Synchronized
    fun tandaiCoba(id: Long) {
        writableDatabase.execSQL("UPDATE outbox SET coba = coba + 1 WHERE _id = ?", arrayOf<Any>(id))
    }

    @Synchronized
    fun hapusAntrian(id: Long) {
        writableDatabase.delete("outbox", "_id = ?", arrayOf(id.toString()))
    }

    // -------------------------------------------------------------- bantuan

    private fun hitung(sql: String, args: Array<String>? = null): Int {
        readableDatabase.rawQuery(sql, args).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    private fun bacaSesi(sql: String, args: Array<String>?): Sesi? {
        readableDatabase.rawQuery(sql, args).use { c ->
            return if (c.moveToNext()) c.keSesi() else null
        }
    }

    private fun isiSesi(s: Sesi) = ContentValues().apply {
        put("_id", s.id)
        put("guru", s.guru)
        put("mapel", s.mapel)
        put("kelas", s.kelas)
        put("token", s.token)
        put("kode", s.kode)
        put("dibuka", s.dibukaPada)
        put("ditutup", s.ditutupPada)
        put("toleransi", s.toleransiMenit)
    }

    private fun Cursor.keSesi() = Sesi(
        id = getString(getColumnIndexOrThrow("_id")),
        guru = getString(getColumnIndexOrThrow("guru")),
        mapel = getString(getColumnIndexOrThrow("mapel")),
        kelas = getString(getColumnIndexOrThrow("kelas")),
        token = getString(getColumnIndexOrThrow("token")),
        kode = getString(getColumnIndexOrThrow("kode")),
        dibukaPada = getLong(getColumnIndexOrThrow("dibuka")),
        ditutupPada = getColumnIndexOrThrow("ditutup").let { if (isNull(it)) null else getLong(it) },
        toleransiMenit = getInt(getColumnIndexOrThrow("toleransi"))
    )

    private fun Cursor.keAbsen() = Absen(
        id = getLong(getColumnIndexOrThrow("_id")),
        sesiId = getString(getColumnIndexOrThrow("sesi")),
        nisn = getString(getColumnIndexOrThrow("nisn")),
        nama = getString(getColumnIndexOrThrow("nama")),
        kelas = getString(getColumnIndexOrThrow("kelas")),
        mapel = getString(getColumnIndexOrThrow("mapel")),
        guru = getString(getColumnIndexOrThrow("guru")),
        waktu = getLong(getColumnIndexOrThrow("waktu")),
        status = getString(getColumnIndexOrThrow("status"))
    )

    companion object {
        private const val NAMA_BERKAS = "presensi.db"
        private const val VERSI = 1
    }
}
