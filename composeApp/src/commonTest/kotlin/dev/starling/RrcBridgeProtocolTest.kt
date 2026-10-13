package dev.starling

import kotlin.test.Test
import kotlin.test.assertEquals

class RrcBridgeProtocolTest {
    @Test
    fun sharedBase64EncoderMatchesBridgeWireFormat() {
        assertEquals("aGVsbG8gd29ybGQ=", encodeBridgeBase64("hello world"))
        assertEquals("8J+MjfCfjI0=", encodeBridgeBase64("🌍🌍"))
    }

    @Test
    fun lxmfBridgeErrorsDecodeAsSharedEvents() {
        val parsed = parseRrcBridgeEvent("LXMF_ERROR\t${encodeBridgeBase64("IFAC rejected the peer")}\t")

        assertEquals(ReticulumEvent.Error("IFAC rejected the peer"), parsed?.event)
    }
}
