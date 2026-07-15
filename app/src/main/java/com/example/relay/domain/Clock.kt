package com.example.relay.domain

interface Clock {
    fun nowMillis(): Long
    fun elapsedRealtimeMillis(): Long
    fun sessionId(): String
}

object SystemClock : Clock {
    private val currentSessionId = java.util.UUID.randomUUID().toString()
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMillis(): Long = android.os.SystemClock.elapsedRealtime()
    override fun sessionId(): String = currentSessionId
}

class MutableClock(initialMillis: Long) : Clock {
    var currentMillis: Long = initialMillis
    var currentElapsedRealtimeMillis: Long = initialMillis
    var currentSessionId: String = "test-session"
    override fun nowMillis(): Long = currentMillis
    override fun elapsedRealtimeMillis(): Long = currentElapsedRealtimeMillis
    override fun sessionId(): String = currentSessionId
}
