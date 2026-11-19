package id.sch.smkn1marancar.presensi.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ekspor rekap kehadiran ke berkas CSV (bisa dibuka di Excel / Google Sheets). */
object Ekspor {

    fun rekapCsv(ctx: Context, daftar: List<Absen>, label: String): File {
        val folder = File(ctx.cacheDir, "ekspor").apply { mkdirs() }
        val cap = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val aman = label.replace(Regex("[^A-Za-z0-9._-]"), "-").ifBlank { "rekap" }
        val berkas = File(folder, "presensi-$aman-$cap.csv")

        berkas.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("\uFEFF") // BOM: agar Excel membaca huruf ber-aksen dengan benar
            w.write("NISN;Nama;Kelas;Mapel;Guru;Tanggal;Jam;Status\r\n")
            for (a in daftar) {
                val kolom = listOf(
                    a.nisn, a.nama, a.kelas, a.mapel, a.guru,
                    Waktu.tanggal(a.waktu), Waktu.jam(a.waktu), a.status
                )
                w.write(kolom.joinToString(";") { kutip(it) })
                w.write("\r\n")
            }
        }
        return berkas
    }

    fun bagikan(ctx: Context, berkas: File, judul: String = "Bagikan rekap presensi") {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", berkas)
        val kirim = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, judul)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pilih = Intent.createChooser(kirim, judul)
        pilih.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(pilih)
    }

    private fun kutip(v: String): String =
        if (v.contains(';') || v.contains('"') || v.contains('\n')) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else {
            v
        }
}
