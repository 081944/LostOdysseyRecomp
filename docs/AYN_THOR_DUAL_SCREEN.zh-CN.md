# AYN Thor 双屏版本实施方案

状态：架构调研与分支初始化，尚未实现双屏功能。
基线：上游 main，f9da0ae9bdd481a5ea4fe6431f3a4724d8261147。
调研日期：2026-11-10。

## 目标与推荐路线

上屏保留游戏场景、剧情与战斗；下屏显示独立地图、地图信息，以及逐步迁移的野外菜单。游戏只运行一个实例。先打通双显示器与只读信息，再迁移小地图绘制，最后处理可交互菜单。推荐混合方案：Android 原生 UI 管理副屏与信息面板，原游戏 UI 的迁移按需增加原生 GPU 输出。

完整菜单迁移不是简单设置屏幕位置：原作的菜单状态机、暂停逻辑、绘制和输入仍然属于游戏。把菜单显示到下屏，并不自动让上屏世界在菜单打开时继续运行；首版保留原有暂停语义。

## 已核实的条件

- 上游已有 Android ARM64、SDL3、Vulkan、物理手柄与 Java 触屏控制，无需重做安卓移植。
- 当前连接的 AYN Thor 运行 Android 13。ADB 显示两块独立显示器：主屏 displayId=0；Screen-2 当前 displayId=4，带 FLAG_PRESENTATION 和 FLAG_TRUSTED，未见 FLAG_PRIVATE。显示 ID 仅为本次观测，禁止写死。
- Screen-2 物理模式 1080×1240，本次逻辑旋转后 real=1240×1080，app=1240×1025。必须使用目标显示器上下文、实际 View 尺寸与 Insets，不能把宣传分辨率作为可用内容区域。
- 这些信息仅证明 Presentation 路线具备系统条件；尚未在设备上创建副屏窗口，也未验证触摸、焦点和后台行为。
- `gpu/hor_plus.cpp` 已封装小地图函数 `sub_82309408`，注释说明其覆盖背景、裁剪后的地图/图标和边框文字，并在边界 FlushCanvas。
- 同文件已有野外菜单 `sub_8230EC88` 及分发 `sub_8230D6B8` 钩子。它们当前用于布局修正，不是已经可用的独立菜单渲染接口；不代表战斗、商店、存读档均已覆盖。
- `debug/map_info.*` 提供地图 ID 与名称快照；`debug/teleport.h` 有玩家位置与 POI 快照；`debug/map_poi.*` 有已加载地图的 POI 扫描。它们不是完整地图底图或道路几何数据。
- `gpu/presentation.h` 有 DrawSeparatedUi 合成接口，但现有 frame_generation_composite.h 明确不保证已分离 UI。不能据此假定小地图或菜单已有可直接导出的纹理。

## 双屏结构

1. `RuntimeActivity extends SDLActivity` 继续拥有主屏 SDL Surface 和唯一游戏运行时。
2. 新增 DualScreenManager：通过 DisplayManager 枚举 Presentation 显示器，排除主屏；保存用户选择并在重连时重新匹配，处理新增、移除和变化。无副屏、窗口被拒绝或创建失败时回退单屏。
3. 新增 CompanionPresentation：使用自身 Context 创建下屏 View；首版用 Android Canvas/View 画地图名称、玩家位置、POI 列表、页签和状态。无需第二个 Vulkan swapchain 即可验证独立信息显示。
4. 游戏线程发布有界、带版本/场景标识的只读 CompanionSnapshot，经 JNI 交给下屏。UI 不直接读取 guest 指针，不扫描 UObject，不持锁等待渲染；加载与切场景时清空旧数据。
5. 下屏操作进入有界命令队列，由游戏线程检查当前菜单、场景和对象有效性后处理。禁止从 Java UI 线程直接调用 guest 函数或写游戏内存。
6. 原游戏小地图/菜单需要迁移时，再为 Presentation 添加独立 SurfaceView，并扩展 Vulkan/plume 呈现路径。每个 Surface 使用独立 swapchain、尺寸、资源寿命和同步；副屏不可用时不能卡住主屏队列。

优先验证 Presentation。若 Thor 固件对其焦点或触摸限制导致不适用，再评估普通副屏 Activity + launchDisplayId；不要启动第二个 SDLActivity，因为现有 SDL/运行时的单实例与生命周期设计需要保留。

## 地图与菜单的实现选择

