package io.max.paperplay.core.sync

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class LanCryptoTest {
    private fun hexOf(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hexStr(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test fun x25519MatchesRfc7748() {
        val a = hexOf("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val b = hexOf("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hexStr(X25519.publicKey(a)))
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", hexStr(X25519.publicKey(b)))
        val expected = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(expected, hexStr(X25519.shared(a, X25519.publicKey(b))))
        assertEquals(expected, hexStr(X25519.shared(b, X25519.publicKey(a))))
    }

    /** Appendix B.1 of draft-irtf-cfrg-cpace-14. */
    @Test fun cpaceMatchesTheDraft() {
        val sid = hexOf("7e4b4791d6a8ef019b936c79fb7f2c57")
        val ci = "oc\u000bB_responder\u000bA_initiator"
        val pw = "Password".encodeToByteArray()
        assertEquals("64e8099e3ea682cfdc5cb665c057ebb514d06bf23ebc9f743b51b82242327074", hexStr(CPace.generator(pw, ci.encodeToByteArray(), sid)))
        val a = CPace(pw, ci, sid, hexOf("21b4f4bd9e64ed355c3eb676a28ebedaf6d8f17bdc365995b319097153044080"))
        val b = CPace(pw, ci, sid, hexOf("848b0779ff415f0af4ea14df9dd1d3c29ac41d836c7808896c4eba19c51ac40a"))
        assertEquals("1b02dad6dbd29a07b6d28c9e04cb2f184f0734350e32bb7e62ff9dbcfdb63d15", hexStr(a.share))
        assertEquals("20cda5955f82c4931545bcbf40758ce1010d7db4db2a907013d79c7a8fcf957f", hexStr(b.share))
        val isk = "a051ee5ee2499d16da3f69f430218b8ea94a18a45b67f9e86495b382c33d14a5c38cecc0cc834f960e39e0d1bf7d76b9ef5d54eecc5e0f386c97ad12da8c3d5f"
        val adA = "ADa".encodeToByteArray()
        val adB = "ADb".encodeToByteArray()
        assertEquals(isk, hexStr(a.key(b.share, true, adA, adB)))
        assertEquals(isk, hexStr(b.key(a.share, false, adA, adB)))
        // Another password, with the same private key, gives another one.
        val other = CPace("Passwore".encodeToByteArray(), ci, sid, hexOf("21b4f4bd9e64ed355c3eb676a28ebedaf6d8f17bdc365995b319097153044080"))
        assertNotEquals(hexStr(b.key(other.share, false)), hexStr(other.key(b.share, true)))
    }

    @Test fun cpaceRefusesAShareThatFixesTheKey() {
        val c = CPace("123456".encodeToByteArray(), "x", ByteArray(32))
        for (bad in listOf(ByteArray(32), ByteArray(32).also { it[0] = 1 }, ByteArray(31))) assertFailsWith<java.io.IOException> { c.key(bad, true) }
    }

    /** Runs a server handshake on a thread; returns the pair of channels and the raw bytes the client wrote. */
    private class Link(val client: SecureChannel, val server: SecureChannel, val clientT: ByteArray, val serverT: ByteArray,
                       val wire: ByteArrayOutputStream, val rawOut: DataOutputStream, val sockets: List<Socket>)

    private fun link(): Link {
        val ss = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        var srv: Handshake? = null
        var sock: Socket? = null
        val th = thread {
            sock = ss.accept()
            srv = serverHandshake(DataInputStream(sock!!.getInputStream()), DataOutputStream(sock!!.getOutputStream()))
        }
        val c = Socket(java.net.InetAddress.getLoopbackAddress(), ss.localPort)
        val wire = ByteArrayOutputStream()
        val tee = object : OutputStream() {
            override fun write(b: Int) { wire.write(b); c.getOutputStream().write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { wire.write(b, off, len); c.getOutputStream().write(b, off, len) }
            override fun flush() { c.getOutputStream().flush() }
        }
        val out = DataOutputStream(tee)
        val ch = clientHandshake(DataInputStream(c.getInputStream()), out)
        th.join(5000)
        ss.close()
        return Link(ch.channel, srv!!.channel, ch.transcript, srv!!.transcript, wire, out, listOf(c, sock!!))
    }

    @Test fun handshakeRoundTripBothDirections() {
        val l = link()
        assertContentEquals(l.clientT, l.serverT)
        l.client.send("hello".encodeToByteArray())
        assertEquals("hello", l.server.receive().decodeToString())
        l.server.send("back".encodeToByteArray())
        l.server.send("again".encodeToByteArray())
        assertEquals("back", l.client.receive().decodeToString())
        assertEquals("again", l.client.receive().decodeToString())
        l.sockets.forEach { it.close() }
    }

    @Test fun freshKeysPerConnection() {
        val a = link(); val b = link()
        assertNotEquals(hexStr(a.clientT), hexStr(b.clientT))
        a.sockets.forEach { it.close() }; b.sockets.forEach { it.close() }
    }

    @Test fun replayedFrameIsRejected() {
        val l = link()
        val before = l.wire.size()
        l.client.send("pay".encodeToByteArray())
        val frame = l.wire.toByteArray().copyOfRange(before, l.wire.size())
        assertEquals("pay", l.server.receive().decodeToString())
        l.rawOut.write(frame); l.rawOut.flush() // the same bytes again
        assertFailsWith<Exception> { l.server.receive() }
        l.sockets.forEach { it.close() }
    }
}
