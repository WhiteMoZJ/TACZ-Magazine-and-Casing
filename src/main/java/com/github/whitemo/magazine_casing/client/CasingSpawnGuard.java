package com.github.whitemo.magazine_casing.client;

/**
 * 弹壳生成去重（跨模型、跨帧）。TACZ 抛壳时会向主模型与 LOD 低模各 addShell 一次，
 * 两个 Data 的时间戳几乎相同（相差 0~1ms）；切换人称时另一份队列又会被渲染一次。
 * 因此用时间戳做全局去重，记录最近一批已发送的时间戳，窗口内重复则返回 false。
 * 放在普通 Java 类里以避免 mixin 静态字段在切换人称时的失效问题。
 */
public final class CasingSpawnGuard {

    /** 最近已发送的时间戳（环形缓冲）。 */
    private static final long[] RECENT = new long[256];
    private static int head;
    private static int count;

    /** 时间戳匹配窗口（毫秒）。主模型与 LOD 的 addShell 相差 0~1ms，连发间隔远大于此。 */
    private static final long WINDOW_MS = 2L;

    private CasingSpawnGuard() {
    }

    /**
     * 判断一个弹壳时间戳是否应该发送。与最近已发送的时间戳相差不超过 {@link #WINDOW_MS}
     * 时视为同一发子弹的重复，返回 false。
     */
    public static boolean tryMark(long timeStamp) {
        for (int i = 0; i < count; i++) {
            if (Math.abs(timeStamp - RECENT[i]) <= WINDOW_MS) {
                return false;
            }
        }
        RECENT[head] = timeStamp;
        head = (head + 1) % RECENT.length;
        if (count < RECENT.length) {
            count++;
        }
        return true;
    }
}
