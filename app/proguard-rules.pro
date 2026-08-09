# Add project specific ProGuard rules here.
# R8 已开启（isMinifyEnabled = true）。Compose / Media3 / CameraX / Room / OkHttp
# 均自带 consumer rules，无需额外 keep；以下仅保留反射/序列化相关兜底规则。

# org.json（Android 平台自带，无需 keep）

# CameraX 动态服务查找（若 R8 警告则放开下面两行）
# -keep class androidx.camera.** { *; }

# 保留 FileProvider 文件路径暴露逻辑
-keep class androidx.core.content.FileProvider { *; }
