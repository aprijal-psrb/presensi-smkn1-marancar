package id.sch.smkn1marancar.presensi.core

import android.content.Context
import android.net.Uri
import android.util.Xml
import java.io.InputStream
import java.io.StringReader
import java.util.zip.ZipInputStream

/**
 * Pembaca berkas Excel (.xlsx) / CSV daftar siswa.
 *
 * Sengaja tanpa pustaka pihak ketiga: cukup membaca ZIP + XML di dalam xlsx,
 * supaya APK tetap kecil dan tidak menambah dependensi.
 *
 * Format yang diharapkan (baris pertama = judul kolom, urutan bebas):
 *   NISN | Nama | Kelas
 */
object ImporSiswa {

    fun baca(ctx: Context, uri: Uri): List<Siswa> {
        val namaBerkas = (uri.lastPathSegment ?: "").lowercase()
        val isCsv = namaBerkas.endsWith(".csv") || namaBerkas.endsWith(".txt")

        val baris: List<List<String>>

        if (isCsv) {
            val teksCsv = ctx.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IllegalStateException("Berkas tidak bisa dibuka.")
            baris = pecahCsv(teksCsv)
        } else {
            val (shared, sheet) = ctx.contentResolver.openInputStream(uri)
                ?.use { bacaZip(it) }
                ?: throw IllegalStateException("Berkas tidak bisa dibuka.")
            if (sheet.isBlank()) throw IllegalStateException(
                "Lembar kerja tidak ditemukan. Simpan Excel sebagai .xlsx atau .csv."
            )
            baris = pecahSheet(sheet, shared)
        }

        return ubahKeSiswa(baris)
    }

    // ------------------------------------------------------------ xlsx (zip)

    private fun bacaZip(stream: InputStream): Pair<List<String>, String> {
        var shared: List<String> = emptyList()
        var sheet = ""

        ZipInputStream(stream).use { zip ->
            var entri = zip.nextEntry
            while (entri != null) {
                val nama = entri.name
                if (nama == "xl/sharedStrings.xml") {
                    shared = bacaSharedStrings(zip.readBytes().toString(Charsets.UTF_8))
                } else if (nama.startsWith("xl/worksheets/sheet") && nama.endsWith(".xml") && sheet.isEmpty()) {
                    sheet = zip.readBytes().toString(Charsets.UTF_8)
                }
                entri = zip.nextEntry
            }
        }
        return shared to sheet
    }

    private fun bacaSharedStrings(xml: String): List<String> {
        val hasil = mutableListOf<String>()
        val p = Xml.newPullParser()
        p.setInput(StringReader(xml))

        var peristiwa = p.eventType
        var dalamSi = false
        val sb = StringBuilder()

        while (peristiwa != XmlPullParser.END_DOCUMENT) {
            when (peristiwa) {
                XmlPullParser.START_TAG -> if (p.name == "si") {
                    sb.setLength(0)
                    dalamSi = true
                }

                XmlPullParser.TEXT -> if (dalamSi) sb.append(p.text)

                XmlPullParser.END_TAG -> if (p.name == "si") {
                    hasil.add(sb.toString())
                    dalamSi = false
                }
            }
            peristiwa = p.next()
        }
        return hasil
    }

