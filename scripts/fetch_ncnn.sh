#!/usr/bin/env bash
set -euo pipefail

version="20260526"
archive="ncnn-${version}-android-vulkan.zip"
url="https://github.com/Tencent/ncnn/releases/download/${version}/${archive}"
target="app/src/main/cpp/third_party/ncnn-${version}-android-vulkan"
cache="${TMPDIR:-/tmp}/${archive}"

if [[ -d "$target/arm64-v8a" && -d "$target/x86_64" ]]; then
  echo "ncnn ${version} is already available at ${target}"
  exit 0
fi

mkdir -p "$(dirname "$target")"
curl --fail --location --retry 3 --output "$cache" "$url"
unzip -q -o "$cache" -d "$(dirname "$target")"
echo "Installed ncnn ${version} for Android Vulkan"
