package com.plexus.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase


@Database(
    entities = [
        ContactEntity::class,
        MessageEntity::class,
        RelayPacketEntity::class,
        AntiPacketEntity::class,
        ConnectionRequestEntity::class,
        ConversationStateEntity::class
    ],
    version = 11,
    exportSchema = false
)
abstract class PlexusDatabase :
    RoomDatabase() {


    /*
     * DAO for contacts / relationship state.
     */
    abstract fun contactDao():
            ContactDao


    /*
     * DAO for messages.
     */
    abstract fun messageDao():
            MessageDao


    /*
     * DAO for persistent mesh relay packets.
     */
    abstract fun relayPacketDao():
            RelayPacketDao


    /*
     * DAO for persistent signed anti-packets / vaccines.
     */
    abstract fun antiPacketDao():
            AntiPacketDao


    /*
     * DAO for durable signed connection requests.
     */
    abstract fun connectionRequestDao():
            ConnectionRequestDao


    /*
     * DAO for local-only hidden/deleted conversation state.
     */
    abstract fun conversationStateDao():
            ConversationStateDao


    companion object {


        @Volatile
        private var INSTANCE:
                PlexusDatabase? = null


        /*
         * =====================================================
         * MIGRATION 1 -> 2
         * =====================================================
         *
         * Database version 1 had:
         *
         * contacts
         *
         * Database version 2 added:
         *
         * messages
         */

        private val MIGRATION_1_2 =
            object : Migration(
                1,
                2
            ) {

                override fun migrate(
                    database:
                    SupportSQLiteDatabase
                ) {


                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS messages (
                            messageId TEXT NOT NULL,
                            contactNodeId TEXT NOT NULL,
                            text TEXT NOT NULL,
                            isMine INTEGER NOT NULL,
                            timestamp INTEGER NOT NULL,
                            PRIMARY KEY(messageId)
                        )
                        """.trimIndent()
                    )
                }
            }



        /*
         * =====================================================
         * MIGRATION 2 -> 3
         * =====================================================
         *
         * Database version 3 added:
         *
         * relay_packets
         *
         * These store encrypted mesh packets that this device
         * temporarily carries for other Plexus nodes.
         */

        private val MIGRATION_2_3 =
            object : Migration(
                2,
                3
            ) {

                override fun migrate(
                    database:
                    SupportSQLiteDatabase
                ) {


                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS relay_packets (
                            packetId TEXT NOT NULL,
                            sourceNodeId TEXT NOT NULL,
                            destinationNodeId TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            expiresAt INTEGER NOT NULL,
                            hopCount INTEGER NOT NULL,
                            encryptedPayload TEXT NOT NULL,
                            storedAt INTEGER NOT NULL,
                            PRIMARY KEY(packetId)
                        )
                        """.trimIndent()
                    )
                }
            }



        /*
         * =====================================================
         * MIGRATION 3 -> 4
         * =====================================================
         *
         * Database version 4 added:
         *
         * anti_packets
         *
         * These are small signed delivery tombstones.
         */

        private val MIGRATION_3_4 =
            object : Migration(
                3,
                4
            ) {

                override fun migrate(
                    database:
                    SupportSQLiteDatabase
                ) {


                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS anti_packets (
                            messageId TEXT NOT NULL,
                            sourceNodeId TEXT NOT NULL,
                            recipientNodeId TEXT NOT NULL,
                            recipientSigningPublicKey TEXT NOT NULL,
                            deliveredAt INTEGER NOT NULL,
                            expiresAt INTEGER NOT NULL,
                            signature TEXT NOT NULL,
                            storedAt INTEGER NOT NULL,
                            PRIMARY KEY(messageId)
                        )
                        """.trimIndent()
                    )
                }
            }



        /*
         * =====================================================
         * MIGRATION 4 -> 5
         * =====================================================
         *
         * Database version 5 introduced relationship state.
         *
         * Existing contacts were already trusted, therefore
         * every old contact becomes CONNECTED.
         */

        private val MIGRATION_4_5 =
            object : Migration(
                4,
                5
            ) {

                override fun migrate(
                    database:
                    SupportSQLiteDatabase
                ) {


                    database.execSQL(
                        """
                        ALTER TABLE contacts
                        ADD COLUMN announcedDisplayName TEXT
                        """.trimIndent()
                    )


                    database.execSQL(
                        """
                        ALTER TABLE contacts
                        ADD COLUMN relationshipState TEXT NOT NULL
                        DEFAULT 'CONNECTED'
                        """.trimIndent()
                    )


                    database.execSQL(
                        """
                        ALTER TABLE contacts
                        ADD COLUMN pendingRequestId TEXT
                        """.trimIndent()
                    )


                    /*
                     * Old displayName was the only name we knew.
                     *
                     * Preserve it as the initial announced name
                     * while leaving displayName unchanged.
                     */
                    database.execSQL(
                        """
                        UPDATE contacts
                        SET announcedDisplayName = displayName
                        WHERE announcedDisplayName IS NULL
                        """.trimIndent()
                    )
                }
            }



        /*
         * =====================================================
         * MIGRATION 5 -> 6
         * =====================================================
         *
         * Database version 6 introduces durable signed
         * connection-request storage.
         *
         * This allows requests to survive:
         *
         * - app restart
         * - phone restart
         * - internet loss
         * - temporary absence of Nearby peers
         *
         * requestId is the primary key, giving us persistent
         * duplicate protection when the same packet arrives
         * through multiple transports.
         */

        private val MIGRATION_5_6 =
            object : Migration(
                5,
                6
            ) {

                override fun migrate(
                    database:
                    SupportSQLiteDatabase
                ) {


                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS connection_requests (
                            requestId TEXT NOT NULL,
                            senderNodeId TEXT NOT NULL,
                            recipientNodeId TEXT NOT NULL,
                            senderDisplayName TEXT NOT NULL,
                            senderSigningPublicKey TEXT NOT NULL,
                            senderAgreementPublicKey TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            expiresAt INTEGER NOT NULL,
                            signature TEXT NOT NULL,
                            storedAt INTEGER NOT NULL,
                            PRIMARY KEY(requestId)
                        )
                        """.trimIndent()
                    )
                }
            }



        /*
         * =====================================================
         * MIGRATION 6 -> 7
         * =====================================================
         *
         * Database version 7 adds persistent read state to
         * messages so Home can display durable unread badges.
         *
         * Existing messages are intentionally marked read.
         * Otherwise upgrading an existing Plexus installation
         * could turn all historical incoming messages into
         * unread notifications.
         */

        private val MIGRATION_6_7 =
            object : Migration(
                6,
                7
            ) {

                override fun migrate(
                    database:
                    SupportSQLiteDatabase
                ) {


                    database.execSQL(
                        """
                        ALTER TABLE messages
                        ADD COLUMN isRead INTEGER NOT NULL DEFAULT 1
                        """.trimIndent()
                    )
                }
            }



        /*
         * =====================================================
         * MIGRATION 7 -> 8
         * =====================================================
         *
         * Database version 8 adds chat presentation/protocol metadata:
         *
         * - deliveryStatus
         * - transportType
         * - replyToMessageId
         *
         * Historical route data cannot be reconstructed, so old messages
         * migrate as UNKNOWN transport.
         *
         * Historical outgoing messages remain SENT unless this device still
         * has a signed anti-packet proving final delivery.
         */

        private val MIGRATION_7_8 =
            object : Migration(
                7,
                8
            ) {

                override fun migrate(
                    database:
                    SupportSQLiteDatabase
                ) {


                    database.execSQL(
                        """
                        ALTER TABLE messages
                        ADD COLUMN deliveryStatus TEXT NOT NULL DEFAULT 'SENT'
                        """.trimIndent()
                    )


                    database.execSQL(
                        """
                        ALTER TABLE messages
                        ADD COLUMN transportType TEXT NOT NULL DEFAULT 'UNKNOWN'
                        """.trimIndent()
                    )


                    database.execSQL(
                        """
                        ALTER TABLE messages
                        ADD COLUMN replyToMessageId TEXT DEFAULT NULL
                        """.trimIndent()
                    )


                    /*
                     * Incoming rows are already on this final device, therefore
                     * they are semantically delivered even though ticks are only
                     * rendered for outgoing messages.
                     */
                    database.execSQL(
                        """
                        UPDATE messages
                        SET deliveryStatus = 'DELIVERED'
                        WHERE isMine = 0
                        """.trimIndent()
                    )


                    /*
                     * Recover delivery state for historical outgoing messages
                     * whenever the signed delivery vaccine is still present.
                     */
                    database.execSQL(
                        """
                        UPDATE messages
                        SET deliveryStatus = 'DELIVERED'
                        WHERE isMine = 1
                          AND messageId IN (
                              SELECT messageId
                              FROM anti_packets
                          )
                        """.trimIndent()
                    )
                }
            }



        /*
         * =====================================================
         * MIGRATION 8 -> 9
         * =====================================================
         *
         * Version 9 adds LOCAL conversation visibility state.
         * Deleting a chat hides its Home card without destroying the trusted
         * ContactEntity or cryptographic relationship. A later incoming message
         * can simply unhide the row and recreate the conversation.
         */
        private val MIGRATION_8_9 =
            object : Migration(
                8,
                9
            ) {

                override fun migrate(
                    database:
                    SupportSQLiteDatabase
                ) {

                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS conversation_states (
                            contactNodeId TEXT NOT NULL,
                            isHidden INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(contactNodeId)
                        )
                        """.trimIndent()
                    )
                }
            }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE relay_packets ADD COLUMN originSigningPublicKey TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE relay_packets ADD COLUMN originSignature TEXT NOT NULL DEFAULT ''")
            }
        }


        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Existing proofs have no recorded AWS ACK: retry them once, idempotently.
                database.execSQL("ALTER TABLE anti_packets ADD COLUMN internetPublishedAt INTEGER")
            }
        }



        /*
         * =====================================================
         * DATABASE INSTANCE
         * =====================================================
         */

        fun getDatabase(
            context: Context
        ): PlexusDatabase {


            return INSTANCE
                ?: synchronized(this) {


                    val instance =
                        Room.databaseBuilder(

                            context.applicationContext,

                            PlexusDatabase::class.java,

                            "plexus_database"
                        )


                            /*
                             * Existing installations can upgrade:
                             *
                             * 1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8 -> 9 -> 10 -> 11
                             *
                             * without deleting existing data.
                             */
                            .addMigrations(

                                MIGRATION_1_2,

                                MIGRATION_2_3,

                                MIGRATION_3_4,

                                MIGRATION_4_5,

                                MIGRATION_5_6,

                                MIGRATION_6_7,

                                MIGRATION_7_8,

                                MIGRATION_8_9,
                                MIGRATION_9_10,
                                MIGRATION_10_11
                            )


                            /*
                             * WAL gives us better concurrent
                             * read/write behavior.
                             */
                            .setJournalMode(
                                JournalMode.WRITE_AHEAD_LOGGING
                            )


                            .build()


                    INSTANCE =
                        instance


                    instance
                }
        }
    }
}
