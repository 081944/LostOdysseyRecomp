# dualscreen 开发与本机验证记录

更新：2026-11-17。个人版本分支：`dualscreen`。本文件不包含游戏内容。

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
- 完整原生运行库已链接，8 个运行库已暂存，JNI 导出和 16 KiB ELF LOAD 对齐检查通过。尚未创建/安装本次修改的 APK，未声称完成触摸、焦点、合盖、恢复或游戏流程实机验证。

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

完整 Android 原生构建与链接成功（1285 个构建步骤），暂存目录为 `packaging/android/build/runtime-jni/arm64-v8a`。libmain.so 为含调试信息的编译中间文件，357,194,800 字节；尚未制成可安装包。

全部 8 个 `.so` 均核实为 AArch64，所有 LOAD 段对齐至少 16 KiB。生产 libmain.so 的两个 CompanionSnapshot JNI 方法均存在动态导出，Java 构造函数 descriptor 也已通过 javap 与原生声明核对。

最后执行下列检查时没有跳过 native library 检查，结果通过：

```powershell
$ErrorActionPreference = 'Stop'
./tools/android/Invoke-LocalGradle.ps1 -Tasks @(':runtime:checkNativeLibraries', ':runtime:lintDebug') -ExtraArguments @('--offline')
```

最终 Lint：0 error、55 warning，新 CompanionSnapshot / DualScreenManager 无专属 lint finding。已完成的 79 项 JVM 测试、Java 快照模型测试和 Thor 原生 JNI/生命周期测试仍有效。

构建环境与运行库已准备好。下一个需要授权的动作是生成测试 APK，并安装到 Thor 验证 Presentation 窗口、触摸、手柄焦点、后台恢复与真实地图数据。当前没有运行 assembleDebug 或其他 APK 生成命令，也没有安装这次修改的应用。

主仓库工作区中 SDL 子模块的修改来自上游要求的 android-surface-lock 补丁；plume 与 XenonRecomp 同样使用仓库记录的依赖补丁，不推进子模块提交指针，不提交游戏数据或生成 PPC。
