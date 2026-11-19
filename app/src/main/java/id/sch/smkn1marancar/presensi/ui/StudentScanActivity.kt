package id.sch.smkn1marancar.presensi.ui

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import id.sch.smkn1marancar.presensi.R
import id.sch.smkn1marancar.presensi.core.App
import id.sch.smkn1marancar.presensi.core.Identitas
import id.sch.smkn1marancar.presensi.core.Net
import id.sch.smkn1marancar.presensi.core.NetGagal
import id.sch.smkn1marancar.presensi.core.Siswa
import org.json.JSONArray
import org.json.JSONObject

/**
 * Layar siswa: pindai QR guru, kehadiran langsung tercatat.
 *
 * Bila jaringan bermasalah, data scan disimpan dulu di HP (tabel outbox)
 * dan dikirim ulang otomatis — jadi tidak ada kehadiran yang hilang.
 */
class StudentScanActivity : AppCompatActivity() {

    private lateinit var txtIdentitas: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtHasil: TextView
    private lateinit var btnScan: Button

    private val app get() = application as App

    private val pemindai = registerForActivityResult(ScanContract()) { hasil ->
        val isi = hasil.contents
        if (isi.isNullOrBlank()) {
            txtStatus.text = "Pemindaian dibatalkan."
        } else {
            prosesIsi(isi)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_student_scan)

        txtIdentitas = findViewById(R.id.txtIdentitas)
        txtStatus = findViewById(R.id.txtStatus)
        txtHasil = findViewById(R.id.txtHasil)
        btnScan = findViewById(R.id.btnScan)

        btnScan.setOnClickListener { mulaiScan() }
        findViewById<Button>(R.id.btnGantiId).setOnClickListener { konfirmasiGantiIdentitas() }
        findViewById<Button>(R.id.btnKode).setOnClickListener { tanyaKodeManual() }

        segarkanIdentitas()
        flushAntrian()

        // Dibuka dari tautan kamera bawaan HP (presensi://absen?u=...)
        intent.getStringExtra("url")?.let { prosesIsi(it) }
    }

    // ------------------------------------------------------------- pemindaian

    private fun mulaiScan() {
        val opsi = ScanOptions()
        opsi.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        opsi.setPrompt("Arahkan kamera ke QR di HP guru")
        opsi.setBeepEnabled(false)
        opsi.setOrientationLocked(false)
        pemindai.launch(opsi)
    }

    /** Mengubah isi QR menjadi alamat http://ip:port/s/token */
    private fun keUrl(isi: String): String? {
        val teks = isi.trim()
        if (teks.startsWith("presensi://")) {
            val u = android.net.Uri.parse(teks).getQueryParameter("u") ?: return null
            return u
        }
        if (teks.startsWith("http://") && teks.contains("/s/")) return teks
        return null
    }

    private fun prosesIsi(isi: String) {
        val url = keUrl(isi)
        if (url == null) {
            txtStatus.text = "QR ini bukan QR presensi sekolah."
            return
        }
        val dasar = url.substringBefore("/s/")
        val token = url.substringAfter("/s/").substringBefore("?").trim()
        if (token.isEmpty()) {
            txtStatus.text = "QR tidak lengkap. Minta guru menampilkan ulang QR."
            return
        }
        txtStatus.text = "Menghubungi HP guru…"
        btnScan.isEnabled = false
        Thread { proses(dasar, token, null) }.start()
    }

