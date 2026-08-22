package com.saas.apkeditorplus

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*

class KeyStoreManager(private val context: Context) {

    data class KeyAliasInfo(
        val alias: String,
        val subject: String,
        val validUntil: Date?
    )

    private val keyStoreDir = File(context.filesDir, "keystores")

    init {
        if (!keyStoreDir.exists()) {
            keyStoreDir.mkdirs()
        }
        // Remove existing "BC" provider and insert ours at the top to avoid conflicts with Android's built-in version
        Security.removeProvider("BC")
        Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
    }

    fun createKeyStore(
        fileName: String,
        password: CharArray,
        alias: String,
        commonName: String,
        orgUnit: String,
        orgName: String,
        locality: String,
        state: String,
        country: String,
        keyPassword: CharArray = password
    ): File {
        val file = File(keyStoreDir, if (fileName.endsWith(".jks")) fileName else "$fileName.jks")
        
        val keyStore = KeyStore.getInstance("PKCS12") 
        keyStore.load(null, null)

        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()

        val cert = generateSelfSignedCertificate(
            keyPair, 
            "CN=$commonName, OU=$orgUnit, O=$orgName, L=$locality, ST=$state, C=$country"
        )

        keyStore.setKeyEntry(alias, keyPair.private, keyPassword, arrayOf(cert))

        FileOutputStream(file).use { keyStore.store(it, password) }
        
        return file
    }

    private fun generateSelfSignedCertificate(keyPair: KeyPair, dn: String): X509Certificate {
        val issuer = X500Name(dn)
        val serialNumber = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date()
        val notAfter = Date(notBefore.time + 3650L * 24 * 60 * 60 * 1000) // 10 anos

        val builder = JcaX509v3CertificateBuilder(
            issuer,
            serialNumber,
            notBefore,
            notAfter,
            issuer,
            keyPair.public
        )

        val contentSigner = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        // Use the BC provider specifically to ensure we use our registered version
        return JcaX509CertificateConverter()
            .setProvider(Security.getProvider("BC"))
            .getCertificate(builder.build(contentSigner))
    }

    fun listKeyStores(): List<File> {
        return keyStoreDir.listFiles()?.filter(File::isFile)?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    fun importKeyStore(displayName: String, bytes: ByteArray): File {
        require(bytes.isNotEmpty()) { "Arquivo de chave vazio" }
        val safeName = displayName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "imported_${System.currentTimeMillis()}.p12" }
        val target = generateSequence(File(keyStoreDir, safeName)) { current ->
            val base = safeName.substringBeforeLast('.', safeName)
            val extension = safeName.substringAfterLast('.', "")
            val index = current.nameWithoutExtension.substringAfterLast('_').toIntOrNull()?.plus(1) ?: 1
            File(keyStoreDir, if (extension.isBlank()) "${base}_$index" else "${base}_$index.$extension")
        }.first { !it.exists() }
        target.writeBytes(bytes)
        return target
    }

    fun inspectKeyStore(file: File, password: CharArray): List<KeyAliasInfo> {
        val keyStore = loadKeyStore(file, password)
        return keyStore.aliases().toList()
            .filter(keyStore::isKeyEntry)
            .map { alias ->
                val certificate = keyStore.getCertificate(alias) as? X509Certificate
                KeyAliasInfo(
                    alias = alias,
                    subject = certificate?.subjectX500Principal?.name.orEmpty(),
                    validUntil = certificate?.notAfter
                )
            }
    }

    companion object {
        fun loadKeyStore(file: File, password: CharArray): KeyStore {
            var lastError: Throwable? = null
            for (type in linkedSetOf("PKCS12", KeyStore.getDefaultType(), "JKS")) {
                try {
                    return KeyStore.getInstance(type).also { keyStore ->
                        file.inputStream().use { keyStore.load(it, password) }
                    }
                } catch (error: Throwable) {
                    lastError = error
                }
            }
            throw GeneralSecurityException("Formato de chave ou senha inválidos", lastError)
        }
    }

    fun getTestKey(): File {
        val testKeyFile = File(keyStoreDir, "testkey.jks")
        if (!testKeyFile.exists()) {
            createKeyStore(
                "testkey.jks",
                "testkey".toCharArray(),
                "testkey",
                "Test Key",
                "Android",
                "ApkEditorPlus",
                "World",
                "Internet",
                "US"
            )
        }
        return testKeyFile
    }
}
