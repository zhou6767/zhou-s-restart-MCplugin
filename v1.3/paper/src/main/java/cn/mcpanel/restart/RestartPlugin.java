package cn.mcpanel.restart;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCPanelRestart（Paper 版）：每天定时重启服务器，并在多个时间点提前广播通知。
 * <p>
 * 默认：04:00 重启；03:00 / 03:30 / 03:45 / 03:50 / 03:55 / 03:59 通知。
 * 时间点全部可配置，支持 /restart 手动倒计时与取消。
 * <p>
 * Paper 相关改进：
 * <ul>
 *   <li>用 Adventure 的 {@link Title} 显示倒计时（支持副标题、淡入淡出时间）</li>
 *   <li>文本支持 MiniMessage 写法（&lt;red&gt;&lt;bold&gt;…&lt;/bold&gt;&lt;/red&gt;）和传统 &amp; 颜色两种</li>
 *   <li>关服走 {@code Bukkit.shutdown()}（Paper 的优雅关闭），保存完成后立即停服</li>
 *   <li>Folia 环境用 RegionScheduler 调度（普通 Paper 走 BukkitScheduler）</li>
 * </ul>
 */
public final class RestartPlugin extends JavaPlugin implements Listener {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 一条提前通知 */
    private record Notify(LocalTime at, String message) {
    }

    private final List<Notify> notifications = new ArrayList<>();
    private LocalTime restartTime = LocalTime.of(4, 0);
    private boolean enabled = true;
    private int shutdownDelay = 5;
    private int countdownFrom = 10;
    private String countdownTitle = "&c&l%time%";
    private String countdownSubtitle = "&f服务器即将重启";
    private int titleFadeIn = 200;
    private int titleStay = 1200;
    private int titleFadeOut = 400;
    private String manualMessage = "&c服务器将在 %time% 后重启服务器。";
    private int manualDefault = 60;
    private boolean welcomeBack = true;
    private String welcomeMessage = "&a服务器已重启完成，欢迎回来！";
    private boolean useMiniMessage = false;
    private boolean foliaSafe = true;

    // ---- TPS 保护（1.1 新增）----
    private double tpsThreshold = 10.0;
    private int tpsDuration = 120;
    private double tpsRecover = 15.0;
    private int tpsRestartDelay = 60;
    private int tpsCooldownMinutes = 30;
    private boolean tpsEnabled = true;
    private int tpsWarmupSeconds = 120;
    private String tpsMessage = "&c&l[服务器公告] &r服务器卡顿严重（TPS 低于 %tps%），将在 &c%time%&r 后自动重启以恢复。";
    private boolean tpsQuietKick = true;

    private final TpsMonitor tps = new TpsMonitor();
    private int lowTpsSeconds = 0;
    private long lastTpsRestartAt = 0L;
    private boolean tpsTriggeredRestart = false;
    private double tpsAtTrigger = 0;
    private boolean useTpsMessage = false;
    private long lastTickMs = 0L;

    /** 已触发过的通知（日期#时间点），防止同一分钟重复发 */
    private final Set<String> fired = new HashSet<>();
    private long startedAt;

    private Object ticker;

