package com.saas.apkeditorplus

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

class ApkSignerManager {

    interface SignerListener {
        fun onStart()
        fun onProgress(message: String)
        fun onSuccess()
        fun onError(message: String)
    }

    /**
     * Assina um APK usando uma KeyStore
     */
    fun signApk(
        inputApk: File,
        outputApk: File,
        keyStoreFile: File,
        keyStorePassword: CharArray,
        keyAlias: String,
        keyPassword: CharArray,
        enableV1: Boolean = true,
        enableV2: Boolean = true,
        enableV3: Boolean = true,
        enableV4: Boolean = false,
        listener: SignerListener? = null
    ): Boolean {
        return try {
            val effectiveV3 = enableV3 || enableV4
            require(enableV1 || enableV2 || effectiveV3) { "Ative ao menos um esquema de assinatura" }
            listener?.onStart()
            
            listener?.onProgress("Carregando KeyStore...")
            val ks = KeyStoreManager.loadKeyStore(keyStoreFile, keyStorePassword)
            
            // Se o alias for vazio, tenta pegar o primeiro disponível
            listener?.onProgress("Identificando alias...")
            val alias = if (keyAlias.isNotEmpty()) {
                keyAlias
            } else {
                ks.aliases().toList().firstOrNull(ks::isKeyEntry)
                    ?: error("Nenhuma chave privada encontrada")
            }
            
            listener?.onProgress("Recuperando chave privada...")
            require(ks.isKeyEntry(alias)) { "Alias de chave privada não encontrado: $alias" }
            val privateKey = ks.getKey(alias, keyPassword) as? PrivateKey
                ?: error("A entrada $alias não contém uma chave privada")
            val certificates = ks.getCertificateChain(alias)
                ?.map { it as X509Certificate }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(ks.getCertificate(alias) as X509Certificate)
            
            listener?.onProgress("Configurando assinador...")
            val signerConfig = ApkSigner.SignerConfig.Builder(
                "CERT",
                privateKey,
                certificates
            ).build()

            val apkSigner = ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setV1SigningEnabled(enableV1)
                .setV2SigningEnabled(enableV2)
                .setV3SigningEnabled(effectiveV3)
                .setV4SigningEnabled(enableV4)
                .setV4SignatureOutputFile(File(outputApk.absolutePath + ".idsig"))
                .build()

            listener?.onProgress("Assinando arquivo...")
            apkSigner.sign()

            listener?.onProgress("Verificando assinatura...")
            val verification = ApkVerifier.Builder(outputApk).build().verify()
            check(verification.isVerified) {
                verification.errors.joinToString("; ").ifBlank { "Assinatura gerada não é válida" }
            }
            
            listener?.onSuccess()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            listener?.onError(e.message ?: "Erro desconhecido")
            false
        }
    }
}
