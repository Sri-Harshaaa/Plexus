package com.plexus.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec


data class PlexusIdentity(

    val displayName: String,

    val nodeId: String,

    val signingPublicKey: String,

    val agreementPublicKey: String,

    val qrPayload: String
)

data class PlexusContactIdentity(
    val displayName: String,
    val nodeId: String,
    val signingPublicKey: String,
    val agreementPublicKey: String
)

object IdentityManager {

    private const val KEYSTORE_PROVIDER =
        "AndroidKeyStore"


    private const val SIGNING_KEY_ALIAS =
        "plexus_signing_identity"


    private const val AGREEMENT_KEY_ALIAS =
        "plexus_key_agreement_identity"



    fun getOrCreateIdentity(
        displayName: String
    ): PlexusIdentity {


        val signingKeyPair =
            getOrCreateSigningKeyPair()


        val agreementKeyPair =
            getOrCreateAgreementKeyPair()



        /*
         * Public signing key
         *
         * This becomes the base of
         * our Plexus identity.
         */
        val signingPublicKeyBytes =
            signingKeyPair.public.encoded



        /*
         * Node ID =
         *
         * SHA-256(signing public key)
         */
        val nodeId =
            sha256Hex(
                signingPublicKeyBytes
            )



        /*
         * Convert public keys into Base64
         *
         * because raw key bytes are not
         * convenient to put inside JSON.
         */
        val signingPublicKeyBase64 =
            Base64.encodeToString(
                signingPublicKeyBytes,
                Base64.NO_WRAP
            )



        val agreementPublicKeyBase64 =
            Base64.encodeToString(
                agreementKeyPair.public.encoded,
                Base64.NO_WRAP
            )



        /*
         * Public identity bundle.
         *
         * This is what eventually goes
         * inside our QR code.
         *
         * NOTE:
         * No private key is included here.
         */
        val qrPayload =
            JSONObject()

                .put(
                    "version",
                    1
                )

                .put(
                    "name",
                    displayName
                )

                .put(
                    "nodeId",
                    nodeId
                )

                .put(
                    "signingPublicKey",
                    signingPublicKeyBase64
                )

                .put(
                    "agreementPublicKey",
                    agreementPublicKeyBase64
                )

                .toString()



        return PlexusIdentity(

            displayName =
                displayName,

            nodeId =
                nodeId,

            signingPublicKey =
                signingPublicKeyBase64,

            agreementPublicKey =
                agreementPublicKeyBase64,

            qrPayload =
                qrPayload
        )
    }



    /*
     * SIGNING KEYPAIR
     *
     * Used later for:
     *
     * - backend authentication
     * - anti-packet signatures
     * - protocol signatures
     */
    private fun getOrCreateSigningKeyPair():
            KeyPair {


        val keyStore =
            KeyStore.getInstance(
                KEYSTORE_PROVIDER
            ).apply {

                load(null)
            }



        /*
         * If we already generated the key,
         * reuse it.
         */
        if (
            keyStore.containsAlias(
                SIGNING_KEY_ALIAS
            )
        ) {


            val privateKey =
                keyStore.getKey(
                    SIGNING_KEY_ALIAS,
                    null
                ) as PrivateKey



            val publicKey =
                keyStore
                    .getCertificate(
                        SIGNING_KEY_ALIAS
                    )
                    .publicKey



            return KeyPair(
                publicKey,
                privateKey
            )
        }



        /*
         * No key exists yet.
         *
         * Generate a new EC keypair.
         */
        val generator =
            KeyPairGenerator.getInstance(

                KeyProperties.KEY_ALGORITHM_EC,

                KEYSTORE_PROVIDER
            )



        val specification =
            KeyGenParameterSpec.Builder(

                SIGNING_KEY_ALIAS,

                KeyProperties.PURPOSE_SIGN or
                        KeyProperties.PURPOSE_VERIFY
            )

                .setAlgorithmParameterSpec(
                    ECGenParameterSpec(
                        "secp256r1"
                    )
                )

                .setDigests(
                    KeyProperties.DIGEST_SHA256
                )

                .setUserAuthenticationRequired(
                    false
                )

                .build()



        generator.initialize(
            specification
        )



        return generator.generateKeyPair()
    }



