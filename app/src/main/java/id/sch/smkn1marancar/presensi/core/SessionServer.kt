package id.sch.smkn1marancar.presensi.core

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Server absensi yang berjalan DI DALAM HP GURU (port 8787).
 *
 * Kenapa begini? Sekolah tidak perlu menyewa VPS atau mengatur Google Sheets:
 * HP guru menjadi "server" kecil selama sesi presensi terbuka. Siswa yang
 * terhubung ke Wi-Fi/hotspot yang sama tinggal memindai QR.
 *
 * Endpoint:
 *   GET  /s/{token}          halaman HTML (bila QR dipindai dengan kamera biasa)
 *   GET  /api/sesi?token=    info sesi yang sedang berjalan
 *   GET  /api/roster?token=  daftar nama siswa kelas tersebut (untuk pilih nama)
 *   POST /api/absen          catat kehadiran  {token|kode, nisn}
 *   GET  /api/ping           cek server hidup
 */
class SessionServer(private val app: App, port: Int) : NanoHTTPD(port) {

    private val db get() = app.db

    override fun serve(sesi: IHTTPSession): Response {
        return try {
            val uri = sesi.uri ?: "/"
            when {
                uri.startsWith("/s/") -> halaman(uri.removePrefix("/s/"))
                uri == "/api/absen" && sesi.method == Method.POST -> apiAbsen(sesi)
                uri == "/api/sesi" -> apiSesi(sesi)
                uri == "/api/roster" -> apiRoster(sesi)
                uri == "/api/ping" -> json(
                    Response.Status.OK,
                    JSONObject().put("ok", true).put("aplikasi", "presensi-smkn1-marancar")
                )
                else -> teks(Response.Status.NOT_FOUND, "Alamat tidak dikenal")
            }
        } catch (e: Exception) {
            json(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("ok", false).put("pesan", "Galat server. Coba lagi.")
            )
        }
    }

    // ------------------------------------------------------------ endpoint

    private fun apiSesi(s: IHTTPSession): Response {
        val token = s.parameters["token"]?.firstOrNull() ?: ""
        val kode = s.parameters["kode"]?.firstOrNull() ?: ""
        val sesi = db.sesiByToken(token) ?: db.sesiByKode(kode)
            ?: return json(
                Response.Status.CONFLICT,
                JSONObject().put("ok", false).put("status", "TIDAK_VALID")
                    .put("pesan", "QR tidak dikenali atau sudah diganti guru.")
            )
        if (!sesi.aktif) return json(
            Response.Status.CONFLICT,
            JSONObject().put("ok", false).put("status", "SESI_DITUTUP")
                .put("pesan", "Sesi presensi sudah ditutup guru.")
        )

        val (hadir, terlambat) = db.hitungStatus(sesi.id)
        return json(
            Response.Status.OK,
            JSONObject().put("ok", true)
                .put(
                    "sesi", JSONObject()
                        .put("guru", sesi.guru)
                        .put("mapel", sesi.mapel)
                        .put("kelas", sesi.kelas)
                        .put("dibuka", sesi.dibukaPada)
                        .put("toleransi", sesi.toleransiMenit)
                )
                .put("tercatat", hadir + terlambat)
        )
    }

    private fun apiRoster(s: IHTTPSession): Response {
        val token = s.parameters["token"]?.firstOrNull() ?: ""
        val sesi = db.sesiByToken(token)
            ?: return json(
                Response.Status.CONFLICT,
                JSONObject().put("ok", false).put("pesan", "QR tidak dikenali.")
            )
        if (!sesi.aktif) return json(
            Response.Status.CONFLICT,
            JSONObject().put("ok", false).put("status", "SESI_DITUTUP")
                .put("pesan", "Sesi presensi sudah ditutup guru.")
        )
        val daftar = JSONArray()
        db.rosterKelas(sesi.kelas).forEach { sw ->
            daftar.put(JSONObject().put("nisn", sw.nisn).put("nama", sw.nama))
        }
        return json(
            Response.Status.OK,
            JSONObject().put("ok", true).put("kelas", sesi.kelas).put("siswa", daftar)
        )
    }

