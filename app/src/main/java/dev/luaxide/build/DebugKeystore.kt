package dev.luaxide.build

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

data class SigningIdentity(
    val keystore: File,
    val storePassword: String,
    val keyPassword: String,
    val alias: String,
    val privateKey: PrivateKey,
    val certificates: List<X509Certificate>,
)

object DebugKeystore {
    private const val STORE_NAME = "luax_debug.p12"
    private const val LEGACY_JKS = "luax_debug.jks"
    private const val ALIAS = "luaxdebug"
    private const val PASSWORD = "android"
    private const val MARKER = ".debug-v2"

    fun getOrCreate(context: Context): SigningIdentity {
        val dir = File(context.filesDir, "signing").apply { mkdirs() }
        File(dir, LEGACY_JKS).delete()
        val storeFile = File(dir, STORE_NAME)
        val marker = File(dir, MARKER)
        if (!storeFile.isFile || !marker.exists()) {
            createPkcs12(storeFile)
            marker.writeText("pkcs12")
        }
        return load(storeFile)
    }

    private fun createPkcs12(dest: File) {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        val cert = SelfSignedCert.create(pair, "CN=LuaX Debug,O=LuaXIDE,C=US")
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, PASSWORD.toCharArray())
        ks.setKeyEntry(ALIAS, pair.private, PASSWORD.toCharArray(), arrayOf(cert))
        val tmp = File(dest.parentFile, ".${dest.name}.tmp")
        FileOutputStream(tmp).use { ks.store(it, PASSWORD.toCharArray()) }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun load(storeFile: File): SigningIdentity {
        val errors = mutableListOf<String>()
        for (type in listOf("PKCS12", KeyStore.getDefaultType(), "JKS")) {
            val identity: SigningIdentity? = runCatching {
                val ks = KeyStore.getInstance(type)
                FileInputStream(storeFile).use { ks.load(it, PASSWORD.toCharArray()) }
                val key = ks.getKey(ALIAS, PASSWORD.toCharArray()) as? PrivateKey
                    ?: error("alias missing")
                val chain = ks.getCertificateChain(ALIAS)?.map { it as X509Certificate }
                    ?: listOf(ks.getCertificate(ALIAS) as X509Certificate)
                SigningIdentity(
                    keystore = storeFile,
                    storePassword = PASSWORD,
                    keyPassword = PASSWORD,
                    alias = ALIAS,
                    privateKey = key,
                    certificates = chain,
                )
            }.onFailure { errors += "$type: ${it.message}" }.getOrNull()
            if (identity != null) return identity
        }
        storeFile.delete()
        createPkcs12(storeFile)
        File(storeFile.parentFile, MARKER).writeText("pkcs12")
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(storeFile).use { ks.load(it, PASSWORD.toCharArray()) }
        val key = ks.getKey(ALIAS, PASSWORD.toCharArray()) as PrivateKey
        val chain = ks.getCertificateChain(ALIAS).map { it as X509Certificate }
        return SigningIdentity(
            keystore = storeFile,
            storePassword = PASSWORD,
            keyPassword = PASSWORD,
            alias = ALIAS,
            privateKey = key,
            certificates = chain,
        )
    }
}
