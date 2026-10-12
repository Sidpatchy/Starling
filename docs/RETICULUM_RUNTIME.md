# Reticulum runtime prototype

## What exists now

- **Local Preview** remains enabled by default. Its session-only Settings switch hides or shows the sample networks, channels, messages, and request; that switch is not persisted. Preview content is never sent over RNS.
- Android has an embedded node using reference `rns==1.5.5` and `lxmf==1.2.0` through Chaquopy 17 / Python 3.13.
- Desktop runs the same Python bridge in a child process, installing those pinned packages into a Starling-private virtual environment on first explicit connection.
- Android starts the node on demand from Settings in a dedicated process and foreground service. Android shows an ongoing notification while it is running.
- Embedded mode configures multiple outbound `TCPClientInterface` instances from the TCP peer list. Each connection can have its own optional IFAC network name and passphrase; both must match that peer's interface settings. RNS transport-node routing is explicitly disabled; Starling is a client, not a gateway.
- Advanced mode attaches to a Reticulum shared instance already running on the same device. Since Starling has a private config directory, the existing instance must be configured with a shared RPC key and the default local ports (37428/37429). This is not a generic remote-daemon management protocol.
- Android config and identity files live under `noBackupFilesDir/reticulum`, to avoid copying identity material through Android backup/restore. Desktop state is stored in the user's Starling application-data directory.
- A host-side smoke test against reference RNS 1.5.5 has verified both an outbound Starling TCP client connecting to a local RNS TCP server and the advanced shared-instance/RPC-key handshake. This validates the generated RNS config and Python bridge, not the Android service on a physical device.
- The shared RRC 1.0 client uses an encrypted RNS Link to an `rrc.hub` destination, performs HELLO/WELCOME, joins configured channel names, and sends/receives room messages. It has been exercised against the reference `rrcd` hub.
- LXMF direct delivery uses the reference router, persists the local Reticulum identity, announces a shareable delivery address, sends opportunistic messages, and routes verified first-time inbound messages to Requests. Two independent host runtimes and the desktop sidecar protocol have passed bidirectional delivery smoke tests.

The UI distinguishes node/interface state from RRC hub connection state. Use + on Networks to register an RRC hub destination and add the channel names Starling should join. Settings connects to one configured RRC hub at a time; its channels are joined by name. RRC has no room-enumeration command and does not retain history. Configured networks and channels are persisted with local chat state; preview mode remains session-only.

## Important limitations

- Android service startup, reconnect, notification behavior, and clean shutdown still need physical-device validation; no attached device has been tested.
- iOS does not yet embed RNS or have a transport adapter.
- The advanced shared-instance option currently assumes the external RNS instance uses the default local shared-instance ports. Custom ports are not exposed yet.
- TCP authentication beyond IFAC, AutoInterface discovery, interface management, and detailed RNS status diagnostics are not exposed yet.
- BLE is not implemented. The first Bluetooth experiment should target BLE to a supported RNode, where Bluetooth is the phone-to-radio link and LoRa (or another configured radio) provides the mesh medium. Generic phone-to-phone BLE mesh is a separate, unvalidated interface project.
- The RNS identity is app-private and excluded from Android backup, but explicit Android Keystore-backed encryption and migration/recovery UX have not been implemented. Do not treat this prototype as production identity storage.
- App-level saved contacts, blocks, Requests decisions, configured networks/channels, and live room/direct-message history are stored locally and restored after process restart. Android stores this data under `noBackupFilesDir`; desktop uses the Starling application-data directory. Preview sample data and the preview on/off choice remain in-memory.
- The app-level chat state is currently plaintext in app-private storage; database encryption, retention controls, export, and recovery UX are not implemented.
- IFAC passphrases and shared-instance RPC keys are written to the app-private RNS config as plaintext, protected by app-private directory/file permissions but not Keystore-encrypted. Do not reuse secrets from untrusted sources.
- QR scanning, attachments, propagation-node configuration, and optional RRC history are not implemented. LXMF direct delivery requires a known Reticulum path; propagation routing is not enabled by default.
- Review licenses and notices for all bundled native/Python dependencies before shipping release builds.

## Build

The Android build fetches `rns==1.5.5`, `lxmf==1.2.0`, and their Chaquopy-compatible dependencies:

```sh
ANDROID_HOME="$HOME/Android/Sdk" ./gradlew :composeApp:assembleDebug
```

The runtime experiment requires Android API 24 or newer and currently packages `arm64-v8a` and `x86_64`. The generated config is intentionally minimal; no transport routing is enabled.

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
