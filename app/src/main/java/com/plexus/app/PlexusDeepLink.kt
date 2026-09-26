package com.plexus.app

import android.net.Uri


object PlexusDeepLink {


    private const val SCHEME =
        "https"


    /*
     * Render already hosts the Plexus backend at this public HTTPS host.
     *
     * The same host serves:
     *
     * /.well-known/assetlinks.json
     * /connect
     *
     * so Android can verify Plexus App Links without buying a separate
     * domain.
     */
    private const val HOST =
        "plexus-server.onrender.com"


    private const val CONNECT_PATH =
        "/connect"



    /*
     * =========================================================
     * BUILD SHARE LINK
     * =========================================================
     *
     * The QR payload already contains the complete public Plexus
     * identity:
     *
     * - display name
     * - nodeId
     * - signing public key
     * - agreement public key
     *
     * We URL-encode that exact payload into the fragment so the
     * same identity data can be imported remotely.
     *
     * Example:
     *
     * https://plexus-server.onrender.com/connect#<encoded-identity>
     *
     * The fragment is not sent in the HTTP request to Render. It
     * stays client-side and is decoded by Plexus after Android opens
     * MainActivity.
     */

    fun buildShareLink(
        qrPayload: String
    ): String {


        val cleanPayload =
            qrPayload.trim()


        require(
            cleanPayload.isNotEmpty()
        ) {

            "Plexus identity payload cannot be empty"
        }


        return "$SCHEME://$HOST$CONNECT_PATH#" +
                Uri.encode(
                    cleanPayload
                )
    }



/*
 * =========================================================
 * EXTRACT QR PAYLOAD
 * =========================================================
 *
 * This intentionally returns the ORIGINAL QR payload.
 *
 * IdentityManager.parseAndValidateContact(...) remains the
 * authority that validates:
 *
 * - JSON structure
 * - protocol version
 * - nodeId/public-key binding
 *
 * Therefore QR and deep-link imports use the exact same
 * identity-validation path.
 */
fun extractQrPayload(
    uri: Uri?
): String? {


    if (
        uri == null || uri.toString().length > 24_576
    ) {

        return null
    }


    if (
        !uri.scheme.equals(
            SCHEME,
            ignoreCase = true
        )
    ) {

        return null
    }


    if (
        !uri.host.equals(
            HOST,
            ignoreCase = true
        )
    ) {

        return null
    }


    val normalizedPath =
        uri.path
            ?.trimEnd('/')
            .orEmpty()


    if (
        normalizedPath !=
        CONNECT_PATH
    ) {

        return null
    }


    /*
     * Use encodedFragment rather than fragment so decoding
     * happens exactly once under our control.
     */
    val encodedPayload =
        uri.encodedFragment
            ?.takeIf {

                it.isNotBlank()
            }
            ?: return null


    val decodedPayload =
        try {


            Uri.decode(
                encodedPayload
            )


        } catch (
            exception: Exception
        ) {


            return null
        }


    return decodedPayload
        .trim()
        .takeIf {

            it.isNotEmpty()
        }
}



    /*
     * =========================================================
     * VALIDATE + PARSE CONTACT IDENTITY
     * =========================================================
     *
     * Deep links do NOT get a weaker trust path.
     *
     * The extracted identity goes through the same validation
     * already used by the QR scanner.
     */

    fun parseContactIdentity(
        uri: Uri?
    ): PlexusContactIdentity? {


        val qrPayload =
            extractQrPayload(
                uri
            )
                ?: return null


        return IdentityManager
            .parseAndValidateContact(
                qrPayload
            )
    }
}
