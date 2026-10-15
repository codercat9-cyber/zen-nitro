import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.zen.blaster"
    compileSdk = 34

    defaultConfig {
        // Видимая система имени. Пакет Kotlin (namespace) НЕ трогаем:
        // в libwg.so зашиты символы Java_com_zen_blaster_core_AmneziaCore_*,
        // переименование пакета оторвало бы ядро Amnezia.
        applicationId = "com.zen.nitro"
        minSdk = 24                 // Android 7.0 (VpnService + современные протоколы)
        targetSdk = 34
        versionCode = 28
        versionName = "3.6"
        // Ядро sing-box (libbox.aar) — нативные .so под эти ABI.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Ключ подписи и пароли к нему — в keystore.properties в корне проекта
    // (в git НЕ попадает, см. .gitignore; образец — keystore.properties.example).
    // Сам ключ лежит ВНЕ проекта: его нельзя потерять и нельзя отдать.
    // Потеря ключа = невозможность выпустить обновление поверх установленного.
    // Нет файла — релиз собирается неподписанным (для чужих сборок это норма).
    val ksFile = rootProject.file("keystore.properties")
    val ks = Properties().also { p -> if (ksFile.exists()) ksFile.inputStream().use { p.load(it) } }
    val hasKey = ks.getProperty("storeFile") != null

    signingConfigs {
        if (hasKey) create("zen") {
            storeFile = file(ks.getProperty("storeFile").replace("~", System.getProperty("user.home")))
            storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias")
            keyPassword = ks.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            if (hasKey) signingConfig = signingConfigs.getByName("zen")
            // R8 режет неиспользуемое и переименовывает классы: и размер меньше,
            // и разбирать сборку заметно неприятнее.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Отладочная сборка остаётся отдельным приложением: можно держать
            // рядом рабочую версию и проверочную.
            applicationIdSuffix = ".dev"
        }
    }

    // Отдельный APK на каждую архитектуру.
    //
    // В общий файл попадают ядра под ВСЕ три ABI, а телефон использует только
    // одну — остальные две лежат мёртвым грузом. Одни лишь Xray и sing-box
    // это примерно по 60 МБ на архитектуру, отсюда и 106 МБ у общего файла.
    //
    // universalApk оставлен: он нужен, когда раздаёшь одним файлом человеку,
    // чью архитектуру не знаешь.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    // Xray поставляется исполняемым файлом внутри jniLibs (libxrayexec.so).
    // Android разрешает exec только из nativeLibraryDir и только если библиотеки
    // РАСПАКОВАНЫ на диск — со сжатыми в APK запустить процесс нельзя.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    // Линтер тянет свой модуль из сети, а собираемся мы офлайн. На сам APK
    // он не влияет — это только статические проверки.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // buildConfig нужен, чтобы код знал СВОЁ имя пакета: оно участвует
    // в исключении приложения из туннеля и не должно быть строкой.
    buildFeatures { compose = true; buildConfig = true }
    // Тесты конфигов идут на обычной JVM: android.jar там из заглушек,
    // и любой случайный вызов Android вернёт ноль, а не уронит прогон.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // Ядро sing-box (gomobile bind). Положи libbox.aar в app/libs/ — см. README.
    implementation(fileTree("libs") { include("*.aar") })

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // HTTP для подписок + JSON.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Проверка конфигов sing-box без телефона (см. src/test, tools/check-configs.sh).
    // org.json настоящий: в android.jar для JVM-тестов он из заглушек.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
