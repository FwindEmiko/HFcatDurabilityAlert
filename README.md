# HFcatDurabilityAlert

> **装备耐久度警告插件** — 基于 Paper 1.20.5+ 组件系统的精确耐久监控
>
> 作者：狐魇星玖 (FCelestial) · MiragEdge · MIT License

## 简介

大多数耐久提醒插件用「基础物品的最大耐久」估算剩余百分比，遇到数据包/自定义物品（例如 Stellarity 把铁头盔的 `max_damage` 覆盖为 407）就会算错。本插件直接读取 **1.20.5+ 数据组件**（`minecraft:damage` / `minecraft:max_damage`），拿到的是物品真实的耐久状态。

- 精确：`剩余 = max_damage - damage`，不依赖 `Material#getMaxDurability()`，不用已弃用的 `getDurability()`
- 轻量：事件驱动（只在耐久真正变化时触发），无轮询、无定时任务、零第三方依赖
- 通用：**同一份 jar 支持 Paper 系服务端 1.20.5 ~ 26.2，Java 21 与 Java 25 服务端都能跑**

## 兼容矩阵（均为实机实测）

| 服务端 | 版本 | 最低 Java | 状态 |
|---|---|---|---|
| Paper | 1.20.5 / 1.20.6 / 1.21.1 / 1.21.4 / 1.21.8 / 1.21.11 | 21 | ✅ 加载 + 命令 + 重载通过 |
| Paper | 26.1.2 / 26.2 | 25 | ✅ 加载 + 命令 + 重载通过 |
| Leaf | 26.2 | 25 | ✅ 通过 |
| Purpur | 1.20.6 / 1.21.8 / 26.2 | 21 / 25 | ✅ 通过 |
| Folia | 26.2 | 25 | ✅ 加载 + 命令通过（区域安全改造见下） |
| Spigot / CraftBukkit | 任意 | — | ❌ 不支持（依赖 Paper 的 Adventure 实现） |
| Paper < 1.20.5 | 任意 | — | ❌ 拒绝加载（组件化耐久 API 不存在，属预期行为） |

构建产物为 **Java 21 字节码**（class version 65），因此 1.21.x（Java 21）与 26.x（Java 25）服务端都能加载；但**不能**在 Java 17 上运行 —— Paper 1.20.5+ 本身就要求 Java 21。

## 功能

- 阈值列表无限扩展，每个阈值可配置独立消息（`{item}` `{slot}` `{durability}` `{max}` `{percent}` `{threshold}`）
- 三种输出通道：聊天 / 动作栏 / Title，可独立开关；可配置警告音效
- **PDC 防重复**：每个阈值只警告一次（`cooldown: -1`），物品修复后可重新触发
- `cooldown: 秒数`：同一阈值冷却后仍可重复提醒
- 经验修补（Mending）物品延迟到低耐久才提醒（`mending-only-warn-below`）
- 物品彻底损坏时单独提示（`break-message`，留空则不提示）
- 忽略指定物品（`ignore-items`，如 elytra / shield）
- `/hdura status` 自检：显示服务端版本、Java 版本、声音解析模式、当前生效配置
- 热重载：`/hdura reload`

## 安装

1. 确认服务端为 Paper / Leaf / Purpur / Folia 的 1.20.5 及以上版本
2. 把 `HFcatDurabilityAlert-1.2.0.jar` 放进服务端 `plugins/` 目录
3. 重启服务端（或 `/reload confirm`）
4. 首次启动生成 `plugins/HFcatDurabilityAlert/config.yml`

## 命令与权限

| 命令 | 说明 |
|---|---|
| `/hfcatdurabilityalert reload` | 重载配置 |
| `/hfcatdurabilityalert status` | 查看运行状态（版本 / 服务端 / 声音模式 / 配置文件当前值） |
| `/hfcatdurabilityalert help` | 显示帮助 |

别名：`/hdura`、`/hdalert`

| 权限 | 默认 | 说明 |
|---|---|---|
| `hfcatdurabilityalert.admin` | op | 管理命令 |
| `hfcatdurabilityalert.use` | true | 接收耐久警告 |

## 配置要点

完整注释见 `config.yml`，这里列出容易踩坑的几项。

### 声音名怎么写

