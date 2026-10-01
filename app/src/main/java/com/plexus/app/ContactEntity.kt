package com.plexus.app

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey


/*
 * Relationship between this device and another Plexus identity.
 *
 * These are stored as Strings in Room deliberately.
 *
 * That keeps the database schema simple and avoids needing a
 * Room TypeConverter just for an enum.
 */
object ContactRelationshipState {
    const val BLOCKED = "BLOCKED"
    const val BLOCKED_CONNECTED = "BLOCKED_CONNECTED"

    fun isBlocked(state: String?): Boolean = state == BLOCKED || state == BLOCKED_CONNECTED
    const val NONE = "NONE"

    fun canRequest(state: String?): Boolean = state == null || state == DECLINED || state == NONE
    // An explicit QR/link import can supersede an outgoing attempt whose closure never arrived.
    // Incoming requests still use canRequest: they cannot replace a pending user decision.
    fun canInitiateRequest(state: String?): Boolean = canRequest(state) || state == OUTGOING_PENDING
    fun canReceiveMessages(state: String?): Boolean = state == CONNECTED || state == ACCEPTED_NEEDS_NAME


    /*
     * We sent a connection request to this identity
     * and are waiting for them to accept or decline it.
     */
    const val OUTGOING_PENDING =
        "OUTGOING_PENDING"


    /*
     * This identity sent us a connection request.
     *
     * The user still needs to Accept or Decline.
     */
    const val INCOMING_PENDING =
        "INCOMING_PENDING"


    /*
     * The remote user accepted our request.
     *
     * Cryptographic trust is already established.
     *
     * We are only waiting for the local user to confirm
     * what name they want to save this person as.
     */
    const val ACCEPTED_NEEDS_NAME =
        "ACCEPTED_NEEDS_NAME"


    /*
     * Fully accepted Plexus contact.
     *
     * Normal chat UI can show this contact.
     */
    const val CONNECTED =
        "CONNECTED"


    /*
     * A connection request was declined.
     *
     * This is NOT blocking.
     *
     * A future connection request can still be made.
     */
    const val DECLINED =
        "DECLINED"
}


@Entity(
    tableName = "contacts"
)
data class ContactEntity(

    /*
     * Persistent cryptographic Plexus identity.
     *
     * nodeId =
     * SHA-256(signing public key)
     *
     * This remains the primary key because one physical
     * Plexus identity must correspond to one contact row.
     */
    @PrimaryKey
    val nodeId: String,


    /*
     * LOCAL NAME shown on this phone.
     *
     * Example:
     *
     * Remote user announces:
     *     "Vishnu"
     *
     * Harsha saves him locally as:
     *     "Bunny"
     *
     * Then:
     *
     * displayName = "Bunny"
     *
     * Existing Plexus UI already reads contact.displayName,
     * so keeping this field also avoids breaking the current UI.
     */
    val displayName: String,


    /*
     * Signing public key belonging to this nodeId.
     *
     * Used for:
     *
     * - connection-request signatures
     * - acceptance signatures
     * - authentication proofs
     * - anti-packet verification
     */
    val signingPublicKey: String,


    /*
     * Long-term ECDH agreement public key.
     *
     * Used by PlexusCrypto to derive the E2E encryption key.
     */
    val agreementPublicKey: String,


    /*
     * Name this person most recently announced as THEIR
     * own Plexus display name.
     *
     * This is different from displayName.
     *
     * Example:
     *
     * announcedDisplayName = "Vishnu"
     * displayName          = "Bunny"
     *
     * Their future self-name changes must not silently
     * overwrite our locally chosen alias.
     *
     * Nullable so existing contacts can migrate safely.
     */
    val announcedDisplayName: String? =
        null,


    /*
     * Current relationship state.
     *
     * Existing Plexus contacts are already trusted contacts,
     * so during our Room migration they will receive
     * CONNECTED as the default.
     */
    @ColumnInfo(
        defaultValue = "'CONNECTED'"
    )
    val relationshipState: String =
        ContactRelationshipState.CONNECTED,


    /*
     * ID of the connection request currently associated
     * with this relationship.
     *
     * Used to correlate:
     *
     * request
     *      ->
     * acceptance / decline
     *
     * null for old contacts and relationships that no
     * longer need a pending request reference.
     */
    val pendingRequestId: String? =
        null
)
