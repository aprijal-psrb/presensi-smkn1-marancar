package id.sch.smkn1marancar.presensi.core

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Galat komunikasi dengan HP guru, membawa pesan siap tampil ke siswa. */
class NetGagal(pesan: String, val kode: Int = 0) : IOException(pesan)

/**
 * Klien HTTP kecil untuk HP siswa.
 * Dipakai untuk: cek sesi, ambil daftar nama kelas, dan kirim kehadiran.
 */
object Net {

    fun ambil(url: String, timeoutMs: Int = 6000): JSONObject {
        val koneksi = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
        }
        return baca(koneksi)
    }

    fun kirim(url: String, isi: JSONObject, timeoutMs: Int = 8000): JSONObject {
        val koneksi = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        koneksi.outputStream.use { it.write(isi.toString().toByteArray(Charsets.UTF_8)) }
        return baca(koneksi)
    }

    private fun baca(koneksi: HttpURLConnection): JSONObject {
        try {
            val kode = koneksi.responseCode
            val aliran = if (kode in 200..299) koneksi.inputStream else koneksi.errorStream
            val teks = aliran?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (teks.isBlank()) JSONObject() else JSONObject(teks)
            if (kode !in 200..299) {
                throw NetGagal(json.optString("pesan", "Server menolak (kode $kode)"), kode)
            }
            return json
        } finally {
            koneksi.disconnect()
        }
    }
}
