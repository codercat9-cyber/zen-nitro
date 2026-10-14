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

Сейчас в проекте **sing-box 1.11**. Конфиг генерируется в старом формате DNS
(`address`, без `type`/`server`) — ядро 1.12+ его не примет. Переезд на
1.14 требует переписать DNS-часть ConfigBuilder и перейти на CommandServer
в ZenVpnService.

```bash
git clone --branch v1.14.3 https://github.com/SagerNet/sing-box && cd sing-box
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.12 \
           github.com/sagernet/gomobile/cmd/gobind@v0.1.12
gomobile init
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/25.1.8937393 \
go run ./cmd/internal/build_libbox -target android
cp libbox.aar ../zen-nitro/app/libs/
```

Контракт `PlatformInterface` сверять через
`javap -cp classes.jar io.nekohasekai.libbox.PlatformInterface`.

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
