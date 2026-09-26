package com.plexus.app

import android.util.Log
import android.os.Handler
import android.os.Looper
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject


class InternetManager(

    private val identity:
    PlexusIdentity,

    private val serverUrl:
    String,

    private val listener:
    Listener

) {


    interface Listener {


        fun onConnected() {}


        fun onAuthenticated() {}


        fun onDisconnected() {}


        fun onMeshPacketReceived(
            packet: NearbyMeshPacket
        ) {
        }


        fun onAntiPacketReceived(
            packet: NearbyAntiPacket
        ) {
        }


        fun onConnectionRequestReceived(
            packet: ConnectionRequestPacket
        ) {
        }


        fun onConnectionResponseReceived(
            packet: ConnectionResponsePacket
        ) {
        }


        fun onError(
            message: String,
            exception: Exception? = null
        ) {
        }
    }



    private val handler = Handler(Looper.getMainLooper())
    private var authWatchdog: Runnable? = null
    @Volatile
    private var socket:
            Socket? = null


    @Volatile
    private var authenticated =
        false



    /*
     * =========================================================
     * START INTERNET CONNECTION
     * =========================================================
     */

    fun start() {


        if (
            socket != null
        ) {

            return
        }


        try {


            val options =
                IO.Options().apply {

                    reconnection =
                        true

                    forceNew =
                        true

                    timeout =
                        10_000
                }


            val createdSocket =
                IO.socket(
                    serverUrl,
                    options
                )


            socket =
                createdSocket


            registerListeners(
                createdSocket
            )


            createdSocket.connect()


        } catch (
            exception: Exception
        ) {


            Log.e(
                "PlexusInternet",
                "Could not create Socket.IO connection",
                exception
            )


            listener.onError(
                "Could not create Socket.IO connection",
                exception
            )
        }
    }



    /*
     * =========================================================
     * SOCKET LISTENERS
     * =========================================================
     */

    private fun registerListeners(
        socket: Socket
    ) {
        fun on(event: String, callback: (Array<out Any?>) -> Unit) {
            socket.on(event) { args ->
                val connectionId = socket.id()
                handler.post {
                    if (this.socket === socket && socket.id() == connectionId) callback(args)
                }
            }
        }



        /*
         * -----------------------------------------------------
         * CONNECTED TO SERVER
         * -----------------------------------------------------
         */

        on(
            Socket.EVENT_CONNECT
        ) {


            authenticated =
                false


            Log.d(
                "PlexusInternet",
                "CONNECTED TO SERVER ✅"
            )


            listener.onConnected()


            authWatchdog?.let(handler::removeCallbacks)
            val connectionId = socket.id()
            authWatchdog = Runnable {
                if (this.socket === socket && socket.connected() &&
                    socket.id() == connectionId && !authenticated) {
                    Log.w("PlexusInternet", "AUTH TIMEOUT - RECONNECTING")
                    socket.disconnect()
                    socket.connect()
                }
            }.also { handler.postDelayed(it, 15_000L) }
            beginAuthentication()
        }



        /*
         * -----------------------------------------------------
         * SERVER AUTH CHALLENGE
         * -----------------------------------------------------
         */

        on(
            "auth:challenge"
        ) { args ->


            try {


                val json =
                    args
                        .firstOrNull()
                            as? JSONObject
                        ?: return@on


                val challengeId =
                    json.optString(
                        "challengeId"
                    )


                val challenge =
                    json.optString(
                        "challenge"
                    )


                if (
                    challengeId.isBlank() ||
                    challenge.isBlank()
                ) {


                    listener.onError(
                        "Invalid server authentication challenge"
                    )


                    return@on
                }


                /*
                 * Must EXACTLY match server.js:
                 *
                 * PLEXUS_SERVER_AUTH_V1
                 * |nodeId
                 * |challengeId
                 * |challenge
                 */
                val authenticationMessage =
                    (
                            "PLEXUS_SERVER_AUTH_V1|" +
                                    identity.nodeId +
                                    "|" +
                                    challengeId +
                                    "|" +
                                    challenge
                            )
                        .toByteArray(
                            Charsets.UTF_8
                        )



                /*
                 * Sign challenge using our existing Plexus
                 * identity private key in Android Keystore.
                 */
                val signature =
                    IdentityCrypto
                        .sign(

                            data =
                                authenticationMessage,

                            responderNodeId =
                                identity.nodeId
                        )



                val proof =
                    JSONObject().apply {


                        put(
                            "challengeId",
                            challengeId
                        )


                        put(
                            "signature",
                            signature
                        )
                    }



                socket.emit(
                    "auth:proof",
                    proof
                )


                Log.d(
                    "PlexusInternet",
                    "SERVER AUTH PROOF SENT 🔐"
                )


            } catch (
                exception: Exception
            ) {


                Log.e(
                    "PlexusInternet",
                    "Server authentication proof failed",
                    exception
                )


                listener.onError(
                    "Server authentication proof failed",
                    exception
                )
            }
        }



        /*
         * -----------------------------------------------------
         * AUTH SUCCESS
         * -----------------------------------------------------
         */

        on(
            "auth:success"
        ) {


            authWatchdog?.let(handler::removeCallbacks)
            authWatchdog = null
            authenticated =
                true


            Log.d(
                "PlexusInternet",
                "SERVER AUTHENTICATED ✅ 🔐"
            )


            listener.onAuthenticated()
        }



        /*
         * -----------------------------------------------------
         * RECEIVE INTERNET MESH PACKET
         * -----------------------------------------------------
         */

        on(
            "internet:packet"
        ) { args ->


            try {


                if (
                    !authenticated
                ) {

                    return@on
                }


                val json =
                    args
                        .firstOrNull()
                            as? JSONObject
                        ?: return@on



                /*
                 * Reuse the SAME NearbyMeshPacket parser used
                 * by the offline mesh path.
                 */
                val packet =
                    NearbyMeshPacket
                        .fromBytes(

                            json
                                .toString()
                                .toByteArray(
                                    Charsets.UTF_8
                                )
                        )
                        ?: return@on



                Log.d(
                    "PlexusInternet",
                    "INTERNET MESH PACKET RECEIVED 🌐 -> " +
                            packet.packetId.take(8)
                )


                listener
                    .onMeshPacketReceived(
                        packet
                    )


            } catch (
                exception: Exception
            ) {


                Log.e(
                    "PlexusInternet",
                    "Failed to process internet packet",
                    exception
                )


                listener.onError(
                    "Failed to process internet packet",
                    exception
                )
            }
        }



        /*
         * -----------------------------------------------------
         * RECEIVE SIGNED ANTI-PACKET
         * -----------------------------------------------------
         */

        on(
            "internet:anti_packet"
        ) { args ->


            try {


                if (
                    !authenticated
                ) {

                    return@on
                }


                val json =
                    args
                        .firstOrNull()
                            as? JSONObject
                        ?: return@on



                val antiPacket =
                    NearbyAntiPacket
                        .fromBytes(

                            json
                                .toString()
                                .toByteArray(
                                    Charsets.UTF_8
                                )
                        )
                        ?: return@on



                Log.d(
                    "PlexusInternet",
                    "INTERNET ANTI-PACKET RECEIVED 💉 -> " +
                            antiPacket.messageId.take(8)
                )


                listener
                    .onAntiPacketReceived(
                        antiPacket
                    )


            } catch (
                exception: Exception
            ) {


                Log.e(
                    "PlexusInternet",
                    "Failed to process internet anti-packet",
                    exception
                )


                listener.onError(
                    "Failed to process internet anti-packet",
                    exception
                )
            }
        }



        /*
         * -----------------------------------------------------
         * RECEIVE SIGNED CONNECTION REQUEST
         * -----------------------------------------------------
         *
         * The server only routes this packet.
         *
         * ConnectionRequestPacket.fromBytes() performs:
         *
         * - packet structure validation
         * - nodeId/public-key binding validation
         * - timestamp / expiry validation
         * - ECDSA signature verification
         *
         * before the packet reaches MainActivity.
         */

        on(
            "internet:connection_request"
        ) { args ->


            try {


                if (
                    !authenticated
                ) {

                    return@on
                }


                val json =
                    args
                        .firstOrNull()
                            as? JSONObject
                        ?: return@on


                val packet =
                    ConnectionRequestPacket
                        .fromBytes(

                            json
                                .toString()
                                .toByteArray(
                                    Charsets.UTF_8
                                )
                        )
                        ?: return@on


                /*
                 * The backend should already route requests only
                 * to their intended recipient.
                 *
                 * Keep the Android side defensive as well.
                 */
                if (
                    packet.recipientNodeId !=
                    identity.nodeId
                ) {

                    Log.e(
                        "PlexusInternet",
                        "CONNECTION REQUEST REJECTED - WRONG RECIPIENT -> " +
                                packet.recipientNodeId.take(8)
                    )

                    return@on
                }


                Log.d(
                    "PlexusInternet",
                    "CONNECTION REQUEST RECEIVED 🌐 -> " +
                            packet.senderNodeId.take(8) +
                            " | request=" +
                            packet.requestId.take(8)
                )


                listener
                    .onConnectionRequestReceived(
                        packet
                    )


            } catch (
                exception: Exception
            ) {


                Log.e(
                    "PlexusInternet",
                    "Failed to process internet connection request",
                    exception
                )


                listener.onError(
                    "Failed to process internet connection request",
                    exception
                )
            }
        }



        /*
         * -----------------------------------------------------
         * RECEIVE SIGNED CONNECTION RESPONSE
         * -----------------------------------------------------
         *
         * The response may be:
         *
         * ACCEPTED
         * DECLINED
         *
         * ConnectionResponsePacket.fromBytes() verifies the
         * responder's identity binding and ECDSA signature
         * before this callback fires.
         */

        on(
            "internet:connection_response"
        ) { args ->


            try {


                if (
                    !authenticated
                ) {

                    return@on
                }


                val json =
                    args
                        .firstOrNull()
                            as? JSONObject
                        ?: return@on


                val packet =
                    ConnectionResponsePacket
                        .fromBytes(

                            json
                                .toString()
                                .toByteArray(
                                    Charsets.UTF_8
                                )
                        )
                        ?: return@on


                /*
                 * Only the original requester should receive
                 * this response.
                 */
                if (
                    packet.requesterNodeId !=
                    identity.nodeId
                ) {

                    Log.e(
                        "PlexusInternet",
                        "CONNECTION RESPONSE REJECTED - WRONG REQUESTER -> " +
                                packet.requesterNodeId.take(8)
                    )

                    return@on
                }


                Log.d(
                    "PlexusInternet",
                    "CONNECTION RESPONSE RECEIVED 🌐 -> " +
                            packet.responderNodeId.take(8) +
                            " | request=" +
                            packet.requestId.take(8) +
                            " | response=" +
                            packet.response
                )


                listener
                    .onConnectionResponseReceived(
                        packet
                    )


            } catch (
                exception: Exception
            ) {


                Log.e(
                    "PlexusInternet",
                    "Failed to process internet connection response",
                    exception
                )


                listener.onError(
                    "Failed to process internet connection response",
                    exception
                )
            }
        }



        /*
         * -----------------------------------------------------
         * SERVER ERROR
         * -----------------------------------------------------
         */

        on(
            "internet:error"
        ) { args ->


            val json =
                args
                    .firstOrNull()
                        as? JSONObject


            val error =
                json
                    ?.optString(
                        "error"
                    )
                    ?.takeIf {

                        it.isNotBlank()
                    }
                    ?: "Unknown Plexus internet error"


            Log.e(
                "PlexusInternet",
                error
            )


            listener.onError(
                error
            )
        }



        /*
         * -----------------------------------------------------
         * SOCKET CONNECTION ERROR
         * -----------------------------------------------------
         */

        on(
            "connect_error"
        ) { args ->


            authenticated =
                false


            val throwable =
                args
                    .firstOrNull()
                        as? Exception


            Log.e(
                "PlexusInternet",
                "SERVER CONNECTION ERROR",
                throwable
            )


            listener.onError(
                "Server connection error",
                throwable
            )
        }



        /*
         * -----------------------------------------------------
         * DISCONNECTED
         * -----------------------------------------------------
         */

        on(
            Socket.EVENT_DISCONNECT
        ) {


            authenticated =
                false


            Log.d(
                "PlexusInternet",
                "DISCONNECTED FROM SERVER"
            )


            listener.onDisconnected()
        }
    }



    /*
     * =========================================================
     * BEGIN CHALLENGE-RESPONSE AUTH
     * =========================================================
     */

    private fun beginAuthentication() {


        val activeSocket =
            socket
                ?: return


        if (
            !activeSocket.connected()
        ) {

            return
        }



        val identityPayload =
            JSONObject().apply {


                put(
                    "nodeId",
                    identity.nodeId
                )


                put(
                    "signingPublicKey",
                    identity.signingPublicKey
                )
            }



        activeSocket.emit(
            "auth:init",
            identityPayload
        )


        Log.d(
            "PlexusInternet",
            "SERVER AUTH INIT SENT -> " +
                    identity.nodeId.take(8)
        )
    }



    /*
     * =========================================================
     * SEND ENCRYPTED MESH PACKET OVER INTERNET
     * =========================================================
     */

    fun sendMeshPacket(
        packet: NearbyMeshPacket
    ): Boolean {


        val activeSocket =
            socket
                ?: return false


        if (
            !activeSocket.connected() ||
            !authenticated
        ) {

            return false
        }


        return try {


            val json =
                JSONObject(

                    packet
                        .toBytes()
                        .toString(
                            Charsets.UTF_8
                        )
                )


            activeSocket.emit(
                "internet:packet",
                json
            )


            Log.d(
                "PlexusInternet",
                "INTERNET MESH PACKET ENQUEUED 🌐 -> " +
                        packet.packetId.take(8)
            )


            true


        } catch (
            exception: Exception
        ) {


            Log.e(
                "PlexusInternet",
                "Could not send mesh packet",
                exception
            )


            listener.onError(
                "Could not send mesh packet",
                exception
            )


            false
        }
    }



    /*
     * =========================================================
     * SEND SIGNED ANTI-PACKET OVER INTERNET
     * =========================================================
     */

    fun sendAntiPacket(
        packet: NearbyAntiPacket
    ): Boolean {


        val activeSocket =
            socket
                ?: return false


        if (
            !activeSocket.connected() ||
            !authenticated
        ) {

            return false
        }


        return try {


            val json =
                JSONObject(

                    packet
                        .toBytes()
                        .toString(
                            Charsets.UTF_8
                        )
                )


            activeSocket.emit(
                "internet:anti_packet",
                json
            )


            Log.d(
                "PlexusInternet",
                "INTERNET ANTI-PACKET SENT 💉🌐 -> " +
                        packet.messageId.take(8)
            )


            true


        } catch (
            exception: Exception
        ) {


            Log.e(
                "PlexusInternet",
                "Could not send anti-packet",
                exception
            )


            listener.onError(
                "Could not send anti-packet",
                exception
            )


            false
        }
    }



    /*
     * =========================================================
     * SEND SIGNED CONNECTION REQUEST OVER INTERNET
     * =========================================================
     */

    fun sendConnectionRequest(
        packet: ConnectionRequestPacket
    ): Boolean {


        val activeSocket =
            socket
                ?: return false


        if (
            !activeSocket.connected() ||
            !authenticated
        ) {

            return false
        }


        /*
         * A device may only submit requests that it actually
         * created and signed itself.
         */
        if (
            packet.senderNodeId !=
            identity.nodeId ||
            !packet.verify()
        ) {

            Log.e(
                "PlexusInternet",
                "REFUSED TO SEND INVALID CONNECTION REQUEST -> " +
                        packet.requestId.take(8)
            )

            return false
        }


        return try {


            val json =
                JSONObject(

                    packet
                        .toBytes()
                        .toString(
                            Charsets.UTF_8
                        )
                )


            activeSocket.emit(
                "internet:connection_request",
                json
            )


            Log.d(
                "PlexusInternet",
                "CONNECTION REQUEST SENT 🌐 -> " +
                        packet.recipientNodeId.take(8) +
                        " | request=" +
                        packet.requestId.take(8)
            )


            true


        } catch (
            exception: Exception
        ) {


            Log.e(
                "PlexusInternet",
                "Could not send connection request",
                exception
            )


            listener.onError(
                "Could not send connection request",
                exception
            )


            false
        }
    }



    /*
     * =========================================================
     * SEND SIGNED CONNECTION RESPONSE OVER INTERNET
     * =========================================================
     */

    fun sendConnectionResponse(
        packet: ConnectionResponsePacket
    ): Boolean {


        val activeSocket =
            socket
                ?: return false


        if (
            !activeSocket.connected() ||
            !authenticated
        ) {

            return false
        }


        /*
         * Only the responder identity that signed this packet
         * is allowed to submit it through this authenticated
         * socket.
         */
        if (
            packet.responderNodeId !=
            identity.nodeId ||
            !packet.verify()
        ) {

            Log.e(
                "PlexusInternet",
                "REFUSED TO SEND INVALID CONNECTION RESPONSE -> " +
                        packet.responseId.take(8)
            )

            return false
        }


        return try {


            val json =
                JSONObject(

                    packet
                        .toBytes()
                        .toString(
                            Charsets.UTF_8
                        )
                )


            activeSocket.emit(
                "internet:connection_response",
                json
            )


            Log.d(
                "PlexusInternet",
                "CONNECTION RESPONSE SENT 🌐 -> " +
                        packet.requesterNodeId.take(8) +
                        " | request=" +
                        packet.requestId.take(8) +
                        " | response=" +
                        packet.response
            )


            true


        } catch (
            exception: Exception
        ) {


            Log.e(
                "PlexusInternet",
                "Could not send connection response",
                exception
            )


            listener.onError(
                "Could not send connection response",
                exception
            )


            false
        }
    }



    /*
     * =========================================================
     * STATE
     * =========================================================
     */

    fun isConnected(): Boolean {


        return socket
            ?.connected()
            ?: false
    }


    fun isAuthenticated(): Boolean {


        return authenticated
    }



    /*
     * =========================================================
     * STOP
     * =========================================================
     */

    fun stop() {
        val oldSocket = socket
        socket = null
        authenticated = false
        authWatchdog?.let(handler::removeCallbacks)
        authWatchdog = null
        handler.removeCallbacksAndMessages(null)
        oldSocket?.off()
        oldSocket?.disconnect()

        Log.d(
            "PlexusInternet",
            "InternetManager stopped"
        )
    }
}
