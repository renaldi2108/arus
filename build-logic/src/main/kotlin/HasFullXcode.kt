import org.gradle.api.provider.ProviderFactory

/**
 * Detects whether a *full* Xcode installation is active, as opposed to only the
 * Xcode Command Line Tools.
 *
 * `xcode-select -p` prints the active developer directory. A Command Line Tools
 * install reports a path containing `CommandLineTools`, which is enough to
 * compile Kotlin/Native klibs but not to link Apple binaries.
 */
internal object HasFullXcode {

    fun value(providers: ProviderFactory): Boolean = runCatching {
        val path = providers.exec {
            commandLine("xcode-select", "-p")
        }.standardOutput.asText.get().trim()

        path.isNotEmpty() && !path.contains("CommandLineTools")
    }.getOrDefault(false)
}
