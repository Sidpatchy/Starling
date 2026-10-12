# Starling — project plan

## Product direction

- Build a focused chat client for Reticulum; chatting is the product, with no unrelated feature scope.
- Target Android, desktop, and iOS with Kotlin Multiplatform and a shared Compose Multiplatform UI.
- Keep platform-specific lifecycle, storage, and connectivity adapters where the OS requires them; SwiftUI-native UI is no longer the default plan.
- Treat web as a possible later target; first validate browser/runtime constraints for connecting to Reticulum.

## UI direction

- Desktop should feel like a traditional IRC client: networks with collapsible channels in a left sidebar and a member list pane on the right.
- On mobile, expose the network/channel tree from a left-side slide-out drawer and the member list from a matching right-side drawer.
- Keep a labeled connection-status entry at the bottom of the network/channel pane for quick status details, next to a separate gear entry for the full Settings page.
- Keep a no-setup local preview mode for trying the UI; allow preview users to add local networks, channels, contacts, and sample DM activity without implying a live Reticulum connection.
- In connected mode, prefer an app-managed embedded Reticulum node for the simplest setup and predictable lifecycle. Offer connection to an existing local RNS shared instance as an advanced alternative, not the default.
- Keep the default room timeline IRC/Discord-like and line-oriented rather than using iMessage-style chat bubbles; support IRC-style actions such as `/me` and basic inline Markdown (bold, italic, underline, strikethrough, and code). A bubble-based presentation can be an optional mode later.
- Offer System, Light (One Light), and Dark (One Dark) color themes, plus an optional bubble-message presentation.
- Use a violet/blue-violet accent rather than Meshtastic-like green; the Reticulum site itself uses a mostly neutral palette with blue links, not a strong green brand color.
- A room member can be saved as a DM contact from their profile. Contacts also have an independent **Add contact** flow for people not currently in a room; adding a contact never opens or creates an empty conversation.
- Keep the Direct Messages section visible even with zero contacts, with a **+** action and an empty state explaining that users can be added by QR or LXMF address.
- The initial out-of-room add flow should accept a shareable LXMF address (including QR scan/share). Reticulum has no central username directory; any announced-peer browser is a separate, opt-in discovery feature, not global search.
- Selecting a saved contact opens the shared IRC/Discord-style message timeline. Create/persist a conversation only when a message is sent or received, not merely when a contact is added.
- First-time inbound DMs from unknown senders go to **Requests**, with accept/add-contact, ignore, and block actions. Accepting a request adds the sender as a contact.
- On mobile, the member pane opens from the right-edge swipe as well as the person/count button.

## Protocol direction

- Start with IP connectivity, initially an outbound TCP client to a user-selected Reticulum peer/gateway; keep transport-node routing disabled by default. Evaluate local `AutoInterface` discovery as a separate convenience path. Android and desktop now have embedded RNS node paths with live RRC and LXMF adapters; physical Android service validation remains open.
- Treat Bluetooth as a later interface project, not an assumed built-in phone-to-phone mesh. First investigate BLE connectivity to supported RNode hardware (BLE is the device link; LoRa or another radio is the over-the-air mesh), then assess custom BLE packet interfaces and their Android/iOS interoperability constraints.
- Preserve an advanced option to connect Starling to an already-running Reticulum instance, while keeping the default embedded-node path behind the same shared transport boundary.
- Start with RRC (Reticulum Relay Chat) for core room messaging.
- Keep the baseline chat protocol usable without optional enhancements.
- Design an optional, negotiated capability/extension for peer-provided room history.
- Clients that opt in retain some room messages and can serve bounded history requests to other clients currently connected to the room.
- A joining client can query peers for a recent range or messages around a known message ID, then merge and deduplicate responses.
- History is best-effort rather than authoritative: peers can be offline, retain different subsets, or decline to retain/share messages.
- Plan for stable message IDs and timestamps, pagination and response limits, and explicit retention/privacy behavior.
- Consider richer IRCv3-inspired features as optional capabilities, rather than assuming IRCv3 itself provides storage. Prioritize history retrieval; expand only when needs are clear.
- Use RRC for room chat and LXMF as the preferred one-to-one DM transport, keeping delivery details mostly transparent in the chat UX. Both live paths are implemented and host-tested; initial local persistence for contacts, Requests, and live message history is implemented. Android device validation, encrypted storage, and user-created network/channel persistence remain open.

## Name

- Working name: **Starling** (e.g. "Starling Chat"). It evokes a flock sharing messages and fits the bird-name preference.
- The name is provisional until availability/trademark checks are done.

## Suggested first milestones

1. Validate the experimental Android embedded node against a real RNS TCP peer, including service lifecycle, reconnects, and identity/config storage.
2. Choose a distributable embedded runtime strategy for desktop and iOS; retain a typed advanced option for an existing local shared instance.
3. Verify RRC's current protocol behavior and interoperability expectations, then implement platform `ReticulumTransport` adapters for RRC hub/room events and sending messages; keep Compose behind the shared repository boundary.
4. Define the smallest useful history capability: negotiation, bounded request/response, message identifiers, timestamps, and retention controls.
5. Prototype shared Kotlin UI/core and Reticulum connectivity on Android and desktop.
6. Add and validate the shared Compose UI on iOS using Xcode; investigate whether a web target is practical later.
7. Build a minimal chat experience before broadening protocol features.
