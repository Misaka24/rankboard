# RankBoard 2.0.0：单文件双加载器构建与验证

## 2.1.0 合并说明

本分支已把上游 2.0.0 的单文件双加载器构建与本分支的菜单、历史分片、日期范围、生物受害榜等特性合并，版本号更新为 2.1.0，并新增 Minecraft 26.3 构建目标。构建方式与本文一致：`scripts/build-unified.ps1`（底层 `gradlew -p unified :assemble`）。下文 2.0.0 的文件名、SHA-256 与测试记录保留为上游历史记录。

## 交付范围

一个直接构建的 JAR，面向 Fabric / NeoForge：Minecraft 1.21、1.21.1–1.21.11、26.1、26.1.1、26.1.2、26.2、26.3。不是承诺兼容未来版本，也不是将已有成品 JAR 合并。

交付文件：`mod-builds/20261018-130726/rankboard-2.0.0+mc1.21-26.3+2610181307.jar`。

SHA-256：`4EE5FFBD7E434E79D8CEA0D79F8B3DB8506AAF678ED277A4ADB9F2B6366AEA4E`。

版本号已更新为 2.0.0，JAR 文件名已移除 `+fabric-neoforge`。推送/PR 自动构建已切换为一次直接构建一个统一 JAR；本地发布动作尚未执行。

## 架构与技术选择

参考 [MultiLoader-Template](https://github.com/jaredlll08/MultiLoader-Template) 的公共逻辑与加载器适配层分离设计；使用 [TinyRemapper](https://github.com/FabricMC/tiny-remapper) 直接映射 Fabric 编译类目录。没有增加运行期跨加载器桥接依赖。

统一入口只在启动时识别加载器和游戏版本，选择唯一实现。三组实现分别是 Fabric 1.21、NeoForge 1.21、26.x Mojang 映射；业务源码由现有维护源生成隔离命名空间，不另行维护三份副本。配置、指令、排行榜顺序与语言条目由功能一致性脚本验证。

Gradle 从源码编译三组 class 目录，一次根 `jar` 任务输出交付文件；任务图禁止中间子项目 JAR，没有嵌套 JAR。所有 class 使用 Java 21 字节码（major 65），26.x 编译时仍使用 JDK 25 读取对应 SDK，防止旧版加载器扫描未激活类时遇到 Java 25 字节码。

各组 Mixin 独立放在 `unified.mixins` 命名空间，通过插件只激活当前组；普通业务类不放入 Mixin 保留包。

## 根因修复记录

【BUG表象】：初次统一包在启动时触发 `IllegalClassLoadError`。

【真实根因】：Mixin 配置将整个实现包声明为 Mixin 包，普通业务类被 Mixin 的保留包规则禁止加载，并非业务类缺失。

【修复方案】：将六个 Mixin 移入专用隔离包，同时保持实现命名空间和动态选择一致；没有捕获异常跳过初始化。

【修改代码】：参见 `unified/namespace-sources.gradle`、`UnifiedMixinPlugin.java` 与 `rankboard.mixins.json`；实现源码及构建入口已纳入本次推送。

【回归校验】：旧失败日志保留于 `.tmp/fabric-direct-smoke-20261018-123632/1.21.4/logs/latest.log`；修复候选使用同一 SHA-256 执行下述矩阵。

## 构建

使用 JDK 25 运行 Gradle，并提供 JDK 21 工具链：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-25.0.3'
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
$env:GRADLE_USER_HOME = 'C:/Users/28656/.gradle'
./scripts/build-unified.ps1 -Offline -Java21Home '.tmp/toolchain-temurin21/jdk-21.0.12.1+1'
```

底层入口必须是 `gradlew -p unified :assemble`（冒号不可省略）。成功产物按时间戳独立归档，不覆盖旧文件。

## 测试定义

1.21 系列：Java 21 启服、HTTP 网页、真实协议客户端登录、比较预览只读、差异列表、详细对比、确认后单玩家/全部应用、重复应用阻止、源文件变化阻止、取消、文件缺失边界。

26.x：Java 25 启服、HTTP 网页、生产比较指令上述流程；通过独立测试探针获取报告 ID。当前协议客户端不支持 26.x，因此不能将这些结果称为真实网络登录通过。

Fabric 26.3 另用 Carpet 实际假人测试：20 玩家、20 榜单接收者、混合榜单、同名假人 20 次召唤/退出，验证读作用域复用、统计周期切换、正常停服最终缓存保存。结果 `.tmp/fake-player-performance-20261018-124024/results.json`：榜单全刷新平均 1.17 ms，假人重入平均 8.16 ms，最大 12.52 ms；是本机合成负载，不代表真实服务器性能承诺。

静态检查：254 个 Java 21 扫描安全类、3 个实现族、1 个原生 NeoForge Mod 注解、无嵌套 JAR；34 个加载器/版本选择组合与未知版本拒绝测试；97 配置、77 指令、20 榜单、300 语言条目一致性检查。

## 矩阵结果

`scripts/verify-unified-matrix.ps1 -Artifact mod-builds/20261018-123855/rankboard-1.10.11+fabric-neoforge+mc1.21-26.3+2610181237.jar` 验证了全部 34 组，报告为 `.tmp/unified-matrix-20261018.json`：全部启服、网页、比较回归通过。

2.0.0 重建后逐一比较 254 个生产 class 文件：与上述完整矩阵使用的候选逐字节相同，无新增、删除或变化。因此既有完整矩阵覆盖当前代码。2.0.0 包另在 Fabric 和 NeoForge 上分别重测 1.21.4、26.3：1.21.4 使用真实登录，26.3 运行启动、网页、比较命令；NeoForge 26.3 另通过服内合成玩家 5 次进退回归。记录目录：Fabric `.tmp/fabric-direct-smoke-20261018-130748`、`130804`；NeoForge `.tmp/neoforge-direct-smoke-20261018-130843`、`130918`。

| Minecraft | Fabric | NeoForge | 真实网络登录 |
| --- | --- | --- | --- |
| 1.21、1.21.1–1.21.11（12 个版本） | 全部通过 | 全部通过 | 两加载器均通过；2.0.0 重测 1.21.4 |
| 26.1、26.1.1、26.1.2、26.2、26.3 | 全部通过 | 全部通过 | 未测试，协议客户端不支持；2.0.0 重测 26.3 |

原始结果目录：

- Fabric：`.tmp/fabric-direct-smoke-20261018-123857`、`124024`、`124245`、`124913`、`125133`（后四个同前缀）。
- NeoForge：`.tmp/neoforge-direct-smoke-20261018-123859`、`124026`、`124248`、`125318`（后三个同前缀）。

NeoForge 全部五个 26.x 版本另完成服内合成玩家测试：每版本连续 5 次实际加入 PlayerList、触发名字 Mixin、设置跳跃统计、请求侧边栏并断言收到目标与分数数据包、核对榜单包含玩家、退出并确认玩家列表清除。测试不依赖 Carpet，也不是 TCP 登录；探针只用于隔离测试，没有打入交付 JAR。

合成测试证据：`.tmp/neoforge-direct-smoke-20261018-125553/26.3/synthetic-player-smoke.log` 和 `.tmp/neoforge-direct-smoke-20261018-125646/<版本>/synthetic-player-smoke.log`。汇总 `.tmp/unified-synthetic-20261018.json`；同一交付 SHA-256 再次核验通过。

安装时移除旧 RankBoard JAR，只放这一个文件；Fabric 仍需对应版本的 Fabric API。不要把测试探针或旧失败候选放入正式服务器。测试覆盖专用服务端，不等于所有第三方模组组合、客户端或未来版本都已验证。
