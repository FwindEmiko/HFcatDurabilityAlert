# HFcatDurabilityAlert 项目记忆

## 架构
- 包：`io.github.fcestial.hfcatdurabilityalert`，四个类：主类、DurabilityListener、SoundResolver、AlertCommand
- 事件链：PlayerItemDamageEvent（主，传 event.getDamage() 预测扣减后耐久）+ EntityDamageEvent（盔甲兜底，extraDamage=0，先做 Folia 区域归属判断）+ PlayerItemBreakEvent（最终警告，尊重 ignore-items），全部 MONITOR + ignoreCancelled（Break 不可取消）
- 防重复：PDC key `hfcatdurabilityalert:last_warn_threshold`（-1 一次性）+ `hfcatdurabilityalert:last_warn_times`（正数冷却，按阈值存时间戳字符串），**必须写入玩家身上真实物品**（事件 ItemStack 是包装副本）；同玩家同槽位 45ms 窗口去重（ConcurrentHashMap + System.nanoTime，不依赖 Bukkit.getCurrentTick）
- 配置：onEnable/reload 时解析为**不可变快照 Settings**（内部类，volatile 发布），事件热路径零配置解析

## 兼容性（2026-09 公开发布前确定）
- 运行时目标：Paper / Leaf / Purpur 1.20.5 ~ 26.2；Folia 26.2 声明 folia-supported: true（已加区域守卫）
- 编译基线 paper-api **1.20.6-R0.1-SNAPSHOT**（1.20.5 的 POM 依赖 adventure-bom:4.17.0-SNAPSHOT 已被仓库清理，无法解析），`options.release=21` → Java 21 字节码，Java 21/25 服务端都能加载
- plugin.yml：`api-version: '1.20.5'`（不支持的旧服务端由服务端直接拒绝加载，好过运行期 NoSuchMethodError）
- 声音兼容铁则：1.20.5~1.21.3 只有枚举 `Sound.valueOf("ENTITY_EXPERIENCE_ORB_PICKUP")`，没有 Registry.SOUND_EVENT；1.21.4+ 注册表键是**点分名**（entity.experience_orb.pickup），用下划线名查注册表返回 null（旧实现因此静默失效）。统一走 SoundResolver 三路兜底
- 构建：Gradle 9.7.1 wrapper + 纯 java 插件（**无 Shadow**，零依赖不需要），移除 gradle.properties 里的硬编码 JDK 路径

## 关键决策
- 耐久读取铁则：hasMaxDamage()→getMaxDamage()，getDamage() 是已损失，剩余=max-damage
- 多阈值级联：降序遍历 + 已警告档位 continue + threshold>=lastWarned 判定
- 修复重置：percent > lastWarned 时清除 PDC 标记；重置后可能立即以最高<=当前百分比的档位重发警告
- Mending：mendingActive = 有修补 && mending-only-warn-below>0；生效时只遍历 <= 修补阈值的档位
- 消息输出全部走 Adventure（自实现 translateColorCodes；LegacyComponentSerializer 显式 builder 开 hexColors）
- 线程：并发容器 + volatile 快照 + 区域归属守卫；PDC 写在玩家所属区域线程

## 踩坑记录
- **Paper 26.1/26.2 是年份版本号，等于 MC 26.1/26.2**（不是 1.21.x）；leaf 26.2 / purpur 26.2 同理
- **Registry.SOUND_EVENT 在 1.21.4 才有**；1.20.5~1.21.3 上访问会 NoSuchFieldError
- **注册表声音键是点分名**，枚举名（大写下划线）与点分名不是一回事，用错静默无声
- Paper 26.2 要求 Java 25 运行；1.20.5~1.21.11 要求 Java 21；class version 65 vs 69 是关键
- `Math.clamp` 需要 Java 21+；`String.isBlank` 需要 Java 11+
- `getServer().getPluginMeta()` / `Bukkit.getMinecraftVersion()` / `Bukkit.isOwnedByCurrentRegion()` 在 1.20.5 均存在（已 javap 核对）
- 1.20.5 的 paper-api POM 依赖已失效的 adventure-bom 快照 → 编译基线必须用 1.20.6
- Paper 服务器 nohup 启动时 stdin 是 /dev/null，控制台命令注入需用 FIFO + `exec 3<>console`
- **/damage 命令不扣盔甲耐久**（实测 1.21.8：helmet 的 minecraft:damage 组件在 /damage 5、/damage 10 后仍未变），
  端到端测试必须用生物攻击等真实伤害源，否则只会走 event.getDamage() 预测路径、耐久实际不变、永远不损坏
- 服务端控制台命令的**输出只进服务端日志**（不在脚本 stdout），自动化断言要 grep boot.log
- Gradle 9 偶发 `Could not store compilation result`（增量编译缓存问题，非源码错误）→ 删掉 build/.gradle 后 clean build 即可
- 服务端无头环境 LANG=C 时控制台中文日志显示为 ?（仅显示问题）

