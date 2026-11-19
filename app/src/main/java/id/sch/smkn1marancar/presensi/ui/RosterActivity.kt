package id.sch.smkn1marancar.presensi.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import id.sch.smkn1marancar.presensi.R
import id.sch.smkn1marancar.presensi.core.App
import id.sch.smkn1marancar.presensi.core.ImporSiswa

/** Layar impor daftar siswa dari Excel (.xlsx) atau CSV — cukup sekali di awal tahun. */
class RosterActivity : AppCompatActivity() {

    private lateinit var txtRingkas: TextView
    private lateinit var listKelas: ListView

    private val app get() = application as App

    private val pilihBerkas = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) impor(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_roster)

        txtRingkas = findViewById(R.id.txtRingkas)
        listKelas = findViewById(R.id.listKelas)

        findViewById<Button>(R.id.btnImpor).setOnClickListener {
            pilihBerkas.launch(arrayOf("*/*"))
        }
        segarkan()
    }

    private fun impor(uri: Uri) {
        txtRingkas.text = "Mengimpor…"
        Thread {
            try {
                val daftar = ImporSiswa.baca(this, uri)
                val jumlah = app.db.imporRoster(daftar)
                runOnUiThread {
                    Toast.makeText(this, "$jumlah siswa berhasil diimpor.", Toast.LENGTH_LONG).show()
                    segarkan()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    txtRingkas.text = "Impor gagal: ${e.message}"
                }
            }
        }.start()
    }

    private fun segarkan() {
        val total = app.db.jumlahSiswa()
        val kelas = app.db.daftarKelas()

        txtRingkas.text = if (total == 0) {
            "Belum ada data siswa. Siapkan berkas Excel dengan kolom NISN, Nama, Kelas."
        } else {
            "$total siswa di ${kelas.size} kelas."
        }

        val data = kelas.map { k ->
            mapOf("satu" to k, "dua" to "${app.db.jumlahSiswaKelas(k)} siswa")
        }
        listKelas.adapter = SimpleAdapter(
            this,
            data,
            android.R.layout.simple_list_item_2,
            arrayOf("satu", "dua"),
            intArrayOf(android.R.id.text1, android.R.id.text2)
        )
    }
}
