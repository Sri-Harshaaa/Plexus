package com.plexus.app

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec


object PlexusCrypto {


    private const val AES_KEY_SIZE_BYTES =
        32


    private const val GCM_NONCE_SIZE_BYTES =
        12


    private const val GCM_TAG_SIZE_BITS =
        128



    data class EncryptedPayload(

        val ciphertext: String,

        val nonce: String
    )



    /*
     * =========================================================
     * DERIVE SESSION KEY
     * =========================================================
     *
     * localAgreementPublicKey:
     *     our own public ECDH key
     *
     * remoteAgreementPublicKey:
     *     peer's public ECDH key
     *
     * localNodeId / remoteNodeId:
     *     used for deterministic HKDF context binding
     *
     * Result:
     *
     *     AES-256 key
     */

    fun deriveSessionKey(

        localAgreementPublicKey: String,

        remoteAgreementPublicKey: String,

        localNodeId: String,

        remoteNodeId: String

    ): ByteArray {


        val privateKey =
            findAgreementPrivateKey(

                localAgreementPublicKey =
                    localAgreementPublicKey
            )
                ?: throw IllegalStateException(
                    "Plexus agreement private key not found"
                )



        val peerPublicKey =
            decodeEcPublicKey(

                remoteAgreementPublicKey
            )



        /*
         * =====================================================
         * ECDH
         * =====================================================
         */

        val keyAgreement =
            KeyAgreement.getInstance(
                "ECDH"
            )


        keyAgreement.init(
            privateKey
        )


        keyAgreement.doPhase(

            peerPublicKey,

            true
        )


        val sharedSecret =
            keyAgreement.generateSecret()



        /*
         * =====================================================
         * DETERMINISTIC PEER ORDER
         * =====================================================
         *
         * Both phones must construct the same HKDF salt/info.
         *
         * Therefore:
         *
         * smaller nodeId first
         * larger nodeId second
         */

        val firstNodeId =
            minOf(
                localNodeId,
                remoteNodeId
            )


        val secondNodeId =
            maxOf(
                localNodeId,
                remoteNodeId
            )



        val saltMaterial =
            (
                    "PLEXUS_HKDF_SALT_V1|" +
                            firstNodeId +
                            "|" +
                            secondNodeId
                    )
                .toByteArray(
                    Charsets.UTF_8
                )



        val salt =
            MessageDigest
                .getInstance(
                    "SHA-256"
                )
                .digest(
                    saltMaterial
                )



        val info =
            (
                    "PLEXUS_SESSION_V1|" +
                            firstNodeId +
                            "|" +
                            secondNodeId
                    )
                .toByteArray(
                    Charsets.UTF_8
                )



        /*
         * =====================================================
         * HKDF-SHA256
         * =====================================================
         */

        return hkdfSha256(

            inputKeyMaterial =
                sharedSecret,

            salt =
                salt,

            info =
                info,

            outputLength =
                AES_KEY_SIZE_BYTES
        )
    }



    /*
     * =========================================================
     * AES-256-GCM ENCRYPT
     * =========================================================
     */

    fun encrypt(

        plaintext: ByteArray,

        sessionKey: ByteArray,

        aad: ByteArray

    ): EncryptedPayload {


        require(
            sessionKey.size ==
                    AES_KEY_SIZE_BYTES
        ) {

            "AES-256 key must be 32 bytes"
        }



        val nonce =
            ByteArray(
                GCM_NONCE_SIZE_BYTES
            )


        SecureRandom()
            .nextBytes(
                nonce
            )



        val cipher =
            Cipher.getInstance(
                "AES/GCM/NoPadding"
            )



        val key =
            SecretKeySpec(

                sessionKey,

                "AES"
            )



        val parameterSpec =
            GCMParameterSpec(

                GCM_TAG_SIZE_BITS,

                nonce
            )



        cipher.init(

            Cipher.ENCRYPT_MODE,

            key,

            parameterSpec
        )



        /*
         * Metadata is authenticated even though it is not
         * encrypted.
         */
        cipher.updateAAD(
            aad
        )



        val ciphertext =
            cipher.doFinal(
                plaintext
            )



        return EncryptedPayload(

            ciphertext =
                Base64.encodeToString(

                    ciphertext,

                    Base64.NO_WRAP
                ),

            nonce =
                Base64.encodeToString(

                    nonce,

                    Base64.NO_WRAP
                )
        )
    }



    /*
     * =========================================================
     * AES-256-GCM DECRYPT
     * =========================================================
     */

