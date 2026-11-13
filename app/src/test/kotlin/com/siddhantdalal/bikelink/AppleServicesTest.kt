package com.siddhantdalal.bikelink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleServicesTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `parses an incoming-call event with a little-endian uid`() {
        // EventID Added, flags, CategoryID IncomingCall, count, UID 0x04030201
        val event = Ancs.parseEvent(bytes(0, 0x18, 1, 1, 0x01, 0x02, 0x03, 0x04))
        assertEquals(Ancs.Event(Ancs.EVENT_ADDED, Ancs.CATEGORY_INCOMING_CALL, 0x04030201), event)
    }

    @Test
    fun `short event packets are rejected`() {
        assertNull(Ancs.parseEvent(bytes(0, 0, 1)))
    }

    @Test
    fun `builds the get-title command`() {
        // CommandID 0, UID 7 (LE), AttributeID Title, max length 64 (LE)
        assertArrayEquals(bytes(0, 7, 0, 0, 0, 1, 64, 0), Ancs.getTitle(uid = 7, maxLength = 64))
    }

    @Test
    fun `builds answer and decline actions`() {
        assertArrayEquals(bytes(2, 0x10, 0, 0, 0, 0), Ancs.callAction(uid = 16, answer = true))
        assertArrayEquals(bytes(2, 0x10, 0, 0, 0, 1), Ancs.callAction(uid = 16, answer = false))
    }

    @Test
    fun `reads an attribute reply that arrives in one packet`() {
        val reply = bytes(0, 5, 0, 0, 0, 1, 3, 0) + "Mom".toByteArray()
        assertEquals(Ancs.Attributes(5, mapOf(1 to "Mom")), Ancs.AttributeReader().add(reply))
    }

    @Test
    fun `reassembles an attribute reply split across packets`() {
        val reader = Ancs.AttributeReader()
        val full = bytes(0, 9, 0, 0, 0, 1, 11, 0) + "Ravi Sharma".toByteArray()
        assertNull(reader.add(full.copyOfRange(0, 6)))
        assertNull(reader.add(full.copyOfRange(6, 12)))
        assertEquals(Ancs.Attributes(9, mapOf(1 to "Ravi Sharma")), reader.add(full.copyOfRange(12, full.size)))
    }

    @Test
    fun `garbage on the data source is dropped`() {
        val reader = Ancs.AttributeReader()
        assertNull(reader.add(bytes(9, 9, 9)))
        val reply = bytes(0, 1, 0, 0, 0, 1, 2, 0) + "Hi".toByteArray()
        assertEquals("Hi", reader.add(reply)?.values?.get(1))
    }

    @Test
    fun `utf-8 caller names survive`() {
        val name = "माँ".toByteArray(Charsets.UTF_8)
        val reply = bytes(0, 2, 0, 0, 0, 1, name.size, 0) + name
        assertEquals("माँ", Ancs.AttributeReader().add(reply)?.values?.get(1))
    }

    @Test
    fun `parses media entity updates`() {
        val update = Ams.parseUpdate(bytes(Ams.ENTITY_TRACK, Ams.TRACK_TITLE, 0) + "Kesariya".toByteArray())
        assertEquals(Ams.Update(Ams.ENTITY_TRACK, Ams.TRACK_TITLE, "Kesariya"), update)
        assertNull(Ams.parseUpdate(bytes(2, 2)))
    }

    @Test
    fun `reads the play state from playback info`() {
        assertTrue(Ams.isPlaying("1,1.0,42.5"))
        assertFalse(Ams.isPlaying("0,0.0,42.5"))
        assertFalse(Ams.isPlaying(""))
    }

    @Test
    fun `media subscriptions ask for artist, title and play state`() {
        assertArrayEquals(bytes(2, 0, 2), Ams.SUBSCRIBE_TRACK)
        assertArrayEquals(bytes(0, 1), Ams.SUBSCRIBE_PLAYER)
    }
}
