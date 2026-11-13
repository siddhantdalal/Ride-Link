package com.siddhantdalal.bikelink

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The secret the iPhone's Shortcuts send with every command, so that only your iPhone can control the bike phone.
 *
 * It's 8 characters from a 31-character alphabet (about 40 bits): far too many combinations to guess over a phone
 * hotspot, but short enough to type once. Look-alike characters (0/O, 1/I/L) are left out so it can't be mistyped.
 */
object BikeCode {

    private const val PREFS = "bike_link"
    private const val KEY = "bike_code"
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val LENGTH = 8

    @Synchronized
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val random = SecureRandom()
        val code = String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })
        prefs.edit().putString(KEY, code).apply()
        return code
    }

    /** "ABCD-EFGH" for display. The dash is optional when typing the code. */
    fun formatted(code: String): String = code.chunked(4).joinToString("-")

    /** Accepts the code with or without the dash, in any letter case. */
    fun matches(expected: String, supplied: String?): Boolean {
        val normalized = supplied?.filter(Char::isLetterOrDigit)?.uppercase() ?: return false
        // Constant-time comparison, so response timing doesn't reveal how much of a guess was right.
        return MessageDigest.isEqual(expected.toByteArray(), normalized.toByteArray())
    }
}
