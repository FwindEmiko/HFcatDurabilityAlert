<div align="center">

<img src="https://cdn.jsdelivr.net/gh/FwindEmiko/HFcatDurabilityAlert@main/assets/banner.png" alt="HFcatDurabilityAlert" width="100%">

# HFcatDurabilityAlert

**装备耐久度警告插件 —— 直接读 1.20.5+ 数据组件，算得准**

<a href="LICENSE"><img src="https://img.shields.io/github/license/FwindEmiko/HFcatDurabilityAlert?style=for-the-badge&color=7BD8A8&labelColor=555" alt="license"></a>
<a href="https://github.com/FwindEmiko/HFcatDurabilityAlert/releases"><img src="https://img.shields.io/badge/Paper-1.20.5_~_26.2-FFA23A?style=for-the-badge&labelColor=555" alt="paper"></a>
<a href="https://github.com/FwindEmiko/HFcatDurabilityAlert/commits/main"><img src="https://img.shields.io/github/last-commit/FwindEmiko/HFcatDurabilityAlert?style=for-the-badge&color=37C8B0&labelColor=555&logo=git" alt="last commit"></a>
<img src="https://img.shields.io/badge/Java-21+-5FA04E?style=for-the-badge&labelColor=555&logo=openjdk&logoColor=white" alt="java">
<img src="https://img.shields.io/badge/依赖-零-556080?style=for-the-badge&labelColor=555" alt="zero deps">

