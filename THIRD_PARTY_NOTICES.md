# Third-party notices

## AndroidX Media3 FFmpeg audio decoder

`player/libs/media3-decoder-ffmpeg-1.4.1.aar` is built from the official AndroidX
Media3 FFmpeg decoder module at tag `1.4.1`, commit
`c35a9d62baec57118ea898e271ac66819399649b`. The wrapper is licensed under
Apache License 2.0. A copy is packaged at
`player/src/main/assets/licenses/androidx-media3-APACHE-2.0.txt`.

The native decoder uses FFmpeg tag `n6.0`, commit
`ea3d24bbe3c58b171e55fe2151fc7ffaca3ab3d2`, configured as an LGPL 2.1-or-later
audio-only build. Enabled decoders are Vorbis, Opus, FLAC, ALAC, PCM μ-law,
PCM A-law, MP3, AAC, AC-3, E-AC-3, DTS, MLP, and TrueHD. A copy of the LGPL 2.1
license is packaged at `player/src/main/assets/licenses/ffmpeg-LGPL-2.1.txt`.

The pinned, reproducible build command is in `scripts/build-media3-ffmpeg.sh`.
The checked-in AAR SHA-256 is
`f22266317b9beff488d061e00e67dfd7b6ea554d99538a1d8b4d9889b2a06a09`.

Source repositories:

- https://github.com/androidx/media
- https://github.com/FFmpeg/FFmpeg
