# Starling

Starling is a focused chat client for Reticulum, built with Kotlin Multiplatform
and Compose Multiplatform. Android and desktop share the same UI.

The app keeps an isolated local preview as its no-setup mode. Its on/off choice
is saved, and preview hides live network settings and communications data.
Android and desktop can
start an embedded RNS node over multiple user-configured TCP peers at once or
attach to an existing local RNS shared instance in advanced mode. Desktop can
also attach RNode firmware radios over serial on Linux, Windows, and macOS.
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
Contacts, requests, and configured networks/channels and live message history are stored locally; QR
scanning is not implemented yet. An iOS SwiftUI/xtool host shim is available; it
uses local preview only until an iOS Reticulum adapter is implemented. See
[`iosApp/README.md`](iosApp/README.md) for the current build flow and host limits.

Android bundles the reference `rns==1.5.7` and `lxmf==1.2.0` Python packages using Chaquopy;
desktop installs the pinned packages in a Starling-private Python environment on
first explicit connection. Their licenses are included in `licenses/`.

Starling is licensed under the GNU Affero General Public License, version 3
(AGPL-3.0-only); see [`LICENSE`](LICENSE). Third-party components remain under
their respective licenses.

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
The initial app version is `0.1.0-alpha.1` in `gradle.properties`; publish a
matching `0.1.0-alpha.1` release tag (a leading `v` is also accepted). MSI/DMG package metadata uses the numeric
`0.1.0` base, while the downloadable asset names keep the prerelease suffix.
The desktop packages are unsigned (the DMGs are not notarized). AppImage, MSI,
and DMG include the JVM but need host Python 3.8+ for Reticulum; Flatpak bundles
its Python/RNS dependencies and requests device access for optional serial
RNode radios. Example serial
ports are `COM3` on Windows, `/dev/cu.usbserial*` on macOS, and
`/dev/ttyUSB*` or `/dev/ttyACM*` on Linux. USB serial drivers and OS device
permissions are still required.
