#!/usr/bin/env bash
# Builds Full Mode's userspace forwarder (/wg) into the AAR the Android app
# links against, and drops it where Gradle looks: android/app/libs.
#
#   scripts/build-wg-aar.sh
#
# CI runs this too, so there is one build of this library rather than one in a
# workflow and a different one on a desk. Needs Go and an Android NDK; nothing
# else in the Android build does, which is exactly why this is a separate step
# and the result is a file rather than a Gradle task.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="${repo}/android/app/libs"

# The NDK: prefer whatever the environment already points at. On GitHub's
# runners that is an image-provided NDK; an action that downloads another one
# once restored a half-extracted copy from its cache, clang and all, and the
# bind failed long after the tests had passed -- which reads as "the endpoint is
# broken" when the endpoint was fine.
ndk="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
if [ -z "$ndk" ] || [ ! -d "$ndk" ]; then
  echo "No NDK found. Set ANDROID_NDK_HOME to one (sdkmanager 'ndk;27.0.12077973')." >&2
  exit 1
fi
clang="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
if [ ! -x "$clang" ] && [ ! -x "${clang/linux-x86_64/darwin-x86_64}" ]; then
  echo "That NDK has no clang ($ndk); gomobile cannot build with it." >&2
  exit 1
fi
export ANDROID_NDK_HOME="$ndk"

# gomobile looks for the SDK too, and defaults to ~/Android/Sdk when neither
# variable is set — which on a machine that has an SDK somewhere else fails with
# a message about a path nobody chose.
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] || [ ! -d "$sdk" ]; then
  echo "No Android SDK found. Set ANDROID_HOME." >&2
  exit 1
fi
export ANDROID_HOME="$sdk"

cd "${repo}/wg"

# Both x/mobile tools use the module's pinned revision. The compiler must match
# go.mod too; prepare it before this step rather than silently fetching another.
export GOTOOLCHAIN=local
expected_go="$(awk '$1=="toolchain" {print $2}' go.mod)"
[ "$(go env GOVERSION)" = "$expected_go" ] || { echo "Expected $expected_go" >&2; exit 1; }

mobile_version="$(go list -m -f '{{.Version}}' golang.org/x/mobile)"
echo "Installing gomobile ${mobile_version}..."
go install "golang.org/x/mobile/cmd/gomobile@${mobile_version}"
go install "golang.org/x/mobile/cmd/gobind@${mobile_version}"
export PATH="$(go env GOPATH)/bin:$PATH"
gomobile init

mkdir -p "$out"
# arm64 first: it is what nearly every phone runs. The other three ride along so
# the universal APK keeps working everywhere -- including the x86_64 emulators
# the device tests run on, which is the only reason Full Mode can be proven in
# CI at all.
# max-page-size: Android 15 introduced devices with 16 KB memory pages, and a
# shared library whose segments are aligned for 4 KB will not load on one at
# all. gomobile's default is 4 KB, so without this the app installs happily and
# then dies the moment someone turns Full Mode on -- on exactly the newest
# phones, and on none of the emulator images any test here runs against.
# `-extldflags` because the alignment is decided by the NDK's linker, not Go's.
echo "Binding for android/arm64,arm,amd64,386..."
gomobile bind \
  -target=android/arm64,android/arm,android/amd64,android/386 \
  -androidapi 26 \
  -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
  -o "${out}/linqavowg.aar" .

# Not a formality: this is a compile flag whose effect is invisible until it is
# too late, and the only place the result can be checked is the ELF header.
echo "Checking segment alignment..."
work="$(mktemp -d)"
unzip -q "${out}/linqavowg.aar" -d "$work"
readelf="$(command -v readelf || echo "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf")"
bad=0
while IFS= read -r so; do
  abi="$(basename "$(dirname "$so")")"
  # 64-bit only: there is no 16 KB page mode on 32-bit Android.
  case "$abi" in arm64-v8a | x86_64) ;; *) continue ;; esac
  for align in $("$readelf" -lW "$so" | awk '$1=="LOAD" {print $NF}'); do
    if [ "$align" != "0x4000" ] && [ "$align" != "0x10000" ]; then
      echo "  $abi: LOAD alignment $align — will not load on a 16 KB device" >&2
      bad=1
    fi
  done
  echo "  $abi: $("$readelf" -lW "$so" | awk '$1=="LOAD" {print $NF}' | sort -u | tr '\n' ' ')"
done < <(find "$work" -name '*.so')
rm -rf "$work"
[ "$bad" -eq 0 ] || exit 1

python3 "$repo/scripts/wg-provenance.py" stamp --aar "$out/linqavowg.aar"
ls -l "${out}/linqavowg.aar"
echo "Full Mode library is in android/app/libs; Gradle will pick it up."
