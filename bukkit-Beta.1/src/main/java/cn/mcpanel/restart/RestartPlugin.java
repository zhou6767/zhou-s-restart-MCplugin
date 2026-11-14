package cn.mcpanel.restart;

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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCPanelRestart：每天定时重启服务器，并在多个时间点提前广播通知。
 * <p>
 * 默认：04:00 重启；03:00 / 03:30 / 03:45 / 03:50 / 03:55 / 03:59 通知。
 * 时间点全部可配置，支持 /restart 手动倒计时与取消。
 */
public final class RestartPlugin extends JavaPlugin implements Listener {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 一条提前通知 */
    private record Notify(LocalTime at, String message) {
    }

    private final List<Notify> notifications = new ArrayList<>();
    private LocalTime restartTime = LocalTime.of(4, 0);
    private boolean enabled = true;
    private int shutdownDelay = 5;
    private int countdownFrom = 10;
    private String countdownTitle = "&c&l%time%";
    private String manualMessage = "&c服务器将在 %time% 后重启服务器。";
    private int manualDefault = 60;
    private boolean welcomeBack = true;
    private String welcomeMessage = "&a服务器已重启完成，欢迎回来！";

    // ---- TPS 保护（1.1 新增）----
    /** TPS 低于该值时开始计数 */
    private double tpsThreshold = 10.0;
    /** TPS 持续低于阈值多少秒后触发自动重启 */
    private int tpsDuration = 120;
    /** TPS 恢复到这个值以上就重置计时（避免抖动误判） */
    private double tpsRecover = 15.0;
    /** TPS 触发重启前的倒计时秒数 */
    private int tpsRestartDelay = 60;
    /** 两次 TPS 自动重启的最小间隔（分钟），防止连环重启 */
    private int tpsCooldownMinutes = 30;
    /** 是否启用 TPS 保护 */
    private boolean tpsEnabled = true;
    /** 启动后多少秒内不检查 TPS（刚开服加载区块会很低） */
    private int tpsWarmupSeconds = 120;
    /** 自动重启时要发给玩家的原因文案 */
    private String tpsMessage = "&c&l[服务器公告] &r服务器卡顿严重（TPS 低于 %tps%），将在 &c%time%&r 后自动重启以恢复。";
    /** TPS 触发后是否静默踢人（不刷被踢提示） */
    private boolean tpsQuietKick = true;

    private final TpsMonitor tps = new TpsMonitor();
    /** 已连续低于阈值多少秒 */
    private int lowTpsSeconds = 0;
    /** 最近一次 TPS 自动重启的时间戳 */
    private long lastTpsRestartAt = 0L;
    /** 本次重启是否由 TPS 保护触发 */
    private boolean tpsTriggeredRestart = false;
    /** 本次重启触发时的 TPS（用于文案） */
    private double tpsAtTrigger = 0;
    /** 本次重启的倒计时是否需要使用 TPS 文案 */
    private boolean useTpsMessage = false;

    /** 已触发过的通知（日期#时间点），防止同一分钟重复发 */
    private final Set<String> fired = new HashSet<>();
    private long startedAt;

