package com.plexus.app

/** Claims are usable for challenges only. Routing switches only after proof verification. */
internal class PeerRoutes {
    val claims = mutableMapOf<String, String>()
    val verified = mutableMapOf<String, String>()

    fun claim(endpoint: String, node: String) {
        val previous = claims.put(endpoint, node)
        if (previous != null && previous != node && verified[previous] == endpoint) {
            verified.remove(previous)
        }
    }

    fun promote(endpoint: String): String? {
        val node = claims[endpoint] ?: return null
        val previous = verified.put(node, endpoint)
        if (previous != null && previous != endpoint) claims.remove(previous)
        return previous?.takeIf { it != endpoint }
    }

    fun remove(endpoint: String) {
        val node = claims.remove(endpoint) ?: return
        if (verified[node] == endpoint) verified.remove(node)
    }

    fun clear() {
        claims.clear()
        verified.clear()
    }
}
