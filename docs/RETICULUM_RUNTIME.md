# Reticulum runtime prototype

## What exists now

- **Local Preview** remains enabled by default and is persisted with chat state on Android, desktop, and iOS. Preview is isolated from live Reticulum state: it shows only sample/local preview data, hides live addresses and connection configuration, ignores live events, and disconnects the node and hub while active. Preview messages are never sent over RNS.
- Android has an embedded node using reference `rns==1.5.7` and `lxmf==1.2.0` through Chaquopy 17 / Python 3.13.
- iOS embeds CPython 3.13 from the pinned BeeWare Python Apple-support framework and uses the same `rns==1.5.7`, `lxmf==1.2.0`, and Starling Python bridge. PyCA `cryptography` and CFFI are built as iOS device/simulator wheels and packaged as signed extension frameworks.
- Desktop runs the same Python bridge in a child process, installing those pinned packages into a Starling-private virtual environment on first explicit connection. Windows and macOS use this same host-Python flow; Flatpak ships the Python/RNS dependencies inside its app bundle.
- Android starts the node on demand from Settings in a dedicated process and foreground service. Android shows an ongoing notification while it is running.
- Embedded mode configures multiple outbound `TCPClientInterface` instances from the TCP peer list. Each connection can have its own optional IFAC network name and passphrase; both must match that peer's interface settings. RNS transport-node routing is explicitly disabled; Starling is a client, not a gateway.
- Desktop can configure one or more reference `RNodeInterface` instances over serial ports (for example `/dev/ttyUSB0`) or the RNS TCP RNode transport. iOS and Android support RNodes over TCP/Wi-Fi (`tcp://host`, RNode's default TCP port 7633) and one BLE RNode at a time (`ble://device name`, or bare `ble://` to select the first nearby RNode). iOS uses CoreBluetooth and Android uses `BluetoothGatt` to bridge the Nordic UART service to a loopback TCP socket consumed by RNS's TCP RNode transport. Direct USB serial is not available on either mobile platform. Enter the radio frequency, bandwidth, transmit power, spreading factor, and coding rate as required by the radio. The RNode must already have compatible RNode firmware installed.
- Advanced mode attaches to a Reticulum shared instance already running on the same device. Since Starling has a private config directory, the existing instance must be configured with a shared RPC key and the default local ports (37428/37429). This is not a generic remote-daemon management protocol.
- Android config and identity files live under `noBackupFilesDir/reticulum`, to avoid copying identity material through Android backup/restore. Desktop state is stored in the user's Starling application-data directory. iOS stores RNS identity/config under app-private Application Support and excludes that directory from device backup.
- A host-side smoke test against reference RNS 1.5.5 has verified both an outbound Starling TCP client connecting to a local RNS TCP server and the advanced shared-instance/RPC-key handshake. This validates the generated RNS config and Python bridge, not the Android service on a physical device.
- The iOS Python bridge smoke test verifies an IFAC TCP connection to a separate RNS server, reconnects after disconnect, and confirms that a mismatched IFAC credential does not establish an RNS path. Physical-device RRC/LXMF interoperability still needs testing.
- The shared RRC 1.0 client uses an encrypted RNS Link to an `rrc.hub` destination, performs HELLO/WELCOME, joins configured channel names, and sends/receives room messages. Starling also sends the optional room-scoped `NOTICE` soft-presence convention `Starling presence: online` after joining and every five minutes; Starling clients use it to refresh their live roster without adding a transcript line. It has been exercised against the reference `rrcd` hub.
- LXMF direct delivery uses the reference router, persists the local Reticulum identity, announces a shareable delivery address, and sends opportunistic messages. Inbound messages are displayed only after the sender identity is resolved and its signature validates; unknown identities trigger a path request and verification retry before the message is withheld. Verified first-time senders go to Requests. Two independent host runtimes and the desktop sidecar protocol have passed bidirectional delivery smoke tests.

The UI distinguishes node/interface state from RRC hub connection state. In live mode, use + on Networks to register an RRC hub destination and add the channel names Starling should join. Settings selects one preferred RRC hub at a time; its channels are joined by name. The Identity screen configures the global nickname used for LXMF and as the default RRC nickname, with optional per-hub overrides. Every manual/automatic hub connection uses the saved effective nickname; changing it for the active hub reconnects that hub and rejoins configured channels. RRC has no room-enumeration command and does not retain history. Android, desktop, and iOS persist live configured networks/channels, the preferred and last active hub, the public identity hash, global/per-hub nicknames, and TCP/RNode interface settings with local chat state. With Local preview off, an existing identity, and a valid saved embedded-node configuration, the node starts at app launch and the preferred hub connects when ready. Existing shared-instance mode remains manual because its RPC key is not retained in app preferences. Preview-created networks, contacts, conversations, and sample activity remain in-memory.

Chat identities are never generated implicitly. Turn off Local preview and use Identity to create or import one before starting live networking. Existing identities remain usable without migration. Identity supports creation, standard 64-byte Reticulum private-key import/export through platform file dialogs, and explicit deletion. Creating/importing/deleting requires a stopped node; exporting is also available while connected. Invalid imports are rejected before replacing the saved key, and replacement writes are atomic. Deletion clears cached public/self identity information but keeps contacts and history. Backups are unencrypted secrets and remain valid after deletion; there is no key revocation or encrypted-backup format. The UI requires confirmation before import, export, or deletion. A welcome/onboarding screen is intentionally not included yet.

## Desktop RNode setup (initial serial support)

1. Use a supported LoRa board and flash it with RNode firmware using the upstream `rnodeconf --autoinstall` workflow. Starling does not flash or update radio firmware.
2. Connect the RNode over USB serial and note its port (`/dev/ttyUSB0` or `/dev/ttyACM0` on Linux, `COM3` on Windows, or `/dev/cu.usbserial*` on macOS).
3. In Settings, add an RNode serial interface and enter its serial path and radio parameters in Hz/dBm.

The radio parameters must match the RNode network and comply with local spectrum and transmit-power rules. RNode firmware replaces the board's Meshtastic firmware; it does not make a Meshtastic radio transparently speak Reticulum. Use a spare or otherwise appropriate device if you need to keep your existing Meshtastic node online. Windows and macOS use the same RNS/PySerial interface; the OS serial driver and device permissions are still required. Flatpak grants device access so serial radios can work inside its sandbox.

The desktop adapter supports serial, while mobile adapters support TCP/Wi-Fi and Nordic-UART BLE. Direct USB serial is not available on Android or iOS. As with other Starling embedded interfaces, transport-node routing remains disabled, so Starling does not relay traffic for other nodes.

## Mobile RNode setup

1. Flash a supported radio with compatible RNode firmware and configure its Wi-Fi TCP service or Nordic UART BLE service.
2. In Starling's embedded-node settings, add an RNode and use `tcp://host` for Wi-Fi (TCP port 7633) or `ble://device name` for BLE. Bare `ble://` selects the first nearby RNode. Android also accepts a BLE device address.
3. Enter the radio frequency, bandwidth, transmit power, spreading factor, and coding rate to match the radio and local regulations. The current Android RNS adapter limits bandwidth to 500 kHz. Android requests Bluetooth scan/connect permission when a BLE interface is started.

On mobile, Starling bridges BLE UART bytes to a local TCP socket and uses RNS's TCP RNode transport; Reticulum continues to perform radio detection/configuration and packet framing. TCP/Wi-Fi connects directly through the same RNS adapter. Only one BLE radio can be connected at a time per app instance.

## Important limitations

- Android service startup and saved-hub reconnect have been exercised on a Pixel. Android BLE/TCP RNode support still needs an Android SDK build and physical-device/radio testing, including service notifications, Bluetooth permission handling, and clean shutdown.
- Desktop RNode serial configuration has not yet been exercised with physical radio hardware. Starling does not discover serial ports or check whether the configured RF parameters are legal for the user's region; configure them to match the RNode network and local regulations.
- iOS supports the same embedded TCP/IFAC, existing shared-instance, RRC, LXMF, and persisted chat-state features as Android. iOS does not provide an Android-style foreground service, so live networking is foreground-only. iOS and Android support one BLE RNode at a time and TCP/Wi-Fi RNodes. Native iOS build and iOS BLE hardware verification require macOS/device access.
- In one iOS app process, TCP peers can be disconnected and reconfigured, but switching between embedded and existing-shared-instance modes requires restarting the app because RNS is a process singleton.
- The advanced shared-instance option currently assumes the external RNS instance uses the default local shared-instance ports. Custom ports are not exposed yet.
- TCP authentication beyond IFAC, AutoInterface discovery, interface management, and detailed RNS status diagnostics are not exposed yet.
- Direct RNode USB serial is unavailable on Android and iOS. BLE support targets a supported RNode, where Bluetooth is the phone-to-radio link and LoRa (or another configured radio) provides the mesh medium. Generic phone-to-phone BLE mesh is a separate, unvalidated interface project.
- The RNS identity is app-private and excluded from Android backup, but explicit Android Keystore-backed encryption and migration/recovery UX have not been implemented. Do not treat this prototype as production identity storage.
- The Identity screen displays the public Reticulum identity hash and LXMF delivery address, including while the node is stopped; it never displays the private key. Explicit export writes the private key to a user-selected file after a warning. Network status dots show the active RRC hub state and long-press/right-click opens detail.
- App-level saved contacts, blocks, Requests decisions, configured networks/channels, TCP/RNode interface preferences, and live room/direct-message history are stored locally and restored after process restart. Android stores this data under `noBackupFilesDir`; desktop uses the Starling application-data directory. Preview sample data and preview-created local data remain in-memory; the preview mode choice is persisted. The shared-instance RPC key is not retained in app preferences.
- The app-level chat state is currently plaintext in app-private storage; database encryption, retention controls, export, and recovery UX are not implemented.
- IFAC passphrases are retained in the app-private chat-state file (base64-encoded, not encrypted) and are also written to the app-private RNS config when the node starts. Shared-instance RPC keys are only written to the RNS config at runtime. These files rely on app-private directory permissions, not Keystore encryption. Do not reuse secrets from untrusted sources.
- QR scanning, attachments, propagation-node configuration, and optional RRC history are not implemented. LXMF direct delivery requires a known Reticulum path; propagation routing is not enabled by default.
- Review licenses and notices for all bundled native/Python dependencies before shipping release builds.

## Build

The Android build uses Chaquopy's Python 3.13 runtime and fetches `rns==1.5.7`, `lxmf==1.2.0`, and their Chaquopy-compatible dependencies. Python 3.13 must also be installed on the build host, alongside JDK 25 and an Android SDK:

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
