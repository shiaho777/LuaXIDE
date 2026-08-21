package dev.luaxide.build

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

object SelfSignedCert {

    fun create(pair: KeyPair, dn: String, days: Int = 3650): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 86_400_000L)
        val notAfter = Date(now + days.toLong() * 86_400_000L)
        val serial = BigInteger.valueOf(now)
        val subject = X500Principal(dn)
        val tbs = encodeTbs(serial, subject, notBefore, notAfter, pair.public.encoded)
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(pair.private)
        sig.update(tbs)
        val signature = sig.sign()
        val certDer = encodeCertificate(tbs, signature)
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    private fun encodeTbs(
        serial: BigInteger,
        subject: X500Principal,
        notBefore: Date,
        notAfter: Date,
        publicKeyInfo: ByteArray,
    ): ByteArray {
        val version = derContext(0, derInteger(BigInteger.valueOf(2)))
        val serialDer = derInteger(serial)
        val sigAlg = sha256WithRsaAlgId()
        val issuer = subject.encoded
        val validity = derSequence(derUtcTime(notBefore) + derUtcTime(notAfter))
        val subjectDer = subject.encoded
        val spki = publicKeyInfo
        return derSequence(version + serialDer + sigAlg + issuer + validity + subjectDer + spki)
    }

    private fun encodeCertificate(tbs: ByteArray, signature: ByteArray): ByteArray {
        val sigAlg = sha256WithRsaAlgId()
        val sigBits = derBitString(signature)
        return derSequence(tbs + sigAlg + sigBits)
    }

    private fun sha256WithRsaAlgId(): ByteArray {
        val oid = derOid(intArrayOf(1, 2, 840, 113549, 1, 1, 11))
        return derSequence(oid + byteArrayOf(0x05, 0x00))
    }

    private fun derUtcTime(date: Date): ByteArray {
        val sdf = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val s = sdf.format(date).toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x17, s.size.toByte()) + s
    }

    private fun derInteger(v: BigInteger): ByteArray {
        var bytes = v.toByteArray()
        return der(0x02, bytes)
    }

    private fun derBitString(bits: ByteArray): ByteArray {
        return der(0x03, byteArrayOf(0x00) + bits)
    }

    private fun derOid(parts: IntArray): ByteArray {
        val out = ArrayList<Byte>()
        out.add((40 * parts[0] + parts[1]).toByte())
        for (i in 2 until parts.size) {
            var v = parts[i]
            val stack = ArrayList<Byte>()
            stack.add((v and 0x7f).toByte())
            v = v ushr 7
            while (v > 0) {
                stack.add(((v and 0x7f) or 0x80).toByte())
                v = v ushr 7
            }
            for (j in stack.size - 1 downTo 0) out.add(stack[j])
        }
        return der(0x06, out.toByteArray())
    }

    private fun derContext(tag: Int, content: ByteArray): ByteArray =
        der(0xa0 or tag, content)

    private fun derSequence(content: ByteArray): ByteArray = der(0x30, content)

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val len = derLength(content.size)
        return byteArrayOf(tag.toByte()) + len + content
    }

    private fun derLength(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
    }
}