```yaml
messages:
  # 老式枚举名（1.20.5 ~ 26.2 通用，推荐）
  warning-sound: "ENTITY_EXPERIENCE_ORB_PICKUP:0.8:1.2"
  # 命名空间键（1.21.4+ 才支持点分名）
  # warning-sound: "entity.experience_orb.pickup:0.8:1.2"
  # warning-sound: "minecraft:entity.experience_orb.pickup:0.8:1.2"
```

格式为 `声音名[:音量[:音调]]`，音量钳制 `0.0 ~ 2.0`，音调钳制 `0.5 ~ 2.0`。

> 为什么不能只写点分名？1.20.5 ~ 1.21.3 没有声音注册表（`Registry.SOUND_EVENT` 是 1.21.4 才有的），此时最稳妥的写法是枚举名；而 1.21.4+ 的注册表键又变成点分名。插件内部按服务端能力自动解析，写枚举名在所有版本都可用。配置写错时启动日志会明确提示，不会静默失效。

### 阈值与冷却

```yaml
warnings:
  thresholds: [50, 30, 15, 10, 5]  # 0 永远不会触发（剩余为 0 时物品已损坏）
  cooldown: -1                     # -1：每个阈值只警告一次，修复后可重新触发
                                   # >0：同一阈值冷却 N 秒后可再提醒
                                   # 0 或 < -1：视为无效，按 -1 处理并给出提示
```

### 提醒位置与损坏提示

```yaml
messages:
  send-chat: true       # 聊天框
  send-actionbar: true  # 动作栏
  send-title: false     # 屏幕中央 Title
  break-message: "&4&l!!! &f{item} &4&l已损坏！"   # 留空 = 既不提示也不播放声音
```

颜色支持 `&a` `&c` `&l` 等传统代码与 `&#RRGGBB` 十六进制（不支持 MiniMessage 标签）。

## 构建

要求 **JDK 21 或更高**（Gradle 9.7.1 wrapper 可在 JDK 21 ~ 25 上运行）。

```bash
./gradlew build        # Linux / macOS
build.bat              # Windows，等价于 gradlew.bat clean build
```

产物：`build/libs/HFcatDurabilityAlert-<version>.jar`。版本号由 `build.gradle.kts` 注入 `plugin.yml`，不会出现「jar 名与插件版本号不一致」。

> **首次构建会联网下载 Gradle 9.7.1（约 130 MB）**：如果网络较慢或无法访问 `services.gradle.org`，
> 可改用本机已安装的 Gradle 9.x 直接构建：`gradle clean build`（本项目不依赖任何 Gradle 插件仓库中的插件，
> 只用内置的 `java` 插件，因此离线环境同样可行）。

编译基线固定为 `paper-api:1.20.6-R0.1-SNAPSHOT`（compileOnly，不打进 jar），`options.release = 21`。源码只使用 1.20.5 就已存在的 API，并用 1.20.5 的 paper-api 直接 `javac` 校验过，因此运行期覆盖 1.20.5 ~ 26.2。

## 常见问题

**Q：为什么最低只支持 1.20.5？**
1.20.5 才引入 `minecraft:damage` / `minecraft:max_damage` 组件与 `Damageable#hasMaxDamage()`。更旧的服务端会在加载阶段被 `api-version` 拦住（比运行期报 `NoSuchMethodError` 更友好）。

**Q：支持 Spigot 吗？**
不支持。插件依赖 Paper 专有 API（`Bukkit#isOwnedByCurrentRegion`、`JavaPlugin#getPluginMeta`、`Player#sendActionBar(Component)` 等），
Spigot 没有这些实现。Paper / Leaf / Purpur / Folia 等 Paper 系服务端均可。

**Q：Folia 能用吗？**
可以。`EntityDamageEvent` 兜底路径会先用 `Bukkit.isOwnedByCurrentRegion(player)` 判断区域归属，不在当前区域时跳过（避免跨线程访问玩家背包）；主路径 `PlayerItemDamageEvent` 本身就在玩家所属区域线程触发。去重表使用并发容器、配置使用 volatile 不可变快照。**注意**：跨区域并发压力的真实场景未做压测，上生产前建议自行验证。

**Q：一次伤害跨过多个阈值，会刷屏吗？**
不会。一次事件只发一条警告（取当前百分比命中的最高档位），并有 45ms 去重窗口防抖。

