package com.plexus.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyRecoveryPolicyTest {
    @Test fun radioErrorRefreshesMedium() {
        assertEquals(NearbyRecoveryPolicy.MEDIUM_REFRESH, NearbyFailureClassifier.forStatus(8007))
    }
    @Test fun endpointIoErrorRemainsLocalInitially() {
        assertEquals(NearbyRecoveryPolicy.ENDPOINT, NearbyFailureClassifier.forStatus(8012))
    }
    @Test fun rejectedConnectionDoesNotDisturbHealthyPeers() {
        assertEquals(NearbyRecoveryPolicy.ENDPOINT, NearbyFailureClassifier.forStatus(13))
    }
}
