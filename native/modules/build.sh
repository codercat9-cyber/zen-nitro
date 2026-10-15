#!/usr/bin/env bash
# =============================================================================
#  Сборка МОДУЛЕЙ ЯДЕР для ZEN NITRO (см. app/…/core/CoreModules.kt).
#
#    native/modules/build.sh [XRAY_TAG] [AWG_TAG]
#        XRAY_TAG — тег Xray-core (по умолчанию последний релиз), напр. v26.10.10
#        AWG_TAG  — версия amneziawg-go/v3 (по умолчанию из zenwg-main/go.mod)
#
#  На выходе build/modules/:
#    xray-<ver>-<abi>         Xray под Android (PIE): Zen запускает его через
#                             /system/bin/linker64 — скачанный файл напрямую
#                             Android 10+ не исполняет
#    awg-<ver>-<abi>.so       AmneziaWG с нашей JNI-обёрткой (System.load)
#    awg1-<ver>-<abi>.so      запасное ядро AWG 1.x
#    cores.json               манифест: версия, уровень интерфейса, размер, sha256
#    cores.json.sig           подпись манифеста (ECDSA P-256, ключ ZEN_CORES_KEY
#                             или ~/.zen-nitro-cores.pem)
#
#  Выложить на наш сервер: storage-messenger/tools/publish-cores.sh
#  Нужно: Go 1.27+ (для Xray 26.x), JDK не нужен, Android NDK 25, openssl.
# =============================================================================
set -euo pipefail
# Xray 26.x требует Go 1.27; системный go может быть старше — пусть подтянет нужный.
export GOTOOLCHAIN="${GOTOOLCHAIN:-go1.27.2}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ZEN="$(cd "$HERE/../.." && pwd)"
OUT="$ZEN/build/modules"
WORK="${WORK:-$HOME/build/zen-modules}"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/25.1.8937393}/toolchains/llvm/prebuilt/linux-x86_64/bin"
KEY="${ZEN_CORES_KEY:-$HOME/.zen-nitro-cores.pem}"
ABIS="arm64-v8a:arm64:aarch64-linux-android24 armeabi-v7a:arm:armv7a-linux-androideabi24 x86_64:amd64:x86_64-linux-android24"

# Уровни интерфейса (должны совпадать с CoreModules.API в приложении).
API_XRAY=1      # «run -c», «version»
API_AWG=2       # + awgRebind (Zen 3.5)
MIN_APP=28      # первая сборка Zen, которая умеет модули

[ -f "$KEY" ] || { echo "нет ключа подписи: $KEY"; exit 1; }
mkdir -p "$OUT" "$WORK"
rm -f "$OUT"/xray-* "$OUT"/awg-* "$OUT"/awg1-* "$OUT"/cores.json*

# ---------------------------------------------------------------- Xray
XRAY_TAG="${1:-$(git ls-remote --tags --refs https://github.com/XTLS/Xray-core | awk -F/ '{print $3}' | grep -E '^v[0-9.]+$' | sort -V | tail -1)}"
XV="${XRAY_TAG#v}"
echo "== Xray $XRAY_TAG"
if [ ! -d "$WORK/Xray-core-$XRAY_TAG" ]; then
  git clone -q --depth 1 --branch "$XRAY_TAG" https://github.com/XTLS/Xray-core "$WORK/Xray-core-$XRAY_TAG"
fi
for A in $ABIS; do
  IFS=: read ABI GA CC <<<"$A"
  ( cd "$WORK/Xray-core-$XRAY_TAG"
    GOOS=android GOARCH=$GA GOARM=7 CGO_ENABLED=1 CC="$NDK/$CC-clang" \
    go build -trimpath -buildvcs=false -buildmode=pie \
      -ldflags="-s -w -buildid= -checklinkname=0 -X github.com/xtls/xray-core/core.build=zen" \
      -o "$OUT/xray-$XV-$ABI" ./main )
  echo "  $ABI: $(stat -c%s "$OUT/xray-$XV-$ABI") байт"
done

# ---------------------------------------------------------------- AmneziaWG
AWG_TAG="${2:-$(awk '/amneziawg-go\/v3 /{print $2}' "$ZEN/native/awg/zenwg-main/go.mod")}"
LEG_TAG="$(awk '/amneziawg-go /{print $2}' "$ZEN/native/awg/zenwg-legacy/go.mod")"
echo "== AmneziaWG $AWG_TAG (+ запасное $LEG_TAG)"
OUT="$WORK/awg-out" "$ZEN/native/awg/build.sh" >/dev/null
for A in $ABIS; do
  IFS=: read ABI _ _ <<<"$A"
  cp "$WORK/awg-out/$ABI/libwg.so"  "$OUT/awg-$AWG_TAG-$ABI.so"
  cp "$WORK/awg-out/$ABI/libwg1.so" "$OUT/awg1-$LEG_TAG-$ABI.so"
done

# ---------------------------------------------------------------- манифест
python3 - "$OUT" "$XV" "$AWG_TAG" "$LEG_TAG" "$API_XRAY" "$API_AWG" "$MIN_APP" <<'PY'
import hashlib, json, os, sys, datetime
out, xv, awg, leg, api_x, api_a, min_app = sys.argv[1:8]
mods = []
for f in sorted(os.listdir(out)):
    p = os.path.join(out, f)
    if f.startswith("xray-"):
        core, variant, ver, api = "xray", "main", xv, int(api_x)
        abi = f[len(f"xray-{xv}-"):]
    elif f.startswith("awg1-"):
        core, variant, ver, api = "awg", "legacy", leg, int(api_a)
        abi = f[len(f"awg1-{leg}-"):-3]
    elif f.startswith("awg-"):
        core, variant, ver, api = "awg", "main", awg, int(api_a)
        abi = f[len(f"awg-{awg}-"):-3]
    else:
        continue
    h = hashlib.sha256(open(p, "rb").read()).hexdigest()
    mods.append(dict(core=core, variant=variant, version=ver, api=api, abi=abi, file=f,
                     size=os.path.getsize(p), sha256=h, minApp=int(min_app), notes=""))
m = dict(schema=1, issued=datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
         modules=mods)
open(os.path.join(out, "cores.json"), "w").write(json.dumps(m, ensure_ascii=False, indent=1) + "\n")
print(f"манифест: {len(mods)} модулей")
PY

openssl dgst -sha256 -sign "$KEY" "$OUT/cores.json" | base64 -w0 > "$OUT/cores.json.sig"
# Самопроверка подписи открытым ключом из того же ключа.
openssl dgst -sha256 -verify <(openssl ec -in "$KEY" -pubout 2>/dev/null) \
  -signature <(base64 -d "$OUT/cores.json.sig") "$OUT/cores.json"
ls -la "$OUT"
