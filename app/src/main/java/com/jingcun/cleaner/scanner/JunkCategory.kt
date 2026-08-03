package com.jingcun.cleaner.scanner

/** 垃圾文件分类 */
enum class JunkCategory(val title: String) {
    APP_CACHE("应用缓存"),
    TEMP_LOG("临时/日志文件"),
    APK_RESIDUAL("APK 残留"),
    THUMBNAIL("缩略图缓存"),
    SYSTEM_JUNK("系统残留文件"),
    EMPTY_FOLDER("空文件夹");

    companion object {
        /** 结果展示顺序 */
        val DISPLAY_ORDER = listOf(
            APP_CACHE, APK_RESIDUAL, THUMBNAIL, TEMP_LOG, SYSTEM_JUNK, EMPTY_FOLDER
        )
    }
}

/** 单个待清理项 */
data class JunkItem(
    val path: String,
    val size: Long,
    val category: JunkCategory,
    val isDirectory: Boolean,
)
