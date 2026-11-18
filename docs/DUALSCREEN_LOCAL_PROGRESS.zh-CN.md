# dualscreen 开发与本机验证记录

更新：2026-11-18。个人版本分支：`dualscreen`。本文件不包含游戏内容。

## 已实现的第一阶段

- RuntimeActivity 仍是唯一 SDLActivity 和游戏实例。
- DualScreenManager 用 DisplayManager 动态发现副屏，并用 Presentation 显示独立窗口。没有写死 Thor 的 displayId。
- 下屏包含“方位示意”和“地点列表”两个触摸页签。示意图标注玩家和最多 64 个 POI，使用一致的 XY 比例；明确注明没有道路/原作地图底图，也不假定楼层或北向。
- JNI 只返回游戏线程发布的自有快照。地图与位置在同一个 engine tick 中收集，250 ms 节流；过期 1.5 秒后清空；会话代号阻止暂停前尚未完成的发布在恢复后回流。UI 不读取 guest 指针，不执行传送或其他游戏命令。
- 副屏窗口设置为不获取键盘焦点，以保留主屏 SDL 的手柄输入；暂停、销毁和副屏断开时关闭窗口并停止轮询，恢复后重新枚举。相关行为仍需真机验收。
- fork 使用独立 applicationId：`io.github.user081944.lostodyssey.thor`，Java namespace 和 JNI 类名保持原值。禁用上游应用更新检查，shader pack 和驱动来源不变。
- fork 的普通 push 不运行上游私有输入 APK 工作流；手动 workflow_dispatch 仍需要单独配置私有输入与签名。

此阶段尚未迁移原作地图纹理、小地图绘制或装备/道具菜单。菜单原有暂停语义未修改。

## ROM 核实

`E:\roms` 下四份 `Lost Odyssey (Europe, Asia) (Disc 1..4).7z` 各包含一份 ISO，未改动原包。第一盘已解到 `E:\roms\LostOdysseyRecomp-local\iso`，7-Zip 解压检查通过；38 字节尾部附加数据未导致 CRC 错误。

第一盘 XDVDFS 根目录包含 default.xex、LO.fpi、LO.fpd 和全部 12 个 xenon_*.fpd。提取的 XEX 信息：

- Title ID `4D5307FA`，Media ID `39F7D748`，Disc 1/4，版本及 base version 均为 `0.0.0.4`，匹配项目亚洲版身份表。
- XEX SHA-256：`40c7dbb12cca03921d52cf4177a0f700cc4ae94ab730ab594940e2bdffd8ecf2`。
- XEX 已暂存到 Git 忽略的 `LostOdysseyRecompLib/private/disc1/default.xex`。
- xexdump 已生成 20,709,376 字节镜像；ppc_codegen 成功，生成 247 个 CPP（约 254.5 MiB）。生成器报告四条不能解码的指令；生成完成不等于运行时游戏验收。
- 第一盘 15 个运行文件已解到 `E:\roms\LostOdysseyRecomp-local\game\disc1`，合计约 5.00 GiB，XEX 哈希再次匹配。其余三盘仅检查了压缩包目录，尚未解压核对 XEX，也未验证四盘完整流程。

## 复用本机工具

| 组件 | 本机选择 | 结果 |
| --- | --- | --- |
| MSVC / Windows SDK | Visual Studio Community 2026，`D:\softwares\VS`，MSVC 14.51 | 通过 Developer PowerShell 加载；不调用 cmd/bat |
| Clang | NDK 29 自带 clang-cl / Clang 21 | 已与 MSVC/Windows SDK 配合构建 XenonRecomp、XenonAnalyse、xexdump |
| Android NDK | 29.0.14206865 | 新增 JNI 模块已交叉编译 ARM64 对象 |
| Android SDK | compileSdk 36，targetSdk 仍为 35 | Android Java 编译通过，无需补装 API 35 |
| Build Tools | 35.0.0 | 复用已安装版本 |
| Java | Android Studio JBR 21 | Gradle 与 Java 数据模型检查通过；源码兼容级别仍为 Java 17 |
| Gradle / AGP | 缓存 Gradle 8.13 / AGP 8.13.2 | 通过本地覆盖参数使用；保留上游默认配置 |
| CMake / Ninja | 现有 CMake 4.3.2 / Ninja 1.13.2 | host 工具与完整 Android 原生运行库构建、链接通过 |

Android Gradle 模块增加 loCompileSdk、loBuildToolsVersion、loNdkVersion、loCmakeVersion 覆盖项，settings.gradle 支持 loAgpVersion。没有为了匹配上游硬编码安装旧版 SDK。