    /**
     * Inti pencegahan kecurangan:
     *  - token harus milik sesi yang MASIH terbuka (QR mati begitu sesi ditutup
     *    atau guru menekan "Ganti QR");
     *  - daftar siswa kelas sesi WAJIB ada. Bila kosong, permintaan DITOLAK
     *    (fail-closed, perbaikan v1.1 — sebelumnya nama dari HP siswa diterima
     *    apa adanya sehingga siapa pun bisa mencatat nama fiktif);
     *  - NISN wajib terdaftar di kelas sesi tersebut, dan nama diambil dari roster.
     */
    private fun apiAbsen(s: IHTTPSession): Response {
        val peta = HashMap<String, String>()
        s.parseBody(peta)
        val badan = peta["postData"] ?: "{}"
        val j = JSONObject(badan)

        val token = j.optString("token", "")
        val kode = j.optString("kode", "").uppercase()
        val nisn = Rapi.nisn(j.optString("nisn", ""))

        val sesi = db.sesiByToken(token) ?: db.sesiByKode(kode)
            ?: return json(
                Response.Status.CONFLICT,
                JSONObject().put("ok", false).put("status", "TIDAK_VALID")
                    .put("pesan", "QR/kode tidak dikenali atau sudah diganti guru.")
            )

        if (!sesi.aktif) return json(
            Response.Status.CONFLICT,
            JSONObject().put("ok", false).put("status", "SESI_DITUTUP")
                .put("pesan", "Sesi presensi sudah ditutup guru.")
        )

        if (nisn.isBlank()) return json(
            Response.Status.BAD_REQUEST,
            JSONObject().put("ok", false).put("pesan", "NISN belum diisi.")
        )

        // Fail-closed: tanpa roster, kehadiran tidak boleh dicatat sama sekali.
        if (db.jumlahSiswaKelas(sesi.kelas) == 0) {
            return json(
                Response.Status.CONFLICT,
                JSONObject().put("ok", false).put("status", "ROSTER_KOSONG")
                    .put("pesan", "Daftar siswa kelas ${sesi.kelas} belum diimpor di HP guru.")
            )
        }

        // Nama SELALU dari roster Excel, bukan dari HP siswa.
        val siswa = db.cariSiswa(sesi.kelas, nisn)
            ?: return json(
                Response.Status.CONFLICT,
                JSONObject().put("ok", false).put("status", "NISN_TIDAK_COCOK")
                    .put("pesan", "NISN $nisn tidak terdaftar di kelas ${sesi.kelas}.")
            )
        val nama = siswa.nama

        val baru = db.catat(sesi, nisn, nama, System.currentTimeMillis())
        val (hadir, terlambat) = db.hitungStatus(sesi.id)

        return json(
            Response.Status.OK,
            JSONObject().put("ok", true)
                .put("status", if (baru) "BARU" else "SUDAH")
                .put("nama", nama)
                .put("mapel", sesi.mapel)
                .put("guru", sesi.guru)
                .put("kelas", sesi.kelas)
                .put("tercatat", hadir + terlambat)
                .put(
                    "pesan",
                    if (baru) "Hadir tercatat: $nama" else "$nama sudah tercatat sebelumnya"
                )
        )
    }

    // -------------------------------------------------------------- halaman

    private fun halaman(token: String): Response {
        val sesi = db.sesiByToken(token)
            ?: return html(
                Response.Status.OK,
                bingkai(
                    "QR tidak dikenali",
                    "QR ini bukan milik sesi presensi yang sedang berjalan, atau guru sudah mengganti QR.",
                    null
                )
            )
        if (!sesi.aktif) return html(
            Response.Status.OK,
            bingkai(
                "Sesi sudah ditutup",
                "Sesi ${sesi.mapel} kelas ${sesi.kelas} sudah ditutup guru.",
                null
            )
        )

        // Pakai alamat yang SAMA dengan yang tercetak di QR, agar tidak pernah
        // terjadi tautan yang menunjuk alamat berbeda dari QR di layar guru.
        val dasar = Jaringan.alamatServer(listeningPort)
            ?: return html(
                Response.Status.OK,
                bingkai(
                    "HP guru belum terhubung jaringan",
                    "Nyalakan hotspot atau sambungkan Wi-Fi, lalu tampilkan QR ulang.",
                    null
                )
            )

        val alamat = "$dasar/s/$token"
        val tautan = "presensi://absen?u=" + URLEncoder.encode(alamat, "UTF-8")
        val info = "<div class='baris'><span>Guru</span><b>${aman(sesi.guru)}</b></div>" +
            "<div class='baris'><span>Mapel</span><b>${aman(sesi.mapel)}</b></div>" +
            "<div class='baris'><span>Kelas</span><b>${aman(sesi.kelas)}</b></div>" +
            "<div class='baris'><span>Dibuka</span><b>${Waktu.jam(sesi.dibukaPada)}</b></div>"

        return html(
            Response.Status.OK,
            bingkai(
                "Sesi presensi sedang berjalan",
                info,
                tautan
            )
        )
    }

    private fun bingkai(judul: String, isi: String, tautan: String?): String {
        val tombol = if (tautan == null) "" else
            "<a class='tombol' href=\"$tautan\">Buka aplikasi &amp; catat hadir</a>" +
                "<p class='kecil' style='margin-top:14px'>Kalau tombol tidak bekerja: buka aplikasi " +
                "<b>Presensi SMKN 1 Marancar</b> di HP, pilih <b>Saya Siswa</b>, lalu pindai QR dari HP guru.</p>"

        return """<!doctype html>
<html lang="id"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Presensi SMKN 1 Marancar</title>
<style>
body{font-family:system-ui,-apple-system,sans-serif;background:#F6F8F7;margin:0;padding:20px;color:#14201B}
.kartu{max-width:420px;margin:32px auto;background:#fff;border-radius:18px;padding:22px;box-shadow:0 6px 20px rgba(0,0,0,.08)}
h1{font-size:19px;color:#0A4C37;margin:0 0 10px;line-height:1.3}
.kecil{font-size:13px;color:#5F6B66;line-height:1.5}
.tombol{display:block;text-align:center;background:#0F6E4F;color:#fff;text-decoration:none;padding:16px;border-radius:12px;font-weight:700;margin-top:18px}
.baris{display:flex;justify-content:space-between;gap:12px;padding:9px 0;border-bottom:1px solid #EDF1EF;font-size:14px}
</style></head><body>
<div class="kartu"><h1>${aman(judul)}</h1><div class="isi">$isi</div>$tombol</div>
</body></html>"""
    }

    /** Cegah HTML/JS dari isian guru ikut tereksekusi di peramban siswa. */
    private fun aman(teks: String): String = teks
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // -------------------------------------------------------------- bantuan

    private fun json(status: Response.Status, isi: JSONObject): Response {
        val r = newFixedLengthResponse(status, "application/json; charset=utf-8", isi.toString())
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun teks(status: Response.Status, t: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", t)

    private fun html(status: Response.Status, h: String): Response =
        newFixedLengthResponse(status, "text/html; charset=utf-8", h)
}
