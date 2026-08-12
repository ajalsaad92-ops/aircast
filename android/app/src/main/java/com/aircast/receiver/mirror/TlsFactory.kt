package com.aircast.receiver.mirror

import android.content.Context
import com.aircast.receiver.core.Logger
import com.aircast.receiver.core.Net
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ServerSocketFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Generates and caches a self-signed server certificate for the mirroring endpoint.
 *
 * Why this exists at all: `navigator.mediaDevices.getDisplayMedia()` — the API the
 * sender page uses to capture a screen — is gated behind a *secure context*. A page
 * served from `http://192.168.1.42:8321` is not one, so screen sharing is refused
 * before the user is even asked. Serving the same page over HTTPS fixes that; the
 * browser shows a one-time "not private" interstitial because the certificate is
 * self-signed, and everything works after the user proceeds.
 *
 * The certificate carries every current LAN IP as a subjectAltName, and is regenerated
 * whenever those addresses change — a SAN mismatch is the usual reason a browser
 * refuses to let the user click through at all.
 */
object TlsFactory {

    private const val ALIAS = "aircast"
    private const val STORE_NAME = "aircast-tls.p12"
    private const val SANS_NAME = "aircast-tls.sans"
    private val PASSWORD = "aircast-local".toCharArray()

    @Volatile
    private var cached: SSLContext? = null

    @Volatile
    private var cachedSans: String = ""

    /**
     * @return a factory bound to a certificate valid for the current addresses, or null
     *         when generation is unavailable — callers must degrade to plain HTTP.
     */
    fun serverSocketFactory(context: Context): ServerSocketFactory? {
        return try {
            sslContext(context)?.serverSocketFactory
        } catch (e: Exception) {
            Logger.e("tls", "cannot build TLS factory: ${e.message}")
            null
        }
    }

    @Synchronized
    private fun sslContext(context: Context): SSLContext? {
        val sans = currentSans()
        cached?.let { if (sans == cachedSans) return it }

        val storeFile = File(context.filesDir, STORE_NAME)
        val sansFile = File(context.filesDir, SANS_NAME)
        val storedSans = if (sansFile.exists()) sansFile.readText() else ""

        val keyStore: KeyStore = if (storeFile.exists() && storedSans == sans) {
            KeyStore.getInstance("PKCS12").apply {
                storeFile.inputStream().use { load(it, PASSWORD) }
            }
        } else {
            Logger.i("tls", "generating certificate for [$sans]")
            val created = generate(sans) ?: return null
            storeFile.outputStream().use { created.store(it, PASSWORD) }
            sansFile.writeText(sans)
            created
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, PASSWORD)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, SecureRandom())
        cached = ctx
        cachedSans = sans
        return ctx
    }

    private fun currentSans(): String =
        (Net.localIpv4Addresses() + listOf("127.0.0.1")).distinct().sorted().joinToString(",")

    private fun generate(sans: String): KeyStore? = try {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keyPair = generator.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + 3650L * 24 * 60 * 60 * 1000)
        val subject = X500Name("CN=AirCast Receiver, O=AirCast, C=US")

        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            notBefore,
            notAfter,
            subject,
            keyPair.public,
        )

        val names = ArrayList<GeneralName>()
        names.add(GeneralName(GeneralName.dNSName, "localhost"))
        for (ip in sans.split(',')) {
            if (ip.isNotBlank()) names.add(GeneralName(GeneralName.iPAddress, ip.trim()))
        }
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toTypedArray()))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment or KeyUsage.keyCertSign),
        )
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
        )

        // No explicit provider: Android's built-in JCA supplies SHA256withRSA, and asking
        // for "BC" here collides with the trimmed BouncyCastle that ships with the OS.
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        val certificate: X509Certificate =
            JcaX509CertificateConverter().getCertificate(builder.build(signer))

        KeyStore.getInstance("PKCS12").apply {
            load(null, PASSWORD)
            setKeyEntry(ALIAS, keyPair.private, PASSWORD, arrayOf(certificate))
        }
    } catch (e: Exception) {
        Logger.e("tls", "certificate generation failed: ${e.message}")
        null
    }

    /** Forces a fresh certificate on the next call — used when the network changes. */
    fun invalidate() {
        cached = null
        cachedSans = ""
    }

    /** SHA-256 fingerprint, shown in the UI so the user can verify the interstitial. */
    fun fingerprint(context: Context): String? = try {
        val storeFile = File(context.filesDir, STORE_NAME)
        if (!storeFile.exists()) null else {
            val ks = KeyStore.getInstance("PKCS12").apply {
                storeFile.inputStream().use { load(it, PASSWORD) }
            }
            val cert = ks.getCertificate(ALIAS) as? X509Certificate
            cert?.encoded?.let { encoded ->
                java.security.MessageDigest.getInstance("SHA-256").digest(encoded)
                    .joinToString(":") { "%02X".format(it) }
            }
        }
    } catch (_: Exception) {
        null
    }
}
