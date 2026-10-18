package com.jingcun.cleaner.shizuku;

/**
 * 应用提取用户服务接口（运行于 Shizuku 的 shell/root 身份进程）。
 *
 * transaction id 固定约定（参考 Shizuku-API 官方 demo）：
 * - destroy() 必须为 16777114（Shizuku 服务端固定调用码）
 */
interface IExtractService {

    void destroy() = 16777114; // Shizuku 服务端定义的销毁方法

    void exit() = 1;

    /**
     * 以 shell/root 身份复制文件（用于读取 /data/app 下受限的用户应用 APK）。
     * @param src 源 APK 绝对路径
     * @param dst 目标文件绝对路径
     * @return 复制是否成功
     */
    boolean copyFile(String src, String dst) = 2;
}
