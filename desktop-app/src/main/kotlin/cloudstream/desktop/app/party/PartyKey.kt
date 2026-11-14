package cloudstream.desktop.app.party

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The secret of a watch party room. The invite code IS the key: whoever has the code can read the room, the relay
 * only ever sees [roomId] (a one-way hash of the code) and AES-GCM ciphertext.
 *
 * Code: 20 Crockford base32 symbols (100 bits), shown as `WP-XXXXX-XXXXX-XXXXX-XXXXX`. Parsing is forgiving: case,
 * dashes, spaces, a `streamlet://party/` link prefix, and O/I/L typed for 0/1 are all accepted.
 */
class PartyKey private constructor(private val symbols: String) {
    val code: String get() = "WP-" + symbols.chunked(5).joinToString("-")
    val link: String get() = "streamlet://party/$code"

    /** What the relay routes by. Derived one-way from the code, so the relay cannot decrypt. */
    val roomId: String = hmac(symbols, "streamlet-party/room").joinToString("") { "%02x".format(it) }.take(32)
    private val aes = SecretKeySpec(hmac(symbols, "streamlet-party/aes"), "AES")

    /** `nonce(12) || ciphertext+tag`, bound to [roomId]. */
    fun seal(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(rng::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, iv))
        c.updateAAD(roomId.toByteArray())
        return iv + c.doFinal(plain)
    }

    /** null for anything not sealed with this key (wrong room, tampered, garbage). */
    fun open(frame: ByteArray): ByteArray? = runCatching {
        if (frame.size < 12 + 16) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(128, frame, 0, 12))
        c.updateAAD(roomId.toByteArray())
        c.doFinal(frame, 12, frame.size - 12)
    }.getOrNull()

    override fun equals(other: Any?) = other is PartyKey && other.symbols == symbols
    override fun hashCode() = symbols.hashCode()
    override fun toString() = "PartyKey(room=$roomId)" // never the code itself (logs)

    companion object {
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val LEN = 20
        private val rng = SecureRandom()

        fun random(): PartyKey = PartyKey(String(CharArray(LEN) { ALPHABET[rng.nextInt(ALPHABET.length)] }))

        fun parse(input: String): PartyKey? {
            var s = input.trim().uppercase().substringAfterLast('/').filter { it.isLetterOrDigit() }
            if (s.length == LEN + 2 && s.startsWith("WP")) s = s.drop(2)
            s = s.map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")
            return if (s.length == LEN && s.all { it in ALPHABET }) PartyKey(s) else null
        }

        private fun hmac(key: String, label: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }.doFinal(label.toByteArray())

        /** Short random member id (not secret). */
        fun newMemberId(): String = String(CharArray(10) { ALPHABET[rng.nextInt(ALPHABET.length)] })
    }
}
