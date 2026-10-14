# ============================================================================
#  Правила для R8. Всё, что вызывается НЕ из Kotlin, переименовывать нельзя:
#  имена таких классов и методов зашиты в нативных библиотеках, и обфускация
#  их просто оторвёт — приложение соберётся, но ядра молча не заведутся.
# ============================================================================

# AmneziaWG: символы в libwg.so называются
# Java_com_zen_blaster_core_AmneziaCore_awgTurnOn и т.д.
-keep class com.zen.blaster.core.AmneziaCore { *; }

# ByeDPI: имя класса зашито в libbyedpi.so.
-keep class io.github.romanvht.byedpi.core.** { *; }

# sing-box (libbox, собран через gomobile) сам дёргает наши классы обратно:
# PlatformInterface и все итераторы вызываются со стороны Go.
-keep class io.nekohasekai.libbox.** { *; }
-keep class com.zen.blaster.vpn.ZenVpnService { *; }
-keep class * implements io.nekohasekai.libbox.PlatformInterface { *; }

# Любые native-методы вместе с их классами.
-keepclasseswithmembernames class * { native <methods>; }

# Служебное: без этого в логе вместо строчки падения будет каша.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SRC
