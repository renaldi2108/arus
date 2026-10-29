package io.github.renaldi2108.arus.core

/**
 * Global configuration for the Arus framework.
 *
 * Holds **only** primitive configuration flags. It never holds a navigator, a
 * controller, a scope, or any UI reference — those are always injected.
 *
 * A global that held a view would leak an Activity, would survive process
 * death pointing at nothing, and would make two windows overwrite each
 * other's state.
 */
public object Arus {
    /**
     * Enables verbose logging of controller creation/disposal and navigation events.
     *
     * Intended for debug builds only; leave it `false` in release. Defaults to `false`.
     */
    public var debugLogging: Boolean = false
}
