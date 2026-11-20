package id.sch.smkn1marancar.presensi.core

/** Satu sesi presensi yang dibuka guru. */
data class Sesi(
    val id: String,
    val guru: String,
    val mapel: String,
    val kelas: String,
    /** Kode acak yang ditanam di dalam QR. Berubah setiap "Ganti QR". */
    val token: String,
    /** Kode 6 huruf untuk siswa yang tidak bisa memindai (input manual). */
    val kode: String,
    val dibukaPada: Long,
    val ditutupPada: Long?,
    /** Menit setelah sesi dibuka; lewat dari ini statusnya TERLAMBAT. */
    val toleransiMenit: Int
) {
    val aktif: Boolean get() = ditutupPada == null
}

/** Satu baris kehadiran siswa. */
data class Absen(
    val id: Long,
    val sesiId: String,
    val nisn: String,
    val nama: String,
    val kelas: String,
    val mapel: String,
    val guru: String,
    val waktu: Long,
    val status: String            // HADIR / TERLAMBAT
)

/** Data siswa hasil impor Excel. */
data class Siswa(
    val nisn: String,
    val nama: String,
    val kelas: String
)

/**
 * Satu scan yang belum berhasil terkirim dari HP siswa.
 *
 * status: MENUNGGU (masih dicoba) atau GAGAL (ditolak permanen — harus dilaporkan ke guru).
 */
data class Antrian(
    val id: Long,
    val url: String,
    val isi: String,
    val coba: Int,
    val terakhir: Long,
    val status: String,
    val pesan: String
)

/** Ringkasan untuk daftar rekap. */
data class RingkasanSesi(
    val sesi: Sesi,
    val hadir: Int,
    val terlambat: Int
) {
    val total: Int get() = hadir + terlambat
}
