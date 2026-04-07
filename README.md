# OpenClaw Android TV Receiver

This repository now contains a starter architecture for an Android TV casting receiver MVP.

## What this skeleton optimizes for

- Fast validation of an Android TV receiver product direction.
- Clean module boundaries before AirPlay and DLNA code lands.
- A shared player contract so multiple protocols can feed the same playback engine.

## Recommended protocol path

1. AirPlay receiver first.
2. DLNA / UPnP second.
3. Google Cast only if product direction requires it.
4. Miracast much later.

## Current state

- Multi-module Android project scaffold.
- Minimal Android TV launcher activity.
- Gradle Wrapper added to the repository.
- Protocol roadmap models and architecture notes in `docs/mvp-architecture.md`.
- Setup and validation guide in `docs/setup-and-validation.md`.

## Next

- Wire a real Media3 player implementation.
- Integrate AirPlay receiving path.
- Integrate DLNA discovery and playback push.
