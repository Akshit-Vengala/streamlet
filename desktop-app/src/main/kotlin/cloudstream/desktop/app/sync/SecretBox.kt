package cloudstream.desktop.app.sync

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption at rest for account logins (tokens, passwords, cookies), so an extension that reads the app's data
 * files only finds ciphertext.
 * - Windows: DPAPI (CryptProtectData), tied to the Windows user; decrypting needs a native call, which the extension
 *   safety check refuses (native code / JNA are hidden from extensions).
 * - Elsewhere: AES-GCM with a random key in `<data>/keys/secrets.key` (owner-only permissions). Weaker: the key is a
 *   file the same user can read.
 * Values look like `dpapi:<base64>` or `aes:<base64>`; anything else is an old plain value (migrated when read).
 */
object SecretBox {
    private const val DPAPI = "dpapi:"
    private const val AES = "aes:"
    private val entropy = "CloudStreamDesktop/accounts/v1".toByteArray()
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val b64 = Base64.getEncoder(); private val unb64 = Base64.getDecoder()

    /** Tests: a key file other than the app's. */
    @Volatile var keyFile: File = File(cloudstream.desktop.contracts.AppDirs.root, "keys/secrets.key")

    fun isSealed(v: String) = v.startsWith(DPAPI) || v.startsWith(AES)

    fun seal(plain: String): String {
        val bytes = plain.toByteArray(Charsets.UTF_8)
        if (windows) runCatching {
            return DPAPI + b64.encodeToString(com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(bytes, entropy, 0, "Streamlet", null))
        }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, iv)) }
        return AES + b64.encodeToString(iv + c.doFinal(bytes))
    }

    /** null when it can't be decrypted (another user / machine, damaged value). */
    fun open(v: String): String? = runCatching {
        when {
            v.startsWith(DPAPI) -> String(com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(unb64.decode(v.removePrefix(DPAPI)), entropy, 0, null), Charsets.UTF_8)
            v.startsWith(AES) -> {
                val all = unb64.decode(v.removePrefix(AES))
                val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, all, 0, 12)) }
                String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
            }
            else -> v
        }
    }.getOrNull()

    @Synchronized private fun key(): ByteArray {
        val f = keyFile
        if (f.isFile && f.length() == 32L) return f.readBytes()
        f.parentFile.mkdirs()
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        f.writeBytes(k)
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(f.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        }
        return k
    }
}
