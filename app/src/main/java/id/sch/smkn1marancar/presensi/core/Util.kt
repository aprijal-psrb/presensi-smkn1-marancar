package id.sch.smkn1marancar.presensi.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Pembantu format tanggal & jam (bahasa Indonesia). */
object Waktu {
    private val lokal = Locale("id", "ID")

    private fun fmt(pola: String, ms: Long) = SimpleDateFormat(pola, lokal).format(Date(ms))

    fun lengkap(ms: Long) = fmt("dd/MM/yyyy HH:mm:ss", ms)
    fun tanggal(ms: Long) = fmt("dd/MM/yyyy", ms)
    fun jam(ms: Long) = fmt("HH:mm", ms)
    fun tanggalPanjang(ms: Long) = fmt("dd MMM yyyy", ms)

    fun awalHari(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun akhirHari(ms: Long): Long = awalHari(ms) + 86_400_000L - 1
}

/** Penyeragam penulisan agar "XI TKJ 1", "XI  TKJ  1" dan " XI TKJ 1 " dianggap sama. */
object Rapi {

    /** Nama kelas: tanpa spasi tepi, spasi ganda dirapatkan jadi satu. */
    fun kelas(s: String): String = s.trim().replace(Regex("\\s+"), " ")

    /** NISN: hanya angka & huruf, tanpa spasi. */
    fun nisn(s: String): String = s.trim().replace(" ", "")
}

/** Utilitas jaringan: mencari alamat IP lokal HP guru. */
object Jaringan {

    /**
     * Ambil IPv4 lokal yang paling mungkin dijangkau siswa.
     *
     * URUTAN PENTING (perbaikan v1.1): antarmuka hotspot (ap atau swlan)
     * didahulukan di atas wlan, karena bila guru menyalakan hotspot SEKALIGUS
     * tersambung ke Wi-Fi sekolah, alamat wlan tidak bisa dijangkau siswa hotspot.
     */
    fun ipLokal(): String {
        try {
            val semua = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val urut = semua.sortedByDescending { nif -> prioritas(nif.name) }
            for (nif in urut) {
                if (!nif.isUp || nif.isLoopback) continue
                for (alamat in nif.inetAddresses.toList()) {
                    if (alamat is Inet4Address && layak(alamat)) {
                        return alamat.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }

    /**
     * Alamat dasar server untuk QR, mis. "http://192.168.43.1:8787".
     * Null bila HP tidak terhubung ke jaringan mana pun yang bisa dijangkau siswa —
     * lebih baik menolak membuat QR daripada menampilkan QR yang pasti gagal.
     */
    fun alamatServer(port: Int): String? {
        if (port <= 0) return null
        val ip = ipLokal()
        if (ip == "127.0.0.1") return null
        return "http://$ip:$port"
    }

    private fun prioritas(nama: String): Int {
        val n = nama.lowercase(Locale.ROOT)
        return when {
            n.startsWith("ap") || n.startsWith("swlan") -> 6   // hotspot bawaan HP
            n.startsWith("wlan1") || n.startsWith("wlan2") -> 5
            n.startsWith("rndis") || n.startsWith("usb") -> 4    // tethering kabel
            n.startsWith("wlan") -> 3
            n.startsWith("eth") -> 2
            else -> 1
        }
    }

    /** Hanya alamat privat yang wajar dipakai jaringan sekolah. */
    private fun layak(a: Inet4Address): Boolean {
        if (a.isLoopbackAddress || a.isLinkLocalAddress) return false
        val ip = a.hostAddress ?: return false
        if (ip.startsWith("169.254.")) return false   // link-local: tidak akan terjangkau
        return a.isSiteLocalAddress
    }
}

/** Pembuat kode acak untuk token sesi. */
object Acak {
    private val rnd = SecureRandom()
    private val abjad = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // tanpa I/O/0/1 agar mudah dibaca

    fun hex(jumlahByte: Int): String {
        val b = ByteArray(jumlahByte)
        rnd.nextBytes(b)
        val sb = StringBuilder(jumlahByte * 2)
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }

    fun kode(panjang: Int): String {
        val sb = StringBuilder(panjang)
        repeat(panjang) { sb.append(abjad[rnd.nextInt(abjad.length)]) }
        return sb.toString()
    }
}

/** Pembuat gambar QR code. */
object Qr {
    fun buat(isi: String, ukuran: Int = 760): Bitmap {
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        hints[EncodeHintType.MARGIN] = 1
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M

        val matriks = QRCodeWriter().encode(isi, BarcodeFormat.QR_CODE, ukuran, ukuran, hints)
        val bmp = Bitmap.createBitmap(matriks.width, matriks.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matriks.width) {
            for (y in 0 until matriks.height) {
                bmp.setPixel(x, y, if (matriks[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}

/** Identitas siswa yang tersimpan di HP siswa (cukup diisi sekali). */
object Identitas {
    private const val BERKAS = "identitas_siswa"

    fun simpan(ctx: Context, siswa: Siswa) {
        ctx.getSharedPreferences(BERKAS, Context.MODE_PRIVATE).edit()
            .putString("nisn", siswa.nisn)
            .putString("nama", siswa.nama)
            .putString("kelas", siswa.kelas)
            .apply()
    }

    fun ambil(ctx: Context): Siswa? {
        val p = ctx.getSharedPreferences(BERKAS, Context.MODE_PRIVATE)
        val nisn = p.getString("nisn", null) ?: return null
        return Siswa(nisn, p.getString("nama", "") ?: "", p.getString("kelas", "") ?: "")
    }

    fun hapus(ctx: Context) {
        ctx.getSharedPreferences(BERKAS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/** Isian terakhir guru, supaya sesi berikutnya cukup sekali ketuk. */
object PrefGuru {
    private const val BERKAS = "pref_guru"

    fun simpan(ctx: Context, guru: String, mapel: String, kelas: String) {
        ctx.getSharedPreferences(BERKAS, Context.MODE_PRIVATE).edit()
            .putString("guru", guru).putString("mapel", mapel).putString("kelas", kelas).apply()
    }

    fun guru(ctx: Context) = ctx.getSharedPreferences(BERKAS, Context.MODE_PRIVATE).getString("guru", "") ?: ""
    fun mapel(ctx: Context) = ctx.getSharedPreferences(BERKAS, Context.MODE_PRIVATE).getString("mapel", "") ?: ""
    fun kelas(ctx: Context) = ctx.getSharedPreferences(BERKAS, Context.MODE_PRIVATE).getString("kelas", "") ?: ""
}
