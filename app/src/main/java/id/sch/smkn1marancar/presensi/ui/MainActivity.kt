package id.sch.smkn1marancar.presensi.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import id.sch.smkn1marancar.presensi.R

/** Layar pembuka: memilih peran (guru / siswa). */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnGuru).setOnClickListener {
            startActivity(Intent(this, TeacherHomeActivity::class.java))
        }
        findViewById<Button>(R.id.btnSiswa).setOnClickListener {
            startActivity(Intent(this, StudentScanActivity::class.java))
        }
        findViewById<TextView>(R.id.txtVersi).text = "Versi 1.0 • Android 10 ke atas"

        mintaIzinNotifikasi()
        tanganiTautan(intent)
    }

    /**
     * QR yang dipindai dengan kamera bawaan HP akan membuka aplikasi lewat
     * tautan presensi://absen?u=... — langsung diteruskan ke layar siswa.
     */
    private fun tanganiTautan(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "presensi") {
            val url = data.getQueryParameter("u") ?: return
            startActivity(Intent(this, StudentScanActivity::class.java).putExtra("url", url))
        }
    }

    private fun mintaIzinNotifikasi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val izin = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, izin) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(izin), 10)
            }
        }
    }
}
