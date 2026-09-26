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
    val vaccines = antiPacketStore.getPropagationCandidates()
    for (peer in peers) {
        val packets = relayStore.getForwardCandidates(peer, localNodeId = localNodeId)
        val (messages, relays) = packets.partition {
            it.destinationNodeId == peer || it.sourceNodeId == localNodeId
        }
        for (packet in messages) {
            if (!manager.sendBytesToNode(peer, packet.toBytes(), NearbyManager.SendPriority.MESSAGE)) break
        }
        // Alternate vaccines and relay traffic; neither historical category owns the backlog.
        for (index in 0 until maxOf(vaccines.size, relays.size)) {
            if (!manager.isNodeVerified(peer)) break
            val vaccine = vaccines.getOrNull(index)
            if (vaccine != null &&
                !manager.sendBytesToNode(peer, vaccine.toBytes(), NearbyManager.SendPriority.BACKGROUND)
            ) break
            val relay = relays.getOrNull(index)
            if (relay != null &&
                !manager.sendBytesToNode(peer, relay.toBytes(), NearbyManager.SendPriority.BACKGROUND)
            ) break
        }
    }
}