## PowerShell 入口

所有新增脚本均采用 Stop 错误处理，直接启动原生可执行文件。

- `tools/android/Build-HostTools.ps1`：通过 vswhere 寻找实际 VS 安装，加载 Developer PowerShell，使用 NDK clang-cl 构建宿主生成器。首次依赖补丁只在干净树上应用。
- `tools/android/Invoke-LocalGradle.ps1`：直接以 Java 启动已安装 Gradle 8.13，使用本机 SDK/NDK/AGP；默认不改永久环境变量。Tasks 参数由调用方指定。
- `tools/android/Build-NativeRuntime.ps1`：应用 Android 依赖补丁、配置并编译原生运行库、检查 AArch64 并暂存依赖；不调用 APK 打包。可用 CompileOnly 跳过 DXC 暂存，并用 DxcIncludeDirectory 指向兼容的公开 API 头文件。
- LO_DXC_INCLUDE_DIR 允许单独指定 DXC 公开头文件，避免为 Android 原生编译下载整个桌面预编译二进制仓库。

已执行的 Java/Lint/JVM 检查等价于：

```powershell
$ErrorActionPreference = 'Stop'
./tools/android/Invoke-LocalGradle.ps1 -Tasks @(':runtime:lintDebug', ':runtime:testDebugUnitTest') -ExtraArguments @('-x', ':runtime:checkNativeLibraries')
```

此处跳过 native library 暂存检查，只证明 Java、资源、静态检查与 JVM 测试通过，不证明完整原生库链接、APK 可安装或双屏可用。

## 验证与剩余事项

- 完整 runtime Java 编译通过。
- runtime Lint 通过：0 error，55 warning；未对新代码添加 lint 抑制。
- 既有 79 项 JVM 测试通过；另执行 CompanionSnapshotTest，检查无效坐标、数组长度、超量 POI、自有数据和比例边界。
- JNI C++ 以 NDK 29、Android API 26、ARM64、-Wall -Wextra -Werror 编译通过；导出名称已检查。
- PowerShell 脚本语法检查和 Git diff --check 通过。
- Thor `c53b4da` 已连接，两块显示器可见，下屏当前 ID 为 4 并带 FLAG_PRESENTATION。已通过 ADB 运行不依赖游戏的 android_companion_test 原生测试，验证 JNI 数据形状、POI 上限、过期/会话清理，以及中文和 UTF-16 代理对转换。该测试使用受控游戏快照与 JNI 测试替身，不代表真实游戏或 Presentation 窗口验收。
- 完整原生运行库已链接，8 个运行库已暂存，JNI 导出和 16 KiB ELF LOAD 对齐检查通过。后续已获授权生成并安装测试 APK；下屏窗口、页签触摸和主屏焦点已实测。合盖、恢复和完整游戏流程仍待验证。

后续实机验收顺序：下屏独立窗口 → 页签触摸与主屏手柄同时工作 → 切图清空与更新 → 后台/唤醒 → 长时间帧时间 → 原作地图与菜单绘制迁移。

详细分层设计见 [AYN Thor 双屏实施方案](AYN_THOR_DUAL_SCREEN.zh-CN.md)。

## 本地依赖来源

- Android DXC 从上游 v1.1.0 发布包提取。下载文件 SHA-256 与 GitHub 发布资产 digest 匹配：`41ec5d557cc78fdc72feb00754a48e7b2851730c27344eaa1619f6478850877a`。其中已发布的 libdxcompiler.so SHA-256 为 `f27d9850a3b43c26eac55a33119fdd519daac7b67c3f80515dd79cb1d08a51d7`；它与 CI 私有输入在再次 strip 前的 digest 不同，不能混称同一个二进制。
- DXC 公开头文件来自 Microsoft DirectXShaderCompiler 的 v1.8.2407 标签，暂存在 out/upstream-deps/dxc-headers。没有把这些缓存加入 Git。
- FFmpeg 使用上游 CMake 固定的 `15ece0882e8d5875051ff5b73c5a8326f7cee9f5` 源码快照；压缩文件 SHA-256：`82630764461ede390fd1fe9e97892d9937837d017d197cdce2b501825b69b699`。
- Zstandard 使用上游 CMake 固定的 v1.5.7 提交 `f8745da6ff1ad1e7bab384bd1f9d742439278e99`，通过 FETCHCONTENT_SOURCE_DIR_LO_PACK_ZSTD 提供本地源码。Windows 无需解出 CLI 测试中的 Unix 符号链接；只提取库、CMake 与许可文件。
- 原生构建目录放在 `E:\roms\LostOdysseyRecomp-local\build\android-runtime`，减少 D 盘占用。Build-NativeRuntime.ps1 支持 FfmpegSourceDirectory 和 ZstdSourceDirectory，避免重复拉取完整 Git 历史。
- plume 补丁幂等性通过独立临时 Git 索引核对完整三层补丁，避免叠加补丁改变上下文后误判基础补丁缺失；不回退用户的依赖工作目录。

