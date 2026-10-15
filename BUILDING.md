# Сборка ZEN NITRO

## Приложение

Нужно: JDK **17** (не 21), Android SDK (platform 34, build-tools 34+), NDK 25.

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
cp keystore.properties.example keystore.properties   # и вписать свой ключ
./gradlew assembleRelease
```

APK по архитектурам — в `app/build/outputs/apk/release/`.
Без `keystore.properties` релиз собирается неподписанным.

`applicationId` = `com.zen.nitro`, а пакет Kotlin — `com.zen.blaster`:
его менять нельзя, в `libwg*.so` зашиты JNI-имена
`Java_com_zen_blaster_core_AmneziaCore_*`.

## Ядро sing-box (`app/libs/libbox.aar`)

В проекте **sing-box 1.14.3**. Запуск — через `CommandServer` (см.
`vpn/BoxCore.kt`), конфиг — в формате 1.14 (`core/ConfigBuilder.kt`);
сохранённые конфиги старого вида переводит `core/SingBoxCompat.kt`.

Собираем не штатным `build_libbox`, а напрямую `gomobile bind`: штатный
тянет naive-выход (cronet), которому нужен NDK новее 25-го, а Zen он не
нужен. Заодно выкинуты Tailscale/OpenVPN/OpenConnect/USBIP и лишняя ABI x86.

```bash
native/sbox/build.sh
```

Скрипт сам скачает sing-box 1.14.3, наложит `native/sbox/zen-noshell.patch`
(отключает удалённую консоль — она одна тянула Tailscale, 210 пакетов),
соберёт `app/libs/libbox.aar` и `build/sing-box-check` для проверки конфигов.

Контракт `PlatformInterface` сверять через
`javap -cp classes.jar io.nekohasekai.libbox.PlatformInterface`.

### Проверка конфигов без телефона

```bash
tools/check-configs.sh          # бинарник берёт из build/sing-box-check
```

Тест `ConfigMatrixTest` собирает конфиги всех протоколов и режимов (плюс
старый сохранённый конфиг 3.4), скрипт прогоняет каждый через `sing-box
check` той же версии. После любой правки ConfigBuilder — обязательно.

### Стенд жизненного цикла ядра

`native/sbox/zenlife/main.go` — то же, что делает `vpn/BoxCore.kt`, но на
ПК и на настоящем libbox: шесть подъёмов подряд с закрытием прошлого ядра
в фоне, журнал через клиент команд, запрос через прокси (curl). Режимы:
`ZL_HANG=1` — прошлое ядро «зависло» и не закрывается; `ZL_UNIQUE=1` —
свой файл кеша на подъём (как в Zen); `ZL_ALL=1` — один подъём с полным
журналом. Положить в `sing-box/cmd/zenlife/`, собрать с тегами из
`native/sbox/build.sh`, запускать с коротким относительным путём базы
(у unix-сокета предел 108 символов): `./zenlife config.json lb`.

## Xray (`libxrayexec.so`)

Обычный исполняемый файл Go, переименованный в `.so`: Android разрешает
запускать бинарники только из `nativeLibraryDir`
(`useLegacyPackaging = true`).

```bash
git clone --depth 1 --branch v26.10.10 https://github.com/XTLS/Xray-core && cd Xray-core
for a in arm64-v8a:arm64: armeabi-v7a:arm:7 x86_64:amd64:; do
  IFS=: read ABI GA GARM <<<"$a"
  CGO_ENABLED=0 GOOS=linux GOARCH=$GA GOARM=$GARM \
    go build -trimpath -buildvcs=false -ldflags="-s -w -buildid=" \
    -o ../zen-nitro/app/src/main/jniLibs/$ABI/libxrayexec.so ./main
done
```

Xray 26.10 требует Go 1.27.

## AmneziaWG (`libwg.so`, `libwg1.so`)

```bash
native/awg/build.sh
```

Сам скачает Go 1.25.12, пропатчит рантайм (таймеры по CLOCK_BOOTTIME — чтобы
рукопожатие не опаздывало после сна телефона) и соберёт оба ядра под три
ABI прямо в `jniLibs`. Версии ядер — в `native/awg/zenwg-*/go.mod`.

## ByeDPI (`libbyedpi.so`)

Взят из ByeByeDPI (romanvht) как есть, JNI-обёртка
`io.github.romanvht.byedpi.core.ByeDpiProxy`.

## Модули ядер (обновление Xray и AmneziaWG без нового APK)

Xray и AmneziaWG можно обновлять у людей отдельно от приложения: Zen качает
**подписанный** модуль под свою архитектуру и грузит его вместо встроенного
ядра (`core/CoreModules.kt`, экран «Настройки → Ядра»). sing-box модулем не
обновляется: его Java-интерфейс меняется между версиями.

- **AmneziaWG** — та же библиотека с нашей JNI-обёрткой, грузится
  `System.load()` в процессе `:awg`.
- **Xray** — собран под Android (`GOOS=android`, PIE). Скачанный файл
  Android 10+ напрямую не исполняет, поэтому Zen запускает
  `/system/bin/linker64 <модуль> run -c …`.
- **Подпись** — ECDSA P-256 над `cores.json` (в нём размер и sha256 каждого
  файла). Открытый ключ зашит в `CoreModules.PUBLIC_KEY`, закрытый —
  `~/.zen-nitro-cores.pem` на машине сборки (в git не кладётся). Сменили
  ключ — нужна новая сборка Zen.
- **Страховка** — встроенные ядра остаются в APK; модуль, трижды не
  поднявшийся по своей вине, Zen выключает сам.

```bash
native/modules/build.sh [тег Xray] [версия amneziawg-go]   # → build/modules/
../storage-messenger/tools/publish-cores.sh                # → наш сервер, /updates/zen/cores/
```

Уровни интерфейса модулей (`api`) и минимальная сборка Zen (`minApp`)
пишутся в манифест; Zen не возьмёт модуль, который ему не по силам.

**Робот** — `.github/workflows/cores.yml`: раз в сутки сверяет версии с
апстримом, собирает и выкладывает модули в релиз `cores` этого репозитория
(нужен секрет `ZEN_CORES_KEY` — содержимое `~/.zen-nitro-cores.pem`). Для
людей адрес вписывается в Zen вторым источником:
`https://github.com/<владелец>/<репозиторий>/releases/download/cores/`.
