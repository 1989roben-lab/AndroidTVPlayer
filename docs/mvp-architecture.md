# OpenClaw Android TV Receiver MVP

## Goal

Build the fastest credible Android TV casting receiver MVP without pretending we can match LeBo, Orange Cast, or vendor-grade commercial compatibility on day one.

## Product boundary

### In scope for MVP

- AirPlay receiver for URL, image, and audio style payloads.
- DLNA / UPnP discovery and push playback.
- Android TV launcher app with home screen, waiting state, and player screen.
- Media3 / ExoPlayer based playback core.
- Local network pairing cues such as device name and connection state.

### Explicitly out of scope for MVP

- AirPlay 2 parity.
- DRM-protected in-app streaming from Tencent Video, iQIYI, Youku, Apple TV, and similar apps.
- Miracast stability across heterogeneous TV hardware.
- Full Google Cast productization.

## Recommended integration order

1. Create a stable TV shell app and player surface.
2. Integrate `warren-bank/Android-ExoPlayer-AirPlay-Receiver` concepts or code path for AirPlay v1 style receiving.
3. Add `yinnho/UPnPCast` for SSDP discovery and DLNA push flow.
4. Unify remote commands through one internal playback contract.
5. Evaluate Google Cast only after the above is stable.
6. Push Miracast to a separate feasibility track.

## Suggested module layout

- `app`
  - Android TV entry point, navigation, DI wiring, launcher activity.
- `core-player`
  - Media3 player wrapper, playback session model, player gateway.
- `core-protocol`
  - Shared protocol models, capability flags, common receiver abstractions.
- `core-ui`
  - Reusable TV UI tokens and shared composables.
- `feature-discovery`
  - Bonjour, mDNS, SSDP, and device-state reporting.
- `feature-player`
  - Player screen, transport controls, playback state translation.

## Receiver abstraction to keep

All protocols should be forced into one internal command pipeline:

- `prepare/play(uri, mimeType, headers)`
- `pause/resume`
- `seek(positionMs)`
- `stop`
- `updateMetadata(title, subtitle, artwork)`

That keeps AirPlay and DLNA integrations from hard-binding themselves directly to the UI layer.

## Protocol notes

### AirPlay

- Best first protocol for iPhone-originating casting.
- Treat open-source receiver support as AirPlay v1 class capability, not AirPlay 2.
- Plan for partial compatibility and communicate supported scenarios clearly in-product.

### DLNA / UPnP

- Strong second protocol because it covers many Android, Windows, NAS, and TV sender cases.
- Prefer a maintained codebase over older Cling-heavy examples.

### Google Cast

- Only worth integrating if product direction needs Chromecast ecosystem support.
- Expect console setup, receiver registration, and more ecosystem-specific work.

### Miracast

- Treat as a hardware program, not a normal app feature.
- Defer until the receiver app has product traction.

## Next implementation steps

1. Replace placeholder protocol cards with live service status.
2. Introduce a real `PlayerGateway` implementation backed by Media3.
3. Add an AirPlay integration spike module and map incoming URL play commands to `PlaybackRequest`.
4. Add DLNA discovery and transport controls.
5. Build the waiting screen, pairing code screen, and full-screen player experience for D-pad navigation.
