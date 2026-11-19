package id.sch.smkn1marancar.presensi.core

import android.app.Application

/**
 * Titik awal aplikasi.
 *
 * Aplikasi ini memakai satu basis data SQLite lokal sebagai "sumber kebenaran":
 * data kehadiran ditulis ke SQLite LEBIH DULU, baru ditampilkan/diekspor.
 * Dengan begitu listrik mati, jaringan putus, atau aplikasi tertutup tidak
 * menghilangkan catatan kehadiran yang sudah masuk.
 */
class App : Application() {

    lateinit var db: Db
        private set

    private var server: SessionServer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = Db(this)
    }

    /**
     * Nyalakan server absensi (HTTP) di HP guru.
     * Bila port bawaan dipakai aplikasi lain, port dicoba naik satu per satu.
     * @return port yang benar-benar aktif, atau -1 bila gagal.
     */
    @Synchronized
    fun mulaiServer(portAwal: Int = PORT_BAWAAN): Int {
        server?.let { if (it.isAlive) return it.listeningPort }

        var port = portAwal
        while (port < portAwal + 10) {
            try {
                val calon = SessionServer(this, port)
                calon.start(5_000, false)
                server = calon
                return calon.listeningPort
            } catch (e: Exception) {
                port++
            }
        }
        return -1
    }

    @Synchronized
    fun hentikanServer() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
    }

    fun portServer(): Int = server?.listeningPort ?: -1

    fun sesiAktif(): Sesi? = db.sesiAktif()

    companion object {
        const val PORT_BAWAAN = 8787

        @Volatile
        lateinit var instance: App
            private set
    }
}
