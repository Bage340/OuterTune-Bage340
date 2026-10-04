package com.dd3boh.outertune.utils.potoken

internal class PoTokenOwnedWork(private val cancelScope: () -> Unit, private val destroy: () -> Unit) {
    private val cancellations = mutableSetOf<() -> Unit>()
    @Volatile
    var isClosed = false
        private set

    fun track(cancel: () -> Unit): () -> Unit {
        val cancelNow = synchronized(cancellations) {
            if (isClosed) true else { cancellations.add(cancel); false }
        }
        if (cancelNow) cancel()
        return cancel
    }

    fun release(cancel: () -> Unit) { synchronized(cancellations) { cancellations.remove(cancel) } }

    fun close() {
        val owned = synchronized(cancellations) {
            if (isClosed) return
            isClosed = true
            cancellations.toList().also { cancellations.clear() }
        }
        cancelScope()
        owned.forEach { it() }
        destroy()
    }
}
