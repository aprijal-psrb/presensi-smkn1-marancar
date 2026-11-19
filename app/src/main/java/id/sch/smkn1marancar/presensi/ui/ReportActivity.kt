package id.sch.smkn1marancar.presensi.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import id.sch.smkn1marancar.presensi.R
import id.sch.smkn1marancar.presensi.core.App
import id.sch.smkn1marancar.presensi.core.Ekspor
import id.sch.smkn1marancar.presensi.core.Waktu
import java.util.Calendar

/**
 * Rekap kehadiran: saring per kelas, per mapel, dan per tanggal,
 * lalu ekspor ke CSV (bisa dibuka di Excel / Google Sheets).
 */
class ReportActivity : AppCompatActivity() {

    private lateinit var inputKelas: AutoCompleteTextView
    private lateinit var inputMapel: AutoCompleteTextView
    private lateinit var btnTanggal: Button
    private lateinit var txtRingkas: TextView
    private lateinit var listSesi: ListView

    private val app get() = application as App

    private var dari: Long? = Waktu.awalHari(System.currentTimeMillis())
    private var sampai: Long? = Waktu.akhirHari(System.currentTimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)

        inputKelas = findViewById(R.id.inputKelas)
        inputMapel = findViewById(R.id.inputMapel)
        btnTanggal = findViewById(R.id.btnTanggal)
        txtRingkas = findViewById(R.id.txtRingkas)
        listSesi = findViewById(R.id.listSesi)

        inputKelas.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, app.db.daftarKelas())
        )
        inputMapel.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, app.db.daftarMapel())
        )

        btnTanggal.text = "Tanggal: ${Waktu.tanggal(dari ?: System.currentTimeMillis())}"
        btnTanggal.setOnClickListener { pilihTanggal() }

        findViewById<Button>(R.id.btnSemuaTanggal).setOnClickListener {
            dari = null
            sampai = null
            btnTanggal.text = "Semua tanggal"
            tampilkan()
        }
        findViewById<Button>(R.id.btnTampil).setOnClickListener { tampilkan() }
        findViewById<Button>(R.id.btnEkspor).setOnClickListener { ekspor() }

        tampilkan()
    }

    private fun pilihTanggal() {
        val kalender = Calendar.getInstance()
        kalender.timeInMillis = dari ?: System.currentTimeMillis()
        DatePickerDialog(
            this,
            { _, tahun, bulan, hari ->
                val pilih = Calendar.getInstance()
                pilih.set(tahun, bulan, hari, 0, 0, 0)
                pilih.set(Calendar.MILLISECOND, 0)
                dari = pilih.timeInMillis
                sampai = dari!! + 86_400_000L - 1
                btnTanggal.text = "Tanggal: ${Waktu.tanggal(dari!!)}"
                tampilkan()
            },
            kalender.get(Calendar.YEAR),
            kalender.get(Calendar.MONTH),
            kalender.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun filterKelas() = inputKelas.text.toString().trim().ifBlank { null }
    private fun filterMapel() = inputMapel.text.toString().trim().ifBlank { null }

    private fun tampilkan() {
        val ringkasan = app.db.ringkasanSesi(filterKelas(), filterMapel(), dari, sampai)
        var hadir = 0
        var terlambat = 0
        val data = ringkasan.map { r ->
            hadir += r.hadir
            terlambat += r.terlambat
            mapOf(
                "satu" to "${r.sesi.mapel} • ${r.sesi.kelas}",
                "dua" to "${Waktu.lengkap(r.sesi.dibukaPada)} • ${r.sesi.guru} • ${r.total} hadir"
            )
        }

        txtRingkas.text = "${ringkasan.size} sesi • $hadir hadir • $terlambat terlambat"
        listSesi.adapter = SimpleAdapter(
            this,
            data,
            android.R.layout.simple_list_item_2,
            arrayOf("satu", "dua"),
            intArrayOf(android.R.id.text1, android.R.id.text2)
        )
    }

    private fun ekspor() {
        val daftar = app.db.rekap(filterKelas(), filterMapel(), dari, sampai)
        if (daftar.isEmpty()) {
            Toast.makeText(this, "Belum ada data untuk diekspor.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val label = listOfNotNull(filterKelas(), filterMapel()).joinToString("-").ifBlank { "semua" }
            val berkas = Ekspor.rekapCsv(this, daftar, label)
            Ekspor.bagikan(this, berkas)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal mengekspor: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
