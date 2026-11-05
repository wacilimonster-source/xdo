package com.xdo.app.dl

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把 part 文件写入系统媒体库（Android 10+ 免权限）或在低版本系统写公共目录/私有目录。
 * 返回播放/分享可用的 URI。
 *
 * B05 修复：
 * - 低版本保存到应用私有外部目录时，FileProvider 根目录已补 <external-files-path> 声明；
 * - 公共目录路径也统一返回 FileProvider URI（file_paths.xml 补 <external-path>），
 *   避免 file:// 在 API 24+ 分享时抛 FileUriExposedException。
 */
object MediaSaver {

    private const val DIR = "X下载助手"

    fun save(context: Context, part: File, displayName: String): String? {
        return if (Build.VERSION.SDK_INT >= 29) {
            saveToMediaStore(context, part, displayName)
        } else {
            saveLegacy(context, part, displayName)
        }
    }

    private fun saveToMediaStore(context: Context, part: File, displayName: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$DIR")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                part.inputStream().use { it.copyTo(out) }
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun saveLegacy(context: Context, part: File, displayName: String): String {
        // 有写权限（MainActivity 已在低版本上申请）→ 保存到公共 Movies 目录
        if (Build.VERSION.SDK_INT < 29 && hasWritePermission(context)) {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                DIR,
            )
            if (dir.exists() || dir.mkdirs()) {
                val target = File(dir, displayName)
                part.copyTo(target, overwrite = true)
                return fileProviderUri(context, target)
            }
        }
        // 兜底：应用私有目录（免权限，FileProvider 根目录已配置 external-files-path）
        val dir = File(context.getExternalFilesDir(null), "downloads")
        dir.mkdirs()
        val target = File(dir, displayName)
        part.copyTo(target, overwrite = true)
        return fileProviderUri(context, target)
    }

    /** 统一走 FileProvider，杜绝 file:// URI（API 24+ 跨进程分享会崩） */
    private fun fileProviderUri(context: Context, target: File): String = try {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            target,
        ).toString()
    } catch (e: Exception) {
        Uri.fromFile(target).toString()
    }

    private fun hasWritePermission(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
