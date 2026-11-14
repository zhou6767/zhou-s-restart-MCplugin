# Zhou's Restart

Minecraft 服务器自动重启插件 —— 定时重启 + 提前通知 + **TPS 卡顿保护**。

> 面向 Bukkit / Spigot / Paper / Purpur / Folia / Mohist 的全平台定时重启插件，原生中文。

## 功能

- **定时重启**：默认每天 04:00，时间可配置
- **多级提前通知**：默认 03:00 / 03:30 / 03:45 / 03:50 / 03:55 / 03:59，任意增删、文案可改
- **沉浸式倒计时**：最后 10 秒屏幕中央大字倒计时 + 聊天提示
- **TPS 卡顿保护**：TPS 低于阈值（默认 10）持续一段时间（默认 2 分钟）自动倒计时重启，带冷却与抖动过滤
- **回归提醒**：重启后 5 分钟内进服的玩家收到「欢迎回来」
- **手动管理**：`/restart`、`/restart <秒数>`、`/restart cancel`、`/restart status`、`/restart tps`、`/restart reload`、`/restart now`
- **安全关服**：`save-all` → 踢人 → 关服；Paper 版走 `Bukkit.shutdown()` 优雅关闭

## 两个版本如何选择

| 你的服务端 | 装哪个 |
|---|---|
| Paper / Purpur / Folia | **paper 版**（Adventure Title、副标题、MiniMessage、优雅关服、Folia 区域调度） |
| Bukkit / Spigot / Mohist | **bukkit 版**（通用实现，Mohist 请用这个） |

> 两个版本插件内部名相同（`Zhou's Restart`），**不要同时安装**。

## 目录结构

```
zhous-restart/
├── bukkit/                Bukkit/Spigot/Mohist 版
│   ├── src/main/java/cn/mcpanel/restart/
│   │   ├── RestartPlugin.java
│   │   └── TpsMonitor.java
│   ├── src/main/resources/{plugin.yml,config.yml}
│   ├── pom.xml / build.gradle / build.bat
└── paper/                 Paper/Purpur/Folia 版
    └── （与 bukkit 结构相同，RestartPlugin 含 Paper 专属实现）
```

## 构建

三个方式任选其一，都需要 **JDK 17**：

```bash
# 方式一：Maven
cd bukkit && mvn package

# 方式二：Gradle
cd bukkit && gradle build

# 方式三：无构建工具（Windows，自动找本地 paper-api）
cd bukkit && build.bat
```

产物在 `build/` 或 `target/` 下，放进服务器 `plugins/` 目录即可。

## 配置

首次启动生成 `plugins/Zhou's Restart/config.yml`：

```yaml
restart:
  enabled: true
  time: "04:00"
  shutdown-delay-seconds: 5

tps-guard:
  enabled: true
  threshold: 10.0            # TPS 低于 10 开始计时
  duration-seconds: 120      # 持续 120 秒才触发
  recover-tps: 15.0          # 回升到 15 清零
  countdown-seconds: 60      # 触发后 60 秒准备
  cooldown-minutes: 30       # 两次自动重启间隔
  warmup-seconds: 120        # 开服 2 分钟内不检查

notify:
  - at: "03:00"
    message: "&e&l[服务器公告] &r服务器将在 &c1 小时&r 后重启维护，请提前保存进度。"
```

改完 `/restart reload` 立即生效。

## 重要：关服后需要外部拉起

插件只负责关服，服务器需要用启动脚本或面板重新拉起：

```bat
:loop
java -Xms2G -Xmx10G -jar paper-1.20.1-196.jar nogui
echo Server stopped, restarting in 10s...
ping -n 10 127.0.0.1 >nul
goto loop
```

## 权限

| 权限 | 说明 |
|---|---|
| `mcpanelrestart.admin` | 使用 `/restart` 系列指令（默认 OP） |

## 版本

| 版本 | 内容 |
|---|---|
| 1.0 | 定时重启 + 多级通知 + 倒计时 + 欢迎回来 |
| 1.1 | 新增 TPS 卡顿保护；插件显示名改为 `Zhou's Restart` |

## 许可证

见 [LICENSE](LICENSE)。
