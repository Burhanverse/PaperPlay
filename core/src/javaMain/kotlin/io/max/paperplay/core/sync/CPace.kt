package io.max.paperplay.core.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/**
 * One side of CPace (draft-irtf-cfrg-cpace, X25519 with SHA-512): both sides get the same key only when they hold the same
 * secret, and whoever answers gets one guess per connection. A short PIN can not be tried out offline from what was sent.
 * [info] names the use, [sid] is the handshake of this link.
 */
internal class CPace(secret: ByteArray, info: String, private val sid: ByteArray, private val priv: ByteArray = X25519.newPrivateKey()) {
    /** Sent to the other side. */
    val share: ByteArray = X25519.shared(priv, generator(secret, info.encodeToByteArray(), sid))

    /** The common key. [first] is true on the side that sent its share first; [ad1] and [ad2] are what the first and second side add to it. */
    fun key(peer: ByteArray, first: Boolean, ad1: ByteArray = ByteArray(0), ad2: ByteArray = ByteArray(0)): ByteArray {
        if (peer.size != 32) throw IOException("bad share")
        val k = X25519.shared(priv, peer)
        if (k.all { it == 0.toByte() }) throw IOException("bad share")
        val (a, b) = if (first) share to peer else peer to share
        return sha512(lvCat("CPace255_ISK".encodeToByteArray(), sid, k), lvCat(a, ad1), lvCat(b, ad2))
    }

    companion object {
        private val P = X25519.P
        private val A = BigInteger.valueOf(486662)
        private val TWO = BigInteger.valueOf(2)

        private fun sha512(vararg parts: ByteArray): ByteArray {
            val d = MessageDigest.getInstance("SHA-512")
            for (p in parts) d.update(p)
            return d.digest()
        }

        private fun lvCat(vararg parts: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            for (p in parts) {
                var n = p.size
                while (n >= 128) { out.write((n and 0x7f) or 0x80); n = n shr 7 }
                out.write(n)
                out.write(p)
            }
            return out.toByteArray()
        }

        fun generator(secret: ByteArray, info: ByteArray, sid: ByteArray): ByteArray {
            val dsi = "CPace255".encodeToByteArray()
            val pad = ByteArray(maxOf(0, 128 - 1 - lvCat(secret).size - lvCat(dsi).size))
            val h = sha512(lvCat(dsi, secret, pad, info, sid)).copyOf(32)
            h[31] = (h[31].toInt() and 127).toByte()
            return X25519.encode(elligator2(X25519.decode(h).mod(P)))
        }

        private fun elligator2(r: BigInteger): BigInteger {
            val v = A.negate().multiply(BigInteger.ONE.add(TWO.multiply(r).multiply(r)).modInverse(P)).mod(P)
            val w = v.multiply(v.multiply(v).add(A.multiply(v)).add(BigInteger.ONE)).mod(P)
            return if (w.modPow(P.subtract(BigInteger.ONE).shiftRight(1), P) == BigInteger.ONE) v else v.negate().subtract(A).mod(P)
        }
    }
}

/** Codes one device accepts at a time (a PIN and a password), so a fake one gets no more guesses out of one connection. */
private const val MAX_CODES = 2

private fun b64(b: ByteArray) = JsonPrimitive(Base64.getEncoder().encodeToString(b))
private fun b64d(s: String): ByteArray = Base64.getDecoder().decode(s)
private fun JsonObject.byteList(key: String): List<ByteArray> = arr(key).orEmpty().map { b64d((it as? JsonPrimitive)?.content ?: throw IOException("bad message")) }

/** The server's part after the first message, whose share is [peer]: for each of [secrets] its key and the proof the client gave for it. */
internal fun pakeServer(ch: SecureChannel, peer: ByteArray, secrets: List<String>, info: String, t: ByteArray): Map<String, Pair<ByteArray, ByteArray>> {
    val own = secrets.take(MAX_CODES).map { it to CPace(it.encodeToByteArray(), info, t) }
    val keys = own.map { (s, c) -> s to c.key(peer, first = false) }
    ch.send(buildJsonObject { put("t", "pake"); put("pk", JsonArray(own.map { (_, c) -> b64(c.share) })) }.toString().encodeToByteArray())
    val m = wireJson.parseToJsonElement(ch.receive(MAX_STRANGER_FRAME).decodeToString()) as? JsonObject
    if (m?.str("t") != "pake-proof") throw IOException("bad message")
    val proofs = m.byteList("proofs")
    if (proofs.size != keys.size) throw IOException("bad message")
    return keys.mapIndexed { i, (s, k) -> s to (k to proofs[i]) }.toMap()
}

/** The client's part after the server's answer [reply]: sends the proofs and gives the keys, one of which the server must prove too. */
internal fun pakeClient(ch: SecureChannel, reply: JsonObject, mine: CPace, info: String, t: ByteArray, bind: ByteArray): List<ByteArray> {
    val shares = reply.byteList("pk")
    if (shares.isEmpty() || shares.size > MAX_CODES) throw IOException("bad message")
    val keys = shares.map { mine.key(it, first = true) }
    ch.send(
        buildJsonObject {
            put("t", "pake-proof")
            put("proofs", JsonArray(keys.map { b64(proof(it, info, "client", t, bind)) }))
        }.toString().encodeToByteArray(),
    )
    return keys
}
