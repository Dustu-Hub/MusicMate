package com.music.shitmate.data.listentogether

/**
 * The public invite URL for a party, and the only shape that is accepted back.
 *
 * Shared because both applications have to agree on it exactly: a link built on one and opened on
 * the other must resolve to the same code, and a link that is not this shape must be refused on
 * both rather than half-parsed into a join attempt.
 */
object JamInvite {

    const val ORIGIN = "https://shitmate-listen-together.onrender.com"
    private const val LEGACY_ORIGIN = "https://shitmate.kushagrasingh.in"

    /** How many characters a party code carries. */
    const val CODE_LENGTH = 6

    /** The normalized party code, for the public invite URL shape only. */
    fun parse(value: String?): String? {
        val text = value?.trim() ?: return null
        val prefix = "$ORIGIN/invite/"
        val legacyPrefix = "$LEGACY_ORIGIN/invite/"
        val codeCandidate = when {
            text.startsWith(prefix, ignoreCase = true) -> text.substring(prefix.length)
            text.startsWith(legacyPrefix, ignoreCase = true) -> text.substring(legacyPrefix.length)
            else -> return null
        }
        val code = codeCandidate.substringBefore('?').substringBefore('#')
        if (code.length != CODE_LENGTH || !code.all(Char::isLetterOrDigit)) return null
        return code.uppercase()
    }

    fun url(code: String): String = "$ORIGIN/invite/${code.uppercase()}"
}