    /*
     * AGREEMENT KEYPAIR
     *
     * Used later for ECDH.
     *
     * ECDH eventually helps two users
     * derive the same shared encryption secret.
     */
    private fun getOrCreateAgreementKeyPair():
            KeyPair {


        val keyStore =
            KeyStore.getInstance(
                KEYSTORE_PROVIDER
            ).apply {

                load(null)
            }



        if (
            keyStore.containsAlias(
                AGREEMENT_KEY_ALIAS
            )
        ) {


            val privateKey =
                keyStore.getKey(
                    AGREEMENT_KEY_ALIAS,
                    null
                ) as PrivateKey



            val publicKey =
                keyStore
                    .getCertificate(
                        AGREEMENT_KEY_ALIAS
                    )
                    .publicKey



            return KeyPair(
                publicKey,
                privateKey
            )
        }



        /*
         * Generate EC keypair specifically
         * for key agreement.
         */
        val generator =
            KeyPairGenerator.getInstance(

                KeyProperties.KEY_ALGORITHM_EC,

                KEYSTORE_PROVIDER
            )



        val specification =
            KeyGenParameterSpec.Builder(

                AGREEMENT_KEY_ALIAS,

                KeyProperties.PURPOSE_AGREE_KEY
            )

                .setAlgorithmParameterSpec(
                    ECGenParameterSpec(
                        "secp256r1"
                    )
                )

                .setUserAuthenticationRequired(
                    false
                )

                .build()



        generator.initialize(
            specification
        )



        return generator.generateKeyPair()
    }



    /*
     * Convert arbitrary bytes into
     * a SHA-256 hexadecimal string.
     */
    private fun sha256Hex(
        data: ByteArray
    ): String {


        val digest =
            MessageDigest
                .getInstance(
                    "SHA-256"
                )
                .digest(
                    data
                )



        return digest.joinToString(
            separator = ""
        ) { byte ->

            "%02x".format(
                byte
            )
        }
    }

    fun parseAndValidateContact(
        payload: String
    ): PlexusContactIdentity? {
        if (payload.length > 8192) return null
        return try {

            val json =
                JSONObject(payload)

            val version =
                json.getInt("version")

            if (version != 1) {
                return null
            }

            val displayName =
                json.getString("name")

            val claimedNodeId =
                json.getString("nodeId")

            val signingPublicKey =
                json.getString(
                    "signingPublicKey"
                )

            val agreementPublicKey =
                json.getString(
                    "agreementPublicKey"
                )

            if (displayName.isBlank() || displayName.length > 64 ||
                signingPublicKey.length > 512 || agreementPublicKey.length > 512) return null
            val keyFactory = java.security.KeyFactory.getInstance("EC")
            for (encoded in listOf(signingPublicKey, agreementPublicKey)) {
                val key = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(
                    Base64.decode(encoded, Base64.NO_WRAP))) as? java.security.interfaces.ECPublicKey
                    ?: return null
                if (key.params.curve.field.fieldSize != 256) return null
            }

            val signingKeyBytes =
                Base64.decode(
                    signingPublicKey,
                    Base64.NO_WRAP
                )

            val calculatedNodeId =
                sha256Hex(
                    signingKeyBytes
                )

            if (
                calculatedNodeId !=
                claimedNodeId
            ) {
                return null
            }

            PlexusContactIdentity(
                displayName =
                    displayName,

                nodeId =
                    claimedNodeId,

                signingPublicKey =
                    signingPublicKey,

                agreementPublicKey =
                    agreementPublicKey
            )

        } catch (
            exception: Exception
        ) {

            null
        }
    }
}
