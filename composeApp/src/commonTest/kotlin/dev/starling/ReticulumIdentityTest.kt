package dev.starling

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReticulumIdentityTest {
    @Test
    fun firstRefreshDoesNotCreateAnIdentity() = runTest {
        val fixture = Fixture()
        assertTrue(fixture.manager.refresh())
        assertEquals(ReticulumIdentityState.Missing, fixture.manager.state.value)
        assertEquals(listOf("IDENTITY_STATUS"), fixture.commands)
        assertNull(fixture.changedIdentity)
    }

    @Test
    fun createAndDeleteUpdateOnlyPublicState() = runTest {
        val fixture = Fixture()
        assertTrue(fixture.manager.create())
        assertEquals(ReticulumIdentityState.Available(INFO), fixture.manager.state.value)
        assertEquals(INFO, fixture.changedIdentity)
        assertTrue(fixture.manager.delete())
        assertEquals(ReticulumIdentityState.Missing, fixture.manager.state.value)
        assertNull(fixture.changedIdentity)
        assertEquals(listOf("IDENTITY_CREATE", "IDENTITY_DELETE"), fixture.commands)
    }

    @Test
    fun exportUsesTheFileAdapterAndClearsTheTransientPrivateBuffer() = runTest {
        val fixture = Fixture()
        fixture.manager.create()
        assertTrue(fixture.manager.exportIdentity())
        assertContentEquals(KEY, fixture.files.exported)
        assertEquals("starling-${INFO.hash}.rnsidentity", fixture.files.filename)
        assertTrue(fixture.files.exportBuffer!!.all { it == 0.toByte() })
        assertEquals(ReticulumIdentityState.Available(INFO), fixture.manager.state.value)
    }

    @Test
    fun importValidatesSizeAndClearsThePickedBuffer() = runTest {
        val fixture = Fixture()
        val picked = KEY.copyOf()
        fixture.files.imported = picked
        assertTrue(fixture.manager.importIdentity())
        assertEquals("IDENTITY_IMPORT\t${Base64.encode(KEY)}", fixture.commands.single())
        assertTrue(picked.all { it == 0.toByte() })
        assertEquals(ReticulumIdentityState.Available(INFO), fixture.manager.state.value)

        fixture.files.imported = ByteArray(65) { 1 }
        assertFalse(fixture.manager.importIdentity())
        assertEquals(1, fixture.commands.size)
        assertNotNull(fixture.manager.error.value)
        assertEquals(ReticulumIdentityState.Available(INFO), fixture.manager.state.value)
    }

    @Test
    fun documentCancellationDoesNotChangeTheIdentity() = runTest {
        val fixture = Fixture()
        fixture.manager.create()
        fixture.files.exportResult = false
        assertFalse(fixture.manager.exportIdentity())
        assertFalse(fixture.manager.importIdentity())
        assertNull(fixture.manager.error.value)
        assertEquals(ReticulumIdentityState.Available(INFO), fixture.manager.state.value)
    }

    @Test
    fun mutationsRequireAFullyStoppedNodeButExportIsAllowedWhileRunning() = runTest {
        val fixture = Fixture()
        fixture.manager.create()
        for (state in listOf(
            ReticulumNodeState.Running(ReticulumRuntimeMode.EMBEDDED, emptyList(), emptySet()),
            ReticulumNodeState.Starting(ReticulumRuntimeMode.EMBEDDED),
            ReticulumNodeState.PreparingRuntime("Preparing"),
            ReticulumNodeState.Failed("May still be shutting down"),
        )) {
            fixture.node.state.value = state
            assertFalse(fixture.manager.create())
            assertFalse(fixture.manager.delete())
            assertFalse(fixture.manager.importIdentity())
        }
        assertEquals(listOf("IDENTITY_CREATE"), fixture.commands)
        assertTrue(fixture.manager.exportIdentity())
    }

    @Test
    fun nodeReadinessIsRecheckedAfterTheImportDialog() = runTest {
        val fixture = Fixture()
        fixture.files.imported = KEY.copyOf()
        fixture.files.onImport = { fixture.node.state.value = ReticulumNodeState.Starting(ReticulumRuntimeMode.EMBEDDED) }
        assertFalse(fixture.manager.importIdentity())
        assertTrue(fixture.commands.isEmpty())
        assertTrue(fixture.files.imported!!.all { it == 0.toByte() })
    }

    @Test
    fun corruptOrFailedReadsNeverFallBackToCreation() = runTest {
        val fixture = Fixture()
        fixture.response = "ERROR\t${encodeBridgeBase64("Saved identity is invalid")}"
        assertFalse(fixture.manager.refresh())
        assertIs<ReticulumIdentityState.Failed>(fixture.manager.state.value)
        assertEquals(listOf("IDENTITY_STATUS"), fixture.commands)
        assertFalse(fixture.manager.busy.value)
    }

    private class Fixture {
        val commands = mutableListOf<String>()
        val node = IdentityTestNode()
        val files = IdentityTestFiles()
        var response: String? = null
        var changedIdentity: ReticulumIdentityInfo? = null
        val manager = ReticulumIdentityManager(ReticulumIdentityStore { command ->
            commands += command
            response ?: when (command.substringBefore('\t')) {
                "IDENTITY_STATUS", "IDENTITY_DELETE" -> "IDENTITY_MISSING"
                "IDENTITY_EXPORT" -> "IDENTITY_EXPORT\t${Base64.encode(KEY)}"
                else -> "MANAGED_IDENTITY\t${INFO.hash}\t${INFO.deliveryAddress}"
            }
        }, files, node, { changedIdentity = it })
    }

    private class IdentityTestNode : ReticulumNodeController {
        override val state = MutableStateFlow<ReticulumNodeState>(ReticulumNodeState.Stopped)
        override val isSupported = true
        override suspend fun start(config: ReticulumNodeConfig) = Unit
        override suspend fun disconnect() { state.value = ReticulumNodeState.Stopped }
    }

    private class IdentityTestFiles : ReticulumIdentityFiles {
        var imported: ByteArray? = null
        var exported: ByteArray? = null
        var exportBuffer: ByteArray? = null
        var filename: String? = null
        var exportResult = true
        var onImport: () -> Unit = {}
        override suspend fun importFile(): ByteArray? { onImport(); return imported }
        override suspend fun exportFile(bytes: ByteArray, suggestedName: String): Boolean {
            exportBuffer = bytes
            exported = bytes.copyOf()
            filename = suggestedName
            return exportResult
        }
    }

    companion object {
        private val KEY = ByteArray(64) { it.toByte() }
        private val INFO = ReticulumIdentityInfo("0123456789abcdef0123456789abcdef", "abcdef0123456789abcdef0123456789")
    }
}