    private BukkitTask ticker;
    private long lastTickMs = 0L;
    /** 重启倒计时（秒）；-1 表示没有进行中的重启 */
    private int countdown = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        load();
        startedAt = System.currentTimeMillis();
        Bukkit.getPluginManager().registerEvents(this, this);
        // 每 20 tick 检查一次（1 秒）
        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        getLogger().info("定时重启已启用：" + restartTime.format(HM) + "，提前通知 " + notifications.size() + " 个");
        if (tpsEnabled) {
            getLogger().info("TPS 保护已启用：低于 " + tpsThreshold + " 持续 " + tpsDuration + " 秒将自动重启"
                    + "（冷却 " + tpsCooldownMinutes + " 分钟" + (tps.isFallback() ? "，使用 tick 估算模式" : "") + "）");
        }
    }

    @Override
    public void onDisable() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // 服务器重启后 5 分钟内进来的玩家，给一句「重启完成」
        if (welcomeBack && System.currentTimeMillis() - startedAt < 300_000L) {
            e.getPlayer().sendMessage(colorize(welcomeMessage));
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
        // 每秒只处理一次（用时间戳判断，服务端卡顿时也不会重复执行）
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastTickMs < 500L) return;
        lastTickMs = nowMs;

        LocalDateTime now = LocalDateTime.now();

        // TPS 采样与保护（不管定时重启是否启用，都先记录 TPS）
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
            countdown = Math.max(shutdownDelay, countdownFrom);
            getLogger().info("到达重启时间 " + restartTime.format(HM) + "，开始关机倒计时");
        }

        // 清理过期记录
        fired.removeIf(k -> !k.startsWith(today));
    }

    private static boolean sameMinute(LocalTime a, LocalTime b) {
        return a.getHour() == b.getHour() && a.getMinute() == b.getMinute();
    }

    /**
     * TPS 保护：TPS 持续低于阈值超过指定秒数 → 自动倒计时重启。
     * 规则：
     * - 服务器启动后 warmup-seconds 秒内不检查（加载区块时 TPS 天然很低）
     * - TPS 低于 threshold 开始累加秒数，回到 recover-tps 以上清零
     * - 冷却期内不重复触发，避免反复重启
     * - 已有倒计时或定时重启正在等待时不重复触发
     */
    private void tickTpsGuard() {
        if (!tpsEnabled) return;
        // 刚启动的预热期
        if (System.currentTimeMillis() - startedAt < tpsWarmupSeconds * 1000L) return;
        // 已经有倒计时在进行
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
        // 阈值与恢复阈值之间：保持计数不变（避免抖动）

        if (lowTpsSeconds < tpsDuration) return;

        // 冷却检查
        long nowMs = System.currentTimeMillis();
        long cooldownMs = tpsCooldownMinutes * 60_000L;
        if (lastTpsRestartAt > 0 && nowMs - lastTpsRestartAt < cooldownMs) {
            lowTpsSeconds = 0;
            getLogger().warning("TPS 仍然很低，但距离上次 TPS 自动重启不足 " + tpsCooldownMinutes + " 分钟，跳过");
            return;
        }

        // 触发
        lowTpsSeconds = 0;
        lastTpsRestartAt = nowMs;
        tpsTriggeredRestart = true;
        useTpsMessage = true;
        tpsAtTrigger = current;
        countdown = tpsRestartDelay;
        String warn = "服务器 TPS 低于 " + tpsThreshold + " 持续 " + tpsDuration + " 秒（当前 "
                + String.format("%.1f", current) + "），开始 " + tpsRestartDelay + " 秒倒计时自动重启";
        getLogger().warning(warn);
        broadcast(tpsMessage
                .replace("%time%", human(tpsRestartDelay))
                .replace("%tps%", String.format("%.1f", current)));
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            try {
                p.sendTitle(colorize(countdownTitle.replace("%time%", String.valueOf(tpsRestartDelay))), "", 5, 30, 10);
            } catch (Throwable ignored) {
            }
        }
    }

    private void tickCountdown() {
        if (countdown == 0) {
            countdown = -1;
            shutdown();
            return;
        }
        boolean announce = countdown <= countdownFrom || countdown % 60 == 0 || countdown % 10 == 0;
        if (announce) {
            String template = useTpsMessage ? tpsMessage : manualMessage;
            String msg = template
                    .replace("%time%", human(countdown))
                    .replace("%tps%", String.format("%.1f", tpsAtTrigger > 0 ? tpsAtTrigger : tps.current()));
            broadcast(msg);
            if (countdown <= countdownFrom) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendTitle(colorize(countdownTitle.replace("%time%", String.valueOf(countdown))), "", 0, 25, 5);
                }
            }
        }
        countdown--;
    }

    private void shutdown() {
        if (tpsTriggeredRestart) {
            getLogger().warning("原因：TPS 持续低于阈值（自动保护重启）");
        } else {
            getLogger().info("原因：定时/手动重启");
        }
        getLogger().info("正在重启服务器：保存世界并关闭");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (tpsQuietKick) {
                try {
                    p.kick(legacyComponent(colorize("&c服务器正在重启\n&7请稍后重新连接")));
                    continue;
                } catch (Throwable ignored) {
                    // 老版本没有 kick(Component)，退回下面
                }
            }
            p.kickPlayer(colorize("&c服务器正在重启\n&7请稍后重新连接"));
        }
        Bukkit.getScheduler().runTaskLater(this, () ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stop"), 20L);
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
                sender.sendMessage("§6===== 定时重启 =====");
                sender.sendMessage("§7状态：§f" + (enabled ? "已启用" : "已关闭"));
                sender.sendMessage("§7每日重启：§f" + restartTime.format(HM));
                List<String> times = new ArrayList<>();
                for (Notify n : notifications) times.add(n.at().format(HM));
                sender.sendMessage("§7提前通知：§f" + String.join("、", times));
                sender.sendMessage("§7当前倒计时：§f" + (countdown >= 0 ? countdown + " 秒" : "无"));
                double[] t = tps.all();
                sender.sendMessage("§7当前 TPS：§f" + String.format("%.1f / %.1f / %.1f", t[0], t[1], t[2])
                        + (tps.isFallback() ? " §7(估算)" : ""));
                sender.sendMessage("§7TPS 保护：§f" + (tpsEnabled
                        ? "开启（< " + tpsThreshold + " 持续 " + tpsDuration + " 秒触发）"
                        : "关闭") + "  §7低 TPS 计时：§f" + lowTpsSeconds + " 秒");
            }
            case "tps" -> {
                double[] t = tps.all();
                sender.sendMessage("§6===== TPS =====");
                sender.sendMessage("§7当前：§f" + String.format("%.1f / %.1f / %.1f", t[0], t[1], t[2])
                        + (tps.isFallback() ? " §7(估算，非 Paper)" : ""));
                sender.sendMessage("§7保护：§f" + (tpsEnabled ? "开启（< " + tpsThreshold + " 持续 "
                        + tpsDuration + " 秒 → 倒计时 " + tpsRestartDelay + " 秒重启）" : "关闭"));
                sender.sendMessage("§7低 TPS 计时：§f" + lowTpsSeconds + " / " + tpsDuration + " 秒");
                sender.sendMessage("§7冷却剩余：§f" + Math.max(0L, (tpsCooldownMinutes * 60_000L
                        - (System.currentTimeMillis() - lastTpsRestartAt)) / 1000L) + " 秒");
            }
            case "reload" -> {
                load();
                sender.sendMessage("§a配置已重新加载，重启时间：" + restartTime.format(HM));
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
            }
        }
        return true;
    }

    // =====================================================================
    //  工具
    // =====================================================================
    private void broadcast(String message) {
        String text = colorize(message);
        net.kyori.adventure.text.Component comp = legacyComponent(text);
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(comp);
        getLogger().info("[通知] " + org.bukkit.ChatColor.stripColor(text).replace("\n", " "));
    }

    private String colorize(String s) {
        return s == null ? "" : org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }

    private static net.kyori.adventure.text.Component legacyComponent(String section) {
        try {
            var ser = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
            return ser.deserialize(section);
        } catch (Throwable t) {
            return net.kyori.adventure.text.Component.text(section);
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
}
