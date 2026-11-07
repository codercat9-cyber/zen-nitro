#!/usr/bin/env bash
# =============================================================================
#  ZEN NITRO — ЯДРА. Программа на ПК: свежие Xray и AmneziaWG от апстрима до
#  телефонов людей, как облачное обновление.
#
#    tools/zen-cores.sh          — окно (ярлык «Zen Ядра» на рабочем столе)
#    tools/zen-cores.sh auto     — без окна, для ночного таймера
#    tools/zen-cores.sh status   — состояние текстом (ещё: cloud, upstream)
#
#  Цепочка: апстрим (теги Xray-core, amneziawg-go/v3, sing-box) → сборка и
#  проверки на ПК (tools/cores-bot.sh: Xray для ПК той же версии принимает все
#  конфиги Zen, модули под три ABI, JNI-символы AWG) → выкладка на сервер
#  обновлений (storage-messenger/tools/publish-cores.sh, подпись сверяется до
#  выкладки, прошлый набор уходит в бекап) → Zen у людей ставит ядра сам
#  (core/CoreAuto.kt: AWG — на любой сети, Xray — на Wi-Fi; три провала —
#  откат на встроенное). Откат на сервере доходит до людей так же сам.
#
#  Решение 06.11.2026: доставка — только через сервер обновлений. Никаких
#  телефонов по проводу в цепочке нет.
#
#  Настройки — ~/.config/zen-cores.conf:
#    AUTO_PUBLISH=1   ночной робот сам выкладывает, если все проверки зелёные
#  Без Python: bash, jq, curl, openssl, zenity.
# =============================================================================
set -u
ZEN="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
SM="${SM:-$HOME/Рабочий стол/STORAGE&MESSAGER/PROJECT/PROJECT STORAGE/storage-messenger}"
WORK="${WORK:-$HOME/build/zen-modules}"
LOG="${LOG:-$WORK/cores-bot.log}"
CONF="${CONF:-$HOME/.config/zen-cores.conf}"
KEY="${ZEN_CORES_KEY:-$HOME/.zen-nitro-cores.pem}"
LOCAL_URL="http://localhost:8080/updates/zen/cores/"
PUBLIC_URL="https://storage-mess.online/updates/zen/cores/"
UA="ZenNitro/bot"
TITLE="Zen Nitro — ядра"
mkdir -p "$WORK" "$(dirname "$CONF")"
[ -f "$CONF" ] || printf 'AUTO_PUBLISH=1\n' > "$CONF"
# shellcheck disable=SC1090
. "$CONF"
AUTO_PUBLISH="${AUTO_PUBLISH:-1}"
export ZEN_CORES_PROGRAM=1   # робот не дублирует строки в журнал (см. cores-bot.sh)

stamp() { date '+%d.%m %H:%M'; }
note() { notify-send -i system-software-update "ZEN NITRO: $1" "${2:-}" 2>/dev/null || true; }

# ------------------------------------------------------------------ данные
tag() { git ls-remote --tags --refs "$1" 2>/dev/null | awk -F/ '{print $3}' | grep -E "$2" | sort -V | tail -1; }