| 内容 | 推荐方式 | 尚需验证 |
| --- | --- | --- |
| 地图名、坐标、POI 信息 | 复用游戏线程快照，下屏原生绘制 | POI 完整性、楼层及切图失效处理 |
| 位置示意图 | 读取位置与 POI，明确标为示意图 | 世界坐标到平面坐标转换，不能伪称完整地图 |
| 原作小地图 | 从已识别钩子开始，追踪绘制批次并重定向到独立纹理 | 底图资源、缩放、旋转、剪裁、图标、可见性与延迟命令 |
| 野外装备/道具/状态菜单 | 优先复用原状态机与绘制，迁移输出目标 | 菜单背景、淡入淡出、暂停语义、子菜单覆盖 |
| 下屏触摸菜单 | 先做页签与缩放；原作菜单先保留手柄操作 | 焦点、按下/松开、触点映射与输入消费规则 |
| 战斗菜单、商店、存读档 | 后续逐项支持 | 各自入口与状态机，不能沿用野外菜单结论 |

原作小地图迁移需先用诊断记录验证批次边界。游戏 CPU 钩子的 thread_local 标志不会自动随异步 GPU 命令传播；目标层标记必须跟随被提交的绘制命令或在有序边界完成捕获，不能在 GPU 线程读取游戏线程的临时标志来分类。重定向后须保存并恢复渲染目标、viewport、scissor、混合与其他状态；确认副屏成功显示后再隐藏上屏对应 UI。

首选 GPU 内的纹理路由/采样，避免逐帧 GPU 回读 Bitmap。若重放绘制，必须确认不存在重复推进动画或修改游戏状态。透明叠加可能依赖目标颜色，不能假定所有菜单都能在透明底上得到相同结果。副屏初期以 30 FPS 为预算起点；信息快照可更低频刷新，上屏与下屏不强制同帧率。

输入须由单一分发层决定当前消费者。下屏获得焦点后 SDL 的手柄事件与 onWindowFocusChanged 行为必须实测；避免同一按键既触发下屏又触发主屏。取消、失焦、暂停和关屏时清理所有按下状态。

## 分阶段交付与验收

1. **构建基线**：用自己的合法游戏数据生成 PPC 源码，准备自己的 Android DXC 和签名；先验证未改功能版本在 Thor 可启动、进入场景、存读档和恢复。保留设备/GPU/驱动与帧时间记录。
2. **副屏与只读信息**：显示下屏测试页，再接入地图名称、位置和 POI；验证上下屏独立、旋转、主副屏选择、触摸、焦点、合盖/唤醒、后台/恢复、无副屏回退。此阶段不声称完成原作地图迁移。
3. **原作小地图**：验证钩子边界、资源和命令归属，输出到下屏；覆盖地图背景、图标、旋转、切图、隐藏/缩放与过场，确保上屏无误删。记录双屏前后 GPU/CPU 帧时间及温度趋势。
4. **野外菜单迁移**：装备、道具、状态逐项测试；保留原暂停逻辑与手柄输入，再添加触摸。菜单迁移失败时仍可使用上屏。
5. **扩展与优化**：按需增加战斗信息、商店和其他页面；限制副屏刷新，测试长时间游玩、驱动差异、资源释放和重复唤醒。

每阶段独立提交。窗口显示、数据正确、游戏可操作、生命周期稳定、性能可接受是不同的验收项；编译通过不能代替设备验收。

## Fork 与维护

- `origin`: https://github.com/081944/LostOdysseyRecomp.git
- `upstream`: https://github.com/freefrank/LostOdysseyRecomp.git
- `main` 跟踪上游，个人版本分支为 `feature/android-dual-screen`。
- 通用副屏管理集中放在 Android 层；跨平台快照与命令接口放独立模块；guest 钩子集中维护；桌面和单屏默认路径保持可用。
- 已发布的个人分支同步上游优先 merge，避免反复改写历史。具体功能可再拆短期分支，完成后合入个人版本分支。
- fork 使用独立 applicationId（建议 `io.github.user081944.lostodyssey.thor`）、应用名及自己持有的固定签名，使上游和个人版本可并存。改 applicationId 时检查 JNI 命名、显式组件、文件路径和文档；Java namespace 可先保留以缩小改动。
- 调整更新检查仓库/发布链接，或在个人版禁用上游应用更新提示；不要连带修改 shader pack 与 GPU 驱动来源。
- 上游 `.github/workflows/android-apk.yml` 需要 `LO_BUILD_INPUT_KEY` 访问作者私有仓库中的 XEX、Android DXC 和 debug.keystore；fork 不继承这些密钥。需建立自己的私有输入或本地构建流程，不能直接期待上游 CI 可运行。
- 所有新执行脚本遵循当前 PowerShell 7、Stop 错误处理和 UTF8 规则；上游现有 Bash/批处理流程需要另行评估或移植，当前未执行。
- 保留项目许可证与第三方声明；公开 fork 只提交源码与文档，不提交游戏数据、生成 PPC、密钥、存档或捕获。

## 参考

- 上游安卓实现与构建说明：../packaging/android/README.md
- Android Presentation：https://developer.android.com/reference/android/app/Presentation
- AYN Thor 产品信息：https://www.ayntec.com/products/ayn-thor

本文件描述计划与源码/只读设备调研，不代表双屏功能已经开发或实机通过。
