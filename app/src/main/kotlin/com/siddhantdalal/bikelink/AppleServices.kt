package com.siddhantdalal.bikelink

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Apple's two Bluetooth Low Energy services for accessories, which give the bike phone the iPhone's calls and music:
 *
 *  - ANCS (Apple Notification Center Service): the iPhone's notifications, including incoming calls, and actions on
 *    them, such as answering or declining a call.
 *  - AMS (Apple Media Service): what's playing, and play/pause/skip commands.
 *
 * This file only encodes and decodes their byte formats, following Apple's published specifications. It has no
 * Android dependencies, so it's unit-tested on the JVM. The Bluetooth connection itself lives in [IphoneLink].
 */
object Ancs {

    val SERVICE: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val NOTIFICATION_SOURCE: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CONTROL_POINT: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val DATA_SOURCE: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")

    const val EVENT_ADDED = 0
    const val EVENT_MODIFIED = 1
    const val EVENT_REMOVED = 2

    const val CATEGORY_INCOMING_CALL = 1

    const val ATTRIBUTE_TITLE = 1 // for an incoming call, the caller's name (or number)

    private const val COMMAND_GET_NOTIFICATION_ATTRIBUTES = 0
    private const val COMMAND_PERFORM_NOTIFICATION_ACTION = 2
    private const val ACTION_POSITIVE = 0 // for a call: answer
    private const val ACTION_NEGATIVE = 1 // for a call: decline

    /** One event from the Notification Source: a notification was added, modified or removed. */
    data class Event(val eventId: Int, val categoryId: Int, val uid: Int)

    /** Decodes an 8-byte Notification Source packet, or returns null if it's malformed. */
    fun parseEvent(bytes: ByteArray): Event? {
        if (bytes.size < 8) return null
        return Event(eventId = bytes.u8(0), categoryId = bytes.u8(2), uid = bytes.le32(4))
    }

    /** Asks for a notification's title (for a call, who's calling), up to [maxLength] bytes. */
    fun getTitle(uid: Int, maxLength: Int = 64): ByteArray = ByteArrayOutputStream().apply {
        write(COMMAND_GET_NOTIFICATION_ATTRIBUTES)
        writeLe32(uid)
        write(ATTRIBUTE_TITLE)
        writeLe16(maxLength)
    }.toByteArray()

    /** Answers ([answer] = true) or declines an incoming call. */
    fun callAction(uid: Int, answer: Boolean): ByteArray = ByteArrayOutputStream().apply {
        write(COMMAND_PERFORM_NOTIFICATION_ACTION)
        writeLe32(uid)
        write(if (answer) ACTION_POSITIVE else ACTION_NEGATIVE)
    }.toByteArray()

    /** A decoded reply to a Get Notification Attributes command. */
    data class Attributes(val uid: Int, val values: Map<Int, String>)

    /**
     * Reassembles a reply to [getTitle] from the Data Source. A long reply can arrive split over several Bluetooth
     * packets, so pieces are buffered until every attribute in [expected] is complete.
     */
    class AttributeReader(private val expected: Set<Int> = setOf(ATTRIBUTE_TITLE)) {
        private val buffer = ByteArrayOutputStream()

        /** Adds one Data Source packet. Returns the attributes once complete, otherwise null. */
        fun add(packet: ByteArray): Attributes? {
            buffer.write(packet)
            val bytes = buffer.toByteArray()
            if (bytes.isNotEmpty() && bytes.u8(0) != COMMAND_GET_NOTIFICATION_ATTRIBUTES) {
                buffer.reset() // not the start of a reply: drop it rather than misread what follows
                return null
            }
            if (bytes.size < 5) return null
            val values = mutableMapOf<Int, String>()
            var i = 5
            while (!values.keys.containsAll(expected)) {
                if (i + 3 > bytes.size) return null
                val id = bytes.u8(i)
                val length = bytes.le16(i + 1)
                if (i + 3 + length > bytes.size) return null
                values[id] = String(bytes, i + 3, length, Charsets.UTF_8)
                i += 3 + length
            }
            buffer.reset()
            return Attributes(uid = bytes.le32(1), values = values)
        }

        fun reset() = buffer.reset()
    }
}

object Ams {

    val SERVICE: UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC")
    val REMOTE_COMMAND: UUID = UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2")
    val ENTITY_UPDATE: UUID = UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102")

    const val ENTITY_PLAYER = 0
    const val ENTITY_TRACK = 2
    const val PLAYER_PLAYBACK_INFO = 1
    const val TRACK_ARTIST = 0
    const val TRACK_TITLE = 2

    /** Subscriptions written to the Entity Update characteristic: the track's artist and title, and play state. */
    val SUBSCRIBE_TRACK = byteArrayOf(ENTITY_TRACK.toByte(), TRACK_ARTIST.toByte(), TRACK_TITLE.toByte())
    val SUBSCRIBE_PLAYER = byteArrayOf(ENTITY_PLAYER.toByte(), PLAYER_PLAYBACK_INFO.toByte())

    enum class Command(val id: Byte) {
        TOGGLE_PLAY_PAUSE(2),
        NEXT_TRACK(3),
        PREVIOUS_TRACK(4),
    }

    /** One Entity Update notification: an attribute of the player or track changed to [value]. */
    data class Update(val entity: Int, val attribute: Int, val value: String)

    fun parseUpdate(bytes: ByteArray): Update? {
        if (bytes.size < 3) return null
        return Update(
            entity = bytes.u8(0),
            attribute = bytes.u8(1),
            // Byte 2 holds flags (whether the value was truncated). The title and artist are only for display, so a
            // shortened value is fine.
            value = String(bytes, 3, bytes.size - 3, Charsets.UTF_8),
        )
    }

    /** PlaybackInfo is "state,rate,elapsed", where state 1 means playing. Rewinding and fast-forwarding count too. */
    fun isPlaying(playbackInfo: String): Boolean =
        playbackInfo.substringBefore(',').trim().toIntOrNull() in setOf(1, 2, 3)
}

private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

private fun ByteArray.le16(index: Int): Int = u8(index) or (u8(index + 1) shl 8)

private fun ByteArray.le32(index: Int): Int =
    u8(index) or (u8(index + 1) shl 8) or (u8(index + 2) shl 16) or (u8(index + 3) shl 24)

private fun ByteArrayOutputStream.writeLe16(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
}

private fun ByteArrayOutputStream.writeLe32(value: Int) {
    for (shift in 0..24 step 8) write((value shr shift) and 0xFF)
}
