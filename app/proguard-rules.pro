# kotlinx-serialization, Compose, WorkManager and OkHttp ship their own consumer rules.
-keepattributes *Annotation*, InnerClasses

# Shizuku starts the per-app shell service by class name in its own process.
-keep class com.the412banner.batterylog.apps.ShellUserService { *; }
-keep class com.the412banner.batterylog.apps.IShellService { *; }
-keep class com.the412banner.batterylog.apps.IShellService$* { *; }
