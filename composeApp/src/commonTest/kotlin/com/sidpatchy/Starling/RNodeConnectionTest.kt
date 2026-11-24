package com.sidpatchy.Starling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RNodeConnectionTest {
    private val radio = ReticulumRNodeInterface("", 915_000_000L, 125_000, 17, 7, 5)
    private val address = "AA:BB:CC:DD:EE:FF"
    private val uuid = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"

    @Test
    fun connectionTypeComesFromThePrefix() {
        assertEquals(RNodeConnectionType.BLUETOOTH, rnodeConnectionType("ble://RNode A1B2"))
        assertEquals(RNodeConnectionType.BLUETOOTH, rnodeConnectionType(" BLE://"))
        assertEquals(RNodeConnectionType.WIFI, rnodeConnectionType("tcp://rnode.local"))
        assertEquals(RNodeConnectionType.WIFI, rnodeConnectionType("TCP://"))
        assertEquals(RNodeConnectionType.SERIAL, rnodeConnectionType("/dev/ttyUSB0"))
        assertEquals(RNodeConnectionType.SERIAL, rnodeConnectionType("COM3"))
        assertEquals(RNodeConnectionType.SERIAL, rnodeConnectionType(""))
    }

    @Test
    fun editorDefaultsToBluetoothOnPhonesAndSerialOnDesktop() {
        assertEquals(RNodeConnectionType.BLUETOOTH, rnodeEditorConnectionType("", supportsBleRNodes = true))
        assertEquals(RNodeConnectionType.SERIAL, rnodeEditorConnectionType("", supportsBleRNodes = false))
        assertEquals(RNodeConnectionType.WIFI, rnodeEditorConnectionType("tcp://", supportsBleRNodes = true))
        assertEquals(RNodeConnectionType.WIFI, rnodeEditorConnectionType("tcp://10.0.0.5", supportsBleRNodes = false))
        assertEquals(RNodeConnectionType.BLUETOOTH, rnodeEditorConnectionType("ble://$address", supportsBleRNodes = true))
        // Desktop never offers Bluetooth.
        assertEquals(RNodeConnectionType.SERIAL, rnodeEditorConnectionType("ble://$address", supportsBleRNodes = false))
    }

    @Test
    fun switchingTypeClearsOnlyTheConnection() {
        assertEquals("tcp://", rnodeEmptyPort(RNodeConnectionType.WIFI))
        assertEquals("", rnodeEmptyPort(RNodeConnectionType.BLUETOOTH))
        assertEquals("", rnodeEmptyPort(RNodeConnectionType.SERIAL))
        assertEquals("rnode.local", rnodeWifiHost("tcp://rnode.local"))
        assertEquals("", rnodeWifiHost("/dev/ttyUSB0"))
    }

    @Test
    fun deviceIdsAreToldFromNamesByFormat() {
        assertEquals(RNodeBleTargetKind.ANY, rnodeBleTargetKind(rnodeBleTarget("ble://")))
        assertEquals(RNodeBleTargetKind.ANY, rnodeBleTargetKind(rnodeBleTarget("ble://  ")))
        assertEquals(RNodeBleTargetKind.ID, rnodeBleTargetKind(rnodeBleTarget("ble://$address")))
        assertEquals(RNodeBleTargetKind.ID, rnodeBleTargetKind(rnodeBleTarget("ble://aa:bb:cc:dd:ee:ff")))
        assertEquals(RNodeBleTargetKind.ID, rnodeBleTargetKind(rnodeBleTarget("ble://$uuid")))
        assertEquals(RNodeBleTargetKind.ID, rnodeBleTargetKind(rnodeBleTarget("ble://${uuid.lowercase()}")))
        assertEquals(RNodeBleTargetKind.NAME, rnodeBleTargetKind(rnodeBleTarget("ble://RNode A1B2")))
        assertEquals(RNodeBleTargetKind.NAME, rnodeBleTargetKind("AA:BB:CC:DD:EE"))
        assertEquals(RNodeBleTargetKind.NAME, rnodeBleTargetKind("AA-BB-CC-DD-EE-FF"))
        assertEquals(RNodeBleTargetKind.NAME, rnodeBleTargetKind("6E400001-B5A3-F393-E0A9-E50E24DCCA9"))
        assertEquals(RNodeBleTargetKind.NAME, rnodeBleTargetKind("6E400001-B5A3-F393-E0A9-E50E24DCCAZZ"))
        assertTrue(isBlePeripheralUuid(uuid))
        assertFalse(isBlePeripheralUuid(address))
        assertEquals("", rnodeBleTarget("tcp://host"))
    }

    @Test
    fun legacyNameIsUpgradedToTheDeviceIdAfterAConnection() {
        val legacy = radio.copy(port = "ble://RNode A1B2")

        val upgrade = assertNotNull(upgradeRNodeBleInterface(legacy, ResolvedBleRNode(address, "RNode A1B2")))

        assertEquals("ble://$address", upgrade.radio.port)
        assertEquals("RNode A1B2", upgrade.radio.bleName)
        assertEquals("Saved RNode A1B2 as your radio.", upgrade.note)
        // Radio parameters and the pairing record are never touched.
        assertEquals(legacy.copy(port = upgrade.radio.port, bleName = upgrade.radio.bleName), upgrade.radio)
        assertFalse(upgrade.radio.pairedByStarling)
    }

    @Test
    fun legacyNameIsKeptWhenTheDeviceReportsNoName() {
        val upgrade = assertNotNull(
            upgradeRNodeBleInterface(radio.copy(port = "ble://RNode A1B2"), ResolvedBleRNode(uuid, "")),
        )

        assertEquals("ble://$uuid", upgrade.radio.port)
        assertEquals("RNode A1B2", upgrade.radio.bleName)
    }

    @Test
    fun bareBleIsNeverPinnedAutomatically() {
        assertNull(upgradeRNodeBleInterface(radio.copy(port = "ble://"), ResolvedBleRNode(address, "RNode A1B2")))
    }

    @Test
    fun savedDeviceIdIsUnchangedAndOnlyGainsAMissingName() {
        val saved = radio.copy(port = "ble://$address")

        val named = assertNotNull(upgradeRNodeBleInterface(saved, ResolvedBleRNode(address.lowercase(), "RNode A1B2")))
        assertEquals(saved.copy(bleName = "RNode A1B2"), named.radio)
        assertNull(named.note)

        assertNull(upgradeRNodeBleInterface(named.radio, ResolvedBleRNode(address, "Renamed")))
        assertNull(upgradeRNodeBleInterface(saved, ResolvedBleRNode(address, "")))
        assertNull(upgradeRNodeBleInterface(saved, ResolvedBleRNode("11:22:33:44:55:66", "Other")))
    }

    @Test
    fun otherConnectionsAndMalformedIdsAreNotUpgraded() {
        val resolved = ResolvedBleRNode(address, "RNode A1B2")

        assertNull(upgradeRNodeBleInterface(radio.copy(port = "tcp://rnode.local"), resolved))
        assertNull(upgradeRNodeBleInterface(radio.copy(port = "/dev/ttyUSB0"), resolved))
        assertNull(upgradeRNodeBleInterface(radio.copy(port = "ble://RNode A1B2"), ResolvedBleRNode("not an id", "x")))
    }

    @Test
    fun upgradeAppliesToTheBluetoothRNodeInAList() {
        val wifi = radio.copy(port = "tcp://rnode.local")
        val legacy = radio.copy(port = "ble://RNode A1B2", frequencyHz = 868_000_000L)

        val (radios, note) = assertNotNull(
            upgradeRNodeBleInterfaces(listOf(wifi, legacy), ResolvedBleRNode(address, "RNode A1B2")),
        )

        assertEquals(listOf(wifi, legacy.copy(port = "ble://$address", bleName = "RNode A1B2")), radios)
        assertEquals("Saved RNode A1B2 as your radio.", note)
        assertNull(upgradeRNodeBleInterfaces(radios, ResolvedBleRNode(address, "RNode A1B2")))
        assertNull(upgradeRNodeBleInterfaces(listOf(wifi), ResolvedBleRNode(address, "RNode A1B2")))
    }

    @Test
    fun bluetoothFieldsSurviveARoundTrip() {
        val config = ReticulumNodeConfig(
            rnodeInterfaces = listOf(
                radio.copy(port = "ble://$address", bleName = "RNode A1B2", pairedByStarling = true),
                radio.copy(port = "tcp://rnode.local"),
            ),
        )

        val decoded = ChatStateCodec.decode(ChatStateCodec.encode(PersistedChatState(nodeConfig = config)))

        assertEquals(config, decoded?.nodeConfig)
    }

    @Test
    fun recordsWithoutBluetoothFieldsStillLoad() {
        fun record(kind: String, vararg values: String) =
            (listOf(kind) + values.map { Base64TextCodec.encode(it) }).joinToString("\t")
        val saved = listOf(
            "STARLING_CHAT_STATE_V13",
            record("V", ReticulumRuntimeMode.EMBEDDED.name),
            record("I", "ble://RNode A1B2", "915000000", "125000", "17", "7", "5"),
        ).joinToString("\n")

        val loaded = ChatStateCodec.decode(saved)?.nodeConfig?.rnodeInterfaces

        assertEquals(listOf(radio.copy(port = "ble://RNode A1B2")), loaded)
        assertNull(loaded?.single()?.bleName)
        assertEquals(false, loaded?.single()?.pairedByStarling)
    }

    @Test
    fun scanRowsAreDedupedByIdAndSortedPinnedThenSignalThenName() {
        val list = RNodeScanList()
        list.beginSearch(0)
        list.observe(RNodeBleSighting("11:11:11:11:11:11", "RNode B", -70), pinned = false, nowMillis = 100)
        list.observe(RNodeBleSighting("22:22:22:22:22:22", "RNode A", -70), pinned = false, nowMillis = 100)
        list.observe(RNodeBleSighting("33:33:33:33:33:33", "RNode C", -50), pinned = false, nowMillis = 100)
        list.observe(RNodeBleSighting("44:44:44:44:44:44", "RNode D", -90), pinned = true, nowMillis = 100)
        list.observe(RNodeBleSighting("11:11:11:11:11:11", "RNode B", -72), pinned = false, nowMillis = 200)

        val rows = list.rows(300)

        assertEquals(listOf("RNode D", "RNode C", "RNode A", "RNode B"), rows.map { it.name })
        assertEquals(-72, rows.last().rssi)
        assertTrue(rows.first().pinned)
        assertTrue(rows.all { it.idSuffix == null })
    }

    @Test
    fun sameNameRowsStayApartAndShowTheEndOfTheirId() {
        val list = RNodeScanList()
        list.beginSearch(0)
        list.observe(RNodeBleSighting("AA:BB:CC:DD:11:22", "RNode", -60), pinned = false, nowMillis = 10)
        list.observe(RNodeBleSighting("AA:BB:CC:DD:33:44", "rnode", -65), pinned = false, nowMillis = 10)
        list.observe(RNodeBleSighting("AA:BB:CC:DD:55:66", "Other", -80), pinned = false, nowMillis = 10)

        val rows = list.rows(20)

        assertEquals(3, rows.size)
        assertEquals(listOf("11:22", "33:44", null), rows.map { it.idSuffix })
        assertEquals("34:56", rnodeIdSuffix("6E400001-B5A3-F393-E0A9-E50E24DC3456"))
    }

    @Test
    fun aBlankAdvertisedNameDoesNotReplaceAKnownOne() {
        val list = RNodeScanList()
        list.beginSearch(0)
        list.observe(RNodeBleSighting("aa:bb:cc:dd:11:22", "RNode", -60), pinned = false, nowMillis = 10)
        list.observe(RNodeBleSighting("AA:BB:CC:DD:11:22", "", -61), pinned = false, nowMillis = 20)

        assertEquals(listOf(RNodeScanRow("AA:BB:CC:DD:11:22", "RNode", -61, false, null)), list.rows(30))
    }

    @Test
    fun onlyDevicesNamedLikeAnRNodeAreListed() {
        assertTrue(isRNodeBleName("RNode 5719"))
        assertTrue(isRNodeBleName("RNode A1B2"))
        assertFalse(isRNodeBleName("MeshCore-A1B2"))
        assertFalse(isRNodeBleName("RNode"))
        assertFalse(isRNodeBleName("RNodeX 5719"))
        assertFalse(isRNodeBleName("rnode 5719"))
        assertFalse(isRNodeBleName("My RNode 5719"))
        assertFalse(isRNodeBleName(""))

        val list = RNodeScanList()
        list.beginSearch(0)
        val rnode = RNodeBleSighting("AA:BB:CC:DD:57:19", "RNode 5719", -60)
        val meshCore = RNodeBleSighting("AA:BB:CC:DD:00:99", "MeshCore-A1B2", -50)
        assertTrue(list.accepts(rnode))
        assertFalse(list.accepts(meshCore))
        // A nameless advertisement is only taken from a device already listed as an RNode.
        assertFalse(list.accepts(rnode.copy(name = "")))
        list.observe(rnode, pinned = false, nowMillis = 10)
        assertTrue(list.accepts(RNodeBleSighting("aa:bb:cc:dd:57:19", "", -62)))
        assertFalse(list.accepts(meshCore.copy(name = "")))
    }

    @Test
    fun orderFreezesAfterThreeSecondsAndSignalUpdatesInPlace() {
        val list = RNodeScanList()
        list.beginSearch(0)
        list.observe(RNodeBleSighting("11:11:11:11:11:11", "Near", -50), pinned = false, nowMillis = 100)
        list.observe(RNodeBleSighting("22:22:22:22:22:22", "Far", -85), pinned = false, nowMillis = 100)
        assertEquals(listOf("Near", "Far"), list.rows(2_999).map { it.name })

        // Before the freeze a stronger signal still moves a row.
        list.observe(RNodeBleSighting("22:22:22:22:22:22", "Far", -40), pinned = false, nowMillis = 2_000)
        assertEquals(listOf("Far", "Near"), list.rows(2_999).map { it.name })
        assertEquals(listOf("Far", "Near"), list.rows(3_000).map { it.name })

        // After it, rows keep their place and later devices go to the end.
        list.observe(RNodeBleSighting("11:11:11:11:11:11", "Near", -30), pinned = false, nowMillis = 4_000)
        list.observe(RNodeBleSighting("33:33:33:33:33:33", "Late", -20), pinned = true, nowMillis = 4_000)
        val rows = list.rows(4_100)
        assertEquals(listOf("Far", "Near", "Late"), rows.map { it.name })
        assertEquals(listOf(-40, -30, -20), rows.map { it.rssi })
    }

    @Test
    fun aNewSearchKeepsRowsForTwoSecondsThenDropsTheUnseen() {
        val list = RNodeScanList()
        list.beginSearch(0)
        list.observe(RNodeBleSighting("11:11:11:11:11:11", "Stays", -50), pinned = false, nowMillis = 100)
        list.observe(RNodeBleSighting("22:22:22:22:22:22", "Gone", -60), pinned = false, nowMillis = 100)
        assertEquals(2, list.rows(20_000).size)

        list.beginSearch(30_000)
        list.observe(RNodeBleSighting("11:11:11:11:11:11", "Stays", -55), pinned = false, nowMillis = 30_500)
        assertEquals(listOf("Stays", "Gone"), list.rows(31_999).map { it.name })
        assertEquals(listOf("Stays"), list.rows(32_000).map { it.name })
        assertEquals(listOf("Stays"), list.rows(40_000).map { it.name })
    }

    @Test
    fun signalLevelsHaveFourLabelledBuckets() {
        assertEquals("Strong signal", rnodeSignalLevel(-45).label)
        assertEquals("Strong signal", rnodeSignalLevel(-60).label)
        assertEquals("Good signal", rnodeSignalLevel(-61).label)
        assertEquals("Good signal", rnodeSignalLevel(-70).label)
        assertEquals("Fair signal", rnodeSignalLevel(-71).label)
        assertEquals("Fair signal", rnodeSignalLevel(-80).label)
        assertEquals("Weak signal", rnodeSignalLevel(-81).label)
        assertEquals("Weak signal", rnodeSignalLevel(-120).label)
        assertEquals(listOf(1, 2, 3, 4), RNodeSignalLevel.entries.map { it.bars })
    }

    @Test
    fun scanStartsAreHeldBackBeforeAndroidThrottlesThem() {
        assertEquals(0, rnodeScanStartDelayMillis(emptyList(), 0))
        assertEquals(0, rnodeScanStartDelayMillis(listOf(0, 1_000, 2_000), 3_000))
        assertEquals(27_000, rnodeScanStartDelayMillis(listOf(0, 1_000, 2_000, 2_500), 3_000))
        assertEquals(28_000, rnodeScanStartDelayMillis(listOf(0, 1_000, 2_000, 2_500, 2_800), 3_000))
        // Starts that have left the 30 second window no longer count.
        assertEquals(0, rnodeScanStartDelayMillis(listOf(0, 1_000, 2_000, 2_500), 31_500))
        assertEquals(0, rnodeScanStartDelayMillis(listOf(0, 1_000, 2_000, 2_500), 60_000))
    }

    @Test
    fun validationNamesWhatIsMissingForEachConnection() {
        assertEquals(
            "Choose an RNode for this interface before starting the node.",
            radio.validationError(supportsBleRNodes = true),
        )
        assertEquals("Enter the RNode's serial port.", radio.validationError())
        assertEquals("Enter the RNode's host name or IP address.", radio.copy(port = "tcp://").validationError(true))
        assertNull(radio.copy(port = "tcp://rnode.local").validationError(true))
        assertNull(radio.copy(port = "ble://").validationError(true))
        assertNull(radio.copy(port = "ble://$address").validationError(true))
        assertNull(radio.copy(port = "/dev/ttyUSB0").validationError())
        assertEquals(
            "RNode frequency must be between 137 MHz and 3 GHz.",
            radio.copy(port = "ble://$address", frequencyHz = 0).validationError(true),
        )
        assertEquals(
            "Choose an RNode for this interface before starting the node.",
            ReticulumNodeConfig(rnodeInterfaces = listOf(radio)).validationError(supportsBleRNodes = true),
        )
    }

    @Test
    fun scanStatesUseTheApprovedCopy() {
        val android = RNodeBlePlatform.ANDROID
        val ios = RNodeBlePlatform.IOS

        assertEquals("Searching for RNodes...", RNodeBleText.scanStatusMessage(RNodeBleScanStatus.Scanning, android, false))
        assertEquals("Searching...", RNodeBleText.scanStatusMessage(RNodeBleScanStatus.Scanning, android, true))
        assertNull(RNodeBleText.scanStatusMessage(RNodeBleScanStatus.Finished, android, true))
        assertTrue(RNodeBleText.scanStatusMessage(RNodeBleScanStatus.Finished, android, false)!!.startsWith("No RNodes found."))
        assertEquals("Bluetooth is turned off.", RNodeBleText.scanStatusMessage(RNodeBleScanStatus.BluetoothOff, android, false))
        assertEquals(
            "Bluetooth is turned off. Turn on Bluetooth in Control Center or Settings.",
            RNodeBleText.scanStatusMessage(RNodeBleScanStatus.BluetoothOff, ios, false),
        )
        assertEquals(
            "Bluetooth search failed (code 2). Try again.",
            RNodeBleText.scanStatusMessage(RNodeBleScanStatus.Failed(2), android, false),
        )
        assertEquals("Turn on Bluetooth", RNodeBleText.scanBlockerAction(RNodeBleScanStatus.BluetoothOff, android))
        assertNull(RNodeBleText.scanBlockerAction(RNodeBleScanStatus.BluetoothOff, ios))
        assertEquals("Allow", RNodeBleText.scanBlockerAction(RNodeBleScanStatus.PermissionNotAsked, android))
        assertEquals("Allow", RNodeBleText.scanBlockerAction(RNodeBleScanStatus.PermissionDenied, android))
        assertEquals("Open settings", RNodeBleText.scanBlockerAction(RNodeBleScanStatus.PermissionPermanentlyDenied, ios))
        assertEquals("Open location settings", RNodeBleText.scanBlockerAction(RNodeBleScanStatus.LocationOff, android))
        assertNull(RNodeBleText.scanBlockerAction(RNodeBleScanStatus.Scanning, android))
    }

    @Test
    fun pairingFailuresAndNodeStartErrorsNameTheRNode() {
        val android = RNodeBlePlatform.ANDROID
        fun failed(reason: RNodeBlePairingFailure, code: Int = 0) = RNodeBlePairingState.Failed("RNode A1B2", reason, code)

        assertTrue(RNodeBleText.pairingFailureMessage(failed(RNodeBlePairingFailure.WRONG_PIN, 1), android).startsWith("The PIN didn't match."))
        assertTrue(RNodeBleText.pairingFailureMessage(failed(RNodeBlePairingFailure.TIMEOUT), android).startsWith("Pairing timed out."))
        assertEquals(
            "Pairing didn't finish. Make sure the RNode is on and in pairing mode, then try again.",
            RNodeBleText.pairingFailureMessage(failed(RNodeBlePairingFailure.OTHER, 9), android),
        )
        assertEquals(
            RNodeBleText.pairingFailureMessage(failed(RNodeBlePairingFailure.OTHER), android),
            RNodeBleText.pairingFailureMessage(failed(RNodeBlePairingFailure.OTHER, 9), android),
        )
        assertTrue(
            RNodeBleText.pairingFailureMessage(failed(RNodeBlePairingFailure.OTHER), RNodeBlePlatform.IOS)
                .startsWith("iOS didn't finish pairing."),
        )
        assertTrue(
            RNodeBleText.pairingFailureMessage(failed(RNodeBlePairingFailure.STALE_BOND), RNodeBlePlatform.IOS)
                .startsWith("This RNode no longer recognises this iPhone."),
        )
        assertEquals(
            "Couldn't reach RNode A1B2. Check that it's powered on and in range, then try again.",
            RNodeBleText.notFound("RNode A1B2"),
        )
        assertEquals("RNode A1B2", rnodeBleLabel("ble://$address", "RNode A1B2"))
        assertEquals("RNode A1B2", rnodeBleLabel("ble://RNode A1B2", null))
        assertEquals("the RNode", rnodeBleLabel("ble://$address", null))
        assertEquals("the RNode", rnodeBleLabel("ble://", " "))
    }

    @Test
    fun displayNameNeverShowsADeviceId() {
        assertEquals("RNode A1B2", rnodeDisplayName(radio.copy(port = "ble://$uuid", bleName = "RNode A1B2")))
        assertEquals("Bluetooth RNode", rnodeDisplayName(radio.copy(port = "ble://$uuid")))
        assertEquals("RNode A1B2", rnodeDisplayName(radio.copy(port = "ble://RNode A1B2")))
        assertEquals("rnode.local", rnodeDisplayName(radio.copy(port = "tcp://rnode.local")))
        assertEquals("/dev/ttyUSB0", rnodeDisplayName(radio.copy(port = "/dev/ttyUSB0")))
    }

    @Test
    fun onlyPhonesReachRNodesOverBluetooth() {
        val controller = UnsupportedReticulumNodeController("unavailable")

        assertFalse(controller.supportsBleRNodes)
        assertNull(controller.rnodeBlePicker)
        assertNull(controller.resolvedBleRNode.value)
    }
}