原生边界测试的复现方式（这是普通测试可执行文件，不生成 APK）：

```powershell
$ErrorActionPreference = 'Stop'
$ndkBin = "$env:LOCALAPPDATA/Android/Sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin"
& "$ndkBin/clang++.exe" --target=aarch64-none-linux-android26 -std=c++20 -Wall -Wextra -Werror -static-libstdc++ -pthread -I LostOdysseyRecomp tools/tests/android_companion_test.cpp -o work/companion-checks/android_companion_test
if ($LASTEXITCODE -ne 0) { throw 'Native fixture build failed' }
adb -s c53b4da push work/companion-checks/android_companion_test /data/local/tmp/lo-companion-test
if ($LASTEXITCODE -ne 0) { throw 'Test transfer failed' }
adb -s c53b4da shell chmod 755 /data/local/tmp/lo-companion-test
if ($LASTEXITCODE -ne 0) { throw 'Test permissions failed' }
adb -s c53b4da shell /data/local/tmp/lo-companion-test
if ($LASTEXITCODE -ne 0) { throw 'Native fixture failed' }
```

测试覆盖原生 UTF-32 字符、guest 宽字符串保留的 UTF-16 代理对与不成对的非法码元，以及数据收集过程中发生暂停/恢复时的旧会话发布拒绝。JNI 转换兼容两种实际数据表示。

## 本轮最终构建结果

完整 Android 原生构建与链接成功（1285 个构建步骤），暂存目录为 `packaging/android/build/runtime-jni/arm64-v8a`。libmain.so 为含调试信息的编译中间文件，357,194,800 字节（首轮原生构建）；后续已生成并安装测试 APK。

全部 8 个 `.so` 均核实为 AArch64，所有 LOAD 段对齐至少 16 KiB。生产 libmain.so 的两个 CompanionSnapshot JNI 方法均存在动态导出，Java 构造函数 descriptor 也已通过 javap 与原生声明核对。

最后执行下列检查时没有跳过 native library 检查，结果通过：

```powershell
$ErrorActionPreference = 'Stop'
./tools/android/Invoke-LocalGradle.ps1 -Tasks @(':runtime:checkNativeLibraries', ':runtime:lintDebug') -ExtraArguments @('--offline')
```

最终 Lint：0 error、55 warning，新 CompanionSnapshot / DualScreenManager 无专属 lint finding。已完成的 79 项 JVM 测试、Java 快照模型测试和 Thor 原生 JNI/生命周期测试仍有效。

已获用户授权生成并安装测试 APK。首轮版本为 1.1.0-dualscreen.1，独立应用 ID 为 io.github.user081944.lostodyssey.thor。安装不覆盖上游应用。实机日志确认 Presentation 在显示器 4 打开，下屏页签触摸有效，主屏仍为顶层焦点显示器。用户完成驱动与 shader 下载后已进入游戏；完整地图数据、菜单迁移、手柄并行操作及后台恢复仍需继续验证。

主仓库工作区中 SDL 子模块的修改来自上游要求的 android-surface-lock 补丁；plume 与 XenonRecomp 同样使用仓库记录的依赖补丁，不推进子模块提交指针，不提交游戏数据或生成 PPC。

## 音频问题调查（2026-11-18）

用户在 Thor 上报告声音断续或爆音。首轮游戏日志显示：每 1875 个 256-sample 音频块（48 kHz 下应为 10 秒）实际需要约 12–13 秒，且队列见底与生产时钟重置计数持续增加。同期渲染约 30 FPS，未据此调整画质或更换驱动。

Android 音频生产改为依据设备队列消耗补充至原有 8 块上限，移除独立定时器丢弃迟到时间的行为；无回调、无输出设备或无提交时仍限制重试速度。桌面端时钟和快进混音逻辑保留。新增 producer_hz 诊断，正常速度目标为 187.5 块/秒。

