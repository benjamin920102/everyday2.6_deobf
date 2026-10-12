# EveryDay v2.6 Beta — SourceFile 类名还原与 Native / JNI 分析

> **文档性质：静态分析与候选映射整理（简体中文）**  
> 合并来源：`everyday_native_analysis(1).md`、`sourcefile_rename_candidates (2).csv`；使用 `EveryDay-v2_6-beta-NOP-clean-stackmap(6).jar` 核对 Class、SourceFile、Native 标记及文件内容。  
> **注意：本文没有将类名映射实际应用到 JAR，也没有将 Native 方法还原为 Java 字节码。**

## 目录

- [一、项目与验证结果](#一项目与验证结果)
- [二、SourceFile 类名还原说明](#二sourcefile-类名还原说明)
- [三、Native 与 JNI 结构分析](#三native-与-jni-结构分析)
- [四、Native 方法及潜在调用者清单](#四native-方法及潜在调用者清单)
- [五、Runtime 提取结果与限制](#五runtime-提取结果与限制)
- [六、反混淆及 Native 研究优先级](#六反混淆及-native-研究优先级)
- [七、完整 SourceFile 映射表（210 条）](#七完整-sourcefile-映射表210-条)
- [八、重命名前的校验建议](#八重命名前的校验建议)
- [九、结论与证据边界](#九结论与证据边界)

## 一、项目与验证结果

### 1.1 文件信息

| 项目 | 结果 | 来源 |
|---|---|---|
| 目标 JAR | `EveryDay-v2_6-beta-NOP-clean-stackmap(6).jar` | 实际检查 |
| `.class` 总数 | **1,007** | 实际检查 |
| `obf/` 下的 Class | **983** | 实际检查 |
| 带 `SourceFile` 属性的 Class | **634**，其中 `obf/` 下 **610** | 实际检查 |
| `obf/` 下无 `SourceFile` 的 Class | **373** | 实际检查 |
| 可核对的 SourceFile 重命名候选 | **210** | CSV 与实际 JAR 逐条对照 |
| 候选目标名称冲突 | **0**（包括候选之间、与当前 JAR Class 路径冲突；候选之间也无不区分大小写的重名） | 实际检查 |
| 包含 `ACC_NATIVE` 的 Class / 方法 | **4 类 / 50 方法** | 实际检查 |
| 候选映射是否直接包含 Native 方法声明所在类 | **否，0 类** | 实际检查 |
| 内嵌 DLL | `native/x64-windows.dll`（**13,024,256 字节 / 12.42 MiB**） | 实际检查 |
| 运行环境线索 | Fabric / Minecraft **1.21.1**；绝大多数 Class 为 **Java 21** 字节码（major 65） | JAR Manifest / Class 版本 |

**JAR SHA-256**

```text
64a8a38fec020e9e8c8baf46bdccfc4ea0d2b263e9d9575b68689e2125f11d19
```

**DLL SHA-256**

```text
32b5092b0b2b5d5a0a908c749dd70ea929b88074810f3edeabd322af3ab00569
```

### 1.2 本次合并后的关键判断

1. **类名还原与 Native 还原是两件事。** 可以先为部分 `obf/` 类生成可读名称，但这不会自动生成 DLL 中的逻辑。
2. CSV 中的 **210 条**都能在当前 JAR 中核对到对应 `SourceFile`，且每条都有独立的 `JPI mapped` 标识和不同的目标类路径。
3. `SourceFile` 仅为 Class 文件中的调试/编译元数据，**不是原始类名的直接证明**；例如，同一 `.java` 源文件可能生成多个 Class（内部类、匿名类或同文件其他类型）。
4. 这 **210 条尚未应用**；对全局类型引用、反射、JNI、Mixin、资源配置及运行时行为仍需验证。
5. JAR 中的 Native 声明仍在：**50 个方法**，其中 `EveryDay.onInitialize()` 依赖 DLL 的实现；仅重新计算 StackMap 或删除 NOP 无法消除这一依赖。

## 二、SourceFile 类名还原说明

### 2.1 `SourceFile` 从哪里来？

`SourceFile` 是每个 `.class` 文件可能携带的**类级属性（attribute）**，通常保存源文件名，例如 `AutoPearl.java`、`CombatUtil.java`。它存在于 JAR 内的 `.class` 字节码结构中，并不代表 JAR 中一定存在对应的 `.java` 源码文件。

可以通过下述示意确认；把类名替换为希望检查的 Class：

```powershell
javap -v -classpath "EveryDay-v2_6-beta-NOP-clean-stackmap(6).jar" "obf.000" 
```

查询结果中的 `SourceFile: "AutoPearl.java"` 是元数据；它既不是 JVM 加载类所用的完整内部名，也不会主动影响真实类名。

### 2.2 CSV 各列的含义

| CSV 列名 | 含义 | 示例 |
|---|---|---|
| `Class (original)` | JAR 中现存的 JVM 内部类名 | `obf/000` |
| `JPI mapped` | 现有 JPI 对应关系所使用的标识符 | `class_286` |
| `SourceFile` | Class 中记录的源文件名 | `AutoPearl.java` |
| `Possible class name` | 推断的目标 JVM 内部类名 | `obf/AutoPearl` |
| `Caution` | CSV 原有注意事项；所有行均为待验证、未应用 | `NOT APPLIED: validate JNI/reflection/bytecode references` |

示例（**候选映射，不代表已经修改文件**）：

```text
obf/000  ->  obf/AutoPearl     (JPI: class_286; SourceFile: AutoPearl.java)
obf/0O   ->  obf/MathUtil      (JPI: class_90;  SourceFile: MathUtil.java)
obf/00OO ->  obf/HoleFiller    (JPI: class_292; SourceFile: HoleFiller.java)
```

请区分 `0`（数字零）、`O`（大写字母 O）与 `o`（小写字母 o）：它们在原始混淆类名中**不可互换**。

### 2.3 覆盖范围与可信度

- 目前有 **210 条 CSV 候选**，对应 `obf/` 目录 983 个类中的一部分。其余 **773 个类不在本映射表中**，并不表示其无法分析。
- **逐条 SourceFile 匹配、目标路径唯一**已经验证；但**候选名是否为该 Class 的真实原始 Java 类型名**，尚未由源码、运行时行为或完整类型关系独立证实。
- 本映射不涉及 Native 方法声明所在的三个 `obf/` 类，所以它们保留现有混淆名称；但不能据此假设改动其他类不会影响 DLL 中的 JNI 类型查找或方法签名。
- 推荐在实际重命名前额外检查 `InnerClasses`、`EnclosingMethod`、父类/接口、方法调用图，以及 Java 反射和 Native 对类名的依赖。

## 三、Native 与 JNI 结构分析

### 3.1 Native 类分布

| JVM 内部类名 | `SourceFile` | Native 方法数 | 作用线索 |
|---|---|---:|---|
| `native/Loader` | `synthetic` | 1 | JNI 注册入口 |
| `obf/0O0o0` | `HWID.java` | 4 | 名称提示可能与设备识别有关，功能未核实 |
| `obf/0oO0O` | `EveryDay.java` | 11 | 含 `onInitialize()V`，属于关键初始化入口 |
| `obf/o0OOOO` | `SafeMethod.java` | 34 | 多个 Native 工具/功能方法，调用点分布较广 |
| **合计** | | **50** | |

### 3.2 DLL 加载与 Native 注册

根据提供的静态报告，加载链大致如下：

```text
native/Loader.<clinit>
    |
    +-- 检查操作系统与 CPU 架构
    |
    +-- 从 JAR 读取 native/x64-windows.dll
    |       |
    |       +-- 写入临时文件
    |
    +-- System.load(临时 DLL 路径)
    |       |
    |       +-- Windows PE x64 DLL / JNI_OnLoad
    |
    +-- 受保护类的 <clinit>
            |
            +-- registerNativesForClass(ID, Class)
            |
            +-- native.hidden.Hidden0.special_clinit_...
```

当前报告记录的注册 ID：

| 注册 ID | `SourceFile` | Class |
|---:|---|---|
| `0` | `SafeMethod.java` | `obf/o0OOOO` |
| `1` | `EveryDay.java` | `obf/0oO0O` |
| `2` | `HWID.java` | `obf/0O0o0` |

**重要发现：**当前 JAR 内没有 `native/hidden/Hidden0.class`，仅有 `native/Loader.class`。因此 `Hidden0` 不能当作 JAR 内现成可修改的普通 `.class`；需要另外确定其运行时来源和定义过程。

### 3.3 DLL 形式与导入 API

原静态报告对 DLL 的观察结果：

- 格式为 **Windows PE x64**，对外导出符号为 **`JNI_OnLoad`**，符合通过 JNI 动态注册 Native 方法的情况。
- `.text` 区段 `Virtual Size = 4,401,240`，`Raw Size = 0`。
- 大型 `.Qp:` 区段的熵约为 **7.865 bits/byte**，显示**疑似加壳或压缩/加密**，尚不能直接据此断言具体保护方案。
- 报告列出的导入 API：`GetCurrentProcessId`、`GetSystemTimeAsFileTime`、`HeapAlloc`、`HeapFree`、`ExitProcess`、`GetModuleHandleA`、`LoadLibraryA`、`GetProcAddress`，以及 MSVC 运行库。

这些 DLL 细节来源于所提供的分析报告，**本次新增的交叉核验主要针对 JAR、Class 元数据、CSV 映射及 DLL 内容哈希**；不要将导入 API 自动解释为已发现反调试、联网验证或数据上传行为。`ExitProcess` 的存在只能说明代码有调用该 API 的可能性。

## 四、Native 方法及潜在调用者清单

下表保留原报告的**全部 50 个 Native 方法**与 Method Descriptor。"直接引用的 Class 数" 来自报告依据 JAR 常量池 `Methodref` 进行的统计，不是实时调用次数；`invokedynamic`、反射、JNI 内部调用或外部调用可能不在统计范围内。**0 不等于此方法不会被执行**。

### `native/Loader.class` — `SourceFile: synthetic` （装载器）（1 个 Native 方法）

| 原始方法名及 Descriptor | 直接引用的 Class 数 | 引用类的 SourceFile 示例 |
|---|---:|---|
| `registerNativesForClass(ILjava/lang/Class;)V` | 3 | EveryDay.java, HWID.java, SafeMethod.java |

### `obf/0O0o0.class` — `SourceFile: HWID.java` （4 个 Native 方法）

| 原始方法名及 Descriptor | 直接引用的 Class 数 | 引用类的 SourceFile 示例 |
|---|---:|---|
| `O(Lobf/0OO0o0;)V` | 0 | 无直接 Methodref |
| `o(Lobf/0OO0o0;)V` | 0 | 无直接 Methodref |
| `O([CJI)Ljava/lang/String;` | 0 | 无直接 Methodref |
| `O()V` | 0 | 无直接 Methodref |

### `obf/0oO0O.class` — `SourceFile: EveryDay.java` （11 个 Native 方法）

| 原始方法名及 Descriptor | 直接引用的 Class 数 | 引用类的 SourceFile 示例 |
|---|---:|---|
| `O()Ljava/lang/String;` | 31 | AutoAnchor.java, AutoWeb.java, BaritoneModule.java, BindCommand.java, BlockUtil.java |
| `O()V` | 22 | AStarCustomPathFinder.java, AntiCheat.java, AntiSurround.java, AutoAnchor.java, AutoPush.java |
| `o()V` | 0 | 无直接 Methodref |
| `onInitialize()V` | 0 | 无直接 Methodref |
| `OO()V` | 0 | 无直接 Methodref |
| `O(Ljava/lang/reflect/Method;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandles$Lookup;` | 0 | 无直接 Methodref |
| `O(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;` | 0 | 无直接 Methodref |
| `o(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;` | 0 | 无直接 Methodref |
| `0(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;` | 0 | 无直接 Methodref |
| `O([CJI)Ljava/lang/String;` | 0 | 无直接 Methodref |
| `0()V` | 0 | 无直接 Methodref |

### `obf/o0OOOO.class` — `SourceFile: SafeMethod.java` （34 个 Native 方法）

| 原始方法名及 Descriptor | 直接引用的 Class 数 | 引用类的 SourceFile 示例 |
|---|---:|---|
| `O()V` | 20 | AntiKit.java, AntiMine.java, AutoPopEZ.java, AutoPush.java, AutoTrap.java |
| `O(Lobf/0ooO;)V` | 1 | AntiSurround.java |
| `O(Lnet/minecraft/class_2338;)V` | 0 | 无直接 Methodref |
| `O(Lnet/minecraft/class_2338;)Z` | 0 | 无直接 Methodref |
| `O(Lnet/minecraft/class_1703;)Ljava/util/ArrayList;` | 3 | AutoEChest.java, AutoKit.java, ChestStealer.java |
| `O(Lnet/minecraft/class_1297;D)Z` | 1 | CombatUtil.java |
| `o()V` | 26 | AntiCheat.java, AntiSurround.java, Aura.java, AutoAnchor.java, AutoPush.java |
| `O(Lnet/minecraft/class_2338;Lnet/minecraft/class_2338;Lnet/minecraft/class_2350;)Z` | 0 | 无直接 Methodref |
| `0()V` | 18 | AirPlace.java, AutoRegear.java, AutoWeb.java, Bot.java, ChestStealer.java |
| `O(Lnet/minecraft/class_2350;)V` | 1 | PacketMine.java |
| `o(Lnet/minecraft/class_2350;)V` | 1 | PacketMine.java |
| `o(Lobf/0ooO;)V` | 1 | SpearVia.java |
| `O(Lnet/minecraft/class_1799;)Z` | 1 | SpearVia.java |
| `O(Lnet/minecraft/class_1799;)I` | 0 | 无直接 Methodref |
| `O(Lnet/minecraft/class_1799;)Ljava/lang/Integer;` | 0 | 无直接 Methodref |
| `O(Lnet/minecraft/class_1799;Lnet/minecraft/class_811;)Lnet/minecraft/class_2960;` | 1 | MixinItemRenderer.java |
| `O(Lnet/minecraft/class_1799;)Ljava/lang/String;` | 1 | SpearVia.java |
| `OO()V` | 18 | AntiKit.java, AutoAnchor.java, AutoKit.java, AutoRegear.java, AutoWeb.java |
| `Oo()V` | 14 | AutoEZ.java, Bot.java, Burrow.java, ChunkESP.java, Criticals.java |
| `o(Lnet/minecraft/class_2338;)Z` | 0 | 无直接 Methodref |
| `O0()V` | 16 | AntiPacket.java, AutoEZ.java, Blocker.java, ChunkESP.java, DamageUtils.java |
| `O(Lnet/minecraft/class_243;Lnet/minecraft/class_243;)Lnet/minecraft/class_243;` | 1 | FireworkBoost.java |
| `0(I)Lnet/minecraft/class_2596;` | 0 | 无直接 Methodref |
| `O(Lnet/minecraft/class_2350;I)Lnet/minecraft/class_2596;` | 0 | 无直接 Methodref |
| `o(Lnet/minecraft/class_2350;I)Lnet/minecraft/class_2596;` | 0 | 无直接 Methodref |
| `o(I)Lnet/minecraft/class_2596;` | 0 | 无直接 Methodref |
| `O(I)Lnet/minecraft/class_2596;` | 0 | 无直接 Methodref |
| `O(Ljava/util/Map$Entry;Ljava/util/Map$Entry;)I` | 0 | 无直接 Methodref |
| `o(Lnet/minecraft/class_2846;I)Lnet/minecraft/class_2596;` | 0 | 无直接 Methodref |
| `O(Lnet/minecraft/class_2846;I)Lnet/minecraft/class_2596;` | 0 | 无直接 Methodref |
| `O(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;` | 0 | 无直接 Methodref |
| `o(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;` | 0 | 无直接 Methodref |
| `O([CJI)Ljava/lang/String;` | 0 | 无直接 Methodref |
| `oO()V` | 0 | 无直接 Methodref |

## 五、Runtime 提取结果与限制

根据原 Native 分析报告中已记录的 Runtime Extractor 结果（**本次没有额外提供可独立复核的内存转储或完整 JNI 追踪**）：

| 项目 | 报告记录 |
|---|---|
| Runtime 抓取的 Class | **567** |
| 仍为 Native 声明的方法 | **49** |
| 共同 Class 的方法名称和 Descriptor | **全部相同**（567 类） |
| 已有 Code 的方法 | **21,538** 个方法的 Code 字节数组与原始版本相同 |
| 原本无 Code 属性的方法 | **174**（含 Native/Abstract） |
| Class 整体文件字节不同 | **555**，但不表示 Code 被替换 |
| Retransform 失败的类 | `obf.OO0OOO`（`invalid class`） |

其中 "JAR 中 50 个 Native 方法" 和 "Runtime 抓取中 49 个 Native 声明" 的统计**对象与覆盖范围不同**，不能直接把少的一个理解成已被还原为 Java 方法。

`java.lang.instrument.Instrumentation.retransformClasses(...)` 的能力主要是获取 JVM 暴露的 Class 定义信息，并不会自动把 JNI 所运行的机器码转换为 Java bytecode。因此，**原报告并未证实任何 Native 方法已经成功还原成 Java Code**。

## 六、反混淆及 Native 研究优先级

以下是**工程实施建议**，不是已完成的修复结果。

| 优先级 | 工作项 | 预期收益 | 难度 / 关键注意点 |
|---:|---|---|---|
| 1 | 对 210 条 SourceFile 候选做静态关系校验 | 提升类名可读性、便于反编译和定位功能 | 中：源文件名不一定等于类型名；须覆盖配置与引用 |
| 2 | 梳理 `SafeMethod` 的调用点与参数/返回值 | 判断各 Native 方法的用途与依赖程度 | 中～高：需要受控环境中观察，不可直接假设是纯函数 |
| 3 | 分析 Native 字符串解码 `([CJI)Ljava/lang/String;` | 建立稳定输入输出映射后，部分常量可能可被静态替代 | 中～高：注意解码逻辑与运行时环境依赖 |
| 4 | 检查 Native 的 `CallSite` bootstrap | 了解动态方法句柄如何关联实际目标 | 高：`invokedynamic` 的 bootstrap target 不在普通 Methodref 计数中 |
| 5 | 分析 `EveryDay.onInitialize()` 与 `Hidden0` | 理解启动与注册的核心路径 | 很高：需要进一步调查 DLL 和运行时定义流程 |
| 6 | 分析 `HWID.java` Native 方法 | 确认是否为设备识别、验证等功能 | 高：仅凭文件名无法判断采集或传输行为 |

若目标是**尽可能获得一个不依赖 Native DLL 的 JAR**，需要分别解决初始化、注册、字符串处理、动态链接以及具体 Native 方法体。**当前数据不足以直接提供一个等价、可启动且完整去 DLL 的版本**。

## 七、完整 SourceFile 映射表（210 条）

以下按 `SourceFile` 名称排序，便于检索。所有映射的状态均为 **`NOT APPLIED`**，不是已确认可安全批量重命名的清单；`Possible class name` 是候选目标路径，非原始源码的直接恢复结果。

| 序号 | Class（原始内部名） | JPI mapped | SourceFile | 候选类名（未应用） |
|---:|---|---|---|---|
| 1 | `obf/OO00OO` | `class_216` | `AimCommand.java` | `obf/AimCommand` |
| 2 | `obf/O0Ooo` | `class_275` | `AirPlace.java` | `obf/AirPlace` |
| 3 | `obf/OO000o` | `class_459` | `AirPotion.java` | `obf/AirPotion` |
| 4 | `obf/0o000` | `class_429` | `Ambience.java` | `obf/Ambience` |
| 5 | `obf/000O0` | `class_432` | `Animation.java` | `obf/Animation` |
| 6 | `obf/Oo00oo` | `class_463` | `AntiCrawlBlock.java` | `obf/AntiCrawlBlock` |
| 7 | `obf/o0OOoo` | `class_285` | `AntiEffects.java` | `obf/AntiEffects` |
| 8 | `obf/Oo0OO0` | `class_428` | `AntiHunger.java` | `obf/AntiHunger` |
| 9 | `obf/OoOOo` | `class_454` | `AntiMine.java` | `obf/AntiMine` |
| 10 | `obf/Oooo0O` | `class_342` | `AntiPacket.java` | `obf/AntiPacket` |
| 11 | `obf/OO00oO` | `class_461` | `AntiSurround.java` | `obf/AntiSurround` |
| 12 | `obf/oo0o00` | `class_427` | `AntiVoid.java` | `obf/AntiVoid` |
| 13 | `obf/o0Oo0O` | `class_417` | `AspectRatio.java` | `obf/AspectRatio` |
| 14 | `obf/OOO0O0` | `class_448` | `AutoEChest.java` | `obf/AutoEChest` |
| 15 | `obf/Oo00o` | `class_398` | `AutoLog.java` | `obf/AutoLog` |
| 16 | `obf/ooOoO` | `class_389` | `AutoMend.java` | `obf/AutoMend` |
| 17 | `obf/000` | `class_286` | `AutoPearl.java` | `obf/AutoPearl` |
| 18 | `obf/OoO000` | `class_386` | `AutoPush.java` | `obf/AutoPush` |
| 19 | `obf/o0oO0o` | `class_464` | `AutoTK.java` | `obf/AutoTK` |
| 20 | `obf/o000oO` | `class_289` | `AutoTool.java` | `obf/AutoTool` |
| 21 | `obf/OO0OOO` | `class_511` | `BaritoneModule.java` | `obf/BaritoneModule` |
| 22 | `obf/o00o00` | `class_447` | `BaritoneUtil.java` | `obf/BaritoneUtil` |
| 23 | `obf/Oo00` | `class_419` | `BedCrafter.java` | `obf/BedCrafter` |
| 24 | `obf/oOo0O0` | `class_215` | `BindCommand.java` | `obf/BindCommand` |
| 25 | `obf/0Oo00` | `class_214` | `BindsCommand.java` | `obf/BindsCommand` |
| 26 | `obf/o0OOOo` | `class_547` | `BindSetting.java` | `obf/BindSetting` |
| 27 | `obf/0oOoo` | `class_376` | `Blink.java` | `obf/Blink` |
| 28 | `obf/OoOo00` | `class_171` | `BlockActivateEvent.java` | `obf/BlockActivateEvent` |
| 29 | `obf/oOO0oO` | `class_498` | `BlockPosX.java` | `obf/BlockPosX` |
| 30 | `obf/oOOOO` | `class_374` | `BlockStrafe.java` | `obf/BlockStrafe` |
| 31 | `obf/oo00Oo` | `class_167` | `BlurManager.java` | `obf/BlurManager` |
| 32 | `obf/oOo000` | `class_549` | `BooleanSetting.java` | `obf/BooleanSetting` |
| 33 | `obf/OoOO00` | `class_399` | `Breaker.java` | `obf/Breaker` |
| 34 | `obf/o00oO0` | `class_367` | `BreakESP.java` | `obf/BreakESP` |
| 35 | `obf/0o` | `class_362` | `CameraClip.java` | `obf/CameraClip` |
| 36 | `obf/0OoOo` | `class_64` | `CaptureUtil.java` | `obf/CaptureUtil` |
| 37 | `obf/0OOO` | `class_345` | `CevBreaker.java` | `obf/CevBreaker` |
| 38 | `obf/OoOooo` | `class_343` | `Chams.java` | `obf/Chams` |
| 39 | `obf/o000o0` | `class_361` | `ChatAppend.java` | `obf/ChatAppend` |
| 40 | `obf/O0OO` | `class_375` | `ChorusControl.java` | `obf/ChorusControl` |
| 41 | `obf/oOo00` | `class_52` | `ChunkOcclusionEvent.java` | `obf/ChunkOcclusionEvent` |
| 42 | `obf/O000o` | `class_213` | `CleanerCommand.java` | `obf/CleanerCommand` |
| 43 | `obf/000Oo` | `class_390` | `CleanerManager.java` | `obf/CleanerManager` |
| 44 | `obf/0OO0o0` | `class_539` | `ClientTickEvent.java` | `obf/ClientTickEvent` |
| 45 | `obf/ooooo` | `class_212` | `ClipCommand.java` | `obf/ClipCommand` |
| 46 | `obf/OOoOO` | `class_552` | `ColorSetting.java` | `obf/ColorSetting` |
| 47 | `obf/Oooo00` | `class_512` | `ColorsModule.java` | `obf/ColorsModule` |
| 48 | `obf/oOoo0O` | `class_71` | `ColorUtil.java` | `obf/ColorUtil` |
| 49 | `obf/oOOoO` | `class_91` | `CombatUtil.java` | `obf/CombatUtil` |
| 50 | `obf/0OOOo0` | `class_217` | `Command.java` | `obf/Command` |
| 51 | `obf/OOOooO` | `class_218` | `CommandManager.java` | `obf/CommandManager` |
| 52 | `obf/oo000O` | `class_530` | `ConfigManager.java` | `obf/ConfigManager` |
| 53 | `obf/0oO0` | `class_329` | `CopyPlayerEntity.java` | `obf/CopyPlayerEntity` |
| 54 | `obf/OOooo` | `class_344` | `Crosshair.java` | `obf/Crosshair` |
| 55 | `obf/o00000` | `class_176` | `DoAttackEvent.java` | `obf/DoAttackEvent` |
| 56 | `obf/ooOooo` | `class_100` | `ElytraUpdateEvent.java` | `obf/ElytraUpdateEvent` |
| 57 | `obf/o0ooo` | `class_326` | `EntityControl.java` | `obf/EntityControl` |
| 58 | `obf/oOoOoO` | `class_157` | `EntityManager.java` | `obf/EntityManager` |
| 59 | `obf/0o00` | `class_87` | `EntitySpawnedEvent.java` | `obf/EntitySpawnedEvent` |
| 60 | `obf/ooOo` | `class_88` | `EntitySpawnEvent.java` | `obf/EntitySpawnEvent` |
| 61 | `obf/O0OoO0` | `class_69` | `EntityVelocityUpdateEvent.java` | `obf/EntityVelocityUpdateEvent` |
| 62 | `obf/oooo` | `class_152` | `EnumConverter.java` | `obf/EnumConverter` |
| 63 | `obf/o0oO` | `class_548` | `EnumSetting.java` | `obf/EnumSetting` |
| 64 | `obf/OO0o00` | `class_337` | `ESP.java` | `obf/ESP` |
| 65 | `obf/OoO0o0` | `class_538` | `EventListener.java` | `obf/EventListener` |
| 66 | `obf/ooOOo` | `class_422` | `ExtraTab.java` | `obf/ExtraTab` |
| 67 | `obf/Oo000o` | `class_487` | `FadeUtils.java` | `obf/FadeUtils` |
| 68 | `obf/oO00OO` | `class_211` | `FakePlayerCommand.java` | `obf/FakePlayerCommand` |
| 69 | `obf/OOoOo0` | `class_451` | `FakeTotem.java` | `obf/FakeTotem` |
| 70 | `obf/OO0o0O` | `class_373` | `FastSwim.java` | `obf/FastSwim` |
| 71 | `obf/oo0oOO` | `class_462` | `FireworkBoost.java` | `obf/FireworkBoost` |
| 72 | `obf/oOoO0o` | `class_182` | `FireworkShooterRotationEvent.java` | `obf/FireworkShooterRotationEvent` |
| 73 | `obf/o0OOo` | `class_315` | `Flatten.java` | `obf/Flatten` |
| 74 | `obf/oOOoOO` | `class_314` | `Fly.java` | `obf/Fly` |
| 75 | `obf/0OOoo` | `class_347` | `Fov.java` | `obf/Fov` |
| 76 | `obf/OooOoo` | `class_298` | `FPSManager.java` | `obf/FPSManager` |
| 77 | `obf/O0oO00` | `class_312` | `Freecam.java` | `obf/Freecam` |
| 78 | `obf/o0OOO0` | `class_311` | `FreeLook.java` | `obf/FreeLook` |
| 79 | `obf/OO0oO` | `class_418` | `Friend.java` | `obf/Friend` |
| 80 | `obf/ooo00O` | `class_210` | `FriendCommand.java` | `obf/FriendCommand` |
| 81 | `obf/0OOoo0` | `class_193` | `FriendManager.java` | `obf/FriendManager` |
| 82 | `obf/OooO0` | `class_172` | `GameLeftEvent.java` | `obf/GameLeftEvent` |
| 83 | `obf/OO00` | `class_208` | `GamemodeCommand.java` | `obf/GamemodeCommand` |
| 84 | `obf/O00ooo` | `class_194` | `GcCommand.java` | `obf/GcCommand` |
| 85 | `obf/OO0oO0` | `class_319` | `HighLight.java` | `obf/HighLight` |
| 86 | `obf/00OO` | `class_292` | `HoleFiller.java` | `obf/HoleFiller` |
| 87 | `obf/o0oO00` | `class_527` | `HoleManager.java` | `obf/HoleManager` |
| 88 | `obf/ooOoO0` | `class_291` | `HoleSnap.java` | `obf/HoleSnap` |
| 89 | `obf/oOo0oO` | `class_129` | `IChatHudHook.java` | `obf/IChatHudHook` |
| 90 | `obf/0oo` | `class_86` | `IChatHudLineHook.java` | `obf/IChatHudLineHook` |
| 91 | `obf/OO0O0` | `class_558` | `IEventBus.java` | `obf/IEventBus` |
| 92 | `obf/O0ooOo` | `class_537` | `IListener.java` | `obf/IListener` |
| 93 | `obf/O00Oo0` | `class_127` | `IMouseHook.java` | `obf/IMouseHook` |
| 94 | `obf/o000o` | `class_358` | `InfiniteTrident.java` | `obf/InfiniteTrident` |
| 95 | `obf/o00Oo` | `class_302` | `InitEvent.java` | `obf/InitEvent` |
| 96 | `obf/OO0o0o` | `class_178` | `InteractBlockEvent.java` | `obf/InteractBlockEvent` |
| 97 | `obf/00o0O` | `class_179` | `InteractItemEvent.java` | `obf/InteractItemEvent` |
| 98 | `obf/oooOOO` | `class_250` | `InteractTweaks.java` | `obf/InteractTweaks` |
| 99 | `obf/Oo0Oo0` | `class_84` | `InventoryS2CPacketEvent.java` | `obf/InventoryS2CPacketEvent` |
| 100 | `obf/oo00` | `class_10` | `InventoryUtil.java` | `obf/InventoryUtil` |
| 101 | `obf/oo00oo` | `class_105` | `IShaderEffectHook.java` | `obf/IShaderEffectHook` |
| 102 | `obf/0` | `class_348` | `ItemsCounter.java` | `obf/ItemsCounter` |
| 103 | `obf/o00oO` | `class_65` | `JelloUtil.java` | `obf/JelloUtil` |
| 104 | `obf/O00O` | `class_177` | `JumpEvent.java` | `obf/JumpEvent` |
| 105 | `obf/ooOO00` | `class_184` | `KeyboardInputEvent.java` | `obf/KeyboardInputEvent` |
| 106 | `obf/OoooOo` | `class_287` | `KillEffect.java` | `obf/KillEffect` |
| 107 | `obf/0O00o` | `class_207` | `KitCommand.java` | `obf/KitCommand` |
| 108 | `obf/0o0O0` | `class_356` | `LavaFiller.java` | `obf/LavaFiller` |
| 109 | `obf/Oo0Oo` | `class_68` | `LerpToEvent.java` | `obf/LerpToEvent` |
| 110 | `obf/0oO00` | `class_206` | `LoadCommand.java` | `obf/LoadCommand` |
| 111 | `obf/OOo00` | `class_290` | `LogoutSpots.java` | `obf/LogoutSpots` |
| 112 | `obf/oooooO` | `class_49` | `LookDirectionEvent.java` | `obf/LookDirectionEvent` |
| 113 | `obf/O0Ooo0` | `class_163` | `ManagedCoreShader.java` | `obf/ManagedCoreShader` |
| 114 | `obf/OOo0o` | `class_162` | `ManagedShaderEffect.java` | `obf/ManagedShaderEffect` |
| 115 | `obf/oo0oO` | `class_531` | `Manager.java` | `obf/Manager` |
| 116 | `obf/0O` | `class_90` | `MathUtil.java` | `obf/MathUtil` |
| 117 | `obf/o0O00O` | `class_555` | `Mod.java` | `obf/Mod` |
| 118 | `obf/OOoo0O` | `class_526` | `ModuleManager.java` | `obf/ModuleManager` |
| 119 | `obf/o0o0oo` | `class_320` | `MotionCamera.java` | `obf/MotionCamera` |
| 120 | `obf/00O0O` | `class_55` | `MovedEvent.java` | `obf/MovedEvent` |
| 121 | `obf/oOooo0` | `class_56` | `MoveEvent.java` | `obf/MoveEvent` |
| 122 | `obf/OoOo0o` | `class_334` | `MovementSync.java` | `obf/MovementSync` |
| 123 | `obf/ooOOO0` | `class_2` | `MovementUtil.java` | `obf/MovementUtil` |
| 124 | `obf/ooO000` | `class_506` | `NameTags.java` | `obf/NameTags` |
| 125 | `obf/O0OOO` | `class_496` | `NightPhase.java` | `obf/NightPhase` |
| 126 | `obf/oOOo0` | `class_235` | `NoJumpDelay.java` | `obf/NoJumpDelay` |
| 127 | `obf/0OOO0O` | `class_556` | `NoLambdaFactoryException.java` | `obf/NoLambdaFactoryException` |
| 128 | `obf/OOoo` | `class_282` | `NoRender.java` | `obf/NoRender` |
| 129 | `obf/O0o0o0` | `class_294` | `NoResourcePack.java` | `obf/NoResourcePack` |
| 130 | `obf/oO0OoO` | `class_276` | `NoSound.java` | `obf/NoSound` |
| 131 | `obf/O00o` | `class_437` | `NoTerrainScreen.java` | `obf/NoTerrainScreen` |
| 132 | `obf/oOO000` | `class_382` | `Nuker.java` | `obf/Nuker` |
| 133 | `obf/o0000` | `class_173` | `OpenScreenEvent.java` | `obf/OpenScreenEvent` |
| 134 | `obf/o0O0` | `class_260` | `PacketControl.java` | `obf/PacketControl` |
| 135 | `obf/ooO0OO` | `class_272` | `PacketEat.java` | `obf/PacketEat` |
| 136 | `obf/00o00` | `class_331` | `PacketLogger.java` | `obf/PacketLogger` |
| 137 | `obf/OO0oOO` | `class_150` | `ParticleEvent.java` | `obf/ParticleEvent` |
| 138 | `obf/OoOoo` | `class_195` | `PeekCommand.java` | `obf/PeekCommand` |
| 139 | `obf/ooOOoO` | `class_205` | `PingCommand.java` | `obf/PingCommand` |
| 140 | `obf/0oooO` | `class_351` | `Placement.java` | `obf/Placement` |
| 141 | `obf/Ooo0o` | `class_112` | `PlaySoundEvent.java` | `obf/PlaySoundEvent` |
| 142 | `obf/o0oOOO` | `class_170` | `PopManager.java` | `obf/PopManager` |
| 143 | `obf/oooo0o` | `class_204` | `PrefixCommand.java` | `obf/PrefixCommand` |
| 144 | `obf/OOo` | `class_76` | `PreRender2DEvent.java` | `obf/PreRender2DEvent` |
| 145 | `obf/OoO` | `class_370` | `Quiver.java` | `obf/Quiver` |
| 146 | `obf/Oo0o00` | `class_203` | `RejoinCommand.java` | `obf/RejoinCommand` |
| 147 | `obf/Ooo000` | `class_165` | `ReloadableShaderEffectManager.java` | `obf/ReloadableShaderEffectManager` |
| 148 | `obf/OOoOoo` | `class_202` | `ReloadCommand.java` | `obf/ReloadCommand` |
| 149 | `obf/oo0O0` | `class_156` | `RemoveEntityEvent.java` | `obf/RemoveEntityEvent` |
| 150 | `obf/0O000` | `class_359` | `Render2DEvent.java` | `obf/Render2DEvent` |
| 151 | `obf/ooO0O0` | `class_34` | `Render2DUtil.java` | `obf/Render2DUtil` |
| 152 | `obf/o0oOOo` | `class_430` | `Render3DEvent.java` | `obf/Render3DEvent` |
| 153 | `obf/oO0OO0` | `class_32` | `Render3DUtil.java` | `obf/Render3DUtil` |
| 154 | `obf/OO0oo` | `class_280` | `RenderEntityEvent.java` | `obf/RenderEntityEvent` |
| 155 | `obf/oO0O00` | `class_160` | `ResettableManagedShaderBase.java` | `obf/ResettableManagedShaderBase` |
| 156 | `obf/O0o00o` | `class_159` | `ResettableManagedShaderEffect.java` | `obf/ResettableManagedShaderEffect` |
| 157 | `obf/oOo0OO` | `class_161` | `ResizeEvent.java` | `obf/ResizeEvent` |
| 158 | `obf/O0o0O` | `class_293` | `RocketExtend.java` | `obf/RocketExtend` |
| 159 | `obf/oOoo00` | `class_54` | `RotationEvent.java` | `obf/RotationEvent` |
| 160 | `obf/OO0oo0` | `class_188` | `RotationManager.java` | `obf/RotationManager` |
| 161 | `obf/0Oo0O` | `class_236` | `SafeWalk.java` | `obf/SafeWalk` |
| 162 | `obf/0ooO0` | `class_158` | `SamplerUniform.java` | `obf/SamplerUniform` |
| 163 | `obf/o0ooO` | `class_201` | `SaveCommand.java` | `obf/SaveCommand` |
| 164 | `obf/o000oo` | `class_244` | `Scaffold.java` | `obf/Scaffold` |
| 165 | `obf/oo0000` | `class_186` | `SendMovementPacketsEvent.java` | `obf/SendMovementPacketsEvent` |
| 166 | `obf/oo000` | `class_338` | `ServerConnectBeginEvent.java` | `obf/ServerConnectBeginEvent` |
| 167 | `obf/o000O` | `class_299` | `ServerManager.java` | `obf/ServerManager` |
| 168 | `obf/O0o0Oo` | `class_553` | `Setting.java` | `obf/Setting` |
| 169 | `obf/oO0oOO` | `class_166` | `ShaderEffectManager.java` | `obf/ShaderEffectManager` |
| 170 | `obf/ooooo0` | `class_126` | `SimpleItemModel.java` | `obf/SimpleItemModel` |
| 171 | `obf/00o` | `class_551` | `SliderSetting.java` | `obf/SliderSetting` |
| 172 | `obf/oo0OOO` | `class_350` | `SnapBack.java` | `obf/SnapBack` |
| 173 | `obf/OOo0OO` | `class_296` | `Sounds.java` | `obf/Sounds` |
| 174 | `obf/Oo0o0o` | `class_297` | `SoundSetting.java` | `obf/SoundSetting` |
| 175 | `obf/oOoOoo` | `class_535` | `SoundUtil.java` | `obf/SoundUtil` |
| 176 | `obf/o00oOO` | `class_321` | `Spammer.java` | `obf/Spammer` |
| 177 | `obf/Oo0o0O` | `class_230` | `Strafe.java` | `obf/Strafe` |
| 178 | `obf/ooooO0` | `class_550` | `StringSetting.java` | `obf/StringSetting` |
| 179 | `obf/OO0O` | `class_490` | `SwingSide.java` | `obf/SwingSide` |
| 180 | `obf/o00O0O` | `class_199` | `TCommand.java` | `obf/TCommand` |
| 181 | `obf/OOO0o` | `class_200` | `TeleportCommand.java` | `obf/TeleportCommand` |
| 182 | `obf/o0O0O0` | `class_306` | `TextRadar.java` | `obf/TextRadar` |
| 183 | `obf/oO0oO` | `class_81` | `TextUtil.java` | `obf/TextUtil` |
| 184 | `obf/0OO` | `class_281` | `TickEntityEvent.java` | `obf/TickEntityEvent` |
| 185 | `obf/oo0Oo` | `class_181` | `TickEvent.java` | `obf/TickEvent` |
| 186 | `obf/OoooO` | `class_187` | `TickMovementEvent.java` | `obf/TickMovementEvent` |
| 187 | `obf/o0ooOO` | `class_507` | `Timer.java` | `obf/Timer` |
| 188 | `obf/0oOOo` | `class_113` | `TimerEvent.java` | `obf/TimerEvent` |
| 189 | `obf/oo00oO` | `class_169` | `TimerManager.java` | `obf/TimerManager` |
| 190 | `obf/OoOOOo` | `class_310` | `TimerModule.java` | `obf/TimerModule` |
| 191 | `obf/O00oo0` | `class_499` | `Timing.java` | `obf/Timing` |
| 192 | `obf/O` | `class_309` | `Tips.java` | `obf/Tips` |
| 193 | `obf/O0oO0` | `class_198` | `ToggleCommand.java` | `obf/ToggleCommand` |
| 194 | `obf/O0000` | `class_225` | `TotemParticle.java` | `obf/TotemParticle` |
| 195 | `obf/000OO` | `class_335` | `Tracers.java` | `obf/Tracers` |
| 196 | `obf/0OoOO` | `class_197` | `TradeCommand.java` | `obf/TradeCommand` |
| 197 | `obf/oOOO00` | `class_444` | `TradeManager.java` | `obf/TradeManager` |
| 198 | `obf/000O` | `class_288` | `Trajectories.java` | `obf/Trajectories` |
| 199 | `obf/O0o0` | `class_180` | `TravelEvent.java` | `obf/TravelEvent` |
| 200 | `obf/OOoooo` | `class_164` | `UniformFinder.java` | `obf/UniformFinder` |
| 201 | `obf/oo000o` | `class_60` | `UpdateEvent.java` | `obf/UpdateEvent` |
| 202 | `obf/oo0` | `class_185` | `UpdateRotateEvent.java` | `obf/UpdateRotateEvent` |
| 203 | `obf/OoO00o` | `class_222` | `ViewModel.java` | `obf/ViewModel` |
| 204 | `obf/OoOo0` | `class_196` | `WatermarkCommand.java` | `obf/WatermarkCommand` |
| 205 | `obf/oooOo` | `class_570` | `Wrapper.java` | `obf/Wrapper` |
| 206 | `obf/ooOoo` | `class_221` | `XCarry.java` | `obf/XCarry` |
| 207 | `obf/Oo00o0` | `class_274` | `Xray.java` | `obf/Xray` |
| 208 | `obf/oO0oo` | `class_209` | `XrayCommand.java` | `obf/XrayCommand` |
| 209 | `obf/0OoOO0` | `class_273` | `XrayManager.java` | `obf/XrayManager` |
| 210 | `obf/Oo0oO0` | `class_313` | `Yaw.java` | `obf/Yaw` |

## 八、重命名前的校验建议

> 建议**先生成审查报告，再制作映射已应用的新 JAR**；保存原始文件和哈希，避免不可逆地覆盖原始样本。

1. **映射合法性：**检查 `Class (original)` 是否存在、`SourceFile` 是否与原始 Class 一致、目标内部类名是否重复、是否与任何原 Class 路径冲突（当前 210 条已通过这一层检查）。
2. **语义确认：**检查类的父类、接口、构造函数、字段、事件注册和调用链；特别处理同名 `SourceFile` 引起的内部类、匿名类及其他顶级类歧义。`SourceFile` 本身不能给出完整的原始包路径。
3. **ASM 处理：**使用经过验证的全局类型映射（如 `ClassRemapper` 搭配 `Remapper`）同步修改类定义、父类/接口、字段与方法 Descriptor、泛型 Signature、注解、方法句柄、`invokedynamic` 引用及其他可由 ASM 识别的类型引用。不要只改 ZIP 中的 `.class` 路径。
4. **非字节码引用：**另外逐一检查 `fabric.mod.json`、Mixin 配置与 refmap、Access Widener、`META-INF/services`、反射类名字面量、序列化名称以及 JNI 的 `FindClass` 等；这些并不保证被 `ClassRemapper` 自动处理。
5. **Native 风险：**即使当前 210 个候选类自身没有 `ACC_NATIVE` 方法，DLL 仍可能通过 Class 名、方法描述符、反射或缓存的类引用依赖它们；在未验证前，不要将它们标成 JNI-safe。
6. **验证输出：**在隔离的测试环境执行类加载和实际功能测试，结合 `CheckClassAdapter` / ASM 校验、Java 验证错误日志及 Fabric/Mixin 日志排查问题。若重新计算帧，应在依赖类可解析的类路径下运行，注意 `getCommonSuperClass` 解析失败的可能性。

### 如何区分“SourceFile 候选”与“已恢复逻辑”？

| 类型 | 本文是否具备？ | 解释 |
|---|---|---|
| Class 的 `SourceFile` 文件名 | **是** | 从 `.class` 元数据直接读取 |
| 210 条 JPI/SourceFile 对照 | **是** | 与用户 CSV 逐条比对 |
| 对这些类实际完成的重命名 | **否** | 未应用修改 |
| JNI Native 方法声明与签名 | **是** | `ACC_NATIVE` 可直接读到 |
| JNI 原生机器码执行语义 | **否** | 仅有原报告静态线索 |
| Native 等价 Java bytecode | **否** | 未获得、未生成 |
| 可完全脱离 DLL 运行的 JAR | **否** | 当前证据不足 |

## 九、结论与证据边界

- **可以确认：**当前 JAR 包含 1,007 个 `.class`、210 个与 CSV 一致的 SourceFile 映射候选、4 个带 Native 声明的类 / 50 个 Native 方法、以及内嵌 Windows x64 DLL。
- **不能直接确认：**这 210 个候选都是真实 Java 原始类型名，或者它们应用之后可以无错误加载。建议先在备份 JAR 上逐步验证。
- **尚未确认：**任何 Native 机器码已变为 Java 字节码、`Hidden0` 的实际定义方式、HWID 是否被采集/上传、DLL 是否存在特定反调试行为、具体 Minecraft Packet 逻辑。
- **后续最关键的区分：**`SourceFile` 能帮助识别类的来源，`Native` 分析能指出功能缺口，二者结合有助于规划反混淆工作，但**类名还原不会自动补齐 Native 实现**。

---

**输入材料：**`everyday_native_analysis(1).md`（JNI、PE、Runtime 提取记录）；`sourcefile_rename_candidates (2).csv`（210 条候选）；`EveryDay-v2_6-beta-NOP-clean-stackmap(6).jar`（静态复核）。  
**文档状态：**分析整理完成；**JAR 未修改、类名未实际替换、Native 未转换**。
