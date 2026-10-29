package io.github.renaldi2108.arus.nav

import androidx.navigation3.runtime.NavKey

/**
 * A destination in the back stack.
 *
 * Routes are typed values, not strings: declare them as `@Serializable` data
 * classes or objects so arguments are checked at compile time and the stack can
 * be persisted across process death.
 *
 * ```kotlin
 * @Serializable data object HomeRoute : Route
 * @Serializable data class ProfileRoute(val userId: String) : Route
 * ```
 *
 * Two routes that compare equal are treated as the same destination, so a data
 * class carrying identical arguments pushed twice produces two entries that are
 * indistinguishable by value. Add a unique field if you need to tell them apart.
 *
 * **Serialization on non-JVM targets.** Restoring the back stack uses
 * reflection by default, which does not exist on Kotlin/Native or Wasm. iOS apps
 * must therefore register every route explicitly and pass the resulting
 * configuration to [rememberArusNavigator]; see [arusSavedStateConfiguration].
 */
public interface Route : NavKey
