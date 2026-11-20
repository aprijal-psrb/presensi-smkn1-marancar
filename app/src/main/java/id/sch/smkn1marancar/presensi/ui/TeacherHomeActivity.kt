package id.sch.smkn1marancar.presensi.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import id.sch.smkn1marancar.presensi.R
import id.sch.smkn1marancar.presensi.core.App
import id.sch.smkn1marancar.presensi.core.PrefGuru
import id.sch.smkn1marancar.presensi.core.Rapi
import id.sch.smkn1marancar.presensi.core.SessionService

/**
 * Layar utama guru.
 *
 * SENGAJA hanya ada tiga isian: Nama guru, Mapel, Kelas.
 * Satu ketukan "Buka Sesi" langsung menampilkan QR.
 */
class TeacherHomeActivity : AppCompatActivity() {

    private lateinit var inputGuru: EditText
    private lateinit var inputMapel: EditText
    private lateinit var inputKelas: AutoCompleteTextView
    private lateinit var txtInfo: TextView

    private val app get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher_home)

        inputGuru = findViewById(R.id.inputGuru)
        inputMapel = findViewById(R.id.inputMapel)
        inputKelas = findViewById(R.id.inputKelas)
        txtInfo = findViewById(R.id.txtInfo)

        // Isian terakhir diingat, jadi sesi berikutnya cukup sekali ketuk.
        inputGuru.setText(PrefGuru.guru(this))
        inputMapel.setText(PrefGuru.mapel(this))
        inputKelas.setText(PrefGuru.kelas(this))

        findViewById<Button>(R.id.btnBuka).setOnClickListener { bukaSesi() }
        findViewById<Button>(R.id.btnRoster).setOnClickListener {
            startActivity(Intent(this, RosterActivity::class.java))
        }
        findViewById<Button>(R.id.btnRekap).setOnClickListener {
            startActivity(Intent(this, ReportActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        siapkanSaranKelas()
        segarkanInfo()
    }

    /** Daftar kelas diambil dari Excel yang sudah diimpor (bukan diketik manual). */
    private fun siapkanSaranKelas() {
        val daftarKelas = app.db.daftarKelas()
        if (daftarKelas.isNotEmpty()) {
            inputKelas.setAdapter(
                ArrayAdapter(this, android.R.layout.simple_list_item_1, daftarKelas)
            )
            inputKelas.threshold = 1
        }
    }

    private fun segarkanInfo() {
        val jumlahSiswa = app.db.jumlahSiswa()
        val jumlahKelas = app.db.daftarKelas().size
        txtInfo.text = if (jumlahSiswa == 0) {
            "Data siswa belum diimpor. Buka \"Data Siswa\" lalu impor berkas Excel."
        } else {
            "$jumlahSiswa siswa terdaftar di $jumlahKelas kelas."
        }
    }

    private fun bukaSesi() {
        val guru = inputGuru.text.toString().trim()
        val mapel = inputMapel.text.toString().trim()
        val kelas = Rapi.kelas(inputKelas.text.toString())

        if (guru.isEmpty() || mapel.isEmpty() || kelas.isEmpty()) {
            Toast.makeText(this, "Isi Nama guru, Mapel, dan Kelas dulu.", Toast.LENGTH_SHORT).show()
            return
        }

        // Fail-closed (perbaikan v1.1): kelas tanpa daftar siswa tidak boleh
        // dibuka, karena tidak ada siswa yang bisa tercatat dan QR-nya sia-sia.
        if (app.db.jumlahSiswaKelas(kelas) == 0) {
            Toast.makeText(
                this,
                "Kelas \"$kelas\" belum punya daftar siswa. Impor Excel dulu di menu Data Siswa.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        PrefGuru.simpan(this, guru, mapel, kelas)

        val aktif = app.sesiAktif()
        val sesi = if (aktif != null && aktif.guru == guru && aktif.mapel == mapel && aktif.kelas == kelas) {
            // Sesi yang sama masih terbuka: lanjutkan, jangan buang kehadiran yang sudah masuk.
            aktif
        } else {
            if (aktif != null) app.db.tutupSesi(aktif.id)
            app.db.bukaSesi(guru, mapel, kelas)
        }

        SessionService.mulai(this)
        startActivity(Intent(this, QrSessionActivity::class.java).putExtra("sesi", sesi.id))
    }
}
