#!/usr/bin/env bash
set -euo pipefail

media3_tag="1.4.1"
media3_commit="c35a9d62baec57118ea898e271ac66819399649b"
ffmpeg_tag="n6.0"
ffmpeg_commit="ea3d24bbe3c58b171e55fe2151fc7ffaca3ab3d2"
ndk_version="26.1.10909125"
android_sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
build_root="${1:-$(mktemp -d)}"

if [[ -z "$android_sdk_root" || ! -d "$android_sdk_root/ndk/$ndk_version" ]]; then
    echo "Install Android NDK $ndk_version and set ANDROID_SDK_ROOT." >&2
    exit 1
fi

git clone --depth 1 --branch "$media3_tag" https://github.com/androidx/media.git "$build_root/media"
git clone --depth 1 --branch "$ffmpeg_tag" https://github.com/FFmpeg/FFmpeg.git "$build_root/ffmpeg"
git -C "$build_root/media" checkout "$media3_commit"
git -C "$build_root/ffmpeg" checkout "$ffmpeg_commit"

ffmpeg_module="$build_root/media/libraries/decoder_ffmpeg/src/main"
ln -s "$build_root/ffmpeg" "$ffmpeg_module/jni/ffmpeg"
"$ffmpeg_module/jni/build_ffmpeg.sh" \
    "$ffmpeg_module" \
    "$android_sdk_root/ndk/$ndk_version" \
    linux-x86_64 \
    21 \
    vorbis opus flac alac pcm_mulaw pcm_alaw mp3 aac ac3 eac3 dca mlp truehd

ANDROID_HOME="$android_sdk_root" ANDROID_SDK_ROOT="$android_sdk_root" \
    "$build_root/media/gradlew" -p "$build_root/media" \
    :lib-decoder-ffmpeg:assembleRelease --no-daemon --max-workers=2

source_aar="$build_root/media/libraries/decoder_ffmpeg/buildout/outputs/aar/lib-decoder-ffmpeg-release.aar"
destination="$repo_root/player/libs/media3-decoder-ffmpeg-$media3_tag.aar"
mkdir -p "$(dirname "$destination")"
cp "$source_aar" "$destination"
sha256sum "$destination"