[📦 下载](https://github.com/FwindEmiko/HFcatDurabilityAlert/releases) · [📐 设计文档](DESIGN.md) · [🐛 问题反馈](https://github.com/FwindEmiko/HFcatDurabilityAlert/issues)

<sub>作者：狐魇星玖 (FCelestial) · MiragEdge</sub>

</div>

---

<img src="https://cdn.jsdelivr.net/gh/FwindEmiko/HFcatDurabilityAlert@main/assets/art_why.png" alt="为什么" width="100%">

## 为什么需要它

大多数耐久提醒插件用 **物品类型的基础最大耐久** 估算百分比：

```java
// 常见做法 —— 遇到数据包/自定义物品就算错
int max = material.getMaxDurability();   // 铁头盔永远是 165
int left = max - item.getDurability();   // 已弃用，且拿不到真实上限
```

问题是：**数据包可以改写物品的 `max_damage`**。比如 Stellarity 把铁头盔的上限改成 407，
上面这段代码会一直按 165 算 —— 玩家看到「还剩 40%」，实际只剩 16%，工具就突然断了。

本插件直接读 1.20.5+ 的**数据组件**：

```java
// 本插件做法 —— 拿的是物品真实状态
int max = meta.hasMaxDamage() ? meta.getMaxDamage() : fallback;
int left = max - ((Damageable) meta).getDamage();
```

| | 常见做法 | 本插件 |
| :--- | :--- | :--- |
| 读取来源 | `Material#getMaxDurability()` | `minecraft:damage` / `minecraft:max_damage` 组件 |
| 数据包改上限 | ❌ 算错 | ✅ 正确 |
| 已弃用 API | 常用 `getDurability()` | 不用 |
| 触发方式 | 多数靠定时轮询 | 事件驱动，耐久真变化才触发 |

---

## 兼容矩阵

<img src="https://cdn.jsdelivr.net/gh/FwindEmiko/HFcatDurabilityAlert@main/assets/compat.png" alt="服务端兼容矩阵" width="100%">

**同一份 jar 通吃下列 13 个服务端**（2026-09 实机验证：加载 + 命令 + 重载均通过）：

| 服务端 | 版本 | 最低 Java | 状态 |
| :--- | :--- | :---: | :---: |
| **Paper** | 1.20.5 / 1.20.6 / 1.21.1 / 1.21.4 / 1.21.8 / 1.21.11 / 26.1.2 / 26.2 | 21 / 25 | ✅ |
| **Leaf** | 26.2 | 25 | ✅ |
| **Purpur** | 1.20.6 / 1.21.8 / 26.2 | 21 / 25 | ✅ |
| **Folia** | 26.2 | 25 | ✅ |
| Spigot / CraftBukkit | 任意 | — | ❌ 依赖 Paper 的 Adventure 实现 |
| Paper | < 1.20.5 | — | ❌ 组件化耐久 API 不存在，加载阶段即被 `api-version` 拦下 |

**为什么能跨这么多版本？** 构建产物固定为 **Java 21 字节码**（`options.release = 21`），
所以 1.21.x（Java 21）与 26.x（Java 25）服务端都能加载；源码只使用 1.20.5 就已存在的 API，
并用 1.20.5 的 paper-api 直接 `javac` 校验过。

> ⚠️ 不能在 **Java 17** 上运行 —— Paper 1.20.5+ 本身就要求 Java 21。

---

## 功能

<img src="https://cdn.jsdelivr.net/gh/FwindEmiko/HFcatDurabilityAlert@main/assets/features.png" alt="功能" width="100%">

- **阈值列表无限扩展** —— 每个档位可写独立文案与颜色
- **三种输出通道** —— 聊天 / 动作栏 / Title 可独立开关，可配警告音效
- **PDC 防重复** —— 每个档位只提醒一次；物品修好后自动清除标记，能重新触发
- **`cooldown: 秒数`** —— 同一档位冷却后仍可重复提醒
- **经验修补（Mending）抑制** —— 有修补的物品延迟到更低耐久才提醒
- **损坏单独提示** —— 物品彻底损坏时额外提醒（可留空关闭）
- **忽略列表** —— 例如 `elytra` / `shield` 不打扰
- **自检与热重载** —— `/hdura status` 显示服务端版本、Java 版本、声音解析模式、当前生效配置；`/hdura reload` 重载

---

## 阈值是怎么触发的

<img src="https://cdn.jsdelivr.net/gh/FwindEmiko/HFcatDurabilityAlert@main/assets/threshold.png" alt="阈值机制" width="100%">

```yaml
warnings:
  thresholds: [50, 30, 15, 10, 5]
  cooldown: -1                  # -1：每个档位只提醒一次，修好后可重新触发
                                # >0 ：同一档位冷却 N 秒后可再提醒
                                # 0 或 < -1：视为无效，按 -1 处理并给出提示
```

**几条容易踩的规则：**

- **一次伤害跨过多档，只提醒一档** —— 取命中的最高档位，不会刷屏；另有 45ms 去重窗口防抖
- **阈值写 `0` 永远不会触发** —— 剩余为 0 时物品已损坏，走 `break-message`
- **修好之后能重新触发** —— 耐久回升到上次告警档位以上时清除标记（日志可见 `warn marker reset`）
- **经验修补物品不再「吃掉」标记** —— 配合 `mending-only-warn-below` 一起工作

---

<img src="https://cdn.jsdelivr.net/gh/FwindEmiko/HFcatDurabilityAlert@main/assets/art_install.png" alt="安装" width="100%">

## 安装

<img src="https://cdn.jsdelivr.net/gh/FwindEmiko/HFcatDurabilityAlert@main/assets/quickstart.png" alt="快速开始" width="100%">

1. 确认服务端是 **Paper / Leaf / Purpur / Folia 1.20.5 及以上**
2. 把 `HFcatDurabilityAlert-1.2.0.jar` 放进服务端 `plugins/` 目录
3. 重启服务端（或 `/reload confirm`）
4. 首次启动生成 `plugins/HFcatDurabilityAlert/config.yml`

```bash
cp HFcatDurabilityAlert-1.2.0.jar server/plugins/
# 重启后
hdura status     # 看服务端版本 / Java 版本 / 声音解析模式 / 当前生效配置
```

---

## 命令与权限

| 命令 | 说明 |
| :--- | :--- |
| `/hfcatdurabilityalert reload` | 重载配置 |
| `/hfcatdurabilityalert status` | 查看运行状态（版本 / 服务端 / 声音模式 / 配置文件当前值） |
| `/hfcatdurabilityalert help` | 显示帮助 |

**别名**：`/hdura`、`/hdalert`

| 权限 | 默认 | 说明 |
| :--- | :---: | :--- |
| `hfcatdurabilityalert.admin` | `op` | 重载配置与查看状态 |
| `hfcatdurabilityalert.use` | `true` | 接收耐久警告 |

---

## 配置要点

完整注释在 `config.yml` 里，这里只说最容易踩的三个坑。

### ① 声音名怎么写

```yaml
messages:
  # 老式枚举名（1.20.5 ~ 26.2 通用，推荐）
  warning-sound: "ENTITY_EXPERIENCE_ORB_PICKUP:0.8:1.2"
  # 命名空间键（1.21.4+ 才支持点分名）
  # warning-sound: "entity.experience_orb.pickup:0.8:1.2"
  # warning-sound: "minecraft:entity.experience_orb.pickup:0.8:1.2"
```

格式为 `声音名[:音量[:音调]]`，音量钳制 `0.0 ~ 2.0`，音调钳制 `0.5 ~ 2.0`。

**为什么不能只写点分名？** 1.20.5 ~ 1.21.3 没有声音注册表（`Registry.SOUND_EVENT` 是 1.21.4 才有的），
此时只能走枚举；而 1.21.4+ 的注册表键又是点分名。插件内部**按服务端能力自动解析**，
所以写枚举名在所有版本都可用。配置写错时启动日志会明确提示，不会静默失效。

### ② 提醒位置与损坏提示

```yaml
messages:
  send-chat: true       # 聊天框
  send-actionbar: true  # 动作栏
  send-title: false     # 屏幕中央 Title
  break-message: "&4&l!!! &f{item} &4&l已损坏！"   # 留空 = 既不提示也不播放声音
```

### ③ 消息占位符与颜色

| 占位符 | 含义 |
| :--- | :--- |
| `{item}` | 物品名（有自定义名优先显示自定义名） |
| `{slot}` | 装备部位（主手 / 副手 / 头盔 / 胸甲 / 护腿 / 靴子） |
| `{durability}` | 剩余耐久点数 |
| `{max}` | 最大耐久点数 |
| `{percent}` | 剩余百分比（整数） |
| `{threshold}` | 触发的阈值 |

颜色支持 `&a` `&c` `&l` 等传统代码与 `&#RRGGBB` 十六进制。
**不支持 MiniMessage 标签。**

---

## 构建

要求 **JDK 21 或更高**（Gradle 9.7.1 wrapper 可在 JDK 21 ~ 25 上运行）。

```bash
./gradlew build     # Linux / macOS
build.bat           # Windows，等价于 gradlew.bat clean build
```

产物：`build/libs/HFcatDurabilityAlert-<version>.jar`。
版本号由 `build.gradle.kts` 注入 `plugin.yml`，不会出现「jar 名与插件版本号不一致」。

> **首次构建会联网下载 Gradle 9.7.1（约 130 MB）**。网络慢或访问不了 `services.gradle.org` 时，
> 可改用本机已装的 Gradle 9.x：`gradle clean build`。
> 本项目不依赖任何 Gradle 插件仓库中的插件（只用内置 `java` 插件），所以离线环境同样可行。

编译基线固定为 `paper-api:1.20.6-R0.1-SNAPSHOT`（`compileOnly`，不打进 jar），`options.release = 21`。

---

## 常见问题

<details>
<summary><b>为什么最低只支持 1.20.5？</b></summary>

1.20.5 才引入 `minecraft:damage` / `minecraft:max_damage` 组件与 `Damageable#hasMaxDamage()`。
更旧的服务端会在**加载阶段**被 `api-version` 拦住 —— 比运行期抛 `NoSuchMethodError` 友好得多。

</details>

<details>
<summary><b>支持 Spigot 吗？</b></summary>

不支持。插件依赖 Paper 专有 API（`Bukkit#isOwnedByCurrentRegion`、`JavaPlugin#getPluginMeta`、
`Player#sendActionBar(Component)` 等），Spigot 没有这些实现。Paper / Leaf / Purpur / Folia 均可。

</details>

<details>
<summary><b>Folia 能用吗？区域安全是怎么做的？</b></summary>

可以，插件声明 `folia-supported: true`。

`EntityDamageEvent` 兜底路径会先用 `Bukkit.isOwnedByCurrentRegion(player)` 判断区域归属，
不在当前区域时跳过（避免跨线程访问玩家背包）；主路径 `PlayerItemDamageEvent` 本身就在玩家所属区域线程触发。
去重表用并发容器，配置用 volatile 不可变快照。

> ⚠️ 跨区域并发压力的真实场景未做压测，上生产前建议自行验证。

</details>

<details>
<summary><b>一次伤害跨过多个阈值，会刷屏吗？</b></summary>

不会。一次事件只发一条警告（取当前百分比命中的**最高档位**），并有 45ms 去重窗口防抖。

</details>

<details>
<summary><b><code>status</code> 显示「声音解析：枚举 Sound.valueOf（老版服务端）」是什么意思？</b></summary>

说明当前服务端是 1.20.5 ~ 1.21.3，还没有声音注册表，插件自动改用枚举方式解析声音 —— 功能正常，不用管。

</details>

<details>
<summary><b>支持自定义 <code>max_damage</code> 的数据包物品吗？</b></summary>

支持，这正是本插件的设计出发点（如 Stellarity 铁头盔 `max_damage=407`）。
物品名优先显示自定义名 / 数据包 `item_name` 翻译键，玩家客户端显示本地化名称。

</details>

<details>
<summary><b>物品修好之后还会再提醒吗？</b></summary>

会。耐久回升到上次告警档位以上时，插件会清除该物品上的告警标记（日志可见
`item repaired above N%, warn marker reset`），之后再次跌破阈值会重新告警。
带「经验修补」的物品同样成立。

</details>

---

## 验证情况

<details>
<summary><b>2026-09 实机验证矩阵（13 个服务端，点击展开）</b></summary>

| 验证项 | 覆盖范围 | 结果 |
| :--- | :--- | :--- |
| 加载 + 命令 + 重载 | Paper 1.20.5 / 1.20.6 / 1.21.1 / 1.21.4 / 1.21.8 / 1.21.11 / 26.1.2 / 26.2、Leaf 26.2、Purpur 1.20.6 / 1.21.8 / 26.2、Folia 26.2（共 13 个） | 全部通过，无插件异常；`api-version: 1.20.5` 均被接受 |
| 声音解析 | 1.21.1（枚举模式）与 1.21.4 / 1.21.8 / 1.21.11 / 26.2（注册表模式） | 枚举名 / 点分名 / `minecraft:` 前缀三种写法全部解析成功；错误名称返回空且不抛异常 |
| 阈值告警与去重 | Paper 1.21.8 + mineflayer 机器人 | `已降至 49% (82/165)`、`仅剩 3%` 等消息正确；同档位重复触发被去重 |
| 一次伤害跨多档 | 同上 | 每次事件只发一条告警，档位选择符合设计 |
| `ignore-items` | 同上 | 命中忽略列表时静默跳过 |
| `cooldown: 3` | 同上 | 同档位 3 秒后可重复告警 |
| 经验修补抑制 | 同上 | `has mending, percent=39 > 5, skipped` |
| 物品损坏提示 | 同上 | 触发 `!!! Iron Helmet 已损坏！` |
| 修复后清除标记 | 同上 | 观测到 `warn marker reset` |
| 重置后再次告警 | 同上 | ⚠ 本轮用例未复现（修复写入会被同 tick 的耐久消耗覆盖），代码层面由标记清除保证 |
| API 下限 | 全部源码用 `paper-api 1.20.5` 直接 `javac` 编译 | 通过（说明只用到了 1.20.5 就存在的 API） |
| 最低支持版本事件链 | Paper 1.20.6 + 机器人真实战斗伤害 | 耐久事件链、配置快照、修补抑制、debug 日志均正常 |

> 判据：无加载错误 / 无 API 拒绝 / 无插件异常类（检查 `Could not load`、`Unsupported API`、
> `NoSuchMethodError`、`NoClassDefFoundError`、`InvalidPlugin`、`does not support Folia`），
> 并逐端核对启用日志与 status / reload / help 输出。各端完整 boot.log 未长期保留（测试目录跑完后清理）。

**复现方法**：各版本服务端放独立目录，`online-mode=false`、`level-type=minecraft:flat`，
放入 jar，用控制台执行 `hdura status` / `hdura reload` / `hfcatdurabilityalert help`；
耐久用例可用 `/give`、`/item replace`、`/damage`、`/summon` 或真实战斗触发。

</details>

---

## 设计文档与许可

实现细节、事件时序、防重复机制与兼容适配说明见 **[DESIGN.md](DESIGN.md)**。

本项目采用 **MIT License**，详见 [LICENSE](LICENSE) © 2026 FCelestial (狐魇星玖)。

参考项目：[spommerening/ToolWarn](https://github.com/spommerening/ToolWarn)（MIT）
—— 借鉴了阈值列表与热重载思路，本插件改为以 `PlayerItemDamageEvent` 为主路径、
PDC 防重复、并新增经验修补抑制与忽略列表。