## 进行中的工作
- 2026-09：公开发布前多版本适配。已完成：SoundResolver 兼容层、配置快照、Folia 区域守卫、构建现代化（Gradle 9.7.1 / release 21 / 去 Shadow / build.bat 重写 / MIT LICENSE）、plugin.yml api-version 下调至 1.20.5、版本号统一 1.2.0
- 已在真实服务端验证：Paper 1.20.5/1.20.6/1.21.1/1.21.4/1.21.8/1.21.11/26.1.2/26.2 + Leaf 26.2 + Purpur + Folia（详见 README 兼容矩阵）
- 验证证据（2026-09-24）：
  - 冒烟矩阵 13/13：Paper 1.20.5/1.20.6/1.21.1/1.21.4/1.21.8/1.21.11/26.1.2/26.2、Leaf 26.2、Purpur 1.20.6/1.21.8/26.2、Folia 26.2
    （加载 + /hdura status + reload + help + 未知子命令，无插件异常）
  - SoundResolver 真机实测：1.21.1 报枚举模式，1.21.4/1.21.8/1.21.11/26.2 报注册表模式；枚举名/点分名/minecraft: 前缀均可解析，错拼返回 NULL
  - 端到端（mineflayer + 僵尸真实伤害，Paper 1.21.8）：49% 告警且不重复、3% 级联、ignore-items 跳过、cooldown=3 重复告警、mending 抑制、
    损坏提示 '!!! Iron Helmet 已损坏！' 触发
  - 1.20.5 API 下限静态验证：全部源码用 1.20.5 paper-api jar 直接 javac 通过（exit 0）
- 已修复的审查发现：满耐久(damage=0)清理残留 PDC 标记；**修复重置逻辑必须放在 ignore-items / mending 提前返回之前**，
  否则带修补物品回升到 mending-only-warn-below 之上就直接 return、旧标记残留 → 该物品之后再跌回低耐久也「永远不再告警」（默认配置即受影响）
- 复核与发布就绪（2026-09-24 收尾）：
  - 冒烟矩阵最终版 **13/13 全通过**（Paper 1.20.5/1.20.6/1.21.1/1.21.4/1.21.8/1.21.11/26.1.2/26.2 + Leaf 26.2 + Purpur 1.20.6/1.21.8/26.2 + Folia 26.2），
    对 NoSuchMethod/NoClassDefFound/InvalidPlugin/Unsupported API/does not support Folia 的检查均为 0
  - 声音解析真机矩阵（1.21.1 枚举模式 / 1.21.4+ 注册表模式）：枚举名、点分名、minecraft: 前缀三种写法全部解析成功
  - 修复重置运行时证据（Paper 1.21.8 + 机器人）：`[Debug] TestBot item repaired above 50%, warn marker reset`
  - README 增加「验证情况」表；DESIGN 增加 §10.5 修复重置的运行时验证（步骤 + 证据）
  - 测试方法论踩坑：装备槽 equipment.*.components 的 data modify 改不动耐久，必须用 Inventory[{Slot:0b}]；
    且物品持续被攻击/挖掘时写入会被同 tick 损害覆盖，写入前需暂停耐久消耗
  - Gradle wrapper 首次构建需联网下载 gradle-9.7.1-bin.zip（约 130MB），本机沙箱内下载曾失败；本地 Gradle 9.7.1 构建始终可用
- 第三轮独立复审（子代理 bc10f424）结论：无 BLOCKER；1 MAJOR + 9 MINOR + 7 NIT。已处理：
  - **MAJOR（文档夸大）**：README/DESIGN 原写「修复后重新告警」为实测，实际只观测到 `warn marker reset`（标记清除），重置后的第二次告警未复现
    → 已改为如实分列「修复后清除标记（已观测）」与「重置后再次告警（本轮未复现，由标记清除保证）」
  - 源码类注释与 plugin.yml 的 folia-supported 声明矛盾 → 注释改为「故声明 folia-supported: true」
  - config.yml 头部署名/私人 QQ 与其他文件不一致 → 统一为「狐魇星玖 - FCelestial」并删除 QQ
  - config.yml 与 plugin.yml 的平台列表补 Folia
  - DESIGN 修复重置前置条件提醒写进 §4.2；§10/§11 编号重复修正；内建兜底文案与 {item} 本地化说明补齐
  - README：Spigot 不支持理由改为 Paper 专有 API；status 文案改为「配置文件当前值」；补充冒烟判据与 boot.log 保留说明
  - `gradlew` 权限 711 → 755
  - 产出干净发布包 `/root/compat-lab/dist/HFcatDurabilityAlert-1.2.0.zip`（jar + README + DESIGN + MEMORY + LICENSE，无 build/.gradle/.idea）
  - 未处理（并入文档说明）：13 端 boot.log 跑完即删（结论依赖过滤后的结果文件）；wrapper 首次构建需联网下载 Gradle

