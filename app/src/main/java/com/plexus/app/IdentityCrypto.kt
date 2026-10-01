package com.plexus.app

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec


object IdentityCrypto {


    /*
     * =========================================================
     * GENERATE CHALLENGE
     * =========================================================
     */

    fun generateChallenge(): String {


        val bytes =
            ByteArray(
                32
            )


        SecureRandom()
            .nextBytes(
                bytes
            )


        return Base64.encodeToString(

            bytes,

            Base64.NO_WRAP or
                    Base64.URL_SAFE
        )
    }



    /*
     * =========================================================
     * AUTH MESSAGE
     * =========================================================
     */

    fun buildAuthenticationMessage(

        challengerNodeId: String,

        responderNodeId: String,

        challenge: String

    ): ByteArray {


        val message =
            "PLEXUS_AUTH_V1|" +
                    challengerNodeId +
                    "|" +
                    responderNodeId +
                    "|" +
                    challenge


        return message.toByteArray(
            Charsets.UTF_8
        )
    }



    /*
     * =========================================================
     * SIGN
     * =========================================================
     *
     * We deliberately DO NOT depend on a hardcoded Android
     * Keystore alias here.
     *
     * Plexus nodeId is:
     *
     * SHA-256(signingPublicKey.encoded)
     *
     * Therefore we inspect this app's Android Keystore entries
     * and locate the private key whose public key hashes to the
     * responder nodeId.
     */

    fun sign(

        data: ByteArray,

        responderNodeId: String

    ): String {
        val diagnosticTiming = PlexusDiagnostics.begin("KEYSTORE_SIGN", "identitySignature")
        try {


        val privateKey =
            findSigningPrivateKey(
                responderNodeId
            )
                ?: throw IllegalStateException(
                    "Plexus signing private key not found for node " +
                            responderNodeId.take(8)
                )


        val signer =
            Signature.getInstance(
                "SHA256withECDSA"
            )


        signer.initSign(
            privateKey
        )


        signer.update(
            data
        )


        val signatureBytes =
            signer.sign()


        return Base64.encodeToString(

            signatureBytes,

            Base64.NO_WRAP
        )

        } finally { PlexusDiagnostics.end(diagnosticTiming) }
    }



    /*
     * =========================================================
     * VERIFY
     * =========================================================
     */

    fun verify(

        data: ByteArray,

        signatureBase64: String,

        publicKeyBase64: String

    ): Boolean {
        val diagnosticTiming = PlexusDiagnostics.begin("VERIFY", "identitySignature")
        try {


        return try {


            val publicKey =
                decodePublicKey(
                    publicKeyBase64
                )


            val signatureBytes =
                Base64.decode(

                    signatureBase64,

                    Base64.NO_WRAP
                )


            val verifier =
                Signature.getInstance(
                    "SHA256withECDSA"
                )


            verifier.initVerify(
                publicKey
            )


            verifier.update(
                data
            )


            verifier.verify(
                signatureBytes
            )


        } catch (
            exception: Exception
        ) {


            false
        }

        } finally { PlexusDiagnostics.end(diagnosticTiming) }
    }



    /*
     * =========================================================
     * FIND OUR SIGNING PRIVATE KEY
     * =========================================================
     */

    private fun findSigningPrivateKey(
        expectedNodeId: String
    ): PrivateKey? {


        val keyStore =
            KeyStore.getInstance(
                "AndroidKeyStore"
            )


        keyStore.load(
            null
        )


        val aliases =
            keyStore.aliases()


        while (
            aliases.hasMoreElements()
        ) {


            val alias =
                aliases.nextElement()


            val certificate =
                keyStore.getCertificate(
                    alias
                )
                    ?: continue


            val publicKey =
                certificate.publicKey


            val calculatedNodeId =
                sha256Hex(
                    publicKey.encoded
                )


            if (
                !calculatedNodeId.equals(
                    expectedNodeId,
                    ignoreCase = true
                )
            ) {


                continue
            }


            val privateKey =
                keyStore.getKey(
                    alias,
                    null
                ) as? PrivateKey
                    ?: continue


            /*
             * Extra sanity check:
             *
             * The matching key must actually support our
             * ECDSA signing algorithm.
             */
            try {


                Signature
                    .getInstance(
                        "SHA256withECDSA"
                    )
                    .initSign(
                        privateKey
                    )


                return privateKey


            } catch (
                exception: Exception
            ) {


                continue
            }
        }


        return null
    }



    /*
     * =========================================================
     * PUBLIC KEY DECODING
     * =========================================================
     */

    private fun decodePublicKey(
        publicKeyBase64: String
    ): PublicKey {


        val bytes =
            Base64.decode(

                publicKeyBase64,

                Base64.NO_WRAP
            )


        val keySpec =
            X509EncodedKeySpec(
                bytes
            )


        return KeyFactory
            .getInstance(
                "EC"
            )
            .generatePublic(
                keySpec
            )
    }



    /*
     * =========================================================
     * SHA-256
     * =========================================================
     */

    private fun sha256Hex(
        bytes: ByteArray
    ): String {


        val digest =
            MessageDigest
                .getInstance(
                    "SHA-256"
                )
                .digest(
                    bytes
                )


        return digest.joinToString(
            separator = ""
        ) { byte ->


            "%02x".format(
                byte
            )
        }
    }
}