    fun decrypt(

        ciphertextBase64: String,

        nonceBase64: String,

        sessionKey: ByteArray,

        aad: ByteArray

    ): ByteArray {


        require(
            sessionKey.size ==
                    AES_KEY_SIZE_BYTES
        ) {

            "AES-256 key must be 32 bytes"
        }



        val ciphertext =
            Base64.decode(

                ciphertextBase64,

                Base64.DEFAULT
            )



        val nonce =
            Base64.decode(

                nonceBase64,

                Base64.DEFAULT
            )



        require(
            nonce.size ==
                    GCM_NONCE_SIZE_BYTES
        ) {

            "Invalid AES-GCM nonce size"
        }



        val cipher =
            Cipher.getInstance(
                "AES/GCM/NoPadding"
            )



        val key =
            SecretKeySpec(

                sessionKey,

                "AES"
            )



        val parameterSpec =
            GCMParameterSpec(

                GCM_TAG_SIZE_BITS,

                nonce
            )



        cipher.init(

            Cipher.DECRYPT_MODE,

            key,

            parameterSpec
        )



        cipher.updateAAD(
            aad
        )



        /*
         * If:
         *
         * ciphertext changed
         * nonce changed
         * metadata/AAD changed
         * wrong session key used
         *
         * doFinal() throws instead of returning corrupted text.
         */

        return cipher.doFinal(
            ciphertext
        )
    }



    /*
     * =========================================================
     * MESSAGE AAD
     * =========================================================
     *
     * We bind routing/message metadata into the AES-GCM tag.
     *
     * An attacker therefore cannot modify:
     *
     * senderNodeId
     * recipientNodeId
     * messageId
     * timestamp
     *
     * without authentication failing.
     */

    fun buildMessageAad(

        senderNodeId: String,

        recipientNodeId: String,

        messageId: String,

        timestamp: Long

    ): ByteArray {


        return (
                "PLEXUS_MESSAGE_V1|" +
                        senderNodeId +
                        "|" +
                        recipientNodeId +
                        "|" +
                        messageId +
                        "|" +
                        timestamp
                )
            .toByteArray(
                Charsets.UTF_8
            )
    }



    /*
     * =========================================================
     * FIND AGREEMENT PRIVATE KEY
     * =========================================================
     *
     * Again: NO hardcoded Android Keystore alias.
     *
     * We already know our agreement public key.
     *
     * So we scan our app's Android Keystore and find the entry
     * whose certificate public key matches it.
     */

    private fun findAgreementPrivateKey(

        localAgreementPublicKey: String

    ): PrivateKey? {


        val expectedPublicKey =
            Base64.decode(

                localAgreementPublicKey,

                Base64.DEFAULT
            )



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



            val actualPublicKey =
                certificate
                    .publicKey
                    .encoded



            if (
                !actualPublicKey.contentEquals(
                    expectedPublicKey
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
             * Verify that this private key can actually perform
             * ECDH.
             */

            try {


                val agreement =
                    KeyAgreement.getInstance(
                        "ECDH"
                    )


                agreement.init(
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
     * PUBLIC EC KEY DECODING
     * =========================================================
     */

    private fun decodeEcPublicKey(

        publicKeyBase64: String

    ): PublicKey {


        val bytes =
            Base64.decode(

                publicKeyBase64,

                Base64.DEFAULT
            )



        val spec =
            X509EncodedKeySpec(
                bytes
            )



        return KeyFactory
            .getInstance(
                "EC"
            )
            .generatePublic(
                spec
            )
    }



    /*
     * =========================================================
     * HKDF-SHA256
     * =========================================================
     *
     * RFC 5869:
     *
     * Extract:
     *
     *     PRK = HMAC(salt, IKM)
     *
     * Expand:
     *
     *     T(1) = HMAC(PRK, info || 0x01)
     *     T(2) = HMAC(PRK, T(1) || info || 0x02)
     *     ...
     */

    private fun hkdfSha256(

        inputKeyMaterial: ByteArray,

        salt: ByteArray,

        info: ByteArray,

        outputLength: Int

    ): ByteArray {


        require(
            outputLength > 0
        )


        require(
            outputLength <=
                    255 * 32
        )



        /*
         * =====================================================
         * EXTRACT
         * =====================================================
         */

        val extractMac =
            Mac.getInstance(
                "HmacSHA256"
            )



        extractMac.init(

            SecretKeySpec(

                salt,

                "HmacSHA256"
            )
        )



        val pseudoRandomKey =
            extractMac.doFinal(
                inputKeyMaterial
            )



        /*
         * =====================================================
         * EXPAND
         * =====================================================
         */

        val output =
            ByteArray(
                outputLength
            )



        var previousBlock =
            ByteArray(
                0
            )



        var generated =
            0



        var counter =
            1



        while (
            generated <
            outputLength
        ) {


            val expandMac =
                Mac.getInstance(
                    "HmacSHA256"
                )



            expandMac.init(

                SecretKeySpec(

                    pseudoRandomKey,

                    "HmacSHA256"
                )
            )



            expandMac.update(
                previousBlock
            )



            expandMac.update(
                info
            )



            expandMac.update(
                counter.toByte()
            )



            previousBlock =
                expandMac.doFinal()



            val remaining =
                outputLength -
                        generated



            val amountToCopy =
                minOf(

                    previousBlock.size,

                    remaining
                )



            previousBlock.copyInto(

                destination =
                    output,

                destinationOffset =
                    generated,

                startIndex =
                    0,

                endIndex =
                    amountToCopy
            )



            generated +=
                amountToCopy



            counter++
        }



        return output
    }
}