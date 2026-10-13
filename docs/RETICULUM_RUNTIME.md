# Reticulum runtime prototype

## What exists now

- **Local Preview** remains enabled by default. Android and desktop persist its on/off choice with chat state; the current iOS preview is session-only. Preview is isolated from live Reticulum state: it shows only sample/local preview data, hides live addresses and connection configuration, ignores live events, and disconnects the node and hub while active. Preview messages are never sent over RNS.
- Android has an embedded node using reference `rns==1.5.7` and `lxmf==1.2.0` through Chaquopy 17 / Python 3.13.
- Desktop runs the same Python bridge in a child process, installing those pinned packages into a Starling-private virtual environment on first explicit connection. Windows and macOS use this same host-Python flow; Flatpak ships the Python/RNS dependencies inside its app bundle.
- Android starts the node on demand from Settings in a dedicated process and foreground service. Android shows an ongoing notification while it is running.
- Embedded mode configures multiple outbound `TCPClientInterface` instances from the TCP peer list. Each connection can have its own optional IFAC network name and passphrase; both must match that peer's interface settings. RNS transport-node routing is explicitly disabled; Starling is a client, not a gateway.
- Desktop can configure one or more reference `RNodeInterface` instances over serial ports (for example `/dev/ttyUSB0`), including the radio frequency, bandwidth, transmit power, spreading factor, and coding rate. RNS configures the radio when the node starts; the RNode must already have compatible RNode firmware installed. The first implementation is desktop serial only; Android USB/Bluetooth and direct BLE support are not wired into Starling yet.
- Advanced mode attaches to a Reticulum shared instance already running on the same device. Since Starling has a private config directory, the existing instance must be configured with a shared RPC key and the default local ports (37428/37429). This is not a generic remote-daemon management protocol.
- Android config and identity files live under `noBackupFilesDir/reticulum`, to avoid copying identity material through Android backup/restore. Desktop state is stored in the user's Starling application-data directory.
- A host-side smoke test against reference RNS 1.5.5 has verified both an outbound Starling TCP client connecting to a local RNS TCP server and the advanced shared-instance/RPC-key handshake. This validates the generated RNS config and Python bridge, not the Android service on a physical device.
- The shared RRC 1.0 client uses an encrypted RNS Link to an `rrc.hub` destination, performs HELLO/WELCOME, joins configured channel names, and sends/receives room messages. It has been exercised against the reference `rrcd` hub.
- LXMF direct delivery uses the reference router, persists the local Reticulum identity, announces a shareable delivery address, and sends opportunistic messages. Inbound messages are displayed only after the sender identity is resolved and its signature validates; unknown identities trigger a path request and verification retry before the message is withheld. Verified first-time senders go to Requests. Two independent host runtimes and the desktop sidecar protocol have passed bidirectional delivery smoke tests.

The UI distinguishes node/interface state from RRC hub connection state. In live mode, use + on Networks to register an RRC hub destination and add the channel names Starling should join. Settings selects one preferred RRC hub at a time; its channels are joined by name. The Identity screen configures the global nickname used for LXMF and as the default RRC nickname, with optional per-hub overrides that apply on the next connection. RRC has no room-enumeration command and does not retain history. Android and desktop persist live configured networks/channels, the preferred and last active hub, the public identity hash, global/per-hub nicknames, and TCP/RNode interface settings with local chat state. On those platforms, Local preview off with a valid saved embedded-node configuration starts the node at app launch and connects the preferred hub when ready. Existing shared-instance mode remains manual because its RPC key is not retained in app preferences. iOS currently has only a session-only plain-TCP reachability check. Preview-created networks, contacts, conversations, and sample activity remain in-memory.

## Desktop RNode setup (initial serial support)

1. Use a supported LoRa board and flash it with RNode firmware using the upstream `rnodeconf --autoinstall` workflow. Starling does not flash or update radio firmware.
2. Connect the RNode over USB serial and note its port (`/dev/ttyUSB0` or `/dev/ttyACM0` on Linux, `COM3` on Windows, or `/dev/cu.usbserial*` on macOS).
3. In Settings, add an RNode serial interface and enter its serial path and radio parameters in Hz/dBm.

The radio parameters must match the RNode network and comply with local spectrum and transmit-power rules. RNode firmware replaces the board's Meshtastic firmware; it does not make a Meshtastic radio transparently speak Reticulum. Use a spare or otherwise appropriate device if you need to keep your existing Meshtastic node online. Windows and macOS use the same RNS/PySerial interface; the OS serial driver and device permissions are still required. Flatpak grants device access so serial radios can work inside its sandbox.

This first Starling adapter is desktop serial only. Direct Android USB/Bluetooth and direct BLE support are not included yet. As with other Starling embedded interfaces, transport-node routing remains disabled, so Starling does not relay traffic for other nodes.

## Important limitations

- Android service startup and saved-hub reconnect have been exercised on a Pixel. Further physical-device testing of service notifications, clean shutdown, and the latest LXMF signature/address changes is still needed; the latest build could not be installed while no device was attached.
- Desktop RNode serial configuration has not yet been exercised with physical radio hardware. Starling does not discover serial ports or check whether the configured RF parameters are legal for the user's region; configure them to match the RNode network and local regulations.
- iOS currently provides only an outbound plain-TCP socket check from Settings. It does not embed RNS, exchange Reticulum packets, support IFAC, or provide RRC/LXMF transport. The temporary host/port settings are not persisted.
- The advanced shared-instance option currently assumes the external RNS instance uses the default local shared-instance ports. Custom ports are not exposed yet.
- TCP authentication beyond IFAC, AutoInterface discovery, interface management, and detailed RNS status diagnostics are not exposed yet.
- Direct RNode USB/Bluetooth support on Android and iOS is not implemented. The first Bluetooth extension should target a supported RNode, where Bluetooth is the phone-to-radio link and LoRa (or another configured radio) provides the mesh medium. Generic phone-to-phone BLE mesh is a separate, unvalidated interface project.
- The RNS identity is app-private and excluded from Android backup, but explicit Android Keystore-backed encryption and migration/recovery UX have not been implemented. Do not treat this prototype as production identity storage.
- The Identity screen displays the public Reticulum identity hash and LXMF delivery address; it never displays the private key. Network status dots show the active RRC hub state and long-press/right-click opens detail.
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
