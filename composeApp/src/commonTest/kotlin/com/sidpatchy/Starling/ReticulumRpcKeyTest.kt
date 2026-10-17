package com.sidpatchy.Starling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReticulumRpcKeyTest {
    @Test
    fun validKeysContainWholeHexadecimalPairsWithAtLeastSixteenBytes() {
        for (key in listOf("a".repeat(32), "0123456789ABCDEF".repeat(2), "b".repeat(34), "c".repeat(128))) {
            assertTrue(isValidReticulumRpcKey(key), key)
        }
    }

    @Test
    fun shortOddLengthNonHexAndWhitespaceKeysAreRejected() {
        for (key in listOf("", "a".repeat(30), "a".repeat(31), "a".repeat(33), "g".repeat(32), "a".repeat(31) + " ")) {
            assertFalse(isValidReticulumRpcKey(key), key)
        }
    }

    @Test
    fun sharedInstanceConfigurationRequiresAValidRpcKey() {
        val config = ReticulumNodeConfig(mode = ReticulumRuntimeMode.EXISTING_SHARED_INSTANCE)
        assertNotNull(config.validationError())
        assertNotNull(config.copy(existingInstanceRpcKeyHex = "invalid").validationError())
        assertNull(config.copy(existingInstanceRpcKeyHex = "a".repeat(32)).validationError())
    }

    @Test
    fun sharedInstanceKeyValidationStillPrecedesInterfaceValidation() {
        val config = ReticulumNodeConfig(
            mode = ReticulumRuntimeMode.EXISTING_SHARED_INSTANCE,
            tcpPeers = listOf(ReticulumTcpPeer("mesh.example", 4242)),
        )
        assertEquals(
            "Enter the existing local RNS instance's RPC key as at least 32 hexadecimal characters.",
            config.validationError(),
        )
        assertEquals(
            "Interfaces can only be configured for Starling's embedded node.",
            config.copy(existingInstanceRpcKeyHex = "a".repeat(32)).validationError(),
        )
    }

    @Test
    fun embeddedConfigurationDoesNotRequireOrValidateASharedInstanceKey() {
        val config = ReticulumNodeConfig(tcpPeers = listOf(ReticulumTcpPeer("mesh.example", 4242)))
        assertNull(config.validationError())
        assertNull(config.copy(existingInstanceRpcKeyHex = "invalid").validationError())
    }
}
