# Starling

Starling is a focused chat client for Reticulum, built with Kotlin Multiplatform
and Compose Multiplatform. Android, desktop, and iOS share the same UI.

The app keeps an isolated local preview as its no-setup mode. Its on/off choice
is saved, and preview hides live network settings and communications data.
Android and iOS can start an embedded RNS node over multiple user-configured TCP
peers at once or attach to an existing local RNS shared instance in advanced mode.
Desktop can also attach RNode firmware radios over serial on Linux, Windows, and macOS.
Each embedded TCP connection can
have its own optional IFAC network name and passphrase. Live rooms use RRC and
direct messages use the reference LXMF router. RRC rooms are joined by channel
name; RRC does not provide room discovery or history. See
[`docs/RETICULUM_RUNTIME.md`](docs/RETICULUM_RUNTIME.md) for scope and limitations.
The preview includes System, Light (One Light palette), and Dark (One Dark palette) themes, with an optional
bubble-style message layout; the IRC-style timeline remains the default. Its DM preview
supports adding local demo contacts by name, opening local DM threads, and handling
a sample unknown-sender request. In live mode, use + under Networks to add an RRC hub
destination, then add the channel names Starling should join. Starling remembers the
preferred hub and reconnects when a saved embedded node starts. In preview, networks and
channels are local-only. Live LXMF unknown senders go to Requests; the
local delivery address is shown in Identity and Settings for sharing. The Identity
screen lets you set a global LXMF nickname and per-hub RRC nickname overrides.
Saved nicknames are used for manual and automatic hub connections; changing the
active hub's effective nickname reconnects it and rejoins configured channels.
Contacts, requests, and configured networks/channels and live message history are stored locally; QR
scanning is not implemented yet. A minimal SwiftUI/xtool host embeds the shared
Compose UI and CPython RNS/LXMF runtime on iOS. iOS supports TCP/IFAC, RRC, and
LXMF while the app is in the foreground; direct RNode support and an Android-style
background service are not available. See
[`iosApp/README.md`](iosApp/README.md) for the current build flow and host limits.

Android bundles the reference `rns==1.5.7` and `lxmf==1.2.0` Python packages using Chaquopy;
iOS embeds CPython 3.13 and the same pinned Python modules; desktop installs the
pinned packages in a Starling-private Python environment when identity management
or live networking needs them. Their licenses are included in `licenses/`.

Starling is licensed under the GNU Affero General Public License, version 3
(AGPL-3.0-only); see [`LICENSE`](LICENSE). Third-party components remain under
their respective licenses.

## Identity management

Fresh installs do **not** generate a chat identity. Turn off Local preview in
Settings, then open **Identity** to create an identity or import an existing
Reticulum private identity file. Existing saved identities are reused unchanged.
An identity is required before live networking starts; there is no welcome screen yet.

Identity provides **Create**, **Import**, **Export backup**, and **Delete**.
Disconnect the node before creating, replacing, or deleting an identity. Import
and export use platform file dialogs and standard 64-byte Reticulum private-key
files (exports use the `.rnsidentity` extension).

**Backups are unencrypted private keys. Anyone with a copy can use your identity.**
Keep backups secure. Import replaces the current identity; export it first if
needed. Delete removes the identity from this app, not contacts, chat history,
or previously exported copies. Private keys are never displayed or stored in chat state.

## Run

Use JDK 25 for the Gradle build. Android builds also require an Android SDK and Python 3.13 on the build host for Chaquopy.

```sh
./gradlew :composeApp:run
```

To build the Android debug APK (requires an Android SDK):

```sh
./gradlew :androidApp:assembleDebug
```

Desktop CI builds Linux AppImage and Flatpak bundles, a Windows MSI, and
Apple Silicon and Intel macOS DMGs. These packages run on published GitHub
Releases or manual dispatch, not on every commit; release builds are attached
to the release page. Mobile CI also attaches an unsigned APK and device IPA.
Published releases use their tag as the app version (with an optional leading
`v`); manual builds from a branch use the `starlingVersion` default in
`gradle.properties`. MSI/DMG package metadata uses the numeric version base,
while downloadable asset names retain the full prerelease tag.
The desktop packages are unsigned (the DMGs are not notarized). AppImage, MSI,
and DMG include the JVM but need host Python 3.8+ for Reticulum; Flatpak bundles
its Python/RNS dependencies and requests device access for optional serial
RNode radios. Example serial
ports are `COM3` on Windows, `/dev/cu.usbserial*` on macOS, and
`/dev/ttyUSB*` or `/dev/ttyACM*` on Linux. USB serial drivers and OS device
permissions are still required.

## Tests

Run the shared Kotlin regression tests on desktop with JDK 25:

```sh
./gradlew :composeApp:desktopTest
```

These tests use local preview data and fake transports; no live Reticulum peer
or Python runtime is required.

The Python bridge and identity tests need only host Python 3.8+. Optional reference
interoperability tests also run when RNS/LXMF are installed:

```sh
PYTHONPATH=composeApp/src/main/python python3 -m unittest discover -s composeApp/src/test/python
```

Test the shared CI release-version resolver with host Python and Bash:

```sh
python3 .github/scripts/test_resolve_version.py
```
