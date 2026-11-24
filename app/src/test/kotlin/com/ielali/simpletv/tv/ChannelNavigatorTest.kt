package com.ielali.simpletv.tv

import com.ielali.simpletv.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelNavigatorTest {

    private fun ch(n: Int) = Channel(id = "id$n", number = n, name = "Ch $n", url = "http://x/$n")
    private val list = listOf(ch(1), ch(3), ch(10))

    @Test
    fun `exact number wins`() = assertEquals(ch(3), ChannelNavigator.resolve(3, list))

    @Test
    fun `missing number lands on next higher`() = assertEquals(ch(10), ChannelNavigator.resolve(5, list))

    @Test
    fun `number above highest wraps to first`() = assertEquals(ch(1), ChannelNavigator.resolve(99, list))

    @Test
    fun `empty list resolves to nothing`() = assertNull(ChannelNavigator.resolve(1, emptyList()))

    @Test
    fun `next wraps around`() {
        assertEquals(ch(3), ChannelNavigator.next(ch(1), list))
        assertEquals(ch(1), ChannelNavigator.next(ch(10), list))
        assertEquals(ch(1), ChannelNavigator.next(null, list))
    }

    @Test
    fun `previous wraps around`() {
        assertEquals(ch(10), ChannelNavigator.previous(ch(1), list))
        assertEquals(ch(3), ChannelNavigator.previous(ch(10), list))
        assertEquals(ch(10), ChannelNavigator.previous(null, list))
    }

    @Test
    fun `skipping steps over failed channels but never leaves the viewer stuck`() {
        val list = listOf(1, 2, 3, 4).map { Channel(id = "c$it", number = it, name = "TV $it", url = "http://x/$it") }
        val failed = setOf("c2", "c3")
        assertEquals(4, ChannelNavigator.next(list[0], list) { it.id in failed }?.number)
        assertEquals(1, ChannelNavigator.previous(list[3], list) { it.id in failed }?.number)
        assertEquals("all others failed: plain next", 2, ChannelNavigator.next(list[0], list) { it.id != "c1" }?.number)
        assertEquals(1, ChannelNavigator.next(null, list) { false }?.number)
        assertEquals(4, ChannelNavigator.previous(null, list) { false }?.number)
    }
}
