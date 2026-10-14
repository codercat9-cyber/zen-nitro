#!/usr/bin/env bash
# Сборка app/libs/libbox.aar (sing-box 1.14.3) и sing-box для ПК, которым
# tools/check-configs.sh проверяет конфиги.
#
#   native/sbox/build.sh [рабочая папка]     (по умолчанию ~/build/zen-sbox)
#
# Нужно: Go 1.26+, JDK 17, Android NDK 25 (ANDROID_NDK_HOME), ~6 ГБ места.
# Отличия от штатного build_libbox (почему он не годится — BUILDING.md):
#   • без naive (cronet требует NDK новее 25-го);
#   • без Tailscale: патч zen-noshell.patch отключает удалённую консоль,
#     которая одна тянула 210 пакетов (61 → 50 МБ на ABI);
#   • три ABI, что везёт Zen; API 24 = minSdk.
set -euo pipefail
VER=v1.14.3
HERE=$(cd "$(dirname "$0")" && pwd)
ZEN=$(cd "$HERE/../.." && pwd)
WORK=${1:-$HOME/build/zen-sbox}
TAGS=with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0,zen_noshell
LDF="-X github.com/sagernet/sing-box/constant.Version=${VER#v} -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -s -w -buildid= -checklinkname=0"
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/25.1.8937393}

mkdir -p "$WORK"; cd "$WORK"
[ -d sing-box ] || git clone --depth 1 --branch $VER https://github.com/SagerNet/sing-box
cd sing-box
git apply --check "$HERE/zen-noshell.patch" 2>/dev/null && git apply "$HERE/zen-noshell.patch"
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.12 github.com/sagernet/gomobile/cmd/gobind@v0.1.12
PATH=$(go env GOPATH)/bin:$PATH
gomobile init
gomobile bind -v -o libbox.aar -target android/arm64,android/arm,android/amd64 -androidapi 24 \
  -javapkg=io.nekohasekai -libname=box -trimpath -buildvcs=false -ldflags "$LDF" -tags "$TAGS" \
  ./experimental/libbox
cp libbox.aar "$ZEN/app/libs/libbox.aar"
mkdir -p "$ZEN/build"
go build -trimpath -tags "$TAGS" -ldflags "$LDF" -o "$ZEN/build/sing-box-check" ./cmd/sing-box
echo "готово: app/libs/libbox.aar, build/sing-box-check ($("$ZEN/build/sing-box-check" version | head -1))"