1.1.0-dualscreen.2 的实机生产速度约 172–176 块/秒，较旧采样改善但仍低于目标；用户确认声音仍然断续，不能将此版本视为完成修复。已追加设备实际 buffer 大小与等待/休眠/回调耗时诊断，继续定位。日志中的 starved 指提交前发现 SDL 输入队列为空，不等同硬件 XRUN。

完整 Android 原生编译和 APK 签名、16 KiB ZIP 对齐核验通过。音频回调注册/替换/注销并发测试与快进音调/速率/切换测试已编译为 ARM64 并在 Thor 执行通过；这些是 CPU 逻辑测试，不打开真实音频设备，不能替代实机听感验收。测试包、设备日志、截图及游戏文件均仅留在忽略目录，不提交 Git。
后续使用 simpleperf 记录 10 秒含离 CPU 的调用栈，并用同次构建的未 strip libmain.so 符号解析：Audio Driver 的回调主要阻塞于 sub_82CC4F10 → KeWaitForMultipleObjects，等待游戏音频工作线程。512-sample SDL 设备缓冲约 10.67 ms；1 ms 休眠实测最长约 3–4 ms，但单次回调等待可达 65–89 ms。临时将两个工作线程 nice 提至 -16 没有消除停顿，已恢复 -4，未提交这项试验。

最终候选方案将 Android 队列储备改为 24 块（128 ms），覆盖上述等待并保留补充余量，代价是相较旧 8 块增加最多约 85 ms 队列延迟。生产目标与提交安全上限必须一致：提交上限由 max(16, kQueuedFrames) 推导；只有 SDL 真正接受数据才递增 g_framesQueued，无成功入队时限制重试速率，避免丢弃音频导致回调失去节拍。验证必须同时满足 producer_hz 约 187.5、queue_drops/queue_errors 不增长以及听感正常，不能仅依据 frames submitted 增长判断通过。
实机验收版本：1.1.0-dualscreen.5，已覆盖安装至同一独立应用，保留存档、驱动及 shader 缓存。APK SHA-256：084c561bb19e85e86aa382c3b99badfa484747a97e72bb6b299eb43508303fd3；签名与 16 KiB ZIP 对齐核验通过。热身后四个连续报告窗口 producer_hz 为 187.9、187.1、186.4、186.5，queue_drops=0、queue_errors=0；前约 52 秒 starved 累计 8，仍有少量输入队列见底，不能宣称所有场景零欠载。用户在该版上确认“声音正常了”。当前保留该版本继续游戏，后续长时间运行、切图、暂停恢复及蓝牙输出还需另行验证。
## Thor 原生 60 帧验证（2026-11-18）

用户要求检查优化空间并运行到 60 帧。设备当时的 settings.ini 为 frame_rate=30；上游已经支持 30/60/90/120 原生帧率，故本轮无需修改游戏时钟、音频节拍或引入插帧。获准操作与重启后，先在应用私有配置目录备份 settings.before-60.ini，再持久化 frame_rate=60、performance_overlay=2。保留 1280×720 内部分辨率、动态阴影、景深、泛光和动态模糊；fast_forward=0、frame_generation_provider=0。

仍使用音频修复版 1.1.0-dualscreen.5，未生成新的 APK。启动日志核实 requested=60、effective=60、source=guest_swap，presentation target=60、guest_refresh=60、clocks=unchanged。战斗与野外截图均显示 60 FPS / 16.7 ms；完整 10 秒窗口的渲染计数差覆盖约 60 秒，分别为 59.32、59.65、59.65、59.93、58.09、57.50 FPS。包含战斗切换，不能声称所有场景锁定 60，且本次没有获得逐帧分位数或扫描输出测量。

同段音频 producer_hz 约 187.0–188.1，queue_drops=0、queue_errors=0；starved 在切图时由 2 增至 4 后，后续窗口未增长。两张实机覆盖场景为开场战斗和乌奥鲁高原。shader 特化编译队列已完成，当前实际使用路径约 96–100% 为特化着色器，未清理缓存或换驱动。

目前优先解除 30 帧配置即可接近目标，无需先降低画质或更改全体 Android 设备默认值。后续优化应针对实测掉帧的具体场景：记录主线程/渲染线程逐帧耗时、GPU 时间与温度，区分切图/首次资源加载和持续负载后，再对阴影、景深、分辨率做单项对比。此前音频工作线程提高优先级的实验无收益，不作为本轮性能优化提交。设备已保留 60 帧配置与详细性能叠加层，原 30 帧配置备份仍在。
