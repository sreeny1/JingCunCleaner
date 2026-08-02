package com.jingcun.cleaner.trash

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 回收站管理器。
 *
 * 实现：把待删文件移动到公共存储下的隐藏目录 .JingCunTrash/，
 * 元数据（原路径、回收站路径、删除时间、大小、是否目录）写入 index.json。
 * 恢复时按原路径还原，路径冲突自动加 _restored 后缀。
 * 过期清理按保留天数删除旧条目。
 *
 * 关键修复（v2.3）：
 * - moveToTrash 先算 size/isDirectory 再移动（修复 size 永远为 0 的 bug）
 * - writeIndex 原子写入（临时文件 + rename，防崩溃损坏索引）
 * - indexMutex 保护所有索引读写（防并发丢更新）
 * - copyDirAndDelete listFiles 返回 null 时不删除源（防数据丢失）
 * - copyFileAndDelete 失败时清理半成品 dst
 * - 用完整 UUID 作 id（防碰撞覆盖）
 * - readIndex 用 optXxx 容错旧索引字段缺失
 */
class TrashManager(private val context: Context) {

    data class TrashItem(
        val id: String,
        val originalPath: String,
        val trashPath: String,
        val name: String,
        val size: Long,
        val isDirectory: Boolean,
        val deletedAt: Long,
    )

    private val trashRoot: File by lazy {
        File(Environment.getExternalStorageDirectory(), ".JingCunTrash").apply { if (!exists()) mkdirs() }
    }

    private val indexFile: File by lazy { File(trashRoot, "index.json") }

    /** 保护索引读写的互斥锁，防止并发 cleanExpired/clearAll/moveToTrash 丢更新 */
    private val indexMutex = Mutex()

