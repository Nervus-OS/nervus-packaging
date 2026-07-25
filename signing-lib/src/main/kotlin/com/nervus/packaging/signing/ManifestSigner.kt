package com.nervus.packaging.signing

import com.nervus.packaging.model.Lineage
import com.nervus.packaging.model.SigBlock
import com.nervus.packaging.model.Signature
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature as JSignature
import java.util.Base64

object ManifestSigner {

    private const val NERVUS_PKG_MANIFEST_PREFIX = "nervus-pkg-manifest-v1\u0000"

    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    fun sign(
        manifestJsonBytes: ByteArray,
        signerKeyPair: KeyPair,
        role: String,
        lineage: Lineage? = null,
    ): SigBlock {
        val toSign = NERVUS_PKG_MANIFEST_PREFIX.toByteArray() + manifestJsonBytes

        val sig = JSignature.getInstance("Ed25519").apply {
            initSign(signerKeyPair.private)
            update(toSign)
        }
        val signatureBytes = sig.sign()

        val pubKeyEncoded = signerKeyPair.public.encoded
        val rawPubKey = pubKeyEncoded.copyOfRange(ED25519_SPKI_PREFIX.size, pubKeyEncoded.size)
        val sha256 = MessageDigest.getInstance("SHA-256")
        val keyId = "sha256:" + sha256.digest(rawPubKey).joinToString("") { "%02x".format(it) }

        val signature = Signature(
            role = role,
            alg = "ed25519",
            keyId = keyId,
            key = if (role == "developer") Base64.getEncoder().encodeToString(rawPubKey) else null,
            sig = Base64.getEncoder().encodeToString(signatureBytes),
        )

        return SigBlock(
            signatures = listOf(signature),
            lineage = lineage,
        )
    }
}