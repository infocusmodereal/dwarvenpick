package com.dwarvenpick.app.datasource

import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedCredential(
    val keyId: String,
    val ciphertext: String,
)

@Service
class DatasourceCredentialCryptoService(
    private val credentialEncryptionProperties: CredentialEncryptionProperties,
) {
    private val secureRandom = SecureRandom()

    fun encryptPassword(plaintext: String): EncryptedCredential {
        val secretKey = deriveAesKey()
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val parameterSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(ciphertext, 0, payload, iv.size, ciphertext.size)

        return EncryptedCredential(
            keyId = credentialEncryptionProperties.activeKeyId,
            ciphertext = Base64.getEncoder().encodeToString(payload),
        )
    }

    fun decryptPassword(encryptedCredential: EncryptedCredential): String {
        val payload = Base64.getDecoder().decode(encryptedCredential.ciphertext)
        require(payload.size > 12) { "Encrypted payload is invalid." }

        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val parameterSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, deriveAesKey(), parameterSpec)

        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    fun reencrypt(encryptedCredential: EncryptedCredential): EncryptedCredential = encryptPassword(decryptPassword(encryptedCredential))

    fun activeKeyId(): String = credentialEncryptionProperties.activeKeyId

    private fun deriveAesKey(): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(credentialEncryptionProperties.masterKey.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(hash.copyOfRange(0, 16), "AES")
    }
}
