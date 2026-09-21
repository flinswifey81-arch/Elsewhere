package com.example.domain.compiler

internal object IdentityMacroResolver {
    private val supportedMacros = Regex("\\{char}|\\{user}")

    fun resolve(
        source: String,
        characterDisplayName: String,
        personaDisplayName: String
    ): String = supportedMacros.replace(source) { match ->
        when (match.value) {
            "{char}" -> characterDisplayName
            "{user}" -> personaDisplayName
            else -> match.value
        }
    }
}