    /**
     * Alur: cek sesi → (bila perlu) pilih nama & NISN → kirim kehadiran.
     * Berjalan di thread latar.
     */
    private fun proses(dasar: String, token: String, kode: String?) {
        try {
            var kelas = "-"
            if (token.isNotEmpty()) {
                val info = Net.ambil("$dasar/api/sesi?token=$token")
                kelas = info.optJSONObject("sesi")?.optString("kelas", "-") ?: "-"
            }

            val identitas = Identitas.ambil(this)
            if (identitas == null) {
                val roster = if (token.isNotEmpty()) {
                    Net.ambil("$dasar/api/roster?token=$token").optJSONArray("siswa") ?: JSONArray()
                } else {
                    JSONArray()
                }
                runOnUiThread { pilihNama(dasar, token, kode, roster, kelas) }
                return
            }

            kirim(dasar, token, kode, identitas)
        } catch (e: NetGagal) {
            runOnUiThread { tampilGagal(e.message ?: "Ditolak server.") }
        } catch (e: Exception) {
            runOnUiThread {
                tampilGagal("Tidak bisa menghubungi HP guru. Pastikan HP terhubung ke Wi-Fi/hotspot yang sama.")
            }
        }
    }

    /** Kirim kehadiran; bila gagal karena jaringan, simpan ke antrean lokal. */
    private fun kirim(dasar: String, token: String, kode: String?, siswa: Siswa) {
        val badan = JSONObject().put("nisn", siswa.nisn).put("nama", siswa.nama)
        if (token.isNotEmpty()) badan.put("token", token) else badan.put("kode", kode ?: "")

        try {
            val jawab = Net.kirim("$dasar/api/absen", badan)
            val pesan = jawab.optString("pesan", "Kehadiran tercatat")
            val status = jawab.optString("status", "")
            runOnUiThread { tampilSukses(pesan, status) }
        } catch (e: NetGagal) {
            runOnUiThread { tampilGagal(e.message ?: "Ditolak server.") }
        } catch (e: Exception) {
            // Jaringan putus / HP guru tidak terjangkau: simpan dulu, kirim nanti.
            app.db.antrikan("$dasar/api/absen", badan.toString())
            runOnUiThread { tampilAntri() }
        }
    }

    // ---------------------------------------------------------------- dialog

    private fun pilihNama(dasar: String, token: String, kode: String?, arr: JSONArray, kelas: String) {
        btnScan.isEnabled = true
        val nama = mutableListOf<String>()
        val nisn = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            nama.add(o.optString("nama"))
            nisn.add(o.optString("nisn"))
        }

        if (nama.isEmpty()) {
            tanyaNamaManual(dasar, token, kode, kelas)
            return
        }

