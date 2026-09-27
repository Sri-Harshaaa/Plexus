package com.plexus.app

/** A terminal event consumes its token exactly once, including a timeout. */
internal class StartAttemptGate {
    var generation = 0L
        private set
    private var pending = false
    fun begin(): Long { check(!pending); pending = true; return ++generation }
    fun complete(token: Long): Boolean {
        if (!pending || token != generation) return false
        pending = false
        return true
    }
    fun invalidate() { pending = false; generation++ }
}
