#!/usr/bin/env bash
# =============================================================================
#  Сборка ядер AmneziaWG для ZEN NITRO.
#
#    ./build.sh            — оба ядра, три ABI, сразу в app/src/main/jniLibs
#
#  libwg.so  ← zenwg-main   (amneziawg-go v3.1.x — основное, AWG 1.0 … 3)
#  libwg1.so ← zenwg-legacy (amneziawg-go v1.0.x — запасное, AWG 1.x)
#
#  Обёртка — tunnel/tools/libwg-go из amneziawg-android, переименованы
#  JNI-функции под com.zen.blaster.core.AmneziaCore и добавлен awgLastError.
#  Go патчится как у Amnezia/WireGuard: таймеры по CLOCK_BOOTTIME, чтобы
#  рукопожатие не опаздывало после сна телефона.
#
#  Обновить ядро: поменять версию amneziawg-go в zenwg-*/go.mod и запустить.
#  Свежие версии: go list -m -versions github.com/amnezia-vpn/amneziawg-go/v3
# =============================================================================
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# OUT — куда класть (по умолчанию прямо в jniLibs приложения; сборщик
# модулей ядер задаёт свою папку, см. native/modules/build.sh).
APP="${OUT:-$HERE/../../app/src/main/jniLibs}"
GOVER="${GOVER:-1.25.12}"
NDK="${NDK:-$HOME/Android/Sdk/ndk/25.1.8937393}/toolchains/llvm/prebuilt/linux-x86_64/bin"
WORK="${WORK:-${TMPDIR:-/tmp}/zen-awg-build}"
mkdir -p "$WORK"

# Патченный Go: копия тулчейна из кеша модулей (его кладёт сам go при
# GOTOOLCHAIN=go$GOVER), один раз.
GOP="$WORK/go-$GOVER-boottime"
if [ ! -x "$GOP/bin/go" ]; then
  SRC="$HOME/go/pkg/mod/golang.org/toolchain@v0.0.1-go$GOVER.linux-amd64"
  [ -d "$SRC" ] || GOTOOLCHAIN="go$GOVER" go version
  cp -r "$SRC" "$GOP"; chmod -R u+w "$GOP"
  patch -p1 -f -N -d "$GOP" < "$HERE/goruntime-boottime-over-monotonic.diff"
fi
export GOROOT="$GOP" PATH="$GOP/bin:$PATH" GOTOOLCHAIN=local GOFLAGS=-mod=mod

for pair in main:libwg legacy:libwg1; do
  V="${pair%%:*}" LIB="${pair##*:}"
  cd "$HERE/zenwg-$V"
  for A in arm64-v8a:arm64:aarch64-linux-android21 armeabi-v7a:arm:armv7a-linux-androideabi21 x86_64:amd64:x86_64-linux-android21; do
    IFS=: read ABI GA CC <<<"$A"
    mkdir -p "$APP/$ABI"
    GOOS=android GOARCH=$GA GOARM=7 CGO_ENABLED=1 CC="$NDK/$CC-clang" \
    CGO_LDFLAGS="-Wl,-soname=$LIB.so -Wl,--build-id=none" \
    go build -tags linux -ldflags="-s -w -buildid=" -trimpath -buildvcs=false \
      -buildmode c-shared -o "$APP/$ABI/$LIB.so" .
    rm -f "$APP/$ABI/$LIB.h"
    echo "$ABI/$LIB.so  $(stat -c%s "$APP/$ABI/$LIB.so") байт"
  done
done
