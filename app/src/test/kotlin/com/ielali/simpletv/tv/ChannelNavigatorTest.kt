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
}
