package id.sch.smkn1marancar.presensi.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Service latar depan yang menjaga server absensi tetap hidup
 * walau layar HP guru dimatikan atau aplikasi ditinggal sebentar.
 */
class SessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val kanal = NotificationChannel(
                KANAL, "Sesi presensi", NotificationManager.IMPORTANCE_LOW
            )
            kanal.description = "Menandai bahwa server presensi sedang berjalan di HP ini"
            nm.createNotificationChannel(kanal)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as App

        if (intent?.action == AKSI_STOP) {
            app.hentikanServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val port = app.mulaiServer()
        val sesi = app.sesiAktif()
        val ringkas = if (sesi != null) "${sesi.mapel} • ${sesi.kelas}" else "Sesi presensi"

        val notif = NotificationCompat.Builder(this, KANAL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Sesi presensi berjalan")
            .setContentText("$ringkas — siap menerima scan siswa")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notif)
        }

        // Jika port gagal dibuka, beri tahu lewat notifikasi agar guru tidak bingung.
        if (port <= 0) {
            val gagal = NotificationCompat.Builder(this, KANAL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Server presensi gagal dinyalakan")
                .setContentText("Tutup sesi lalu buka kembali.")
                .build()
            getSystemService(NotificationManager::class.java).notify(ID + 1, gagal)
        }

        return START_STICKY
    }

    companion object {
        private const val KANAL = "sesi_presensi"
        private const val ID = 1001
        const val AKSI_STOP = "id.sch.smkn1marancar.presensi.STOP"

        /** Dipanggil guru saat membuka sesi. */
        fun mulai(ctx: Context) {
            val i = Intent(ctx, SessionService::class.java)
            ctx.startForegroundService(i)
        }

        /** Dipanggil guru saat menutup sesi. */
        fun hentikan(ctx: Context) {
            val i = Intent(ctx, SessionService::class.java).setAction(AKSI_STOP)
            ctx.startService(i)
        }
    }
}
