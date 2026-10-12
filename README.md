# Starling

Starling is a focused chat client for Reticulum, built with Kotlin Multiplatform
and Compose Multiplatform. Android and Linux desktop share the initial UI.

The app keeps an interactive local preview as its no-setup mode, with a
session-only switch to hide or show its sample content. Android and desktop can
start an embedded RNS node over multiple user-configured TCP peers at once or
attach to an existing local RNS shared instance in advanced mode. Desktop can
also attach RNode firmware radios over serial. Each embedded TCP connection can
have its own optional IFAC network name and passphrase. Live rooms use RRC and
direct messages use the reference LXMF router. RRC rooms are joined by channel
name; RRC does not provide room discovery or history. See
[`docs/RETICULUM_RUNTIME.md`](docs/RETICULUM_RUNTIME.md) for scope and limitations.
The preview includes System, Light (One Light palette), and Dark (One Dark palette) themes, with an optional
bubble-style message layout; the IRC-style timeline remains the default. Its DM preview
supports adding local contacts by name/address, opening local DM threads, and handling
a sample unknown-sender request. Use + under Networks to add an RRC hub
destination, then add the channel names Starling should join. Live LXMF unknown senders go to Requests; the
local delivery address is shown in Settings for sharing. Contacts, requests, and
configured networks/channels and live message history are stored locally; QR
scanning is not implemented yet. iOS has no runtime adapter.

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
