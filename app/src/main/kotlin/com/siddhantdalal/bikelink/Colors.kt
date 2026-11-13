package com.siddhantdalal.bikelink

/** This ARGB colour with its alpha replaced by [alpha] (0 to 1). */
fun Int.withAlpha(alpha: Float): Int =
    (this and 0x00FFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)
