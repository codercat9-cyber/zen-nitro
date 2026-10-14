#!/usr/bin/env bash
# Прогон всех конфигов, что Zen собирает для sing-box, через НАСТОЯЩЕЕ ядро
# той же версии, что в libbox.aar. Ловит то, что иначе всплыло бы только на
# телефоне: удалённые поля, неверные типы, «missing domain resolver».
#
#   tools/check-configs.sh [путь к бинарнику sing-box]
#
# Без аргумента ищет sing-box в $SINGBOX или в build/sing-box-check.
# Собрать бинарник (та же ветка, что libbox.aar):
#   cd sing-box && go build -trimpath -tags with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api \
#        -o <zen>/build/sing-box-check ./cmd/sing-box
set -u
cd "$(dirname "$0")/.."
SB=${1:-${SINGBOX:-build/sing-box-check}}
[ -x "$SB" ] || { echo "нет бинарника sing-box: $SB"; exit 2; }
./gradlew -q :app:testDebugUnitTest --tests 'com.zen.blaster.core.ConfigMatrixTest' || exit 1
dir=app/build/zen-configs
ok=0; bad=0
for f in "$dir"/*.json; do
  # Проверяем то, что ядро проверить может без телефона: TUN на ПК не
  # поднимаем, exclude_package и прочее Android-only оставляем как есть.
  if out=$("$SB" check -c "$f" 2>&1); then ok=$((ok+1))
  else bad=$((bad+1)); echo "✗ $(basename "$f"): $out" | head -3; fi
done
echo "sing-box $("$SB" version 2>/dev/null | head -1 | awk '{print $3}'): принято $ok, отвергнуто $bad"
[ $bad -eq 0 ]
