#!/usr/bin/env bash
# =============================================================================
#  Робот модулей ядер на машине сборки (то же, что .github/workflows/cores.yml,
#  только выкладывает прямо на наш сервер).
#
#    tools/cores-bot.sh            — проверить апстрим; вышло новое — собрать,
#                                    прогнать конфиги и ПОЗВАТЬ на проверку
#                                    (уведомление на рабочем столе). Людям
#                                    НЕ выкладывает.
#    tools/cores-bot.sh --publish  — после проверки на телефоне: собрать
#                                    (если ещё нет), подписать и выложить. У
#                                    людей в Zen появится «Доступно обновление
#                                    ядер».
#    tools/cores-bot.sh --force    — то же, даже если версии не новее
#
#  Почему не выкладывает сам (решение 31.10.2026): новое ядро уходит живым
#  людям, а прогон конфигов на ПК не заменяет проверки на телефоне.
#
#  sing-box модулем не обновляется: он внутри APK (libbox.aar). Робот только
#  замечает его новый выпуск, пишет в журнал и шлёт уведомление на рабочий
#  стол — дальше нужна новая сборка приложения (native/sbox/build.sh).
#
#  Порядок: свежие теги Xray-core и amneziawg-go/v3 → сравнение с манифестом
#  на нашем сервере → (если новее) Xray для ПК той же версии + прогон всех
#  конфигов Zen через него (tools/check-configs.sh) → модули под три ABI
#  (native/modules/build.sh) → storage-messenger/tools/publish-cores.sh.
#  Конфиги не прошли — НИЧЕГО не выкладывается: новое ядро не понимает наши
#  поля, и людям его давать нельзя, пока не поправим Zen.
#
#  Запуск по расписанию — tools/systemd/zen-cores-bot.{service,timer}.
# =============================================================================
set -euo pipefail
FORCE=""; PUBLISH=""
for a in "$@"; do case "$a" in --force) FORCE=1 ;; --publish) PUBLISH=1 ;; esac; done
ZEN="$(cd "$(dirname "$0")/.." && pwd)"
SM="${SM:-$HOME/Рабочий стол/STORAGE&MESSAGER/PROJECT/PROJECT STORAGE/storage-messenger}"
WORK="${WORK:-$HOME/build/zen-modules}"
LOG="${LOG:-$WORK/cores-bot.log}"
mkdir -p "$WORK"
exec > >(tee -a "$LOG") 2>&1
echo "=== $(date '+%F %T') робот модулей ядер"
export GOTOOLCHAIN="${GOTOOLCHAIN:-go1.27.2}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

