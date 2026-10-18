package com.jingcun.cleaner.shizuku

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Shizuku 用户服务实现：以 shell（uid 2000）或 root（uid 0）身份运行，
 * 用于复制普通应用无权读取的用户应用 APK（位于 /data/app 分区）。
 *
 * 注意：此进程不是有效 Android 应用进程，只能使用标准 Java IO，
 * 不可使用 Android 框架的 Context 相关 API。
 */
class ExtractService : IExtractService.Stub() {

    override fun destroy() {
        // Shizuku 服务端通过固定事务码 16777114 调用，结束服务进程
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    override fun copyFile(src: String?, dst: String?): Boolean {
        if (src.isNullOrEmpty() || dst.isNullOrEmpty()) return false
        return try {
            val target = File(dst)
            target.parentFile?.let { if (!it.exists()) it.mkdirs() }
            FileInputStream(src).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output, bufferSize = 128 * 1024)
                }
            }
            target.length() > 0
        } catch (e: Exception) {
            false
        }
    }
}
