package cloudstream.desktop.app

import cloudstream.desktop.app.sync.SecretBox
import cloudstream.desktop.app.sync.ServiceStore
import cloudstream.desktop.contracts.fakes.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretBoxTest {
    init { SecretBox.keyFile = java.nio.file.Files.createTempDirectory("keys").resolve("secrets.key").toFile() }

    @Test fun `logins are stored encrypted and read back`() {
        val kv = InMemoryKeyValueStore()
        val s = ServiceStore(kv, "sync/anilist")
        s["token"] = "very-secret-token"
        val raw = kv.get("sync/anilist/token", String::class.java)!!
        assertTrue(SecretBox.isSealed(raw)); assertFalse("very-secret-token" in raw)
        assertEquals("very-secret-token", s["token"])
    }

    @Test fun `old plain values still work and get encrypted on first read`() {
        val kv = InMemoryKeyValueStore()
        kv.set("sync/simkl/token", "plain-old")
        val s = ServiceStore(kv, "sync/simkl")
        assertEquals("plain-old", s["token"])
        assertTrue(SecretBox.isSealed(kv.get("sync/simkl/token", String::class.java)!!))
        assertEquals("plain-old", s["token"])
    }

    @Test fun `a damaged value reads as signed out, not as garbage`() {
        assertNull(SecretBox.open("dpapi:AAAA"))
        assertNull(SecretBox.open("aes:AAAA"))
    }
}
