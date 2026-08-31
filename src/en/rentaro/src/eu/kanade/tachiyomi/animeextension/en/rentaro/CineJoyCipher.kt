package eu.kanade.tachiyomi.animeextension.en.rentaro

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Request sealing and reply decryption for the CineJoy (Jay) backend.
 *
 * CineJoy builds its request body in a WASM module fetched at runtime, which is
 * why this used to need enc-dec.app. The construction underneath turns out to be
 * entirely standard, so it runs here instead:
 *
 *  1. ECDH on P-256 between a fresh ephemeral key and the server's static key,
 *     which is embedded in the WASM module.
 *  2. HKDF-SHA256 over the shared secret, salted with the client's own public
 *     key, expanded once per direction.
 *  3. AES-256-GCM, keyed per direction, with the suite header bound in as
 *     additional data.
 *
 * Wire format, for a 107-byte plaintext giving 202 bytes:
 *
 *     [0:2]     02 01        version / suite
 *     [2:67]    04 X Y       ephemeral P-256 public key, SEC1 uncompressed
 *     [67:79]   nonce        12 bytes
 *     [79:..]   ciphertext   same length as the plaintext
 *     [..:+16]  tag
 *
 * Verified by reproducing a captured live request byte-for-byte, and by sealing
 * fresh requests that the API accepts and answers.
 */
internal object CineJoyCipher {

    /** Curve both sides use. */
    private const val CURVE = "secp256r1"

    /** GCM nonce length. */
    private const val IV_SIZE = 12

    /** GCM tag length in bits, as `GCMParameterSpec` wants it. */
    private const val TAG_BITS = 128

    /** Bytes the tag occupies. */
    private const val TAG_SIZE = TAG_BITS / 8

    /** Length of an uncompressed SEC1 point: `04` plus two 32-byte coordinates. */
    private const val POINT_SIZE = 65

    /** Bytes before the nonce: the two version bytes plus the public key. */
    private const val HEADER_SIZE = 2 + POINT_SIZE

    /** Domain separator prefixed to the additional data. */
    private val AAD_LABEL = "lumen-gate-v2\u0000".toByteArray(Charsets.UTF_8)

    /** HKDF info for the client-to-server key. */
    private const val INFO_C2S = "lumen-gate-v2|c2s"

    /** HKDF info for the server-to-client key. */
    private const val INFO_S2C = "lumen-gate-v2|s2c"

    /** Direction byte the request binds into its additional data. */
    private const val DIRECTION_REQUEST: Byte = 1

    /** Direction byte the reply binds into its additional data. */
    private const val DIRECTION_REPLY: Byte = 2

    /**
     * The server's static P-256 public key, as `X ++ Y`.
     *
     * Read out of the WASM module, where it is the only point that satisfies the
     * curve equation.
     */
    private const val SERVER_PUBLIC_KEY =
        "83c7a82132b8516e3eb4061b82e9c881cc585593a4709001131bff7443eabc17" +
            "01c1f0d50e23ac02b0b9a5979903dbd7e9055aab5e4a5532132d1d200707f5f2"

    /** A sealed request, plus the key needed to read its reply. */
    class Sealed(val body: ByteArray, val replyKey: ByteArray, private val header: ByteArray) {
        /**
         * Decrypts the reply to this request.
         *
         * @return the JSON plaintext, or null if authentication fails
         */
        fun open(response: ByteArray): String? = openReply(response, replyKey, header)
    }

    /**
     * Seals `plaintext` for `api.shegu.st/g`.
     *
     * A fresh ephemeral key is generated per call, as the site does.
     *
     * @return the body to POST plus the reply key, or null if the platform
     *   lacks P-256 or AES-GCM
     */
    fun seal(plaintext: String): Sealed? = runCatching {
        val params = ecParameters()
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(CURVE), SecureRandom())
        }
        val pair = generator.generateKeyPair()
        val publicKey = encodePoint((pair.public as ECPublicKey).w)

        val agreement = KeyAgreement.getInstance("ECDH").apply {
            init(pair.private)
            doPhase(serverPublicKey(params), true)
        }
        val shared = agreement.generateSecret()

        // HKDF-Extract, salted with our own public key rather than a constant.
        val prk = hmac(sha256(publicKey), shared)
        val requestKey = expand(prk, INFO_C2S)
        val replyKey = expand(prk, INFO_S2C)

        val nonce = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        // The suite byte and public key are bound in, so a reply cannot be
        // replayed against a different request.
        val header = byteArrayOf(2, 1) + publicKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(requestKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad(DIRECTION_REQUEST, header))
        }
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        Sealed(header + nonce + sealed, replyKey, header)
    }.getOrNull()

    /**
     * Decrypts a reply, which frames as `nonce(12) ++ ciphertext ++ tag(16)`.
     *
     * The tag stays attached: GCM expects it as the tail of the input.
     */
    private fun openReply(response: ByteArray, key: ByteArray, header: ByteArray): String? = runCatching {
        // Below this there is no room for both a nonce and a tag, and the length
        // arithmetic would go negative.
        if (response.size <= IV_SIZE + TAG_SIZE) return null

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, response, 0, IV_SIZE),
            )
            updateAAD(aad(DIRECTION_REPLY, header))
        }
        String(cipher.doFinal(response, IV_SIZE, response.size - IV_SIZE), Charsets.UTF_8)
    }.getOrNull()

    /** Label, direction, then the header without its leading version byte. */
    private fun aad(direction: Byte, header: ByteArray): ByteArray = AAD_LABEL + byteArrayOf(direction) + header.copyOfRange(1, header.size)

    /** HKDF-Expand for a single 32-byte output block. */
    private fun expand(prk: ByteArray, info: String): ByteArray = hmac(prk, info.toByteArray(Charsets.UTF_8) + byteArrayOf(1))

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun sha256(data: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(data)

    /** Curve parameters, read from the platform rather than hardcoded. */
    private fun ecParameters(): ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec(CURVE))
        getParameterSpec(ECParameterSpec::class.java)
    }

    private fun serverPublicKey(params: ECParameterSpec): ECPublicKey {
        val raw = SERVER_PUBLIC_KEY.hexToBytes()
        val point = ECPoint(
            BigInteger(1, raw.copyOfRange(0, 32)),
            BigInteger(1, raw.copyOfRange(32, 64)),
        )
        return KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
    }

    /** Encodes a point as SEC1 uncompressed, left-padding both coordinates. */
    private fun encodePoint(point: ECPoint): ByteArray {
        val out = ByteArray(POINT_SIZE)
        out[0] = 4
        writeCoordinate(point.affineX, out, 1)
        writeCoordinate(point.affineY, out, 33)
        return out
    }

    /**
     * Writes a coordinate as exactly 32 big-endian bytes.
     *
     * `BigInteger.toByteArray` adds a leading zero when the high bit is set and
     * omits leading zeros otherwise, so neither end can be copied blindly.
     */
    private fun writeCoordinate(value: BigInteger, out: ByteArray, offset: Int) {
        val bytes = value.toByteArray()
        val from = maxOf(0, bytes.size - 32)
        val length = bytes.size - from
        bytes.copyInto(out, offset + 32 - length, from, bytes.size)
    }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
