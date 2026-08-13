package com.fetch.core.log

/**
 * Minimal logging seam.
 *
 * A library should not drag a logging framework into its consumers' apps, and
 * Android's Log is awkward to assert against in unit tests. Two functions and a
 * swappable sink covers it.
 */
public object Log {

    public fun interface Sink {
        public fun log(level: Level, message: String, error: Throwable?)
    }

    public enum class Level { DEBUG, INFO, WARN, ERROR }

    @Volatile
    public var sink: Sink? = null

    @Volatile
    public var debugEnabled: Boolean = false

    public fun debug(message: String) {
        if (debugEnabled) sink?.log(Level.DEBUG, message, null)
    }

    public fun info(message: String) {
        sink?.log(Level.INFO, message, null)
    }

    public fun warn(message: String, error: Throwable? = null) {
        sink?.log(Level.WARN, message, error)
    }

    public fun error(message: String, error: Throwable? = null) {
        sink?.log(Level.ERROR, message, error)
    }
}