    /** 把文件/目录移入回收站，成功返回对应 TrashItem */
    suspend fun moveToTrash(sourcePath: String): TrashItem? = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            val source = File(sourcePath)
            if (!source.exists()) return@withContext null
            // 关键修复：先计算 size 和 isDirectory，再移动（移动后 source 不存在，size 会变 0）
            val isDir = source.isDirectory
            val size = if (isDir) calcDirSize(source) else runCatching { source.length() }.getOrDefault(0L)
            // 用完整 UUID 防碰撞（原 take(8) 32bit 熵值约 65536 次后碰撞）
            val id = UUID.randomUUID().toString()
            val trashName = "${source.name}.$id"
            val trashFile = File(trashRoot, trashName)
            val ok = moveFile(source, trashFile)
            if (!ok) return@withContext null
            val item = TrashItem(
                id = id,
                originalPath = source.absolutePath,
                trashPath = trashFile.absolutePath,
                name = source.name,
                size = size,
                isDirectory = isDir,
                deletedAt = System.currentTimeMillis(),
            )
            addIndex(item)
            item
        }
    }

    /** 恢复单个条目到原路径（冲突自动重命名），成功后从索引移除 */
    suspend fun restore(item: TrashItem): Boolean = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            val trashFile = File(item.trashPath)
            if (!trashFile.exists()) {
                removeIndex(item.id)
                return@withContext false
            }
            val original = File(item.originalPath)
            original.parentFile?.takeIf { !it.exists() }?.mkdirs()
            val target = if (original.exists()) {
                // 目录用 name_restored，文件用 nameWithoutExtension_restored.ext
                if (item.isDirectory) {
                    File(original.parentFile, "${original.name}_restored")
                } else {
                    val base = original.nameWithoutExtension
                    val ext = original.extension
                    val newName = if (ext.isNotEmpty()) "${base}_restored.$ext" else "${base}_restored"
                    File(original.parentFile, newName)
                }
            } else original
            val ok = moveFile(trashFile, target)
            if (ok) removeIndex(item.id)
            ok
        }
    }

    /** 一键恢复全部回收站条目，返回成功恢复的数量 */
    suspend fun restoreAll(): Int = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            val items = readIndex()
            var count = 0
            for (item in items) {
                if (restoreLocked(item)) count++
            }
            count
        }
    }

    /** restoreAll 内部调用，已持锁，不再嵌套加锁 */
    private fun restoreLocked(item: TrashItem): Boolean {
        val trashFile = File(item.trashPath)
        if (!trashFile.exists()) {
            removeIndex(item.id)
            return false
        }
        val original = File(item.originalPath)
        original.parentFile?.takeIf { !it.exists() }?.mkdirs()
        val target = if (original.exists()) {
            if (item.isDirectory) File(original.parentFile, "${original.name}_restored")
            else {
                val base = original.nameWithoutExtension
                val ext = original.extension
                val newName = if (ext.isNotEmpty()) "${base}_restored.$ext" else "${base}_restored"
                File(original.parentFile, newName)
            }
        } else original
        val ok = moveFile(trashFile, target)
        if (ok) removeIndex(item.id)
        return ok
    }

    /** 彻底删除单个回收站条目（不再可恢复） */
    suspend fun deletePermanently(item: TrashItem): Boolean = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            val f = File(item.trashPath)
            val ok = if (f.exists()) deleteRecursivelySafe(f) else true
            if (ok) removeIndex(item.id)
            ok
        }
    }

    /** 清空整个回收站 */
    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            val items = readIndex()
            for (item in items) {
                File(item.trashPath).let { if (it.exists()) deleteRecursivelySafe(it) }
            }
            writeIndex(emptyList())
            items.size
        }
    }

    /** 读取回收站所有条目（按删除时间倒序） */
    suspend fun list(): List<TrashItem> = withContext(Dispatchers.IO) {
        indexMutex.withLock { readIndex().sortedByDescending { it.deletedAt } }
    }

    /** 过期清理：删除超过保留天数的条目 */
    suspend fun cleanExpired(retentionDays: Int): Int = withContext(Dispatchers.IO) {
        if (retentionDays <= 0) return@withContext 0
        indexMutex.withLock {
            val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
            val items = readIndex()
            val expired = items.filter { it.deletedAt < cutoff }
            for (item in expired) {
                File(item.trashPath).let { if (it.exists()) deleteRecursivelySafe(it) }
            }
            if (expired.isNotEmpty()) {
                writeIndex(items.filter { it.deletedAt >= cutoff })
            }
            expired.size
        }
    }

    /** 回收站总占用字节 */
    fun totalSize(): Long = calcDirSize(trashRoot)

    // ---- 索引文件读写（JSON） ----

    /** 用 optXxx 容错读取，旧索引字段缺失时跳过该条目而非整体失败 */
    private fun readIndex(): List<TrashItem> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank()) return@mapNotNull null
                TrashItem(
                    id = id,
                    originalPath = o.optString("originalPath"),
                    trashPath = o.optString("trashPath"),
                    name = o.optString("name"),
                    size = o.optLong("size", 0L),
                    isDirectory = o.optBoolean("isDirectory", false),
                    deletedAt = o.optLong("deletedAt", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 原子写入：先写临时文件再 rename，防崩溃导致半截 JSON 损坏索引 */
    private fun writeIndex(items: List<TrashItem>) {
        val arr = JSONArray()
        for (item in items) {
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("originalPath", item.originalPath)
                put("trashPath", item.trashPath)
                put("name", item.name)
                put("size", item.size)
                put("isDirectory", item.isDirectory)
                put("deletedAt", item.deletedAt)
            })
        }
        val tmp = File(trashRoot, "index.json.tmp")
        runCatching {
            tmp.writeText(arr.toString())
            // rename 原子替换，失败则手动删除目标再重试
            if (!tmp.renameTo(indexFile)) {
                indexFile.delete()
                tmp.renameTo(indexFile)
            }
        }.onFailure {
            runCatching { tmp.delete() }
        }
    }

    private fun addIndex(item: TrashItem) {
        val list = readIndex().toMutableList()
        list.add(item)
        writeIndex(list)
    }

    private fun removeIndex(id: String) {
        val list = readIndex().filterNot { it.id == id }
        writeIndex(list)
    }

    // ---- 文件操作工具 ----

    /** 优先 renameTo（同卷快），失败则复制+删除（跨卷） */
    private fun moveFile(src: File, dst: File): Boolean {
        if (src.renameTo(dst)) return true
        return if (src.isDirectory) copyDirAndDelete(src, dst) else copyFileAndDelete(src, dst)
    }

    /** 复制失败时清理半成品 dst，防磁盘泄漏 */
    private fun copyFileAndDelete(src: File, dst: File): Boolean = runCatching {
        dst.parentFile?.mkdirs()
        src.inputStream().use { input ->
            dst.outputStream().use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
        }
        src.delete()
    }.getOrElse {
        runCatching { dst.delete() }
        false
    }

    /**
     * 复制目录后删除源。
     * 关键修复：listFiles 返回 null 时不删除源（防数据丢失），直接返回 false。
     */
    private fun copyDirAndDelete(src: File, dst: File): Boolean = runCatching {
        dst.mkdirs()
        val children = src.listFiles() ?: return@runCatching false
        for (child in children) {
            val target = File(dst, child.name)
            if (child.isDirectory) {
                if (!copyDirAndDelete(child, target)) return@runCatching false
            } else {
                if (!copyFileAndDelete(child, target)) return@runCatching false
            }
        }
        deleteRecursivelySafe(src)
        true
    }.getOrDefault(false)

    private fun calcDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return runCatching { dir.length() }.getOrDefault(0L)
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            d.listFiles()?.forEach { c ->
                if (c.isDirectory) stack.addLast(c) else total += runCatching { c.length() }.getOrDefault(0L)
            }
        }
        return total
    }

    private fun deleteRecursivelySafe(f: File): Boolean {
        if (!f.exists()) return false
        if (f.isDirectory) {
            f.listFiles()?.forEach { deleteRecursivelySafe(it) }
        }
        return runCatching { f.delete() }.getOrDefault(false)
    }
}
