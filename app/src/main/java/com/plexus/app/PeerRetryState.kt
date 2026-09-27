package com.plexus.app

/** Discovery hints are scheduling keys only, never authenticated routing identities. */
internal class PeerRetryState(private val now: () -> Long) {
    data class Candidate(val key: String, val endpoint: String, val name: String, val generation: Long)
    data class Retry(val candidate: Candidate, val deadline: Long)
    private var generation = 0L
    private val candidates = linkedMapOf<String, Candidate>()
    private val busy = mutableSetOf<String>()
    val cooldown = PeerCooldownState(timeProvider = now)

    fun found(endpoint: String, name: String): Candidate? {
        val key = NearbyEndpointName.key(name)
        val old = candidates[key]
        if (old?.endpoint == endpoint) return old
        if (old == null && candidates.size >= 64) return null
        return Candidate(key, endpoint, name, ++generation).also { candidates[key] = it }
    }
    fun candidate(endpoint: String) = candidates.values.firstOrNull { it.endpoint == endpoint }
    fun lost(endpoint: String) {
        candidate(endpoint)?.let { candidates.remove(it.key) }
    }
    fun begin(candidate: Candidate): Boolean {
        if (candidates[candidate.key] != candidate || candidate.key in busy || cooldown.isCoolingDown(candidate.key)) return false
        busy.add(candidate.key)
        return true
    }
    fun failed(candidate: Candidate): Retry? {
        busy.remove(candidate.key)
        val delay = cooldown.applyFailure(candidate.key)
        return candidates[candidate.key]?.let { Retry(it, now() + delay) }
    }
    fun retry(candidate: Candidate): Retry? = candidates[candidate.key]?.let {
        Retry(it, cooldown.getExpireAt(it.key) ?: now())
    }
    fun ready(retry: Retry): Boolean = candidates[retry.candidate.key] == retry.candidate &&
        retry.candidate.key !in busy && now() >= retry.deadline && !cooldown.isCoolingDown(retry.candidate.key)
    fun authenticated(candidate: Candidate) { cooldown.clear(candidate.key); busy.add(candidate.key) }
    fun clearCandidates() { candidates.clear() }
    fun clear() { candidates.clear(); busy.clear(); cooldown.clearAll() }
}

internal object NearbyEndpointName {
    // v2 peers use full stable node IDs for role selection. Legacy peers retain
    // their original lexical comparison, so an upgrade does not create two waiters.
    fun create(nodeId: String, displayName: String) = "p2:$nodeId:${displayName.take(12)}"
    fun node(name: String): String? = name.takeIf { it.startsWith("p2:") }
        ?.split(':', limit = 3)?.getOrNull(1)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    fun key(name: String): String = node(name) ?: name.substringAfterLast('-')
        .takeIf { it.matches(Regex("[0-9a-f]{8}")) } ?: name
    fun initiates(local: String, remote: String): Boolean {
        val localNode = node(local)
        val remoteNode = node(remote)
        if (localNode != null && remoteNode != null) return localNode < remoteNode
        return local < remote
    }
}
