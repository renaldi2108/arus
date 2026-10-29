package io.github.renaldi2108.arus.core

/**
 * Minimal internal logger gated on [Arus.debugLogging].
 *
 * Deliberately not public: applications should use their own logging. The
 * message is built lazily so that disabled logging costs one boolean read and
 * allocates nothing.
 */
internal object ArusLog {
    inline fun debug(message: () -> String) {
        if (Arus.debugLogging) {
            println("[Arus] ${message()}")
        }
    }
}
