package me.rerere.rikkahub.data.remote.p14

import android.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object P20E2EE {
    private val magic = "P20E2E1\n".toByteArray(Charsets.US_ASCII)
    private const val chunkSize = 8 * 1024 * 1024
    private val random = SecureRandom()

    data class EncryptResult(
        val ciphertext: File,
        val size: Long,
        val sha256: String,
    )

    fun encrypt(
        source: File,
        destination: File,
        recipientPublicKey: String,
        displayName: String,
        mimeType: String,
    ): EncryptResult {
        require(source.isFile) { "Attachment source is not a file" }
        val recipientRaw = decodeUrlSafe(recipientPublicKey)
        require(recipientRaw.size == 32) { "Invalid P14 receiver public key" }

        val recipient = X25519PublicKeyParameters(recipientRaw, 0)
        val ephemeral = X25519PrivateKeyParameters(random)
        val shared = ByteArray(32)
        ephemeral.generateSecret(recipient, shared, 0)
        val salt = randomBytes(16)
        val kek = hkdfSha256(shared, salt, "p20-cmcloud-e2ee-v1".toByteArray(), 32)
        val dek = randomBytes(32)
        val wrapNonce = randomBytes(12)
        val wrappedDek = aesGcmEncrypt(kek, wrapNonce, dek, magic + ":keywrap".toByteArray())
        val metaNonce = randomBytes(12)
        val metadata = buildJsonObject {
            put("filename", displayName)
            put("mime", mimeType)
        }.toString().toByteArray(Charsets.UTF_8)
        val encryptedMeta = aesGcmEncrypt(dek, metaNonce, metadata, magic + ":meta".toByteArray())
        val noncePrefix = randomBytes(8)
        val plaintextSize = source.length()
        val chunks = maxOf(1L, (plaintextSize + chunkSize - 1) / chunkSize).toInt()
        val ephemeralPublic = ByteArray(32).also { ephemeral.generatePublicKey().encode(it, 0) }

        val header = buildJsonObject {
            put("v", 1)
            put("alg", "A256GCM-chunked")
            put("chunk_size", chunkSize)
            put("plaintext_size", plaintextSize)
            put("chunks", chunks)
            put("nonce_prefix", encodeUrlSafe(noncePrefix))
            put("key_wrap", buildJsonObject {
                put("alg", "X25519-HKDF-SHA256+A256GCM")
                put("ephemeral_public", encodeUrlSafe(ephemeralPublic))
                put("salt", encodeUrlSafe(salt))
                put("nonce", encodeUrlSafe(wrapNonce))
                put("wrapped_dek", encodeUrlSafe(wrappedDek))
            })
            put("meta_nonce", encodeUrlSafe(metaNonce))
            put("encrypted_meta", encodeUrlSafe(encryptedMeta))
        }.toString().toByteArray(Charsets.UTF_8)

        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileInputStream(source).use { rawInput ->
                BufferedInputStream(rawInput, 1024 * 1024).use { input ->
                    FileOutputStream(temp).use { rawOutput ->
                        DigestOutputStream(rawOutput, digest).use { digestOutput ->
                            DataOutputStream(BufferedOutputStream(digestOutput, 1024 * 1024)).use { output ->
                                output.write(magic)
                                output.writeInt(header.size)
                                output.write(header)
                                val buffer = ByteArray(chunkSize)
                                repeat(chunks) { index ->
                                    val expected = if (plaintextSize == 0L) 0 else minOf(chunkSize.toLong(), plaintextSize - index.toLong() * chunkSize).toInt()
                                    var read = 0
                                    while (read < expected) {
                                        val count = input.read(buffer, read, expected - read)
                                        check(count >= 0) { "Attachment ended before expected plaintext size" }
                                        read += count
                                    }
                                    val plaintext = if (expected == 0) ByteArray(0) else buffer.copyOf(expected)
                                    val nonce = noncePrefix + int32(index)
                                    val aad = magic + ":chunk:".toByteArray() + int64(plaintextSize) + int32(index)
                                    output.write(aesGcmEncrypt(dek, nonce, plaintext, aad))
                                }
                                output.flush()
                                rawOutput.fd.sync()
                            }
                        }
                    }
                }
            }
            if (destination.exists()) destination.delete()
            check(temp.renameTo(destination)) { "Failed to finalize encrypted attachment" }
        } finally {
            temp.delete()
        }
        return EncryptResult(destination, destination.length(), digest.digest().toHex())
    }

    private fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    private fun hkdfSha256(input: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = extract.doFinal(input)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val expand = Mac.getInstance("HmacSHA256")
            expand.init(SecretKeySpec(prk, "HmacSHA256"))
            expand.update(previous)
            expand.update(info)
            expand.update(counter.toByte())
            previous = expand.doFinal()
            val count = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, 0, count)
            offset += count
            counter += 1
        }
        return output
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    private fun encodeUrlSafe(value: ByteArray): String =
        Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decodeUrlSafe(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun int32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun int64(value: Long): ByteArray = ByteArray(8) { index ->
        (value ushr (56 - index * 8)).toByte()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
