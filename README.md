# Crown Media

Clean-room native Android IPTV client for phones, tablets, Android TV, and Fire TV.

## Targets

- Android API 21+
- Touch and D-pad presentation shells
- Xtream Codes-compatible services
- Media3 ExoPlayer and external VLC/MX playback
- Offline catalog cache, favorites, progress, playlists, EPG, and parental controls

## Current implementation checkpoint

- Service-based Xtream authentication and encrypted multi-playlist credentials
- Live, movie, series, episode, short-EPG, catch-up, and trailer flows
- Media3 internal player plus VLC, MX Player, and system chooser handoff
- Room-backed cached-first catalogs and local cross-catalog search
- Playlist-scoped favorites, hidden categories, sorting, and parental PIN
- Phone/tablet responsive grid and Android TV/Fire TV launcher + D-pad focus

Device-code activation UI is available directly from login. Production provisioning
still requires the Crown Media activation service base URL and API contract.

## Build

```bash
./gradlew :app:assembleDebug
```

Brand surfaces consistently present the Crown Media mark on a white background. The
exact replacement artwork can be dropped into `crown_media_logo.png` without layout changes.

## Anonymous usage analytics

Firebase Analytics integration is optional at build time and safely remains disabled when no
Firebase configuration is present. To enable it:

1. Create or select a Firebase project with Google Analytics enabled.
2. Register Android apps `uk.crownmedia.app` and `uk.crownmedia.app.debug` in the same project so
   release and debug variants are both represented in the downloaded configuration.
3. Place its `google-services.json` at `app/google-services.json` before building the release.

The configuration file is intentionally ignored by Git. Supply it to release builds through the
local build environment or CI secrets. Users are asked to opt in before collection starts and can
change their choice under Settings. Collected events cover screens, content types, search usage,
login outcomes, category selection, and playback requests. Credentials, provider URLs, playlist
or content identifiers, titles, and search terms are never added to analytics events.
