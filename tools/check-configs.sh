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

# Xray — если есть бинарник для ПК той же версии (XRAY или build/xray-check).
XR=${XRAY:-build/xray-check}
xbad=0
if [ -x "$XR" ]; then
  xok=0; xskip=0
  for f in "$dir"/xray/*.json; do
    # XRAY_MODULE=1 (робот ядер): голый VLESS без TLS и шифрования клиент
    # модулю не отдаёт — гоняет на встроенном ядре (XrayEngine.bareVless).
    # Проверять им новый Xray незачем: он такое до IP не пускает по замыслу.
    if [ -n "${XRAY_MODULE:-}" ] && jq -e '.outbounds[0] as $o
        | ($o.streamSettings.security // "") as $s
        | ((($o.settings.vnext // [{}])[0].users // [{}])[0].encryption // "") as $u
        | $o.protocol == "vless" and ($s == "" or $s == "none") and ($u == "" or $u == "none")' \
        "$f" >/dev/null
    then xskip=$((xskip+1)); continue; fi
    if out=$("$XR" run -test -c "$f" 2>&1); then xok=$((xok+1))
    else xbad=$((xbad+1)); echo "✗ xray/$(basename "$f"): $(echo "$out" | tail -2)" | head -3; fi
  done
  echo "Xray $("$XR" version 2>/dev/null | head -1 | awk '{print $2}'): принято $xok, отвергнуто $xbad" \
    "$([ "$xskip" -gt 0 ] && echo "· на встроенном ядре (VLESS без TLS): $xskip")"
fi
[ $bad -eq 0 ] && [ $xbad -eq 0 ]
