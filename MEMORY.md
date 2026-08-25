# HFcatDurabilityAlert 项目记忆

## 架构
- 包：`io.github.fcestial.hfcatdurabilityalert`，三个类：主类、DurabilityListener、AlertCommand
- 事件链：PlayerItemDamageEvent（主，传 event.getDamage() 预测扣减后耐久）+ EntityDamageEvent（盔甲兜底，extraDamage=0）+ PlayerItemBreakEvent（最终警告，尊重 ignore-items），全部 MONITOR + ignoreCancelled（Break 不可取消）
- 防重复：PDC key `hfcatdurabilityalert:last_warn_threshold`，**必须写入玩家身上真实物品**（事件 ItemStack 是包装副本）；同 tick 同玩家同槽位去重（lastWarnTick Map，主线程独占）

## 关键决策
- 耐久读取铁则：hasMaxDamage()→getMaxDamage()，getDamage() 是已损失，剩余=max-damage，绝不 getDurability()/type.getMaxDurability() 唯一来源
- 多阈值级联：降序遍历 + 已警告档位 continue + threshold>=lastWarned 判定；每个阈值各触发一次（DESIGN.md 4.1/4.2/第六节已同步此语义）
- 修复重置：percent > lastWarned 时清除 PDC 标记（DESIGN 4.2 方案A）；重置后可能立即以最高<=当前百分比的档位重发警告（如 30%→45% 触发 50 档），DESIGN 4.2 已写入行为说明
- Mending：mendingActive = 有修补 && mending-only-warn-below>0；生效时只遍历 <= 修补阈值的档位（0 与 -1 均禁用）
- 消息输出全部走 Adventure：自实现 translateColorCodes（& 与 &#RRGGBB → § 序列）；LegacyComponentSerializer 用显式 builder：character('§') + hexColors() + useUnusualXRepeatedCharacterHexFormat()（不依赖 Paper 运行时 Provider 注入，脱离 Paper 环境也正确）
- 声音：Registry.SOUND_EVENT.get(NamespacedKey.minecraft(name))，禁用 Sound.valueOf；参数钳制 volume [0,2] / pitch [0.5,2]
- cooldown>0 尚未实现（仅 -1 每阈值一次），reload 时对配置告警
- 线程：非 Folia 主线程假设（Bukkit.getCurrentTick + HashMap），类注释已记录 Folia 迁移注意事项
- debug 日志用 Supplier<String> 惰性求值，开关关闭时零字符串拼接

## 踩坑记录
- **gradle.properties 双平台 JDK 路径不能混写**：Linux 上 `F:/...` 会被解析为 URL → InvalidUserDataException 构建失败。方案：gradle.properties 只放 Linux 路径；Windows 路径由 build.bat 通过 -Dorg.gradle.java.installations.paths 传入
- Gradle 8.12 必须 JDK 21 运行；paper-api 26.2 是 class version 69，必须 JDK 25 编译（toolchain）
- Paper 26.2 的 paper-api 坐标：`io.papermc.paper:paper-api:26.2.build.+`（117-stable 可用）；服务端 jar 下载用 fill-data.papermc.io/v1/objects/<sha256>/...
- plugin.yml api-version: '26.2' 是 Paper 26.x 官方写法（year-based 版本号）
- Player 通过 CommandSender extends Audience 获得 Adventure sendMessage/sendActionBar/showTitle
- PlayerItemDamageEvent 在扣减前触发（MONITOR 读到扣减前状态），必须用 event.getDamage() 预测
- 服务端无头环境 LANG=C 时控制台中文日志显示为 ?（仅显示问题，非插件 bug）；测试用 tmux + LANG=C.UTF-8 + -Dfile.encoding=UTF-8
- Adventure 5.2.0 的 Title.Times.times(Duration,Duration,Duration) 可用；legacySection() 默认 hexColours=false，必须显式 builder 开启

## 进行中的工作
- 2026-08-25：骨架→完整实现；三轮子代理审查完成（方案符合性 D-1~D-11、API 边界 14 项、代码质量 22 项、最终验收 N1~N6），MAJOR/BLOCKER 全部修复；DESIGN.md 已同步级联/重置语义；真实 Paper 26.2 服务端冒烟测试通过（enable/status/reload/help 全部正常）
