package com.plexus.app

/** Main-thread owned, bounded scheduling state. Room owns delivery and retry, not this queue. */
internal class EndpointOutbox(val session: Long) {
    data class Item(val key: String, val bytes: ByteArray, val priority: Int)
    data class Transfer(val session: Long, val payloadId: Long, val item: Item)

    private val queues = List(3) { LinkedHashMap<String, Item>() }
    private val completed = LinkedHashMap<String, Long>()
    private var consecutiveControl = 0
    var inFlight: Transfer? = null
        private set
    val size: Int get() = queues.sumOf { it.size }

    fun offer(key: String, bytes: ByteArray, priority: Int, now: Long): Boolean {
        require(priority in 0..2)
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return false
        // An explicit response to retried ciphertext may need to repair a lost delivery proof.
        if (priority < 2 && key.startsWith("anti:")) completed.remove(key)
        if (inFlight?.item?.key == key || (completed[key] ?: Long.MIN_VALUE) > now) return true
        completed.remove(key)
        val existing = queues.indexOfFirst { it.containsKey(key) }
        if (existing >= 0) {
            if (priority < existing && hasCapacity(priority, queues[existing].getValue(key).bytes.size)) {
                val item = queues[existing].remove(key)!!
                queues[priority][key] = item.copy(priority = priority)
            }
            return true
        }
        // Reserve independent capacity for control and messages. Background cannot consume it.
        if (!hasCapacity(priority, bytes.size)) return false
        queues[priority][key] = Item(key, bytes.copyOf(), priority)
        return true
    }

    fun poll(): Item? {
        if (inFlight != null) return null
        // Authentication still leads, but a continuous control stream cannot starve chat.
        if (consecutiveControl >= 8 && queues[1].isNotEmpty()) {
            val entry = queues[1].entries.first()
            queues[1].remove(entry.key)
            consecutiveControl = 0
            return entry.value
        }
        for (queue in queues) {
            val entry = queue.entries.firstOrNull() ?: continue
            queue.remove(entry.key)
            consecutiveControl = if (entry.value.priority == 0) consecutiveControl + 1 else 0
            return entry.value
        }
        return null
    }

    private fun hasCapacity(priority: Int, bytes: Int): Boolean =
        queues[priority].size < LIMITS[priority] &&
            queues[priority].values.sumOf { it.bytes.size } + bytes <= BYTE_LIMITS[priority]

    fun discardQueued(prefix: String) {
        queues.forEach { queue -> queue.keys.removeAll { it.startsWith(prefix) } }
    }

    fun start(payloadId: Long, item: Item): Transfer {
        check(inFlight == null)
        return Transfer(session, payloadId, item).also { inFlight = it }
    }

    fun matches(transfer: Transfer): Boolean = inFlight === transfer && transfer.session == session

    fun finish(transfer: Transfer, now: Long): Boolean {
        if (!matches(transfer)) return false
        inFlight = null
        // Suppress retry scans briefly; this is transport success, never a delivery receipt.
        completed[transfer.item.key] = now +
            if (transfer.item.key.startsWith("anti:")) 300_000L else RETRY_SUPPRESSION_MS
        while (completed.size > 8192) completed.remove(completed.keys.first())
        return true
    }

    companion object {
        const val MAX_BYTES = 32 * 1024
        const val TRANSFER_TIMEOUT_MS = 12_000L
        const val RETRY_SUPPRESSION_MS = 60_000L
        private val LIMITS = intArrayOf(32, 128, 64)
        private val BYTE_LIMITS = intArrayOf(128 * 1024, 1024 * 1024, 256 * 1024)
    }
}