    private fun pecahSheet(xml: String, shared: List<String>): List<List<String>> {
        val baris = mutableListOf<List<String>>()
        val p = Xml.newPullParser()
        p.setInput(StringReader(xml))

        var peristiwa = p.eventType
        var sel: MutableList<String>? = null
        var indeksKolom = 0
        var selShared = false
        var dalamNilai = false
        val nilai = StringBuilder()

        while (peristiwa != XmlPullParser.END_DOCUMENT) {
            when (peristiwa) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> sel = mutableListOf()
                    "c" -> {
                        indeksKolom = nomorKolom(p.getAttributeValue(null, "r"))
                        selShared = p.getAttributeValue(null, "t") == "s"
                        nilai.setLength(0)
                    }
                    "v", "t" -> dalamNilai = true
                }

                XmlPullParser.TEXT -> if (dalamNilai && sel != null) nilai.append(p.text)

                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> dalamNilai = false
                    "c" -> {
                        val daftar = sel
                        if (daftar != null && indeksKolom >= 0) {
                            while (daftar.size < indeksKolom) daftar.add("")
                            val mentah = nilai.toString()
                            val isi = if (selShared) {
                                shared.getOrElse(mentah.toIntOrNull() ?: -1) { "" }
                            } else {
                                mentah
                            }
                            daftar.add(isi)
                        }
                    }
                    "row" -> {
                        sel?.let { baris.add(it) }
                        sel = null
                    }
                }
            }
            peristiwa = p.next()
        }
        return baris
    }

    /** "B7" -> 1 (indeks kolom mulai 0). */
    private fun nomorKolom(ref: String?): Int {
        if (ref.isNullOrBlank()) return -1
        var i = 0
        while (i < ref.length && ref[i].isLetter()) i++
        if (i == 0) return -1
        var n = 0
        for (c in ref.substring(0, i).uppercase()) n = n * 26 + (c - 'A' + 1)
        return n - 1
    }

    // ------------------------------------------------------------------ csv

    private fun pecahCsv(teks: String): List<List<String>> {
        val hasil = mutableListOf<List<String>>()
        for (b in teks.split("\n")) {
            val bersih = b.trimEnd('\r')
            if (bersih.isBlank()) continue
            val pemisah = if (bersih.count { it == ';' } >= bersih.count { it == ',' }) ';' else ','
            hasil.add(bersih.split(pemisah).map { it.trim().trim('"') })
        }
        return hasil
    }

    // --------------------------------------------------------- pemetaan kolom

    private fun ubahKeSiswa(baris: List<List<String>>): List<Siswa> {
        if (baris.isEmpty()) throw IllegalStateException("Berkas kosong.")

        var indeksNisn = -1
        var indeksNama = -1
        var indeksKelas = -1
        var indeksJudul = -1

        for ((i, r) in baris.withIndex()) {
            if (i > 5) break
            val kecil = r.map { it.lowercase() }
            val nisn = kecil.indexOfFirst { it.contains("nisn") }
            val nama = kecil.indexOfFirst { it.contains("nama") }
            val kelas = kecil.indexOfFirst { it.contains("kelas") || it.contains("rombel") }
            if (nisn >= 0 && nama >= 0 && kelas >= 0) {
                indeksNisn = nisn; indeksNama = nama; indeksKelas = kelas; indeksJudul = i
                break
            }
        }

        if (indeksJudul < 0) throw IllegalStateException(
            "Judul kolom tidak ditemukan. Baris pertama harus berisi: NISN, Nama, Kelas."
        )

        val hasil = mutableListOf<Siswa>()
        val sudahAda = HashSet<String>()

        for (i in (indeksJudul + 1) until baris.size) {
            val r = baris[i]
            fun ambil(idx: Int) = r.getOrNull(idx)?.trim().orEmpty()

            // Penyeragaman (v1.1): NISN tanpa spasi, nama kelas dirapatkan.
            // Tanpa ini, "XI TKJ 1" vs "XI  TKJ 1" dianggap kelas berbeda dan
            // membuat siswa gagal scan.
            val nisn = Rapi.nisn(ambil(indeksNisn))
            val nama = ambil(indeksNama)
            val kelas = Rapi.kelas(ambil(indeksKelas))
            if (nisn.isBlank() || nama.isBlank() || kelas.isBlank()) continue

            val kunci = "$kelas|$nisn"
            if (!sudahAda.add(kunci)) continue
            hasil.add(Siswa(nisn = nisn, nama = nama, kelas = kelas))
        }

        if (hasil.isEmpty()) throw IllegalStateException("Tidak ada baris siswa yang bisa dibaca.")
        return hasil
    }
}