**Q：`status` 显示「声音解析：枚举 Sound.valueOf（老版服务端）」是什么意思？**
说明当前服务端是 1.20.5 ~ 1.21.3，没有声音注册表，插件自动改用枚举方式解析声音，功能正常。

**Q：支持自定义 `max_damage` 的数据包物品吗？**
支持。这正是本插件的设计出发点（如 Stellarity 铁头盔 `max_damage=407`）。物品名优先显示自定义名 / 数据包 `item_name` 翻译键，玩家客户端显示本地化名称。

## 验证情况（2026-09-24 实机实测）

| 验证项 | 覆盖范围 | 结果 |
|---|---|---|
| 加载 + 命令 + 重载 | Paper 1.20.5 / 1.20.6 / 1.21.1 / 1.21.4 / 1.21.8 / 1.21.11 / 26.1.2 / 26.2、Leaf 26.2、Purpur 1.20.6 / 1.21.8 / 26.2、Folia 26.2（共 13 个服务端）| 全部通过，无插件异常；`api-version: 1.20.5` 在全部 13 个服务端均被接受 |
| 声音解析 | 1.21.1（枚举模式）与 1.21.4 / 1.21.8 / 1.21.11 / 26.2（注册表模式）| 枚举名 / 点分名 / `minecraft:` 前缀三种写法全部解析成功；错误名称返回空且不抛异常 |
| 阈值告警与去重 | Paper 1.21.8 + mineflayer 机器人 | `已降至 49% (82/165)`、`仅剩 3%` 等消息正确；同一档位重复触发被去重（`already warned ... try next lower`）|
| 一次伤害跨多档 | 同上 | 每次事件只发一条告警，档位选择符合 DESIGN 第六节 |
| `ignore-items` | 同上 | 命中忽略列表时静默跳过 |
| `cooldown: 3` | 同上 | 同档位 3 秒后可重复告警 |
| 经验修补抑制 | 同上 | `has mending, percent=39 > 5, skipped` |
| 物品损坏提示 | 同上 | 触发 `!!! Iron Helmet 已损坏！` |
| 修复后清除标记 | 同上（`mending-only-warn-below: 90` + 背包槽 `data modify` 修复）| `[Debug] TestBot item repaired above 50%, warn marker reset` —— 已观测到标记被清除 |
| 重置后的再次告警 | 同上 | ⚠ 本轮用例未复现（修复写入会被同一 tick 的耐久消耗覆盖，物品未真正回到阈值以上），代码层面由上面的标记清除保证 |
| API 下限 | 全部源码用 `paper-api 1.20.5` 直接 `javac` 编译 | 通过（说明代码只用到 1.20.5 就存在的 API）|
| 最低支持版本事件链 | Paper 1.20.6 + 机器人真实战斗伤害 | 耐久事件链、配置快照、经验修补抑制、debug 日志均正常（`has mending, percent=49 > 5, skipped`）|

> 说明：冒烟矩阵的判据是「无加载错误 / 无 API 拒绝 / 无插件异常类」（检查 Could not load、Unsupported API、
> NoSuchMethodError、NoClassDefFoundError、InvalidPlugin、does not support Folia），并逐端核对启用日志、
> status / reload / help 输出；各端完整 boot.log 未长期保留（测试目录在跑完后清理）。

> 复现方法：把各版本服务端放在独立目录、`online-mode=false`、`level-type=minecraft:flat`，放入本插件 jar，
> 通过服务端控制台执行 `hdura status` / `hdura reload` / `hfcatdurabilityalert help`；耐久相关用例可用
> `/give`、`/item replace`、`/damage`、`/summon` 或真实战斗触发。

**Q：物品修好之后还会再提醒吗？**
会。物品耐久回升到上次告警档位以上时，插件会清除该物品上的告警标记（日志可见 `item repaired above N%, warn marker reset`），
之后再次跌到阈值以下会重新告警。这一点对带「经验修补」的物品同样成立（修补阈值不再会“吃掉”标记）。

## 设计文档

实现细节、事件时序、防重复机制与兼容适配说明见 [DESIGN.md](DESIGN.md)。

## 许可证

[MIT](LICENSE) © 2026 FCelestial (狐魇星玖)

参考项目：[spommerening/ToolWarn](https://github.com/spommerening/ToolWarn) (MIT)
