package com.plexus.app

import android.util.Base64
import java.security.MessageDigest


object PeerIdentityVerifier {


    /*
     * =========================================================
     * VERIFY SELF-AUTHENTICATING NODE ID
     * =========================================================
     *
     * Plexus defines:
     *
     * nodeId =
     *     SHA-256(
     *         signingPublicKey.encoded
     *     )
     *
     * Therefore an unknown nearby peer can announce:
     *
     * nodeId
     * signingPublicKey
     *
     * and we can verify that those two values actually belong
     * together WITHOUT requiring that person to already exist
     * in our contacts.
     *
     * This proves identity-key consistency.
     *
     * The subsequent ECDSA challenge-response proves that the
     * peer actually possesses the corresponding private key.
     */

    fun isValidIdentity(

        nodeId: String,

        signingPublicKey: String

    ): Boolean {


        if (
            nodeId.isBlank() ||
            signingPublicKey.isBlank()
        ) {

            return false
        }


        return try {


            val publicKeyBytes =
                Base64.decode(

                    signingPublicKey,

                    Base64.DEFAULT
                )


            val calculatedNodeId =
                sha256Hex(
                    publicKeyBytes
                )


            calculatedNodeId.equals(

                nodeId,

                ignoreCase = true
            )


        } catch (
            exception: Exception
        ) {


            false
        }
    }



    /*
     * =========================================================
     * SAVED CONTACT CHECK
     * =========================================================
     *
     * If this peer IS already one of our contacts, we apply a
     * stronger check:
     *
     * the announced keys must exactly match the identity that
     * was previously saved through QR/link exchange.
     */

    fun matchesSavedContact(

        announcedIdentity:
        NearbyIdentityPacket,

        savedContact:
        ContactEntity

    ): Boolean {


        if (
            announcedIdentity.nodeId !=
            savedContact.nodeId
        ) {

            return false
        }


        if (
            announcedIdentity.signingPublicKey !=
            savedContact.signingPublicKey
        ) {

            return false
        }


        if (
            announcedIdentity.agreementPublicKey !=
            savedContact.agreementPublicKey
        ) {

            return false
        }


        return true
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