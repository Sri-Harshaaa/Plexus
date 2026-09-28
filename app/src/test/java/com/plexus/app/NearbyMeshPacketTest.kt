package com.plexus.app

import org.junit.Assert.*
import org.junit.Test

class NearbyMeshPacketTest {
    @Test fun originBytesBindImmutableFieldsAndIgnoreRelayHop() {
        val packet = NearbyMeshPacket("id", "a", "b", 1, 10000, 0, "YWJj", "key", "signature")
        assertEquals("PLEXUS_MESH_ORIGIN_V1|2:id|1:a|1:b|1:1|5:10000|4:YWJj|",
            String(packet.originBytes()))
        val forwarded = packet.forwardedCopy(now = 2)!!
        assertArrayEquals(packet.originBytes(), forwarded.originBytes())
        assertEquals(1L, forwarded.hopCount)
        assertEquals(packet.originSignature, forwarded.originSignature)
        assertFalse(packet.originBytes().contentEquals(packet.copy(destinationNodeId = "c").originBytes()))
        assertFalse(packet.originBytes().contentEquals(packet.copy(encryptedPayload = "YQ==").originBytes()))
        assertFalse(packet.originBytes().contentEquals(packet.copy(expiresAt = 9999).originBytes()))
    }

    @Test fun legacySigningRaceUsesPersistedWinnerAndDropsUnsignedForeign() {
        val unsigned = NearbyMeshPacket("id", "local", "remote", 1, 10000, 0, "YWJj", "", "")
        val signed = unsigned.copy(originSigningPublicKey = "key", originSignature = "loser")
        val winner = RelayPacketEntity.fromMeshPacket(signed.copy(originSignature = "winner"))
        assertEquals(signed, resolvedLegacyPacket(signed, 1, null))
        assertEquals("winner", resolvedLegacyPacket(signed, 0, winner)?.originSignature)
        assertNull(resolvedLegacyPacket(signed, 0, null))
        assertNull(resolvedLegacyPacket(signed, 0, RelayPacketEntity.fromMeshPacket(unsigned.copy(sourceNodeId = "foreign"))))
    }
}
