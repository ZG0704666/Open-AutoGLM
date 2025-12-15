# ----------------------------------------------------------------------------
# ProGuard 规则配置
# 用于 Release 版本的代码混淆（当前 Demo 未启用）
# ----------------------------------------------------------------------------

# 保留所有 Android 组件
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# 保留无障碍服务
-keep class com.autoglm.phonedemo.AutomationAccessibilityService { *; }

# 保留数据类
-keep class com.autoglm.phonedemo.ChatMessage { *; }

# 保留 Gson 相关
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# 保留 OkHttp 相关
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
