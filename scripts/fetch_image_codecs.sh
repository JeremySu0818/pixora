#!/usr/bin/env bash
set -euo pipefail

third_party="app/src/main/cpp/third_party/image-codecs"
cache="${TMPDIR:-/tmp}/pixora-image-codecs"
mkdir -p "$third_party" "$cache"

fetch_and_unpack() {
  local name="$1"
  local url="$2"
  local sha256="$3"
  local destination="$third_party/$name"
  local archive="$cache/$name.tar.gz"

  if [[ -f "$destination/CMakeLists.txt" ]]; then
    return
  fi

  curl --fail --location --retry 3 --output "$archive" "$url"
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s  %s\n' "$sha256" "$archive" | sha256sum --check --status
  elif command -v shasum >/dev/null 2>&1; then
    printf '%s  %s\n' "$sha256" "$archive" | shasum -a 256 --check
  else
    echo "Neither sha256sum nor shasum is available to verify codec sources" >&2
    exit 1
  fi
  mkdir -p "$destination"
  tar -xzf "$archive" --strip-components=1 -C "$destination"
}

fetch_and_unpack \
  "libjpeg-turbo-3.2.0" \
  "https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.2.0/libjpeg-turbo-3.2.0.tar.gz" \
  "6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e"

fetch_and_unpack \
  "libwebp-1.6.0" \
  "https://github.com/webmproject/libwebp/archive/refs/tags/v1.6.0.tar.gz" \
  "93a852c2b3efafee3723efd4636de855b46f9fe1efddd607e1f42f60fc8f2136"