upstream_check() {
  local xr awg sb
  xr=$(tag https://github.com/XTLS/Xray-core '^v[0-9]+\.[0-9]+\.[0-9]+$')
  awg=$(tag https://github.com/amnezia-vpn/amneziawg-go '^v3\.[0-9]+\.[0-9]+$')
  sb=$(tag https://github.com/SagerNet/sing-box '^v[0-9]+\.[0-9]+\.[0-9]+$')
  [ -n "$xr" ] && [ -n "$awg" ] || { echo "апстрим не ответил (GitHub)"; return 1; }
  printf '%s %s %s %s\n' "${xr#v}" "$awg" "${sb:-?}" "$(stamp)" > "$WORK/upstream.txt"
  echo "апстрим: Xray ${xr#v}, AmneziaWG $awg, sing-box ${sb:-?}"
}

# Что раздаёт наш сервер: «xray 26.10.31, awg v3.1…, awg v1.0.4» и дата выпуска.
served() {
  local j
  j=$(curl --noproxy '*' -fs -m 8 -A "$UA" "${LOCAL_URL}cores.json" 2>/dev/null) || { echo "сервер не ответил"; return 1; }
  echo "$j" | jq -r '([.modules[] | .core + " " + .version] | unique | join(", ")) + " · выпуск " + (.issued // "?")'
}
served_set() {
  curl --noproxy '*' -fs -m 8 -A "$UA" "${LOCAL_URL}cores.json" 2>/dev/null \
    | jq -r '[.modules[] | .core + ":" + .version] | unique | join(" ")' 2>/dev/null
}

built() {
  local b; b=$(cat "$WORK/BUILT" 2>/dev/null)
  [ -n "$b" ] && [ -f "$ZEN/build/modules/cores.json" ] || { echo "нет"; return; }
  jq -r '([.modules[] | .core + " " + .version] | unique | join(", ")) + " · собрано " + (.issued // "?")' \
    "$ZEN/build/modules/cores.json"
}
built_set() {
  [ -f "$ZEN/build/modules/cores.json" ] && \
    jq -r '[.modules[] | .core + ":" + .version] | unique | join(" ")' "$ZEN/build/modules/cores.json"
}

timer_line() {
  if systemctl --user is-enabled zen-cores-bot.timer >/dev/null 2>&1; then
    local next; next=$(systemctl --user list-timers zen-cores-bot.timer --no-legend 2>/dev/null | awk '{print $2, $3}')
    echo "вкл, следующий запуск ${next:-?}"
  else echo "выкл"; fi
}

status_text() {
  local up="не проверялся"
  [ -f "$WORK/upstream.txt" ] && read -r ux ua us ut < "$WORK/upstream.txt" && \
    up="Xray $ux · AmneziaWG $ua · sing-box $us (проверено $ut)"
  local sbin; sbin=$(grep -m1 -oP '^VER=\K\S+' "$ZEN/native/sbox/build.sh" 2>/dev/null)
  local last; last=$(grep '^=== ' "$LOG" 2>/dev/null | tail -1 | sed 's/^=== //')
  local pend=""
  if [ -n "$(built_set)" ] && [ "$(built_set)" != "$(served_set)" ]; then pend="  ← собрано, людям ещё не выложено"; fi
  cat <<EOF
Апстрим:     $up
Людям сейчас: $(served)
Собрано:     $(built)$pend
sing-box в приложении: ${sbin:-?}$( [ -f "$WORK/SINGBOX-NEW" ] && echo " — вышел $(cat "$WORK/SINGBOX-NEW"): нужна новая сборка APK")
Робот по ночам: $(timer_line) · сам выкладывает: $( [ "$AUTO_PUBLISH" = 1 ] && echo да || echo нет)
Последний запуск робота: ${last:-—}
EOF
}

# Что получают люди: публичный манифест, подпись нашим ключом, файлы на месте.
cloud_check() {
  local t; t=$(mktemp -d)
  echo "Проверяю, что сервер отдаёт людям: $PUBLIC_URL"
  curl -fs -m 15 -A "$UA" -o "$t/cores.json" "${PUBLIC_URL}cores.json" &&
  curl -fs -m 15 -A "$UA" -o "$t/cores.json.sig" "${PUBLIC_URL}cores.json.sig" ||
    { echo "⛔ публичный адрес не отдал манифест или подпись"; rm -rf "$t"; return 1; }
  if [ -r "$KEY" ]; then
    openssl ec -in "$KEY" -pubout -out "$t/pub.pem" 2>/dev/null
    base64 -d "$t/cores.json.sig" > "$t/sig.bin" 2>/dev/null
    if openssl dgst -sha256 -verify "$t/pub.pem" -signature "$t/sig.bin" "$t/cores.json" >/dev/null 2>&1; then
      echo "✅ подпись манифеста сходится с нашим ключом"
    else echo "⛔ подпись манифеста НЕ сходится — Zen такой набор не примет"; rm -rf "$t"; return 1; fi
  else echo "… ключа $KEY нет — подпись не сверяю"; fi
  local bad=0 f size got
  while read -r f size; do
    got=$(curl -fsI -m 15 -A "$UA" "${PUBLIC_URL}$f" | tr -d '\r' | awk 'tolower($1)=="content-length:"{print $2}' | tail -1)
    if [ "$got" = "$size" ]; then echo "✅ $f ($((size / 1048576)) МБ)"
    else echo "⛔ $f: сервер отдаёт ${got:-ничего} байт вместо $size"; bad=1; fi
  done < <(jq -r '.modules[] | "\(.file) \(.size)"' "$t/cores.json")
  echo "Людям раздаётся: $(jq -r '[.modules[] | .core + " " + .version] | unique | join(", ")' "$t/cores.json")"
  rm -rf "$t"
  [ $bad -eq 0 ] && echo "☁️ Всё на месте: Zen поставит эти ядра сам (Xray — на Wi-Fi)."
  return $bad
}

do_build()   { echo "=== $(date '+%F %T') сборка из программы"; "$ZEN/tools/cores-bot.sh" "$@"; }
do_publish() {
  echo "=== $(date '+%F %T') выкладка из программы"
  [ -f "$ZEN/build/modules/cores.json" ] || { echo "⛔ собранного набора нет — сначала «Собрать»"; return 1; }
  "$SM/tools/publish-cores.sh" && echo && cloud_check
}
do_rollback() {
  echo "=== $(date '+%F %T') откат из программы на $1"
  "$SM/tools/rollback-cores.sh" "$1" && echo && cloud_check
}

backups_list() {
  local d
  for d in $(ls -1 "$SM/backups/zen-cores" 2>/dev/null | sort -r); do
    local v; v=$(jq -r '[.modules[] | .core + " " + .version] | unique | join(", ")' \
      "$SM/backups/zen-cores/$d/cores.json" 2>/dev/null)
    printf '%s\n%s\n' "$d" "${v:-?}"
  done
}

# ------------------------------------------------------------------ ночью
auto_run() {
  {
    echo "=== $(date '+%F %T') робот ядер (программа, AUTO_PUBLISH=$AUTO_PUBLISH)"
    upstream_check
    local before after
    before=$(served_set)
    if [ "$AUTO_PUBLISH" = 1 ]; then "$ZEN/tools/cores-bot.sh" --publish; else "$ZEN/tools/cores-bot.sh"; fi
    local rc=$?
    after=$(served_set)
    if [ $rc -ne 0 ]; then
      note "ядра не выложены" "Проверки не прошли — смотри «Zen Ядра» → журнал"
    elif [ -n "$after" ] && [ "$after" != "$before" ]; then
      cloud_check && note "ядра выложены людям" "$after — Zen поставит их сам" \
                  || note "ядра выложены, но сервер отдаёт их с ошибкой" "Открой «Zen Ядра»"
    fi
    exit $rc
  } >> "$LOG" 2>&1
}

# ------------------------------------------------------------------ окно
# Долгая работа: строка прогресса — последняя строка вывода, в конце — весь
# вывод. «Отмена» гасит работу целиком.
run_job() {
  local title=$1; shift
  local out; out=$(mktemp)
  ( "$@" ; echo "__RC__=$?" ) > "$out" 2>&1 &
  local pid=$!
  ( while kill -0 "$pid" 2>/dev/null; do
      echo "# $(grep -v '^__RC__' "$out" | tail -1 | cut -c1-110)"; sleep 1
    done; echo 100 ) |
    zenity --progress --pulsate --auto-close --title="$title" --text="Начинаю…" --width=700 2>/dev/null
  if kill -0 "$pid" 2>/dev/null; then
    pkill -TERM -P "$pid" 2>/dev/null; kill -TERM "$pid" 2>/dev/null
    echo "⏹ отменено человеком" >> "$out"; echo "__RC__=130" >> "$out"
  fi
  local rc; rc=$(grep -o '__RC__=[0-9]*' "$out" | tail -1 | cut -d= -f2)
  grep -v '^__RC__' "$out" > "$out.txt"; cat "$out.txt" >> "$LOG"
  zenity --text-info --title="$title — $([ "${rc:-1}" = 0 ] && echo "готово" || echo "не вышло (код ${rc:-?})")" \
    --filename="$out.txt" --width=900 --height=620 2>/dev/null
  rm -f "$out" "$out.txt"
  return "${rc:-1}"
}

automation_dialog() {
  local tm=FALSE ap=FALSE
  systemctl --user is-enabled zen-cores-bot.timer >/dev/null 2>&1 && tm=TRUE
  [ "$AUTO_PUBLISH" = 1 ] && ap=TRUE
  local sel
  sel=$(zenity --list --checklist --title="$TITLE — автоматика" --width=760 --height=300 \
    --text="Что делать без меня" --column="" --column="key" --column="Режим" --hide-column=2 \
    --separator=" " --print-column=2 \
    $tm timer "Каждую ночь проверять апстрим, собирать и проверять новые ядра" \
    $ap publish "Если все проверки зелёные — сразу выкладывать людям (Zen поставит сам)" 2>/dev/null) || return
  if echo " $sel " | grep -q " timer "; then
    systemctl --user enable --now zen-cores-bot.timer >/dev/null 2>&1
  else systemctl --user disable --now zen-cores-bot.timer >/dev/null 2>&1; fi
  if echo " $sel " | grep -q " publish "; then AUTO_PUBLISH=1; else AUTO_PUBLISH=0; fi
  printf 'AUTO_PUBLISH=%s\n' "$AUTO_PUBLISH" > "$CONF"
}

rollback_dialog() {
  local list; mapfile -t list < <(backups_list)
  [ ${#list[@]} -gt 0 ] || { zenity --info --text="Бекапов прошлых наборов нет." 2>/dev/null; return; }
  local d
  d=$(zenity --list --title="$TITLE — откат" --width=760 --height=380 \
    --text="Какой прошлый набор вернуть людям?\nZen у людей снимет текущие модули и встанет на этот набор сам." \
    --column="Бекап" --column="Что внутри" "${list[@]}" 2>/dev/null) || return
  [ -n "$d" ] || return
  zenity --question --title="$TITLE" --width=520 \
    --text="Вернуть людям набор $d?\n\nТекущий набор уйдёт в бекап." 2>/dev/null || return
  run_job "Откат ядер на $d" do_rollback "$d"
}

publish_dialog() {
  local b; b=$(built)
  [ "$b" != "нет" ] || { zenity --info --width=420 --text="Собранного набора нет — сначала «Собрать и проверить»." 2>/dev/null; return; }
  zenity --question --title="$TITLE" --width=560 \
    --text="Выложить людям?\n\n$b\n\nZen поставит сам: AmneziaWG — сразу, Xray — на Wi-Fi.\nТри провала на новом ядре — Zen вернётся на встроенное." 2>/dev/null || return
  run_job "Выкладка ядер людям" do_publish
}

gui() {
  command -v zenity >/dev/null || { echo "нужен zenity"; exit 1; }
  while true; do
    local act
    act=$(zenity --list --title="$TITLE" --width=860 --height=620 \
      --ok-label="Выполнить" --cancel-label="Закрыть" \
      --text="$(status_text)" --column="key" --column="Действие" --hide-column=1 --print-column=1 \
      upstream "🔄  Проверить апстрим сейчас" \
      build    "🧩  Собрать и проверить новые ядра" \
      force    "🛠  Пересобрать принудительно (даже если версии те же)" \
      publish  "🚀  Выложить людям через сервер обновлений" \
      cloud    "☁️  Проверить, что сервер отдаёт людям" \
      rollback "↩️  Откатить людям ядра на прошлый набор" \
      auto     "⚙️  Автоматика: робот по ночам, выкладывать сам" \
      log      "📜  Журнал робота" 2>/dev/null) || break
    case "$act" in
      upstream) run_job "Апстрим" upstream_check ;;
      build)    run_job "Сборка и проверка ядер" do_build ;;
      force)    run_job "Пересборка ядер" do_build --force ;;
      publish)  publish_dialog ;;
      cloud)    run_job "Что сервер отдаёт людям" cloud_check ;;
      rollback) rollback_dialog ;;
      auto)     automation_dialog ;;
      log)      zenity --text-info --title="$TITLE — журнал" --filename="$LOG" --width=900 --height=620 2>/dev/null ;;
      *) break ;;
    esac
  done
}

case "${1:-gui}" in
  auto)     auto_run ;;
  status)   status_text ;;
  cloud)    cloud_check ;;
  upstream) upstream_check ;;
  gui|*)  gui ;;
esac
