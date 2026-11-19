package id.sch.smkn1marancar.presensi.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import id.sch.smkn1marancar.presensi.R
import id.sch.smkn1marancar.presensi.core.App
import id.sch.smkn1marancar.presensi.core.Jaringan
import id.sch.smkn1marancar.presensi.core.Qr
import id.sch.smkn1marancar.presensi.core.Sesi
import id.sch.smkn1marancar.presensi.core.SessionService
import id.sch.smkn1marancar.presensi.core.Waktu

/**
 * Layar QR sesi.
 *
 * QR berisi alamat http://<ip-hp-guru>:<port>/s/<token>. Token hanya hidup
 * selama sesi ini terbuka; "Ganti QR" membuat token baru sehingga QR lama mati.
 */
class QrSessionActivity : AppCompatActivity() {

    private lateinit var sesi: Sesi
    private lateinit var imgQr: ImageView
    private lateinit var txtStatus: TextView
    private lateinit var txtMapel: TextView
    private lateinit var txtKelas: TextView
    private lateinit var txtGuru: TextView
    private lateinit var txtKode: TextView
    private lateinit var txtAlamat: TextView
    private lateinit var txtJumlah: TextView
    private lateinit var listHadir: ListView

    private val app get() = application as App
    private val penjadwal = Handler(Looper.getMainLooper())
    private var isiQrTerakhir = ""

    private val pengulang = object : Runnable {
        override fun run() {
            segarkan()
            penjadwal.postDelayed(this, 1_500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_session)

        val id = intent.getStringExtra("sesi").orEmpty()
        val ditemukan = app.db.sesiById(id)
        if (ditemukan == null) {
            finish()
            return
        }
        sesi = ditemukan

        imgQr = findViewById(R.id.imgQr)
        txtStatus = findViewById(R.id.txtStatus)
        txtMapel = findViewById(R.id.txtMapel)
        txtKelas = findViewById(R.id.txtKelas)
        txtGuru = findViewById(R.id.txtGuru)
        txtKode = findViewById(R.id.txtKode)
        txtAlamat = findViewById(R.id.txtAlamat)
        txtJumlah = findViewById(R.id.txtJumlah)
        listHadir = findViewById(R.id.listHadir)

        // Layar jangan mati selama sesi berlangsung.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        findViewById<Button>(R.id.btnGanti).setOnClickListener { gantiQr() }
        findViewById<Button>(R.id.btnTutup).setOnClickListener { konfirmasiTutup() }

        segarkan()
    }

    override fun onResume() {
        super.onResume()
        penjadwal.post(pengulang)
    }

    override fun onPause() {
        super.onPause()
        penjadwal.removeCallbacksAndMessages(null)
    }

    private fun segarkan() {
        val terkini = app.db.sesiById(sesi.id) ?: return
        sesi = terkini

        txtMapel.text = sesi.mapel
        txtKelas.text = "Kelas ${sesi.kelas}"
        txtGuru.text = "Guru: ${sesi.guru}"

        val (hadir, terlambat) = app.db.hitungStatus(sesi.id)
        txtJumlah.text = buildString {
            append("${hadir + terlambat} siswa tercatat")
            if (terlambat > 0) append(" • $terlambat terlambat")
        }

        val port = app.portServer()
        if (port > 0) {
            val alamat = "http://${Jaringan.ipLokal()}:$port/s/${sesi.token}"
            txtAlamat.text = alamat
            if (alamat != isiQrTerakhir) {
                imgQr.setImageBitmap(Qr.buat(alamat))
                isiQrTerakhir = alamat
            }
        } else {
            txtAlamat.text = "Server belum aktif — tutup lalu buka ulang sesi."
        }

        txtKode.text = "Kode manual: ${sesi.kode}"

        if (sesi.aktif) {
            txtStatus.text = "Sesi dibuka ${Waktu.jam(sesi.dibukaPada)} • ${sesi.toleransiMenit} menit pertama = HADIR"
        } else {
            txtStatus.text = "Sesi sudah ditutup."
            findViewById<Button>(R.id.btnGanti).isEnabled = false
            findViewById<Button>(R.id.btnTutup).isEnabled = false
        }

        tampilkanDaftar()
    }

    private fun tampilkanDaftar() {
        val daftar = app.db.daftarAbsen(sesi.id)
        val data = daftar.map { a ->
            mapOf(
                "satu" to a.nama,
                "dua" to "${a.nisn} • ${Waktu.jam(a.waktu)} • ${a.status}"
            )
        }
        listHadir.adapter = SimpleAdapter(
            this,
            data,
            android.R.layout.simple_list_item_2,
            arrayOf("satu", "dua"),
            intArrayOf(android.R.id.text1, android.R.id.text2)
        )
    }

    /** Ganti QR: token baru, QR lama langsung tidak bisa dipakai lagi. */
    private fun gantiQr() {
        val baru = app.db.gantiToken(sesi.id) ?: return
        sesi = baru
        isiQrTerakhir = ""
        segarkan()
        Toast.makeText(this, "QR baru dibuat. QR lama tidak berlaku.", Toast.LENGTH_SHORT).show()
    }

    private fun konfirmasiTutup() {
        AlertDialog.Builder(this)
            .setTitle("Tutup sesi presensi?")
            .setMessage("Setelah ditutup, QR ini tidak bisa dipakai lagi. Data yang sudah masuk tetap tersimpan.")
            .setPositiveButton("Tutup") { _, _ -> tutup() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun tutup() {
        app.db.tutupSesi(sesi.id)
        SessionService.hentikan(this)
        val (hadir, terlambat) = app.db.hitungStatus(sesi.id)
        Toast.makeText(
            this,
            "Sesi ditutup. ${hadir + terlambat} kehadiran tersimpan.",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }
}
