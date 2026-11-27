# Reticulum runtime prototype

## What exists now

- **Local Preview** remains enabled by default and is persisted with chat state on Android, desktop, and iOS. Preview is isolated from live Reticulum state: it shows only sample/local preview data, hides live addresses and connection configuration, ignores live events, and disconnects the node and hub while active. Preview messages are never sent over RNS.
- Android has an embedded node using reference `rns==1.5.7` and `lxmf==1.2.0` through Chaquopy 17 / Python 3.13. For Bluetooth peers it also bundles [`ble-reticulum`](https://github.com/torlando-tech/ble-reticulum) 0.2.3 (MIT License, pinned to commit `63557acfe5c923dcdafeb7bc37508bf835f16514`; license text in `licenses/BLE-Reticulum-LICENSE.txt`).
- iOS embeds CPython 3.13 from the pinned BeeWare Python Apple-support framework and uses the same `rns==1.5.7`, `lxmf==1.2.0`, and Starling Python bridge. PyCA `cryptography` and CFFI are built as iOS device/simulator wheels and packaged as signed extension frameworks.
- iOS Bluetooth peering is implemented using the pinned `ble-reticulum` package and the native CoreBluetooth driver in `starling_ble_ios.py` / `StarlingBLEPeerBridge.swift`. It is foreground-only; it has not yet been compiled or verified with BLE hardware. There is no App Store or TestFlight release path for this implementation.
- Desktop runs the same Python bridge in a child process, installing those pinned packages into a Starling-private virtual environment on first explicit connection. Windows and macOS use this same host-Python flow; Flatpak ships the Python/RNS dependencies inside its app bundle.
- Android starts the node on demand from Settings in a dedicated process and foreground service. Android shows an ongoing notification while it is running.
- Embedded mode configures multiple outbound `TCPClientInterface` instances from the TCP peer list. Each connection can have its own optional IFAC network name and passphrase; both must match that peer's interface settings. RNS transport-node routing is explicitly disabled; Starling is a client, not a gateway.
- Desktop can configure one or more reference `RNodeInterface` instances over serial ports (for example `/dev/ttyUSB0`) or the RNS TCP RNode transport. iOS and Android support RNodes over Wi-Fi (the RNode's default TCP port 7633) and one Bluetooth RNode at a time, chosen from a list of nearby RNodes in Settings. iOS uses CoreBluetooth and Android uses `BluetoothGatt` to bridge the Nordic UART service to a loopback TCP socket consumed by RNS's TCP RNode transport. Direct USB serial is not available on either mobile platform. Enter the radio frequency, bandwidth, transmit power, spreading factor, and coding rate as required by the radio. The RNode must already have compatible RNode firmware installed.
- Advanced mode attaches to a Reticulum shared instance already running on the same device. Since Starling has a private config directory, the existing instance must be configured with a shared RPC key and the default local ports (37428/37429). This is not a generic remote-daemon management protocol.
- Android config and identity files live under `noBackupFilesDir/reticulum`, to avoid copying identity material through Android backup/restore. Desktop state is stored in the user's Starling application-data directory. iOS stores RNS identity/config under app-private Application Support and excludes that directory from device backup.
- A host-side smoke test against reference RNS 1.5.5 has verified both an outbound Starling TCP client connecting to a local RNS TCP server and the advanced shared-instance/RPC-key handshake. This validates the generated RNS config and Python bridge, not the Android service on a physical device.
- The iOS Python bridge smoke test verifies an IFAC TCP connection to a separate RNS server, reconnects after disconnect, and confirms that a mismatched IFAC credential does not establish an RNS path. Physical-device RRC/LXMF interoperability still needs testing.
- The shared RRC 1.0 client uses an encrypted RNS Link to an `rrc.hub` destination, performs HELLO/WELCOME, joins configured channel names, and sends/receives room messages. Starling also sends the optional room-scoped `NOTICE` soft-presence convention `Starling presence: online` after joining and every five minutes; Starling clients use it to refresh their live roster without adding a transcript line. It has been exercised against the reference `rrcd` hub.
- LXMF direct delivery uses the reference router, persists the local Reticulum identity, announces a shareable delivery address, and sends opportunistic messages. Inbound messages are displayed only after the sender identity is resolved and its signature validates; unknown identities trigger a path request and verification retry before the message is withheld. Verified first-time senders go to Requests. Two independent host runtimes and the desktop sidecar protocol have passed bidirectional delivery smoke tests.

The UI distinguishes node/interface state from RRC hub connection state. In live mode, use + on Networks to register an RRC hub destination and add the channel names Starling should join. Settings selects one preferred RRC hub at a time; its channels are joined by name. The Identity screen configures the global nickname used for LXMF and as the default RRC nickname, with optional per-hub overrides. Every manual/automatic hub connection uses the saved effective nickname; changing it for the active hub reconnects that hub and rejoins configured channels. RRC has no room-enumeration command and does not retain history. Android, desktop, and iOS persist live configured networks/channels, the preferred and last active hub, the public identity hash, global/per-hub nicknames, and TCP/RNode interface settings and the Bluetooth peers setting with local chat state. With Local preview off, an existing identity, and a valid saved embedded-node configuration, the node starts at app launch and the preferred hub connects when ready. Existing shared-instance mode remains manual because its RPC key is not retained in app preferences. Preview-created networks, contacts, conversations, and sample activity remain in-memory.

Chat identities are never generated implicitly. Turn off Local preview and use Identity to create or import one before starting live networking. Existing identities remain usable without migration. Identity supports creation, standard 64-byte Reticulum private-key import/export through platform file dialogs, and explicit deletion. Creating/importing/deleting requires a stopped node; exporting is also available while connected. Invalid imports are rejected before replacing the saved key, and replacement writes are atomic. Deletion clears cached public/self identity information but keeps contacts and history. Backups are unencrypted secrets and remain valid after deletion; there is no key revocation or encrypted-backup format. The UI requires confirmation before import, export, or deletion. A welcome/onboarding screen is intentionally not included yet.

## Desktop RNode setup (initial serial support)

1. Use a supported LoRa board and flash it with RNode firmware using the upstream `rnodeconf --autoinstall` workflow. Starling does not flash or update radio firmware.
2. Connect the RNode over USB serial and note its port (`/dev/ttyUSB0` or `/dev/ttyACM0` on Linux, `COM3` on Windows, or `/dev/cu.usbserial*` on macOS).
3. In Settings, add an RNode serial interface and enter its serial path and radio parameters in Hz/dBm.

The radio parameters must match the RNode network and comply with local spectrum and transmit-power rules. RNode firmware replaces the board's Meshtastic firmware; it does not make a Meshtastic radio transparently speak Reticulum. Use a spare or otherwise appropriate device if you need to keep your existing Meshtastic node online. Windows and macOS use the same RNS/PySerial interface; the OS serial driver and device permissions are still required. Flatpak grants device access so serial radios can work inside its sandbox.

The desktop adapter supports serial, while mobile adapters support TCP/Wi-Fi and Nordic-UART BLE. Direct USB serial is not available on Android or iOS. As with other Starling embedded interfaces, transport-node routing remains disabled, so Starling does not relay traffic for other nodes.

## Mobile RNode setup

1. Flash a supported radio with compatible RNode firmware and configure its Wi-Fi TCP service or Nordic UART BLE service.
2. In Starling's embedded-node settings, add an RNode and choose **Bluetooth** or **Wi-Fi** under **Connect over**. For Wi-Fi, enter the RNode's host name or IP address (TCP port 7633). For Bluetooth, tap **Find RNode** with the node stopped, pick the radio from the list, and enter the PIN shown on the RNode's screen if the phone asks for one. Starling saves the device itself (its Bluetooth address on Android, its peripheral identifier on iOS) and connects to that device only. A radio saved by name in an older version keeps working and is saved by device after its next successful connection; a saved bare `ble://` still connects to the first RNode found until a specific one is chosen.
3. Enter the radio frequency, bandwidth, transmit power, spreading factor, and coding rate to match the radio and local regulations. The current Android RNS adapter limits bandwidth to 500 kHz. Android requests Bluetooth scan/connect permission when a BLE interface is started.

On mobile, Starling bridges BLE UART bytes to a local TCP socket and uses RNS's TCP RNode transport; Reticulum continues to perform radio detection/configuration and packet framing. TCP/Wi-Fi connects directly through the same RNS adapter. Only one BLE radio can be connected at a time per app instance.

## BLE peering (Android and iOS)

Bluetooth peers lets two phones exchange Reticulum traffic directly over Bluetooth LE, with no radio hardware or network in between. It is off by default. Turn on **Bluetooth peers** in the embedded-node settings; it can be the node's only interface or run alongside TCP peers and RNodes. Android asks for Bluetooth scan, connect, and advertise permission (location permission on Android 11 and older). iOS uses its Bluetooth usage description and CoreBluetooth; peering is available only while Starling is foregrounded.

The interface speaks the ble-reticulum v2.2 GATT protocol and should interoperate with other clients that implement it. The protocol logic (peer selection, identity handshake handling, fragmentation and reassembly, per-peer interfaces) is the upstream [`ble-reticulum`](https://github.com/torlando-tech/ble-reticulum) package (MIT License), used unmodified. Android uses `starling_ble_android.py` and `AndroidBlePeerBridge.kt`; iOS uses `starling_ble_ios.py` and `StarlingBLEPeerBridge.swift`. RNS loads the selected platform driver as an external interface: when enabled, Starling writes a small loader to `interfaces/StarlingBLEPeerInterface.py` in its private RNS config directory and adds a `Starling BLE Peers` interface to the generated config.

Each phone advertises the service `37145b00-442d-4a94-917f-8f42c5da28e3` and scans for it. The side that connects reads the peer's 16-byte identity, requests a 517-byte MTU, subscribes to the TX characteristic, and writes its own identity to RX as the handshake. Data then flows as writes to RX in one direction and TX notifications in the other. Transport routing stays disabled, so a phone does not relay traffic between its Bluetooth peers and its other interfaces.

Current limitations:

- **iOS is implemented but not yet compiled or hardware-verified.** No iOS devices have exchanged packets over this interface; treat iOS support as experimental.
- Android peering is implemented. iOS peering is foreground-only. Desktop does not offer the setting.
- Android does not tell apps their own Bluetooth address and advertises from rotating private addresses, so the protocol's rule that the lower address connects cannot be applied. Both phones may connect to each other at first; the duplicate link is then dropped by peer identity, as the protocol does for rotating addresses. Expect a few seconds of settling when two phones meet.
- Fragments are sized to what one write or notification can carry (ATT MTU minus 3 bytes, at most 512), because Android truncates anything longer.
- Up to 7 peers at once (the protocol default). Pairing is not used; the link has only Reticulum's own encryption.
- The node must be running for peering to work. Android uses its foreground service and notification; iOS peering works only while the app is foregrounded. Battery use has not been measured.
- If Bluetooth is switched off while the node is running, restart the node after switching it back on.
- A BLE RNode and Bluetooth peers can be enabled together; this combination is untested on hardware.

## Important limitations

- Android service startup and saved-hub reconnect have been exercised on a Pixel. Android BLE/TCP RNode support still needs an Android SDK build and physical-device/radio testing, including service notifications, Bluetooth permission handling, and clean shutdown.
- Desktop RNode serial configuration has not yet been exercised with physical radio hardware. Starling does not discover serial ports or check whether the configured RF parameters are legal for the user's region; configure them to match the RNode network and local regulations.
- iOS supports the same embedded TCP/IFAC, existing shared-instance, RRC, LXMF, and persisted chat-state features as Android. iOS does not provide an Android-style foreground service, so live networking is foreground-only. iOS and Android support one BLE RNode at a time and TCP/Wi-Fi RNodes. Native iOS build and BLE peering hardware verification have not been done; no App Store or TestFlight path is available for iOS peering.
- In one iOS app process, TCP peers can be disconnected and reconfigured, but switching between embedded and existing-shared-instance modes requires restarting the app because RNS is a process singleton.
- The advanced shared-instance option currently assumes the external RNS instance uses the default local shared-instance ports. Custom ports are not exposed yet.
- TCP authentication beyond IFAC, AutoInterface discovery, interface management, and detailed RNS status diagnostics are not exposed yet.
- Direct RNode USB serial is unavailable on Android and iOS. BLE support targets a supported RNode, where Bluetooth is the phone-to-radio link and LoRa (or another configured radio) provides the mesh medium. Phone-to-phone Bluetooth is a separate Android-only interface; see BLE peering (Android).
- The RNS identity is app-private and excluded from Android backup, but explicit Android Keystore-backed encryption and migration/recovery UX have not been implemented. Do not treat this prototype as production identity storage.
- The Identity screen displays the public Reticulum identity hash and LXMF delivery address, including while the node is stopped; it never displays the private key. Explicit export writes the private key to a user-selected file after a warning. Network status dots show the active RRC hub state and long-press/right-click opens detail.
- App-level saved contacts, blocks, Requests decisions, configured networks/channels, TCP/RNode interface preferences, and live room/direct-message history are stored locally and restored after process restart. Android stores this data under `noBackupFilesDir`; desktop uses the Starling application-data directory. Preview sample data and preview-created local data remain in-memory; the preview mode choice is persisted. The shared-instance RPC key is not retained in app preferences.
- The app-level chat state is currently plaintext in app-private storage; database encryption, retention controls, export, and recovery UX are not implemented.
- IFAC passphrases are retained in the app-private chat-state file (base64-encoded, not encrypted) and are also written to the app-private RNS config when the node starts. Shared-instance RPC keys are only written to the RNS config at runtime. These files rely on app-private directory permissions, not Keystore encryption. Do not reuse secrets from untrusted sources.
- QR scanning, attachments, propagation-node configuration, and optional RRC history are not implemented. LXMF direct delivery requires a known Reticulum path; propagation routing is not enabled by default.
- Review licenses and notices for all bundled native/Python dependencies before shipping release builds.

## Build

The Android build uses Chaquopy's Python 3.13 runtime and fetches `rns==1.5.7`, `lxmf==1.2.0`, and their Chaquopy-compatible dependencies. It also fetches `ble-reticulum` from its Git repository at a pinned commit, so `git` must be available. Python 3.13 must also be installed on the build host, alongside JDK 25 and an Android SDK:

```sh
ANDROID_HOME="$HOME/Android/Sdk" ./gradlew :androidApp:assembleDebug
```

The runtime experiment requires Android API 24 or newer and currently packages `arm64-v8a` and `x86_64`. The generated config is intentionally minimal; no transport routing is enabled.

The manual iOS workflow defaults to **iOS only** and produces an unsigned IPA for AltStore. It downloads the pinned Python Apple-support XCFramework, cross-builds PyCA cryptography for device and simulator, stages the RNS/LXMF modules and Starling bridges, builds the app with xtool, and post-processes the IPA to install Python's standard library and extension frameworks.

Desktop CI packages the self-contained JVM image as an x86_64 AppImage,
Flatpak, and Windows MSI, plus Apple Silicon and Intel macOS DMGs. The AppImage, MSI,
and DMG require host Python 3.8+ for live Reticulum. Flatpak bundles the Python
runtime dependencies. Windows serial names look like `COM3`; macOS ports are
typically `/dev/cu.*`. These desktop packages have not yet been tested against
physical Windows/macOS systems or RNode hardware.

## Existing shared instance setup

To opt into the advanced local-instance mode, the separate RNS daemon must use the same RPC key as entered in Starling. Its configuration needs matching values similar to:

```ini
[reticulum]
enable_transport = no
share_instance = yes
shared_instance_type = tcp
shared_instance_port = 37428
instance_control_port = 37429
rpc_key = <shared-hex-key>
```

Never reuse a key from an untrusted source. The RNS RPC key grants access to that local shared instance; keep it private.