tag() { git ls-remote --tags --refs "$1" | awk -F/ '{print $3}' | grep -E "$2" | sort -V | tail -1; }
XR=$(tag https://github.com/XTLS/Xray-core '^v[0-9]+\.[0-9]+\.[0-9]+$')
AWG=$(tag https://github.com/amnezia-vpn/amneziawg-go '^v3\.[0-9]+\.[0-9]+$')
[ -n "$XR" ] && [ -n "$AWG" ] || { echo "апстрим не ответил"; exit 1; }

HAVE=$(curl --noproxy '*' -fs -A "ZenNitro/bot" http://localhost:8080/updates/zen/cores/cores.json \
  | python3 -c 'import json,sys; m=json.load(sys.stdin)["modules"]; print(" ".join(sorted({x["core"]+":"+x["version"] for x in m})))' \
  2>/dev/null || true)
echo "апстрим: xray ${XR#v}, awg $AWG · у нас: ${HAVE:-ничего}"
# sing-box: следим за выпуском, но собрать модулем не можем — см. шапку.
SB=$(tag https://github.com/SagerNet/sing-box '^v[0-9]+\.[0-9]+\.[0-9]+$' || true)
SB_HAVE=$(grep -m1 -oP '^VER=\K\S+' "$ZEN/native/sbox/build.sh" || true)
if [ -n "$SB" ] && [ -n "$SB_HAVE" ] && [ "$SB" != "$SB_HAVE" ] &&
   [ "$(printf '%s\n' "$SB" "$SB_HAVE" | sort -V | tail -1)" = "$SB" ]; then
  echo "🆕 sing-box $SB вышел, в приложении $SB_HAVE — нужен новый APK (VER в native/sbox/build.sh)"
  echo "$SB" > "$WORK/SINGBOX-NEW"
  notify-send "ZEN NITRO: вышел sing-box $SB" "В приложении $SB_HAVE — нужна новая сборка APK" 2>/dev/null || true
else
  echo "sing-box: в приложении ${SB_HAVE:-?}, свежий ${SB:-?}"
  rm -f "$WORK/SINGBOX-NEW"
fi
if [ -z "$FORCE" ] && echo " $HAVE " | grep -q " xray:${XR#v} " && echo " $HAVE " | grep -q " awg:$AWG "; then
  echo "новее нет — выходим"; exit 0
fi

# Уже собрано этой же версии и ждёт проверки — второй раз не собираем:
# ночью только напоминаем, а --publish выкладывает собранное.
if [ -z "$FORCE" ] && [ "$(cat "$WORK/BUILT" 2>/dev/null)" = "$XR $AWG" ] &&
   ls "$ZEN"/build/modules/xray-* >/dev/null 2>&1; then
  echo "уже собрано (xray ${XR#v}, awg $AWG) — сборку пропускаю"
else
  # Копия набора AmneziaWG с нужной версией — исходники приложения не трогаем.
  KIT="$WORK/awg-kit"
  rm -rf "$KIT" && cp -r "$ZEN/native/awg" "$KIT"
  ( cd "$KIT/zenwg-main"
    GOTOOLCHAIN=go1.25.12 go get "github.com/amnezia-vpn/amneziawg-go/v3@$AWG" >/dev/null
    GOTOOLCHAIN=go1.25.12 go mod tidy >/dev/null )

  # Xray для ПК той же версии — им проверяем, что новое ядро понимает наши конфиги.
  SRC="$WORK/Xray-core-$XR"
  [ -d "$SRC" ] || git clone -q --depth 1 --branch "$XR" https://github.com/XTLS/Xray-core "$SRC"
  ( cd "$SRC" && CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o "$WORK/xray-check-$XR" ./main )
  if ! XRAY="$WORK/xray-check-$XR" XRAY_MODULE=1 "$ZEN/tools/check-configs.sh"; then
    echo "⛔ Xray $XR не принял конфиги Zen — модули НЕ выкладываю"
    notify-send "ZEN NITRO: Xray $XR не принял наши конфиги" \
      "Новое ядро людям не идёт — нужна правка клиента. Журнал: $LOG" 2>/dev/null || true
    exit 1
  fi

  AWG_KIT="$KIT" WORK="$WORK" "$ZEN/native/modules/build.sh" "$XR" "$AWG"

  # Дымовая проверка: у библиотек AWG на месте наши JNI-функции.
  NM="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/25.1.8937393}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm"
  for f in "$ZEN"/build/modules/awg-*.so "$ZEN"/build/modules/awg1-*.so; do
    for sym in awgTurnOn awgTurnOff awgGetSocketV4 awgVersion; do
      "$NM" -D --defined-only "$f" | grep -q "AmneziaCore_$sym" || { echo "⛔ $f: нет $sym"; exit 1; }
    done
  done
  "$NM" -D --defined-only "$ZEN"/build/modules/awg-*arm64-v8a.so | grep -q AmneziaCore_awgRebind \
    || { echo "⛔ основное ядро без awgRebind (нужен уровень 2)"; exit 1; }

  echo "$XR $AWG" > "$WORK/BUILT"
fi
if [ -z "$PUBLISH" ]; then
  echo "🧩 собраны и проверены: xray ${XR#v}, awg $AWG — $ZEN/build/modules"
  echo "   НЕ выложены: проверить на телефоне, потом tools/cores-bot.sh --publish"
  notify-send "ZEN NITRO: новые ядра собраны" \
    "Xray ${XR#v}, AmneziaWG $AWG — проверь на телефоне, потом cores-bot.sh --publish" 2>/dev/null || true
  exit 0
fi
"$SM/tools/publish-cores.sh"
echo "=== готово: xray ${XR#v}, awg $AWG"
