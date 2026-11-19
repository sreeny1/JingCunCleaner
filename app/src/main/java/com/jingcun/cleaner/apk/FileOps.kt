package com.jingcun.cleaner.apk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Shizuku 删除兜底钩子：由调用方注入（未激活 Shizuku 时传 null），参数为文件绝对路径 */
typealias ShizukuDelete = suspend (String) -> Boolean

/**
 * 文件删除/移动多策略回退链。
 *
 * 背景（v3.5.5，卓易通/出境易等鸿蒙兼容环境实测确诊）：
 * 这类 ROM 把 Android 兼容层的全部文件映射在鸿蒙文件管理器「我的手机 /
 * 兼容应用数据」目录下，由代理文件系统承载——写入/复制放行，但应用进程的
 * [File.delete] 与 [File.renameTo] 一律**静默失败**（返回 false、不抛异常），
 * 导致归纳（移动）与删除整体不可用。
 *
 * 策略链（逐级回退，全部失败才报错，参考开源文件管理器对受限存储的通用处理）：
 * 1. java.io.File —— 标准环境首选；静默失败是兼容环境的典型特征
 * 2. java.nio.file.Files —— 走不同系统调用路径，部分代理文件系统放行
 * 3. Shizuku（shell uid 2000）删除 —— 权限更高，可绕过应用进程的限制
 */
object FileOps {

    /** 删除结果：ok=true 已删除；ok=false 时 reason 为最后一次失败原因 */
    data class DeleteResult(val ok: Boolean, val reason: String = "")

    /** 移动结果 */
    enum class MoveOutcome {
        /** 已整体移动（源文件已不存在） */
        MOVED,
        /** 目标已复制成功，但源文件删除被系统限制（不回滚，副本真实有效） */
        COPIED_SOURCE_LOCKED,
        /** 移动失败（target 已清理，不留半成品） */
        FAILED,
    }

    data class MoveResult(val outcome: MoveOutcome, val reason: String = "")

    /**
     * 删除文件的回退链：File.delete → Files.delete → Shizuku 删除。
     * 全链失败返回 ok=false 并携带最后失败原因（供 UI 如实提示）。
     */
    suspend fun deleteFile(file: File, shizukuDelete: ShizukuDelete? = null): DeleteResult =
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext DeleteResult(true)
            var lastReason = ""
            // ① 标准 File.delete：兼容环境下静默返回 false，不抛异常
            runCatching { file.delete() }
                .onFailure { lastReason = it.message ?: it.javaClass.simpleName }
            if (!file.exists()) return@withContext DeleteResult(true)
            // ② NIO Files.delete：异常自带具体原因（如 AccessDeniedException）
            runCatching { java.nio.file.Files.delete(file.toPath()) }
                .onFailure { lastReason = it.message ?: it.javaClass.simpleName }
            if (!file.exists()) return@withContext DeleteResult(true)
            // ③ Shizuku（shell 身份）删除：未注入/未激活/服务不可用时跳过
            if (shizukuDelete != null) {
                val ok = runCatching { shizukuDelete(file.absolutePath) }.getOrDefault(false)
                if (ok && !file.exists()) return@withContext DeleteResult(true)
                if (!ok) lastReason = "Shizuku 删除也被拒绝"
            }
            DeleteResult(false, lastReason.ifBlank { "系统拒绝删除（未返回具体原因）" })
        }

    /**
     * 移动文件的回退链：renameTo → Files.move → 复制+删除源。
     *
     * 关键取舍（卓易通实测）：复制成功但源删除失败时**不回滚 target**——
     * 副本真实存在且可安装，回滚反而把成功变失败；重复归纳会被
     * 「同名同大小跳过」判定拦住，不会堆积副本。
     */
    suspend fun moveFile(src: File, target: File, shizukuDelete: ShizukuDelete? = null): MoveResult =
        withContext(Dispatchers.IO) {
            // ① 同卷 rename（零拷贝秒完成）
            val renamed = runCatching {
                src.renameTo(target) && target.exists() && target.length() == src.length()
            }.getOrDefault(false)
            if (renamed && !src.exists()) return@withContext MoveResult(MoveOutcome.MOVED)
            // ② NIO Files.move：部分代理 FS 放行；注意无 ATOMIC_MOVE 时可能
            //    退化为内部复制+删除，须检查源是否仍存在
            val nioMoved = runCatching {
                target.parentFile?.let { if (!it.exists()) it.mkdirs() }
                java.nio.file.Files.move(
                    src.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                true
            }.getOrDefault(false)
            if (nioMoved) {
                return@withContext if (!src.exists()) MoveResult(MoveOutcome.MOVED)
                else MoveResult(MoveOutcome.COPIED_SOURCE_LOCKED, "源文件删除被系统限制")
            }
            // ③ 复制 + 删除源
            var copyReason = ""
            val copied = runCatching {
                target.parentFile?.let { if (!it.exists()) it.mkdirs() }
                src.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output, bufferSize = 128 * 1024) }
                }
                target.length() == src.length()
            }.getOrElse { copyReason = it.message ?: it.javaClass.simpleName; false }
            if (!copied) {
                // 复制失败清理半成品 target，防磁盘泄漏
                runCatching { target.delete() }
                return@withContext MoveResult(MoveOutcome.FAILED, copyReason.ifBlank { "复制失败" })
            }
            val del = deleteFile(src, shizukuDelete)
            if (del.ok) return@withContext MoveResult(MoveOutcome.MOVED)
            MoveResult(
                MoveOutcome.COPIED_SOURCE_LOCKED,
                del.reason.takeIf { it.isNotBlank() } ?: "源文件删除被系统限制",
            )
        }
}
