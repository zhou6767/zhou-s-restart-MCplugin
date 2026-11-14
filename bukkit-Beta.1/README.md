# MCPanelRestart v1.1 — 定时重启 + TPS 卡顿保护（独立插件）

不依赖 MCPanel，单独放进 `plugins` 即可。基于 `Zhou's Restart-bukkit-1.0.jar` 升级。

## 功能

- 每天 **04:00** 自动重启（保存 → 踢人 → `/stop`）
- 提前在 **03:00 / 03:30 / 03:45 / 03:50 / 03:55 / 03:59** 广播通知（可自由增删改）
- **【1.1 新增】TPS 低于 10% 持续 2 分钟 → 自动倒计时重启**
- 最后 10 秒倒计时 Title + 聊天提示，然后保存并关服
- 服务器起来后 5 分钟内进入的玩家会收到「重启完成，欢迎回来」
- `/restart` 手动重启（带倒计时），`/restart cancel` 取消

## TPS 卡顿保护（1.1）

`config.yml`：

```yaml
tps-guard:
  enabled: true
  threshold: 10.0            # TPS 低于 10 开始计时
  duration-seconds: 120      # 持续 120 秒才触发（防卡一下误判）
  recover-tps: 15.0          # 回升到 15 以上就清零计时
  countdown-seconds: 60      # 触发后给玩家 60 秒准备
  cooldown-minutes: 30       # 两次 TPS 重启至少间隔 30 分钟
  warmup-seconds: 120        # 开服 2 分钟内不检查（加载区块天然低 TPS）
  message: "&c&l[服务器公告] &r服务器卡顿严重（TPS 仅 %tps%），将在 &c%time%&r 后自动重启以恢复正常。"
  quiet-kick: true
```

保护逻辑：

1. 每 0.5 秒读取一次 TPS（Paper 用 `getServer().getTPS()`，普通 Bukkit 自动退回 tick 计数估算）
2. TPS < 10 → 开始累计秒数；回到 ≥ 15 → 清零（阈值之间保持计数，防抖动）
3. 累计满 120 秒 → 广播并开始 60 秒倒计时，期间每 10 秒提示、最后 10 秒大字倒计时
4. 冷却 30 分钟内不重复触发，避免连环重启

查看状态：`/restart tps`（当前 TPS、低 TPS 计时、冷却剩余）

## 安装

1. 需要 **Paper/Spigot 1.20+（Java 17）**、Mohist 也可以
2. 把 `MCPanelRestart-1.1.0.jar` 放进 `plugins`（**先删掉旧版 1.0.0 的 jar**）
3. 重启服务器，生成 `plugins/MCPanelRestart/config.yml`

> 从 1.0 升级：旧 `config.yml` 里没有 `tps-guard:` 段也能用（默认开启），
> 想让注释齐全可以删掉旧 config.yml 让它重新生成（会重置你改过的文案）。

## 注意：关服后需要「自动拉起」

插件只能执行 `/stop`，**服务器关掉后要重新启动，必须靠你的启动脚本或面板**：

- 如果是 `run.bat` 双击启动的窗口：关服后窗口会停在 `pause`，不会自动重开。
  可以把 run.bat 改成自动重启循环（见下方 run.bat 示例），或者用面板/计划任务拉起。
- 如果用了面板（Pterodactyl 等）自带的自动重启，保持开启即可。

### run.bat 自动重启示例

```bat
:loop
java -Xms2G -Xmx10G -XX:+UseG1GC -jar mohist-1.20.1-6e3e2a12-server.jar nogui
echo 服务器已退出，10 秒后自动重启...
timeout /t 10
goto loop
```

## 配置 `plugins/MCPanelRestart/config.yml`

```yaml
restart:
  enabled: true
  time: "04:00"                 # 每天重启时间
  shutdown-delay-seconds: 5

notify:                         # 提前通知（可任意增减）
  - at: "03:00"
    message: "&e&l[服务器公告] &r服务器将在 &c1 小时&r 后（&c04:00&r）重启维护，请提前保存进度。"
  - at: "03:30"
    message: "&e&l[服务器公告] &r服务器将在 &c30 分钟&r 后（&c04:00&r）重启，请尽快结束当前活动。"
  - at: "03:45"
    message: "&e&l[服务器公告] &r服务器将在 &c15 分钟&r 后重启，请回到安全位置。"
  - at: "03:50"
    message: "&e&l[服务器公告] &r服务器将在 &c10 分钟&r 后重启。"
  - at: "03:55"
    message: "&c&l[服务器公告] &r服务器将在 &c5 分钟&r 后重启，请做好准备。"
  - at: "03:59"
    message: "&c&l[服务器公告] &r服务器将在 &c1 分钟&r 后重启！"

countdown:
  from: 10                      # 最后 10 秒 Title 倒计时，0 = 关闭
  title: "&c&l%time%"
```

颜色用 `&`，`%time%` 会替换成剩余时间（如「5 分钟」「30 秒」）。

## 指令

| 指令 | 说明 |
|---|---|
| `/restart` | 按默认秒数开始倒计时重启 |
| `/restart 300` | 300 秒后重启 |
| `/restart cancel` | 取消倒计时 |
| `/restart status` | 查看下次重启时间与通知点 |
| `/restart reload` | 重新加载配置 |
| `/restart now` | 立即进入倒计时重启 |

权限：`mcpanelrestart.admin`（默认 OP）。

## 构建

- 有 Maven：`mvn package`
- 没有 Maven：双击 `build.bat`（JDK 17）
