# MCPanelRestart — Paper 版定时重启

> 基于同一个功能做成 **Paper 专用版本**，解决普通 Bukkit 版在 Paper/Folia 上的几处短板。
> jar：`MCPanelRestart-1.0.0-paper.jar`

## 与 Bukkit 版的区别

| | Bukkit 版 | **Paper 版** |
|---|---|---|
| 倒计时显示 | `sendTitle(String)`，只有标题、固定时间 | Adventure `Title`：**主标题 + 副标题**，淡入/停留/淡出时间可配 |
| 颜色格式 | 只支持 `&` 颜色 | 支持 `&`，也可切换 **MiniMessage**（`<red><bold>…</bold></red>`） |
| 关服方式 | `/stop` 控制台指令 | **`Bukkit.shutdown()`**（Paper 优雅关闭），保存完立即停 |
| 踢人 | `kickPlayer(String)` | `Player.kick(Component)`（Paper 新接口） |
| Folia | 不支持（BukkitScheduler） | 自动检测并走 **GlobalRegionScheduler** |
| 状态查看 | 基础信息 | 额外显示运行核心（Paper/Folia）、下次重启时间、下载版本 |

功能完全一致：每天 **04:00** 重启、**03:00 / 03:30 / 03:45 / 03:50 / 03:55 / 03:59** 提前通知、最后 10 秒大字倒计时、`/restart` 手动倒计时、重启后欢迎语。

## 安装

1. **Paper / Purpur / Folia 1.20+（Java 17）**
2. 把 `MCPanelRestart-1.0.0-paper.jar` 放进 `plugins`
3. 启动服务端，生成 `plugins/MCPanelRestart/config.yml`

`plugin.yml` 的插件名仍是 `MCPanelRestart`，**不要和 Bukkit 版同时装**（会冲突），只留一个。

## 配置 `plugins/MCPanelRestart/config.yml`

```yaml
restart:
  enabled: true
  time: "04:00"
  shutdown-delay-seconds: 5
  folia-safe: true          # Folia 自动适配，普通 Paper 保持即可

text:
  use-minimessage: false    # true = 用 MiniMessage 写法

notify:
  - at: "03:00"
    message: "&e&l[服务器公告] &r服务器将在 &c1 小时&r 后（&c04:00&r）重启维护，请提前保存进度。"
  # … 03:30 / 03:45 / 03:50 / 03:55 / 03:59 默认已写好

countdown:
  from: 10
  title: "&c&l%time%"
  subtitle: "&f服务器即将重启"
  title-fade-in-ms: 200
  title-stay-ms: 1200
  title-fade-out-ms: 400
```

改完 `/restart reload`。

### 想用 MiniMessage 写法

把 `text.use-minimessage` 设为 `true`，然后：

```yaml
  - at: "03:55"
    message: "<gold><bold>[服务器公告]</bold></color> 服务器将在 <red>5 分钟</red> 后重启"
```

## 指令

| 指令 | 说明 |
|---|---|
| `/restart` | 按默认秒数倒计时 |
| `/restart 300` | 300 秒后重启 |
| `/restart cancel` | 取消 |
| `/restart status` | 状态（含下次重启时间、运行核心） |
| `/restart reload` | 重载配置 |
| `/restart now` | 立即倒计时 |

权限：`mcpanelrestart.admin`（默认 OP）。

## 重要：关服后要自动拉起来

插件只能关服，**重启需要外部拉起**。Paper 服务器推荐用循环启动脚本（纯英文，避免 cmd 编码问题）：

```bat
:loop
java -Xms2G -Xmx10G -XX:+UseG1GC -jar paper-1.20.1-196.jar nogui
echo Server stopped, restarting in 10s...
ping -n 10 127.0.0.1 >nul
goto loop
```

或者用面板（Pterodactyl 等）自带的自动重启。

## 构建

- Maven：`mvn package` → `target/MCPanelRestart-1.0.0-paper.jar`
- 无 Maven：双击 `build.bat`（JDK 17）
