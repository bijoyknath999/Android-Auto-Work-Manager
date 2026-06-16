package com.workmanager.app

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * ADB connection backed by the libadb-android library (the same approach as
 * github.com/mouldybread/adb-auto-enable). The library implements the ADB wire
 * protocol, STLS/TLS 1.3 and the Android 11+ SPAKE2 pairing; we only supply a
 * persistent RSA key + self-signed certificate and drive shell commands.
 *
 * The public surface (connect / pair / exec / connected / disconnect) matches the
 * old hand-rolled implementation so the rest of the app is unchanged.
 */
class AdbConnection(private val context: Context) {

    @Volatile
    var connected = false
        private set

    private var manager: Manager? = null

    companion object {
        private const val TAG = "ADB"

        init {
            // libadb's TLS/pairing needs Conscrypt; cert generation needs BouncyCastle.
            try { Security.insertProviderAt(Conscrypt.newProvider(), 1) } catch (_: Exception) {}
            if (Security.getProvider("BC") == null) {
                try { Security.addProvider(BouncyCastleProvider()) } catch (_: Exception) {}
            }
        }
    }

    private fun log(msg: String) = AppLogger.log(TAG, msg)

    private fun mgr(): Manager = manager ?: Manager(context).also { manager = it }

    /** Wireless pairing (Android 11+) with a 6-digit code on the pairing port. */
    fun pair(host: String, port: Int, code: String): Boolean = try {
        log("Pairing with $host:$port …")
        val ok = mgr().pair(host, port, code)
        log(if (ok) "Pairing successful" else "Pairing returned false")
        ok
    } catch (e: Exception) {
        log("Pairing ERROR: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    /** Connects on the main wireless-debugging port using the paired key. */
    fun connect(host: String, port: Int): Boolean = try {
        log("Connecting to $host:$port …")
        mgr().connect(host, port)
        connected = true
        // Save for auto-reconnect
        context.getSharedPreferences("adb_auto", Context.MODE_PRIVATE).edit()
            .putString("last_host", host)
            .putInt("last_port", port)
            .apply()
        log("✓ CONNECTED to $host:$port")
        true
    } catch (e: Exception) {
        log("connect ERROR: ${e.javaClass.simpleName}: ${e.message}")
        connected = false
        false
    }

    /** Runs `shell:<command>` and returns its combined output. */
    fun  exec(command: String): String {
        if (!connected) return "ERROR: Not connected"
        log("exec: $command")
        return try {
            val stream = mgr().openStream("shell:$command")
            val buffer = java.io.ByteArrayOutputStream()
            val input = stream.openInputStream()
            val chunk = ByteArray(8192)
            try {
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buffer.write(chunk, 0, n)
                }
            } catch (e: java.io.IOException) {
                // libadb's AdbStream.read() throws IOException("Stream closed")
                // at end-of-output (non-graceful CLSE from adbd) instead of
                // returning -1. The bytes were already read, so treat as EOF —
                // anything else is a real error.
                if (e.message?.contains("Stream closed", ignoreCase = true) != true) throw e
            }
            try { stream.close() } catch (_: Exception) {}
            val result = buffer.toString("UTF-8").trim()
            log("exec result (${result.length} chars): ${result.take(200)}")
            result
        } catch (e: Exception) {
            log("exec ERROR: ${e.message}")
            "ERROR: ${e.message}"
        }
    }

    /**
     * Streams [input] to [remotePath] on the device using the binary-safe `exec:`
     * service (`cat > path`). Used to drop an APK into /data/local/tmp, which the
     * shell user can read, before `pm install`. Returns the number of bytes
     * written, or -1 on failure.
     */
    fun push(remotePath: String, input: java.io.InputStream): Long {
        if (!connected) return -1
        return try {
            var count = 0L
            mgr().openStream("exec:cat > $remotePath").use { stream ->
                stream.openOutputStream().use { out ->
                    count = input.copyTo(out)
                    out.flush()
                }
            }
            log("Pushed $count bytes to $remotePath")
            count
        } catch (e: Exception) {
            log("push ERROR: ${e.message}")
            -1
        }
    }

    fun disconnect() {
        connected = false
        try { manager?.close() } catch (_: Exception) {}
        manager = null
    }

    /** Quick health check — runs `echo ok` with a 4-second timeout.
     *  If the stream hangs (half-open TCP), closes the Manager to unblock
     *  the stuck read() and returns false so the caller can reconnect. */
    fun isAlive(): Boolean {
        if (!connected) return false
        val future = FutureTask { exec("echo ok") }
        Thread(future).apply { isDaemon = true; start() }
        return try {
            val r = future.get(4, TimeUnit.SECONDS)
            val alive = r.trim() == "ok"
            if (!alive) {
                log("Health check failed: ${r.take(50)}")
                connected = false
            }
            alive
        } catch (e: TimeoutException) {
            log("Health check timed out — resetting connection")
            connected = false
            try { manager?.close() } catch (_: Exception) {}
            manager = null
            false
        } catch (e: Exception) {
            log("Health check exception: ${e.message}")
            connected = false
            false
        }
    }

    /** Try to reconnect using the last known host/port from SharedPreferences. */
    fun tryReconnect(): Boolean {
        val prefs = context.getSharedPreferences("adb_auto", Context.MODE_PRIVATE)
        val host = prefs.getString("last_host", null) ?: return false
        val port = prefs.getInt("last_port", -1)
        if (port == -1) return false
        log("Reconnecting to $host:$port …")
        return connect(host, port)
    }

    /**
     * libadb connection manager that persists its RSA key + self-signed cert in
     * the app's filesDir, so a paired key survives app restarts and reboots.
     * Mirrors adb-auto-enable's SimpleAdbManager.
     */
    private class Manager(context: Context) : AbsAdbConnectionManager() {
        private val privateKey: PrivateKey
        private val certificate: X509Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            val keyFile = File(context.filesDir, "adb_key")
            val certFile = File(context.filesDir, "adb_cert")
            if (keyFile.exists() && certFile.exists()) {
                val kf = KeyFactory.getInstance("RSA")
                privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
            } else {
                val kp = KeyPairGenerator.getInstance("RSA")
                    .apply { initialize(2048, SecureRandom()) }.generateKeyPair()
                val notBefore = Date(System.currentTimeMillis() - 86_400_000L)
                val notAfter = Date(System.currentTimeMillis() + 3650L * 86_400_000L)
                val cert = JcaX509v3CertificateBuilder(
                    X500Name("CN=WorkManager"),
                    BigInteger.valueOf(System.currentTimeMillis()),
                    notBefore, notAfter,
                    X500Name("CN=WorkManager"),
                    kp.public
                ).build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
                certificate = JcaX509CertificateConverter().getCertificate(cert)
                privateKey = kp.private
                keyFile.writeBytes(privateKey.encoded)
                certFile.writeBytes(certificate.encoded)
            }
        }

        override fun getPrivateKey(): PrivateKey = privateKey
        override fun getCertificate(): Certificate = certificate
        override fun getDeviceName(): String = "WorkManager"
    }
}
