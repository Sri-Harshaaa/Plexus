package com.plexus.app

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class ProofPublicationOutboxTest {
    private val application get() = RuntimeEnvironment.getApplication()
    private val name = "proof-publication-${UUID.randomUUID()}"
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var scope = CoroutineScope(SupervisorJob() + dispatcher)
    private lateinit var db: PlexusDatabase
    private lateinit var store: AntiPacketStore
    private lateinit var outbox: ProofPublicationOutbox
    private val offered = mutableListOf<Pair<NearbyAntiPacket, Boolean>>()
    private val keys = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val key = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val local = MessageDigest.getInstance("SHA-256").digest(keys.public.encoded)
        .joinToString("") { "%02x".format(it) }

    @Before fun open() {
        db = Room.databaseBuilder(application, PlexusDatabase::class.java, name)
            .addMigrations(PlexusDatabase.MIGRATION_10_11).allowMainThreadQueries().build()
        store = AntiPacketStore(db, local)
        outbox = ProofPublicationOutbox(db.antiPacketDao(), local, scope) { packet, background ->
            // Observe the committed production table at the actual offer boundary.
            db.openHelper.readableDatabase.query("SELECT internetPublishedAt FROM anti_packets WHERE messageId = ?",
                arrayOf(packet.messageId)).use {
                assertTrue("Offer must follow durable proof storage", it.moveToFirst())
                assertTrue("Published proof must not be offered", it.isNull(0))
            }
            offered.add(packet to background)
            true // Simulates InternetSession ACCEPTED, deliberately not an AWS ACK.
        }
    }
    @After fun close() {
        scope.cancel()
        db.close()
        dispatcher.close()
        application.deleteDatabase(name)
    }

    private fun proof(id: String = UUID.randomUUID().toString()): NearbyAntiPacket {
        val now = System.currentTimeMillis()
        val packet = NearbyAntiPacket(id, "a".repeat(64), local, key, now, now + 120_000, "")
        val bytes = "PLEXUS_DELIVERY_V1|$id|${packet.sourceNodeId}|$local|$now|${packet.expiresAt}"
            .toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private); update(bytes); sign()
        }
        return packet.copy(signature = Base64.getEncoder().encodeToString(signature))
    }
    private suspend fun persist(packet: NearbyAntiPacket, storedAt: Long = System.currentTimeMillis()) {
        assertEquals(AntiPacketStore.StoreResult.STORED, store.store(packet, storedAt, locallyCreated = true))
    }
    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(5_000) { while (!predicate()) delay(10) }
    }
    private suspend fun restart() {
        scope.cancel()
        db.close()
        offered.clear()
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        open()
    }

    @Test fun proofStoredBeforeAcceptanceRemainsPendingAndFlushCannotReportSuccess() = runBlocking(dispatcher) {
        val packet = proof()
        persist(packet)
        outbox.connected()
        outbox.offerFresh(packet)
        await { offered.isNotEmpty() }
        assertNull(db.antiPacketDao().getAntiPacket(packet.messageId)!!.internetPublishedAt)
        assertFalse(outbox.flush(100))
    }

    @Test fun exactAckIsIdempotentAndPublishedVaccineRemainsAvailableToNearby() = runBlocking(dispatcher) {
        val packet = proof()
        persist(packet)
        outbox.connected()
        await { offered.isNotEmpty() }
        outbox.acknowledge(packet.copy(signature = "wrong"))
        outbox.acknowledge(packet.copy(sourceNodeId = "b".repeat(64)))
        assertNull(db.antiPacketDao().getAntiPacket(packet.messageId)!!.internetPublishedAt)
        outbox.acknowledge(packet)
        val publishedAt = db.antiPacketDao().getAntiPacket(packet.messageId)!!.internetPublishedAt
        assertNotNull(publishedAt)
        outbox.acknowledge(packet)
        assertEquals(publishedAt, db.antiPacketDao().getAntiPacket(packet.messageId)!!.internetPublishedAt)
        assertTrue(outbox.flush(500))
        assertEquals(listOf(packet), store.getPropagationCandidates())
        val offersBefore = offered.size
        outbox.retryPending()
        delay(100)
        assertEquals(offersBefore, offered.size)
        assertEquals(AntiPacketStore.StoreResult.DUPLICATE, store.store(packet, locallyCreated = true))
        assertEquals(publishedAt, db.antiPacketDao().getAntiPacket(packet.messageId)!!.internetPublishedAt)
    }

    @Test fun disconnectAndProcessRestartRetryUnackedProofWithoutFgsAndNeverReuploadAckedProof() = runBlocking(dispatcher) {
        val packet = proof()
        persist(packet)
        outbox.connected()
        await { offered.isNotEmpty() }
        outbox.disconnected()
        assertFalse(outbox.flush(500))
        assertNull(db.antiPacketDao().getAntiPacket(packet.messageId)!!.internetPublishedAt)
        restart() // A new coordinator and real reopened Room database, no service/Nearby owner.
        assertNull(db.antiPacketDao().getAntiPacket(packet.messageId)!!.internetPublishedAt)
        outbox.connected()
        await { offered.any { it.first == packet } }
        outbox.acknowledge(packet)
        assertTrue(outbox.flush(500))
        restart()
        outbox.connected()
        assertTrue(outbox.flush(500))
        delay(100)
        assertTrue(offered.isEmpty())
        assertEquals(listOf(packet), store.getPropagationCandidates())
    }

    @Test fun hundredPublishedHistoricalProofsCannotDelayFreshProof() = runBlocking(dispatcher) {
        repeat(100) {
            val packet = proof()
            persist(packet)
            outbox.acknowledge(packet)
        }
        val fresh = proof()
        persist(fresh)
        outbox.connected()
        outbox.offerFresh(fresh)
        await { offered.isNotEmpty() }
        assertTrue(offered.all { it.first == fresh })
        assertTrue(offered.any { it.first == fresh && !it.second })
        assertEquals(101, store.getPropagationCandidates().size)
    }

    @Test fun oldestUnpublishedProofsAdvanceInBoundedAckDrivenWindows() = runBlocking(dispatcher) {
        val packets = (0 until 25).map { proof("pending-$it") }
        packets.forEachIndexed { i, packet -> persist(packet, packet.deliveredAt + i) }
        outbox.connected()
        await { offered.size == 16 }
        assertEquals(packets.take(16), offered.map { it.first })
        packets.take(16).forEach { outbox.acknowledge(it) }
        await { offered.size == 25 }
        assertEquals(packets, offered.map { it.first })
        packets.drop(16).forEach { outbox.acknowledge(it) }
        assertTrue(outbox.flush(500))
    }

    @Test fun cancelledFlushPreservesProofAndRecoveryStillWaitsForAck() = runBlocking(dispatcher) {
        val packet = proof()
        persist(packet)
        outbox.connected()
        val flush = async { outbox.flush(5_000) }
        await { offered.isNotEmpty() }
        flush.cancel()
        flush.join()
        assertNull(db.antiPacketDao().getAntiPacket(packet.messageId)!!.internetPublishedAt)
        outbox.disconnected()
        outbox.connected()
        outbox.acknowledge(packet)
        assertTrue(outbox.flush(500))
    }

    @Test fun invalidProofStillCannotCreatePublicationObligation() = runBlocking(dispatcher) {
        val invalid = proof().copy(signature = "AAAA")
        assertEquals(AntiPacketStore.StoreResult.INVALID, store.store(invalid, locallyCreated = true))
        assertNull(db.antiPacketDao().getAntiPacket(invalid.messageId))
        outbox.connected()
        outbox.offerFresh(invalid)
        assertTrue(outbox.flush(500))
        assertTrue(offered.isEmpty())
    }

    @Test fun foreignRecipientProofRemainsOnlyANearbyVaccine() = runBlocking(dispatcher) {
        val foreignKeys = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val foreign = MessageDigest.getInstance("SHA-256").digest(foreignKeys.public.encoded)
            .joinToString("") { "%02x".format(it) }
        val packet = proof().copy(recipientNodeId = foreign,
            recipientSigningPublicKey = Base64.getEncoder().encodeToString(foreignKeys.public.encoded))
        val bytes = "PLEXUS_DELIVERY_V1|${packet.messageId}|${packet.sourceNodeId}|$foreign|${packet.deliveredAt}|${packet.expiresAt}"
            .toByteArray(Charsets.UTF_8)
        val signed = packet.copy(signature = Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").run { initSign(foreignKeys.private); update(bytes); sign() }))
        assertEquals(AntiPacketStore.StoreResult.STORED, store.store(signed))
        outbox.connected()
        outbox.offerFresh(signed)
        outbox.acknowledge(signed)
        assertTrue(outbox.flush(500))
        assertTrue(offered.isEmpty())
        assertNull(db.antiPacketDao().getAntiPacket(signed.messageId)!!.internetPublishedAt)
        assertEquals(listOf(signed), store.getPropagationCandidates())
    }

    @Test fun versionTenMigrationPreservesProofAndStartsWithUnknownPublicationPending() = runBlocking(dispatcher) {
        val packet = proof()
        persist(packet)
        outbox.acknowledge(packet)
        val contact = ContactEntity("a".repeat(64), "Saved contact", key, key,
            relationshipState = ContactRelationshipState.CONNECTED)
        val history = MessageEntity("retained-history", contact.nodeId, "Test history", false, packet.deliveredAt)
        db.contactDao().insertContact(contact)
        db.messageDao().insertMessage(history)
        db.close()
        // Reconstruct the real v10 anti_packets shape inside an otherwise intact database.
        SQLiteDatabase.openDatabase(application.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
            sql.execSQL("ALTER TABLE anti_packets RENAME TO anti_packets_v11")
            sql.execSQL("""CREATE TABLE anti_packets (messageId TEXT NOT NULL PRIMARY KEY,
                sourceNodeId TEXT NOT NULL, recipientNodeId TEXT NOT NULL, recipientSigningPublicKey TEXT NOT NULL,
                deliveredAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL, signature TEXT NOT NULL, storedAt INTEGER NOT NULL)""")
            sql.execSQL("""INSERT INTO anti_packets SELECT messageId, sourceNodeId, recipientNodeId,
                recipientSigningPublicKey, deliveredAt, expiresAt, signature, storedAt FROM anti_packets_v11""")
            sql.execSQL("DROP TABLE anti_packets_v11")
            sql.version = 10
        }
        open() // Room executes and validates the actual migration.
        val row = db.antiPacketDao().getAntiPacket(packet.messageId)!!
        assertEquals(packet, row.toNearbyAntiPacket())
        assertNull(row.internetPublishedAt)
        assertEquals(contact, db.contactDao().getContactById(contact.nodeId))
        assertEquals(history, db.messageDao().getMessageById(history.messageId))
        assertEquals(1, db.antiPacketDao().observeInternetPending(local, System.currentTimeMillis()).first().size)
        outbox.connected()
        await { offered.any { it.first == packet } }
    }
}
