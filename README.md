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
