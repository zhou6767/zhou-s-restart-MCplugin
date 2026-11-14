package cn.mcpanel.restart;

import org.bukkit.Bukkit;

import java.lang.reflect.Method;

/**
 * TPS 读取（兼容 Paper / Spigot / 普通 Bukkit）。
 * <p>
 * 优先使用 {@code Bukkit.getServer().getTPS()}（Paper/Spigot 提供）；
 * 不存在时退回 tick 计数估算：每秒统计实际执行了多少个 tick。
 */
public final class TpsMonitor {

    private final Method getTps;
    private volatile int ticksInWindow;
    private volatile long windowStart = System.currentTimeMillis();
    private volatile double[] fallback = {20, 20, 20};

    public TpsMonitor() {
        Method m = null;
        try {
            m = Bukkit.getServer().getClass().getMethod("getTPS");
        } catch (Throwable ignored) {
            // 普通 Bukkit 没有 getTPS，走估算
        }
        this.getTps = m;
    }

    /** 是否走了估算模式（普通 Bukkit） */
    public boolean isFallback() {
        return getTps == null;
    }

    /** 当前 TPS（1 分钟值） */
    public double current() {
        return all()[0];
    }

    /** TPS 三元组 [1 分钟, 5 分钟, 15 分钟] */
    public double[] all() {
        if (getTps != null) {
            try {
                Object r = getTps.invoke(Bukkit.getServer());
                if (r instanceof double[] d && d.length >= 3) return d;
            } catch (Throwable ignored) {
                // 反射失败时退回估算值
            }
        }
        return fallback;
    }

    /** 每秒调用一次：估算模式下刷新 TPS */
    public void tickSecond() {
        if (getTps != null) return;
        long now = System.currentTimeMillis();
        long elapsed = now - windowStart;
        if (elapsed < 1000) return;
        double tps = Math.min(20.0, ticksInWindow * 1000.0 / elapsed);
        ticksInWindow = 0;
        windowStart = now;
        fallback = new double[]{tps, tps, tps};
    }

    /** 每个 tick 调用一次 */
    public void countTick() {
        if (getTps == null) ticksInWindow++;
    }
}