        txtStatus.text = "Pilih namamu dulu (sekali saja)."
        AlertDialog.Builder(this)
            .setTitle("Pilih namamu — $kelas")
            .setItems(nama.toTypedArray()) { _, i ->
                tanyaNisn(dasar, token, kode, Siswa(nisn[i], nama[i], kelas))
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun tanyaNisn(dasar: String, token: String, kode: String?, siswa: Siswa) {
        val input = EditText(this).apply {
            hint = "NISN kamu"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(siswa.nama)
            .setMessage("Masukkan NISN untuk memastikan ini benar-benar kamu.")
            .setView(input)
            .setPositiveButton("Kirim") { _, _ ->
                val nisn = input.text.toString().trim()
                if (nisn != siswa.nisn) {
                    txtStatus.text = "NISN tidak cocok dengan nama yang dipilih."
                } else {
                    Identitas.simpan(this, siswa)
                    segarkanIdentitas()
                    txtStatus.text = "Mengirim kehadiran…"
                    btnScan.isEnabled = false
                    Thread { kirim(dasar, token, kode, siswa) }.start()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun tanyaNamaManual(dasar: String, token: String, kode: String?, kelas: String) {
        val wadah = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 24, 56, 0)
        }
        val inNama = EditText(this).apply { hint = "Nama lengkap" }
        val inNisn = EditText(this).apply { hint = "NISN" }
        wadah.addView(inNama)
        wadah.addView(inNisn)

        AlertDialog.Builder(this)
            .setTitle("Isi identitas ($kelas)")
            .setView(wadah)
            .setPositiveButton("Kirim") { _, _ ->
                val nama = inNama.text.toString().trim()
                val nisn = inNisn.text.toString().trim()
                if (nama.isEmpty() || nisn.isEmpty()) {
                    txtStatus.text = "Nama dan NISN harus diisi."
                    return@setPositiveButton
                }
                val siswa = Siswa(nisn, nama, kelas)
                Identitas.simpan(this, siswa)
                segarkanIdentitas()
                btnScan.isEnabled = false
                Thread { kirim(dasar, token, kode, siswa) }.start()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Cadangan bila kamera bermasalah: masukkan alamat server + kode 6 huruf. */
    private fun tanyaKodeManual() {
        val prefs = getSharedPreferences("siswa", MODE_PRIVATE)
        val wadah = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 24, 56, 0)
        }
        val inAlamat = EditText(this).apply {
            hint = "http://192.168.43.1:8787"
            setText(prefs.getString("alamat", ""))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val inKode = EditText(this).apply { hint = "Kode 6 huruf dari layar guru" }
        wadah.addView(inAlamat)
        wadah.addView(inKode)

        AlertDialog.Builder(this)
            .setTitle("Input kode manual")
            .setMessage("Alamat server tertulis di bawah QR pada layar guru.")
            .setView(wadah)
            .setPositiveButton("Kirim") { _, _ ->
                val alamat = inAlamat.text.toString().trim().trimEnd('/')
                val kode = inKode.text.toString().trim().uppercase()
                if (alamat.isEmpty() || kode.isEmpty()) return@setPositiveButton
                prefs.edit().putString("alamat", alamat).apply()

                val identitas = Identitas.ambil(this)
                if (identitas == null) {
                    txtStatus.text = "Pindai QR guru sekali dulu untuk mengisi identitas."
                    return@setPositiveButton
                }
                txtStatus.text = "Mengirim kehadiran…"
                btnScan.isEnabled = false
                Thread { proses(alamat, "", kode) }.start()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // -------------------------------------------------------------- tampilan

    private fun segarkanIdentitas() {
        val saya = Identitas.ambil(this)
        txtIdentitas.text = if (saya == null) {
            "Identitas belum diisi — akan diminta saat pemindaian pertama."
        } else {
            "${saya.nama} • ${saya.nisn} • ${saya.kelas}"
        }
    }

    private fun tampilSukses(pesan: String, status: String) {
        btnScan.isEnabled = true
        txtHasil.text = if (status == "SUDAH") "$pesan (tidak dobel)" else pesan
        txtStatus.text = "Tercatat ✓"
        flushAntrian()
    }

    private fun tampilGagal(pesan: String) {
        btnScan.isEnabled = true
        txtStatus.text = pesan
        txtHasil.text = ""
    }

    private fun tampilAntri() {
        btnScan.isEnabled = true
        txtHasil.text = "Tersimpan di HP"
        txtStatus.text = "Jaringan bermasalah. Kehadiranmu aman dan akan dikirim otomatis."
    }

    private fun konfirmasiGantiIdentitas() {
        AlertDialog.Builder(this)
            .setTitle("Ganti identitas?")
            .setMessage("Identitas akan diminta ulang saat pemindaian berikutnya.")
            .setPositiveButton("Ganti") { _, _ ->
                Identitas.hapus(this)
                segarkanIdentitas()
                txtStatus.text = "Identitas dihapus."
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Kirim ulang scan yang tertunda (dipanggil saat layar dibuka & setelah sukses). */
    private fun flushAntrian() {
        Thread {
            for ((id, url, isi) in app.db.daftarAntrian()) {
                try {
                    Net.kirim(url, JSONObject(isi))
                    app.db.hapusAntrian(id)
                } catch (e: NetGagal) {
                    // Ditolak permanen (mis. sesi sudah ditutup) — jangan diulang terus.
                    app.db.hapusAntrian(id)
                } catch (e: Exception) {
                    app.db.tandaiCoba(id)
                }
            }
        }.start()
    }
}
