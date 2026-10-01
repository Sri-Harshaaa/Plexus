package com.plexus.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class PlexusDiagnosticsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Before fun start() {
        ShadowLog.clear()
        PlexusDiagnostics.start(scope) { PlexusDiagnostics.NearbyState(0, emptyList()) }
    }
    @After fun stop() { PlexusDiagnostics.stop(); scope.cancel() }
    private fun logs() = ShadowLog.getLogsForTag(PlexusDiagnostics.TAG).map { it.msg }

    @Test fun failedStagePreservesExceptionAndOnlyCallbackFailureIsLogged() {
        val original = IllegalStateException("private-test-marker")
        val trace = PlexusDiagnostics.callbackEnter("endpoint", 1, 2)
        try {
            PlexusDiagnostics.ingress(trace) {
                PlexusDiagnostics.timed("VERIFY", "signature") { throw original }
            }
            fail("Original exception must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(original, actual)
            PlexusDiagnostics.callbackResult(trace, "RX_CALLBACK_REJECTED_LISTENER_EXCEPTION")
        }
        assertEquals(1, logs().size)
        assertTrue(logs().single().startsWith("RX_CALLBACK_REJECTED_LISTENER_EXCEPTION"))
        assertFalse(logs().any { it.contains("private-test-marker") })
    }

    @Test fun cancelledBeforeStartDoesNotLeakPendingIngressOrFinishTwice() {
        val cancelledScope = CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.Default)
        val trace = PlexusDiagnostics.callbackEnter("endpoint", 1, 2)
        PlexusDiagnostics.ingress(trace) {
            PlexusDiagnostics.launch(cancelledScope) { fail("Cancelled ingress must not run") }
        }
        // Completion is Main-dispatched, just as production ingress is.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(logs().isEmpty())
        assertTrue(PlexusDiagnostics.counts().contains("activeIngressJobs=0 pendingIngressJobs=0"))
    }

    @Test fun roomTimingReturnsOriginalValueAndReportsHighestCrossedThreshold() = runBlocking {
        val result = PlexusDiagnostics.room("testRoomOperation") {
            ShadowSystemClock.advanceBy(Duration.ofMillis(1100))
            "unchanged"
        }
        assertEquals("unchanged", result)
        val warnings = logs().filter { it.startsWith("SLOW_OPERATION") }
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("thresholdMs=1000"))
        assertEquals(1, logs().size)
        assertTrue(warnings.single().contains("durationMs=1100"))
    }

    @Test fun cachedOutboxSnapshotAgeAdvancesWithoutReadingLiveManagerCollections() {
        val captured = PlexusDiagnostics.QueueState("endpoint", 3, 1, 2, 4, 9, 2, 100)
        assertTrue(captured.render(200).contains("inFlightAgeMs=100"))
        assertTrue(captured.render(1200).contains("inFlightAgeMs=1100"))
        assertTrue(captured.render(1200).contains("CONTROL=1 MESSAGE=2 BACKGROUND=4"))
    }

    @Test fun messageAndProofCorrelationRemainVisibleWithParticipationOffWithoutRawIdentifiers() {
        PlexusDiagnostics.stop()
        ShadowLog.clear()
        val raw = "private-full-message-identifier"
        val id = PlexusDiagnostics.shortId(raw)
        assertTrue(id.matches(Regex("[0-9a-f]{12}")))
        assertNotEquals(id, PlexusDiagnostics.shortId("different-message"))
        PlexusDiagnostics.message("OUTGOING_MESSAGE_CREATED", raw)
        val before = PlexusDiagnostics.internetMessageCount()
        PlexusDiagnostics.internetPacketReceived(raw)
        assertEquals(before + 1, PlexusDiagnostics.internetMessageCount())
        PlexusDiagnostics.message("DELIVERY_PROOF_RECEIVED", raw, "source=INTERNET")
        assertEquals(3, logs().size)
        assertTrue(logs().all { it.contains("id=$id") && !it.contains(raw) })
    }

    @Test fun proofOfferCorrelationObservesOriginalResultsWithoutNetworkParticipation() {
        PlexusDiagnostics.stop()
        ShadowLog.clear()
        val raw = "private-full-message-identifier"
        PlexusDiagnostics.message("OUTGOING_MESSAGE_CREATED", raw)
        ShadowLog.clear()
        var attempts = 0
        assertTrue(PlexusDiagnostics.proofOffer("NEARBY", raw) { attempts++; true })
        assertFalse(PlexusDiagnostics.proofOffer("NEARBY", raw) { attempts++; false })
        assertEquals(2, attempts)
        assertTrue(logs()[0].contains("status=ACCEPTED"))
        assertTrue(logs()[1].contains("status=DEFERRED"))
        assertTrue(logs().all { it.startsWith("DELIVERY_PROOF_OFFERED id=${PlexusDiagnostics.shortId(raw)}") })
        assertFalse(logs().any { it.contains(raw) })
    }

    @Test fun historicalProofStormIsSummarizedWithoutEnrollingProofIds() {
        PlexusDiagnostics.stop()
        ShadowLog.clear()
        val prefix = java.util.UUID.randomUUID().toString()
        repeat(100) { index ->
            val id = "$prefix-$index"
            PlexusDiagnostics.message("DELIVERY_PROOF_RECEIVED", id, "source=INTERNET")
            PlexusDiagnostics.message("DELIVERY_PROOF_STORE_RESULT", id, "result=DUPLICATE")
            PlexusDiagnostics.message("MARK_MESSAGE_DELIVERED", id, "rowsUpdated=0")
            PlexusDiagnostics.message("DELIVERY_PROOF_OFFERED", id, "transport=INTERNET status=ACCEPTED")
        }
        assertTrue(logs().none { it.contains("id=") })
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        PlexusDiagnostics.lifecycle("SYNC_WORK_STARTED")
        assertEquals(1, logs().count { it == "HISTORICAL_PROOFS_SUPPRESSED count=100" })
        val id = "$prefix-0"
        PlexusDiagnostics.internetPacketReceived(id)
        PlexusDiagnostics.message("DELIVERY_PROOF_RECEIVED", id, "source=INTERNET")
        assertTrue(logs().any { it.startsWith("RECEIVER_PACKET_INGRESS id=${PlexusDiagnostics.shortId(id)}") })
        assertTrue(logs().any { it.startsWith("DELIVERY_PROOF_RECEIVED id=${PlexusDiagnostics.shortId(id)}") })
    }

    @Test fun ordinaryStagesCallbacksAndOutboxEventsStaySilent() = runBlocking {
        repeat(100) {
            val trace = PlexusDiagnostics.callbackEnter("endpoint", it.toLong(), 1)
            PlexusDiagnostics.ingress(trace) {
                PlexusDiagnostics.timed("VERIFY", "signature") { true }
                PlexusDiagnostics.outbox("DRAIN", "fixed-test-state")
            }
            PlexusDiagnostics.callbackResult(trace, "RX_CALLBACK_ACCEPTED")
            assertTrue(PlexusDiagnostics.room("testRoomOperation") { true })
        }
        assertTrue(logs().isEmpty())
        assertTrue(PlexusDiagnostics.counts().contains("roomOps=0"))
    }
}
