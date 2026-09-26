package com.plexus.app

internal object MeshPolicy {
    const val MAX_MESH_HOPS = 8L
    const val MAX_PACKET_BYTES = 32 * 1024

    fun validLifetime(createdAt: Long, expiresAt: Long, hopCount: Long, now: Long): Boolean =
        createdAt > 0 && expiresAt > createdAt &&
            expiresAt - createdAt <= PacketReplayGuard.MESSAGE_TTL_MS &&
            createdAt <= now + PacketReplayGuard.MAX_FUTURE_CLOCK_SKEW_MS &&
            expiresAt > now && hopCount in 0..MAX_MESH_HOPS

    fun nextHop(hopCount: Long, expiresAt: Long, now: Long): Long? =
        if (hopCount in 0 until MAX_MESH_HOPS && expiresAt > now) hopCount + 1 else null
}
