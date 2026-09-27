package com.plexus.app

internal enum class NearbyRecoveryPolicy { ENDPOINT, MEDIUM_REFRESH }

internal object NearbyFailureClassifier {
    // Play Services STATUS_RADIO_ERROR is medium-wide. STATUS_ENDPOINT_IO_ERROR
    // is endpoint-local initially; repeated failures escalate in NearbyManager.
    fun forStatus(statusCode: Int): NearbyRecoveryPolicy =
        if (statusCode == 8007) NearbyRecoveryPolicy.MEDIUM_REFRESH else NearbyRecoveryPolicy.ENDPOINT
}
