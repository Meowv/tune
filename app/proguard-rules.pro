# 目标一 Release 构建的 R8 规则。
# 依赖库（Compose、Media3、OkHttp、Coil、kotlinx.serialization）均自带 consumer 规则，
# 这里只保留会被系统或反射实例化的入口，以及避免误删的可选依赖告警。

# 系统按清单实例化，显式保留入口类。
-keep class com.meowv.tune.TuneApplication { *; }
-keep class com.meowv.tune.MainActivity { *; }
-keep class com.meowv.tune.playback.RadioPlaybackService { *; }

# Media3 会话服务通过反射读取会话回调与播放器代理。
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.exoplayer.** { *; }

# 可选依赖在运行期不存在时的告警，不影响功能。
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn kotlinx.coroutines.debug.**

# dnsjava 的可选集成：slf4j 日志、lombok 注解、JDK 内部 NameService SPI 均不在 APK 内。
-dontwarn lombok.**
-dontwarn org.slf4j.**
-dontwarn org.xbill.DNS.spi.**
-dontwarn sun.net.spi.nameservice.**