    /** 重启倒计时（秒）；-1 表示没有进行中的重启 */
    private int countdown = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        load();
        startedAt = System.currentTimeMillis();
        Bukkit.getPluginManager().registerEvents(this, this);
        scheduleTick();
        getLogger().info("定时重启已启用：" + restartTime.format(HM) + "，提前通知 " + notifications.size() + " 个"
                + (isFolia() ? "（Folia 区域调度）" : ""));
        if (tpsEnabled) {
            getLogger().info("TPS 保护已启用：低于 " + tpsThreshold + " 持续 " + tpsDuration + " 秒将自动重启（冷却 "
                    + tpsCooldownMinutes + " 分钟" + (tps.isFallback() ? "，tick 估算模式" : "") + "）");
        }
        logNext();
    }

    @Override
    public void onDisable() {
        cancelTicker();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // 服务器重启后 5 分钟内进来的玩家，给一句「重启完成」
        if (welcomeBack && System.currentTimeMillis() - startedAt < 300_000L) {
            e.getPlayer().sendMessage(toComponent(welcomeMessage, e.getPlayer()));
        }
    }

    // =====================================================================
    //  调度（Paper / Folia 兼容）
    // =====================================================================
    private void scheduleTick() {
        if (isFolia() && foliaSafe) {
            ticker = Bukkit.getServer().getGlobalRegionScheduler()
                    .runAtFixedRate(this, task -> tick(), 1L, 1L);
        } else {
            ticker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        }
    }

    private void cancelTicker() {
        if (ticker == null) return;
        try {
            if (ticker instanceof BukkitTask t) {
                t.cancel();
            } else if (ticker instanceof io.papermc.paper.threadedregions.scheduler.ScheduledTask f) {
                f.cancel();
            }
        } catch (Throwable ignored) {
        }
        ticker = null;
    }

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void runLater(Runnable r, long ticks) {
        if (isFolia() && foliaSafe) {
            Bukkit.getServer().getGlobalRegionScheduler().runDelayed(this, task -> r.run(), Math.max(1, ticks));
        } else {
            Bukkit.getScheduler().runTaskLater(this, r, ticks);
        }
    }

    // =====================================================================
    //  配置
    // =====================================================================
    private void load() {
        reloadConfig();
        enabled = getConfig().getBoolean("restart.enabled", true);
        restartTime = parseTime(getConfig().getString("restart.time", "04:00"), LocalTime.of(4, 0), "restart.time");
        shutdownDelay = Math.max(1, getConfig().getInt("restart.shutdown-delay-seconds", 5));
        useMiniMessage = getConfig().getBoolean("text.use-minimessage", false);
        foliaSafe = getConfig().getBoolean("restart.folia-safe", true);

        notifications.clear();
        List<?> raw = getConfig().getList("notify");
        if (raw != null) {
            for (Object o : raw) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Object at = m.get("at");
                Object msg = m.get("message");
                if (at == null || msg == null) continue;
                LocalTime time = parseTime(String.valueOf(at), null, "notify.at");
                if (time == null) continue;
                notifications.add(new Notify(time, String.valueOf(msg)));
            }
        }
        notifications.sort((a, b) -> a.at().compareTo(b.at()));

        countdownFrom = Math.max(0, getConfig().getInt("countdown.from", 10));
        countdownTitle = getConfig().getString("countdown.title", "&c&l%time%");
        countdownSubtitle = getConfig().getString("countdown.subtitle", "");
        titleFadeIn = Math.max(0, getConfig().getInt("countdown.title-fade-in-ms", 200));
        titleStay = Math.max(100, getConfig().getInt("countdown.title-stay-ms", 1200));
        titleFadeOut = Math.max(0, getConfig().getInt("countdown.title-fade-out-ms", 400));
        manualDefault = Math.max(10, getConfig().getInt("manual-default-seconds", 60));
        manualMessage = getConfig().getString("manual-message", manualMessage);
        welcomeBack = getConfig().getBoolean("welcome-back.enabled", true);
        welcomeMessage = getConfig().getString("welcome-back.message", welcomeMessage);

        // ---- TPS 保护配置 ----
        tpsEnabled = getConfig().getBoolean("tps-guard.enabled", true);
        tpsThreshold = getConfig().getDouble("tps-guard.threshold", 10.0);
        tpsDuration = Math.max(5, getConfig().getInt("tps-guard.duration-seconds", 120));
        tpsRecover = Math.max(tpsThreshold, getConfig().getDouble("tps-guard.recover-tps", 15.0));
        tpsRestartDelay = Math.max(10, getConfig().getInt("tps-guard.countdown-seconds", 60));
        tpsCooldownMinutes = Math.max(0, getConfig().getInt("tps-guard.cooldown-minutes", 30));
        tpsWarmupSeconds = Math.max(0, getConfig().getInt("tps-guard.warmup-seconds", 120));
        tpsMessage = getConfig().getString("tps-guard.message", tpsMessage);
        tpsQuietKick = getConfig().getBoolean("tps-guard.quiet-kick", true);
    }

    private LocalTime parseTime(String raw, LocalTime def, String key) {
        if (raw == null || raw.isBlank()) return def;
        String s = raw.trim();
        try {
            if (s.length() == 5) return LocalTime.parse(s, HM);
            if (s.length() == 8) return LocalTime.parse(s, HMS);
        } catch (DateTimeParseException ignored) {
        }
        getLogger().warning("配置项 " + key + " 时间格式错误：'" + raw + "'，应为 HH:mm，已忽略");
        return def;
    }

    // =====================================================================
    //  每秒检查
    // =====================================================================
    private void tick() {
        // 每秒只处理一次（时间戳节流，服务端卡顿时不会重复执行）
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastTickMs < 500L) return;
        lastTickMs = nowMs;

        LocalDateTime now = LocalDateTime.now();

        // TPS 采样与保护
        tps.tickSecond();
        tickTpsGuard();

        if (countdown >= 0) {
            tickCountdown();
            return;
        }
        if (!enabled) return;

        String today = LocalDate.now().toString();

        // 提前通知
        for (Notify n : notifications) {
            String key = today + "#" + n.at().format(HM);
            if (fired.contains(key)) continue;
            if (sameMinute(now.toLocalTime(), n.at())) {
                fired.add(key);
                broadcast(n.message());
            }
        }

        // 到点重启
        String restartKey = today + "#restart";
        if (sameMinute(now.toLocalTime(), restartTime) && !fired.contains(restartKey)) {
            fired.add(restartKey);
            countdown = Math.max(shutdownDelay, Math.max(countdownFrom, 1));
            getLogger().info("到达重启时间 " + restartTime.format(HM) + "，开始关机倒计时");
        }

        // 清理过期记录
        fired.removeIf(k -> !k.startsWith(today));
    }

    private static boolean sameMinute(LocalTime a, LocalTime b) {
        return a.getHour() == b.getHour() && a.getMinute() == b.getMinute();
    }

    private void tickCountdown() {
        if (countdown == 0) {
            countdown = -1;
            shutdown();
            return;
        }
        boolean announce = countdown <= countdownFrom || countdown % 60 == 0 || countdown % 10 == 0;
        if (announce) {
            String timeText = human(countdown);
            String template = useTpsMessage ? tpsMessage : manualMessage;
            broadcast(template.replace("%time%", timeText)
                    .replace("%tps%", String.format("%.1f", tpsAtTrigger > 0 ? tpsAtTrigger : tps.current())));
            if (countdown <= countdownFrom) {
                String title = countdownTitle.replace("%time%", String.valueOf(countdown));
                String sub = countdownSubtitle.replace("%time%", timeText);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    sendTitle(p, title, sub);
                }
            }
        }
        countdown--;
    }

    /**
     * TPS 保护：TPS 持续低于阈值超过指定秒数 → 自动倒计时重启。
     * 预热期内不检查；回到 recover 阈值以上清零；冷却期内不重复触发。
     */
    private void tickTpsGuard() {
        if (!tpsEnabled) return;
        if (System.currentTimeMillis() - startedAt < tpsWarmupSeconds * 1000L) return;
        if (countdown >= 0) return;

        double current = tps.current();
        if (current < tpsThreshold) {
            lowTpsSeconds++;
        } else if (current >= tpsRecover) {
            if (lowTpsSeconds > 0) {
                getLogger().info("服务器 TPS 已恢复到 " + String.format("%.1f", current) + "，取消卡顿计数");
            }
            lowTpsSeconds = 0;
            return;
        }
        if (lowTpsSeconds < tpsDuration) return;

        long nowMs = System.currentTimeMillis();
        if (lastTpsRestartAt > 0 && nowMs - lastTpsRestartAt < tpsCooldownMinutes * 60_000L) {
            lowTpsSeconds = 0;
            getLogger().warning("TPS 仍然很低，但距离上次 TPS 自动重启不足 " + tpsCooldownMinutes + " 分钟，跳过");
            return;
        }

        lowTpsSeconds = 0;
        lastTpsRestartAt = nowMs;
        tpsTriggeredRestart = true;
        useTpsMessage = true;
        tpsAtTrigger = current;
        countdown = tpsRestartDelay;
        getLogger().warning("服务器 TPS 低于 " + tpsThreshold + " 持续 " + tpsDuration + " 秒（当前 "
                + String.format("%.1f", current) + "），开始 " + tpsRestartDelay + " 秒倒计时自动重启");
        broadcast(tpsMessage.replace("%time%", human(tpsRestartDelay))
                .replace("%tps%", String.format("%.1f", current)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            sendTitle(p, countdownTitle.replace("%time%", String.valueOf(tpsRestartDelay)), countdownSubtitle);
        }
    }

    private void sendTitle(Player p, String title, String subtitle) {
        try {
            Component t = toComponent(title, p);
            Component s = subtitle == null || subtitle.isBlank() ? Component.empty() : toComponent(subtitle, p);
            Title.Times times = Title.Times.times(
                    Duration.ofMillis(titleFadeIn), Duration.ofMillis(titleStay), Duration.ofMillis(titleFadeOut));
            p.showTitle(Title.title(t, s, times));
        } catch (Throwable ignored) {
        }
    }

    /** Paper 优雅关闭：保存世界 → 静默踢人 → Bukkit.shutdown() */
    private void shutdown() {
        if (tpsTriggeredRestart) {
            getLogger().warning("正在重启服务器（原因：TPS 持续低于阈值，自动保护重启）");
        } else {
            getLogger().info("正在重启服务器：保存世界并关闭");
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                if (tpsQuietKick) {
                    // Paper: 静默踢人（客户端只看得到断开原因）
                    p.kick(toComponent("&c服务器正在重启\n&7请稍后重新连接", p));
                } else {
                    p.kickPlayer(colorize("&c服务器正在重启\n&7请稍后重新连接"));
                }
            } catch (Throwable t) {
                p.kickPlayer(colorize("&c服务器正在重启\n&7请稍后重新连接"));
            }
        }
        runLater(() -> {
            try {
                Bukkit.shutdown();
            } catch (Throwable t) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stop");
            }
        }, Math.max(20L, shutdownDelay * 20L));
    }

    // =====================================================================
    //  /restart
    // =====================================================================
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("restart")) return false;
        if (!sender.hasPermission("mcpanelrestart.admin")) {
            sender.sendMessage("§c你没有权限。");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "cancel" -> {
                if (countdown >= 0) {
                    countdown = -1;
                    useTpsMessage = false;
                    tpsTriggeredRestart = false;
                    broadcast("&a&l[服务器公告] &r重启已取消，服务器继续运行。");
                    sender.sendMessage("§a已取消重启。");
                } else {
                    sender.sendMessage("§7当前没有进行中的重启倒计时。");
                }
            }
            case "status" -> {
                sender.sendMessage("§6===== 定时重启（Paper）=====");
                sender.sendMessage("§7版本：§f" + getDescription().getVersion() + "  §7运行于：§f" + (isFolia() ? "Folia" : "Paper/Spigot"));
                sender.sendMessage("§7状态：§f" + (enabled ? "已启用" : "已关闭"));
                sender.sendMessage("§7每日重启：§f" + restartTime.format(HM));
                List<String> times = new ArrayList<>();
                for (Notify n : notifications) times.add(n.at().format(HM));
                sender.sendMessage("§7提前通知：§f" + String.join("、", times));
                sender.sendMessage("§7当前倒计时：§f" + (countdown >= 0 ? countdown + " 秒" : "无"));
                sender.sendMessage("§7下次重启：§f" + nextRestartText());
                double[] t = tps.all();
                sender.sendMessage("§7当前 TPS：§f" + String.format("%.1f / %.1f / %.1f", t[0], t[1], t[2])
                        + (tps.isFallback() ? " §7(估算)" : ""));
                sender.sendMessage("§7TPS 保护：§f" + (tpsEnabled ? "开启（< " + tpsThreshold + " 持续 "
                        + tpsDuration + " 秒触发）" : "关闭") + "  §7低 TPS 计时：§f" + lowTpsSeconds + " 秒");
            }
            case "tps" -> {
                double[] t = tps.all();
                sender.sendMessage("§6===== TPS =====");
                sender.sendMessage("§7当前：§f" + String.format("%.1f / %.1f / %.1f", t[0], t[1], t[2])
                        + (tps.isFallback() ? " §7(估算)" : ""));
                sender.sendMessage("§7保护：§f" + (tpsEnabled ? "开启（< " + tpsThreshold + " 持续 "
                        + tpsDuration + " 秒 → 倒计时 " + tpsRestartDelay + " 秒重启）" : "关闭"));
                sender.sendMessage("§7低 TPS 计时：§f" + lowTpsSeconds + " / " + tpsDuration + " 秒");
                sender.sendMessage("§7冷却剩余：§f" + Math.max(0L, (tpsCooldownMinutes * 60_000L
                        - (System.currentTimeMillis() - lastTpsRestartAt)) / 1000L) + " 秒");
            }
            case "reload" -> {
                load();
                sender.sendMessage("§a配置已重新加载，重启时间：" + restartTime.format(HM));
                logNext();
            }
            case "now" -> {
                countdown = Math.max(10, countdownFrom);
                useTpsMessage = false;
                tpsTriggeredRestart = false;
                broadcast("&c&l[服务器公告] &r服务器即将重启！");
                sender.sendMessage("§a已开始重启倒计时。");
            }
            default -> {
                int seconds = manualDefault;
                if (!sub.isEmpty()) {
                    try {
                        seconds = Integer.parseInt(sub);
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§c用法：/restart [秒数|cancel|status|reload|now]");
                        return true;
                    }
                }
                if (countdown >= 0) {
                    sender.sendMessage("§c已有重启倒计时在进行，先 /restart cancel。");
                    return true;
                }
                countdown = Math.max(1, seconds);
                useTpsMessage = false;
                tpsTriggeredRestart = false;
                sender.sendMessage("§a已开始 " + seconds + " 秒倒计时重启。");
                getLogger().info("已开始 " + seconds + " 秒倒计时重启（执行者：" + sender.getName() + "）");
            }
        }
        return true;
    }

    // =====================================================================
    //  工具
    // =====================================================================
    private void broadcast(String message) {
        String text = colorize(message);
        Component comp = toComponent(text, null);
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(comp);
        getLogger().info("[通知] " + text.replaceAll("§.", "").replace("\n", " "));
    }

    private String colorize(String s) {
        return s == null ? "" : org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }

    /** 文本 → Adventure Component：可选 MiniMessage，默认传统 & 颜色 */
    private Component toComponent(String raw, Player player) {
        String text = raw == null ? "" : raw;
        if (player != null) {
            text = text.replace("%player%", player.getName());
        }
        try {
            if (useMiniMessage) {
                return MINI.deserialize(text);
            }
            return LegacyComponentSerializer.legacySection().deserialize(colorize(text));
        } catch (Throwable t) {
            return Component.text(text);
        }
    }

    private static String human(int seconds) {
        if (seconds >= 3600 && seconds % 3600 == 0) return (seconds / 3600) + " 小时";
        if (seconds >= 60 && seconds % 60 == 0) return (seconds / 60) + " 分钟";
        return seconds + " 秒";
    }

    /** 距离下次重启还有多久 */
    public String nextRestartText() {
        LocalTime now = LocalTime.now();
        Duration d = now.isBefore(restartTime)
                ? Duration.between(now, restartTime)
                : Duration.between(now, restartTime).plusDays(1);
        long m = d.toMinutes();
        return restartTime.format(HM) + "（还有 " + (m / 60) + " 小时 " + (m % 60) + " 分钟）";
    }

    private void logNext() {
        LocalTime now = LocalTime.now();
        LocalTime next = now.isBefore(restartTime) ? restartTime : restartTime;
        Instant instant = LocalDate.now().atTime(next).atZone(ZoneId.systemDefault()).toInstant();
        if (now.isAfter(next)) instant = instant.plus(Duration.ofDays(1));
        getLogger().info("下一次重启：" + FULL.format(instant.atZone(ZoneId.systemDefault())));
    }

    
}
