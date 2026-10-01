package com.plexus.app

/** Reconstruct transient offers from Room, including work rejected by backpressure. */
internal suspend fun retryNearbyPackets(
    manager: NearbyManager,
    relayStore: MeshRelayStore,
    antiPacketStore: AntiPacketStore,
    localNodeId: String
) {
    val peers = manager.getVerifiedNodeIds()
    if (peers.isEmpty()) return
    val vaccineCount = antiPacketStore.activeAntiPacketCount()
    for (peer in peers) {
        val packets = relayStore.getForwardCandidates(peer, localNodeId = localNodeId)
        val (messages, relays) = packets.partition {
            it.destinationNodeId == peer || it.sourceNodeId == localNodeId
        }
        for (packet in messages) {
            if (!manager.sendBytesToNode(peer, packet.toBytes(), NearbyManager.SendPriority.MESSAGE)) break
        }
        // Four historical slots per five-second scan, rotating through the full
        // history rather than filling the queue on every reconnect. Room retains
        // everything skipped. Fresh messages above never wait for this window.
        val vaccineOffset = manager.nextBackgroundOffset("$peer:vaccine", vaccineCount, 2)
        val vaccines = antiPacketStore.getPropagationWindow(vaccineOffset, 2)
        val relayOffset = manager.nextBackgroundOffset("$peer:relay", relays.size, 2)
        for (vaccine in vaccines) {
            if (!manager.isNodeVerified(peer) || !manager.hasBackgroundCapacity(peer) ||
                !PlexusDiagnostics.proofOffer("NEARBY", vaccine.messageId) {
                    manager.sendBytesToNode(peer, vaccine.toBytes(), NearbyManager.SendPriority.BACKGROUND)
                }) break
        }
        for (relay in relays.drop(relayOffset).take(2)) {
            if (!manager.isNodeVerified(peer) || !manager.hasBackgroundCapacity(peer) ||
                !manager.sendBytesToNode(peer, relay.toBytes(), NearbyManager.SendPriority.BACKGROUND)) break
        }
    }
}
