package dev.luaxide.build

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.UUID

data class KeystoreInfo(
    val id: String,
    val alias: String,
    val displayName: String,
    val fileName: String,
    val sha256: String,
    val createdAt: Long,
)

class KeystoreManager(private val context: Context) {

    private val dir = File(context.filesDir, "signing/release").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")

    private val secrets: SharedPreferences by lazy {
        val master = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "luax_keystore_secrets",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun list(): List<KeystoreInfo> = readIndex()

    fun getIdentity(id: String): SigningIdentity {
        val info = readIndex().firstOrNull { it.id == id }
            ?: throw IllegalStateException("keystore not found: $id")
        val storePass = secrets.getString(passKey(id, "store"), null)
            ?: throw IllegalStateException("missing store password for $id")
        val keyPass = secrets.getString(passKey(id, "key"), null) ?: storePass
        val file = File(dir, info.fileName)
        if (!file.isFile) throw IllegalStateException("keystore file missing: ${info.fileName}")
        return loadIdentity(file, info.alias, storePass, keyPass)
    }

    fun importKeystore(
        source: File,
        alias: String,
        storePassword: String,
        keyPassword: String,
        displayName: String,
    ): KeystoreInfo {
        val identity = loadIdentity(source, alias, storePassword, keyPassword)
        val id = UUID.randomUUID().toString().take(8)
        val destName = "ks_$id.jks"
        val dest = File(dir, destName)
        val tmp = File(dir, ".$destName.tmp")
        source.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        secrets.edit()
            .putString(passKey(id, "store"), storePassword)
            .putString(passKey(id, "key"), keyPassword)
            .apply()
        val info = KeystoreInfo(
            id = id,
            alias = alias,
            displayName = displayName.ifBlank { alias },
            fileName = destName,
            sha256 = fingerprint(identity.certificates.first()),
            createdAt = System.currentTimeMillis(),
        )
        writeIndex(readIndex() + info)
        return info
    }

    fun createKeystore(
        alias: String,
        storePassword: String,
        keyPassword: String,
        displayName: String,
        cn: String = "LuaXIDE Release",
    ): KeystoreInfo {
        val id = UUID.randomUUID().toString().take(8)
        val destName = "ks_$id.jks"
        val dest = File(dir, destName)
        val tmp = File(dir, ".$destName.tmp")
        generateJks(tmp, alias, storePassword, keyPassword, cn)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        secrets.edit()
            .putString(passKey(id, "store"), storePassword)
            .putString(passKey(id, "key"), keyPassword)
            .apply()
        val identity = loadIdentity(dest, alias, storePassword, keyPassword)
        val info = KeystoreInfo(
            id = id,
            alias = alias,
            displayName = displayName.ifBlank { alias },
            fileName = destName,
            sha256 = fingerprint(identity.certificates.first()),
            createdAt = System.currentTimeMillis(),
        )
        writeIndex(readIndex() + info)
        return info
    }

    fun exportBackup(id: String, dest: File): KeystoreInfo {
        val info = readIndex().firstOrNull { it.id == id }
            ?: throw IllegalStateException("keystore not found: $id")
        val source = File(dir, info.fileName)
        if (!source.isFile) throw IllegalStateException("keystore file missing")
        val tmp = File(dest.parentFile, ".${dest.name}.tmp")
        source.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        val meta = JSONObject()
            .put("alias", info.alias)
            .put("displayName", info.displayName)
            .put("sha256", info.sha256)
            .put("createdAt", info.createdAt)
            .put("note", "passwords are not exported; keep them separately")
        val metaFile = File(dest.parentFile, dest.nameWithoutExtension + ".meta.json")
        val metaTmp = File(dest.parentFile, ".${metaFile.name}.tmp")
        metaTmp.writeText(meta.toString(2))
        if (!metaTmp.renameTo(metaFile)) {
            metaFile.writeText(meta.toString(2))
            metaTmp.delete()
        }
        return info
    }

    fun keystoreFile(id: String): File? {
        val info = readIndex().firstOrNull { it.id == id } ?: return null
        val f = File(dir, info.fileName)
        return f.takeIf { it.isFile }
    }

    fun delete(id: String) {
        val list = readIndex()
        val info = list.firstOrNull { it.id == id } ?: return
        File(dir, info.fileName).delete()
        secrets.edit()
            .remove(passKey(id, "store"))
            .remove(passKey(id, "key"))
            .apply()
        writeIndex(list.filterNot { it.id == id })
    }

    fun debugIdentity(): SigningIdentity = DebugKeystore.getOrCreate(context)

    private fun passKey(id: String, kind: String) = "ks.$id.$kind"

    private fun loadIdentity(
        file: File,
        alias: String,
        storePassword: String,
        keyPassword: String,
    ): SigningIdentity {
        val ks = openStore(file, storePassword)
        val key = ks.getKey(alias, keyPassword.toCharArray()) as? PrivateKey
            ?: throw IllegalStateException("alias '$alias' not found or wrong key password")
        val chain = ks.getCertificateChain(alias)?.map { it as X509Certificate }
            ?: listOf(ks.getCertificate(alias) as X509Certificate)
        return SigningIdentity(
            keystore = file,
            storePassword = storePassword,
            keyPassword = keyPassword,
            alias = alias,
            privateKey = key,
            certificates = chain,
        )
    }

    private fun openStore(file: File, storePassword: String): KeyStore {
        val errors = mutableListOf<String>()
        for (type in listOf("PKCS12", "JKS", KeyStore.getDefaultType())) {
            runCatching {
                val ks = KeyStore.getInstance(type)
                FileInputStream(file).use { ks.load(it, storePassword.toCharArray()) }
                return ks
            }.onFailure { errors += "$type: ${it.message}" }
        }
        throw IllegalStateException("cannot open keystore: ${errors.joinToString("; ")}")
    }

    private fun generateJks(
        dest: File,
        alias: String,
        storePassword: String,
        keyPassword: String,
        cn: String,
    ) {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        val cert = SelfSignedCert.create(pair, "CN=$cn,O=LuaXIDE,C=US")
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, storePassword.toCharArray())
        ks.setKeyEntry(alias, pair.private, keyPassword.toCharArray(), arrayOf(cert))
        FileOutputStream(dest).use { ks.store(it, storePassword.toCharArray()) }
    }

    private fun fingerprint(cert: X509Certificate): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val dig = md.digest(cert.encoded)
        return dig.joinToString(":") { "%02X".format(it) }
    }

    private fun readIndex(): List<KeystoreInfo> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        KeystoreInfo(
                            id = o.getString("id"),
                            alias = o.getString("alias"),
                            displayName = o.optString("displayName", o.getString("alias")),
                            fileName = o.getString("fileName"),
                            sha256 = o.optString("sha256", ""),
                            createdAt = o.optLong("createdAt", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(list: List<KeystoreInfo>) {
        val arr = JSONArray()
        list.forEach { info ->
            arr.put(
                JSONObject()
                    .put("id", info.id)
                    .put("alias", info.alias)
                    .put("displayName", info.displayName)
                    .put("fileName", info.fileName)
                    .put("sha256", info.sha256)
                    .put("createdAt", info.createdAt),
            )
        }
        val tmp = File(dir, ".index.json.tmp")
        tmp.writeText(arr.toString(2))
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(arr.toString(2))
            tmp.delete()
        }
    }
}
