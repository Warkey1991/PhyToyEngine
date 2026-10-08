# ToviCam UI 重构方案

日期：2026-10-08。状态：黑金界面代码、独立资源及导航接线已实施，已完成指定模拟器范围的界面验收；真机拍摄及真实商店结算仍待验证。完整构建、准确 APK 哈希与测试范围见 [验收报告](/Users/songyuanjin/GoogleApp/PhyToyCamera/PhyToyEngine/reports/ui_redesign_20261007/README.md)。

目标：以工作区 png 中的 9 张稿件为视觉依据，重构 Android 界面、控件和页间交互。保留现有拍照逻辑、引擎、风格参数、照片存储及购买授权规则。沿用当前 Native Views，不迁移 UI 框架。

## 1. 设计依据

| 稿件 | 用途 |
|---|---|
| [UI/UX 规范展板](/Users/songyuanjin/GoogleApp/PhyToyCamera/png/PhyToy相机UI_UX设计规范展板.png) | 总体层级、六类页面、反馈及动效的依据。展板标注的 0.16.0 只作为设计参考；接入项目现有功能。 |
| [图 1：Camera Style](</Users/songyuanjin/GoogleApp/PhyToyCamera/png/ChatGPT 图像 2026年10月7日 13_43_30-1.png>) | 风格详情：相机主视觉、性格短语、示例图、风格列表、购买操作。 |
| [图 2：曝光](</Users/songyuanjin/GoogleApp/PhyToyCamera/png/ChatGPT 图像 2026年10月7日 13_43_31-2.png>) | 顶部风格胶囊、闪光灯与设置、对焦框及曝光滑杆、底部拍摄区。 |
| [图 3：变焦](</Users/songyuanjin/GoogleApp/PhyToyCamera/png/ChatGPT 图像 2026年10月7日 13_43_34-3.png>) | 实时倍率、连续变焦滑杆和倍率快捷档位。 |
| [图 4：Gallery](</Users/songyuanjin/GoogleApp/PhyToyCamera/png/ChatGPT 图像 2026年10月7日 13_43_35-4.png>) | 按日期分组的照片网格、风格角标、底部 Camera / Gallery / Settings 入口。 |
| [图 5：照片回看](</Users/songyuanjin/GoogleApp/PhyToyCamera/png/ChatGPT 图像 2026年10月7日 13_43_36-5.png>) | 顶部返回与序号、照片主体、信息卡片、操作区、Continue shooting。 |
| [图 6：Settings](</Users/songyuanjin/GoogleApp/PhyToyCamera/png/ChatGPT 图像 2026年10月7日 13_43_38-6.png>) | Shooting / Experience / App 三组设置卡片及开关样式。 |
| [图 7：STREET 84](</Users/songyuanjin/GoogleApp/PhyToyCamera/png/ChatGPT 图像 2026年10月7日 13_43_39-7.png>) | 付费风格的主视觉、效果展示与底部解锁区域。与图 1 使用同一详情组件。 |
| [主拍摄页补充稿](/Users/songyuanjin/GoogleApp/PhyToyCamera/png/复古滤镜下的落日有轨电车.png) | 拍摄页常态布局。曝光数值和控制按图 2 补齐。 |

采用同一套视觉语言：近黑背景、半透明深色胶囊、暖金色选中态、白色正文、灰色次级文字、圆角相机卡片和线性图标。对外品牌统一为 ToviCam；关于页使用 ToviCam，完整商店名称继续使用 ToviCam: Retro Digital Camera。

## 2. 公共视觉组件

- 已建立 ToviTheme 集中主题：背景 #0B0B0C，卡片 #171719，强调色 #FFC629，正文 #F5F5F5，次级文字 #A5A5AC；公共标题和按钮沿用这些值，待当前构建截图校准。
- 统一 4 / 8 / 12 / 16 / 24 dp 间距、16 dp 常规页边距、16–24 dp 卡片圆角。按钮使用一致的圆角、描边和按压反馈。
- 使用原生文字和矢量图标绘制返回、闪光灯、设置、分享、删除等控件；相机插画和示例照片作为独立图片资源。整页 PNG 只作比对参考，不作为可交互页面背景。
- 风格列表与详情通过 CameraArtworkView 复用六款独立相机图片；照片信息卡使用统一相机线性图标。艺术资源只用于界面装饰，原图/效果示例独立展示，不作为实时成像或引擎输入。
- 默认英文本地化，保留中文资源。字号使用 sp，字体放大时允许换行或重排。所有可点击控件触摸区域至少 48 dp。
- 主按钮保留黄色实体底，非主操作使用深色或线框。减少装饰性光晕；实时取景上使用渐变和透明底板，避免持续实时模糊带来的额外绘制负担。

## 3. 页面与交互

### 拍摄首页

布局由三个区域组成：取景与顶部工具、风格横滑列表、底部快门栏。顶部左侧风格胶囊显示当前相机名和一句短描述，点击打开风格详情；右侧保留闪光灯和设置。底部左侧为最近照片，中央为白色快门加金色外环，右侧为前后摄像头切换。

风格卡片使用相机图、两行名称、金色选中边框和锁标。手机宽度不足时横向滚动，不把六张卡片和文字强行压进一屏。免费相机可直接拍摄；点击未解锁风格继续直接进入实时试用，并明确显示“仅预览 / 解锁拍摄”。购买详情由显式解锁入口或锁定快门进入。

保留原预览 TextureView 和 Surface，依据当前风格画幅与原有裁剪规则布局。不能为仿照长屏稿件拉伸预览或改变保存裁剪。常规竖屏按稿件布置，横屏将拍摄操作放到侧边，并保留可滚动风格条。

### 对焦、曝光与变焦

- 点击取景显示金色对焦框，框的成功或失败反馈绑定现有对焦回调，不使用固定延时假定对焦成功。
- 点击 EV 打开纵向曝光滑杆，显示带正负号的真实 EV；大字体或短窗口回退为横向滑杆，继续使用现有曝光回调和设备范围。已有取景手势保留，通过 Done 收起，EV 入口可再次打开。
- 倍率入口默认显示真实当前倍率；点击打开图 3 的连续滑杆，并保留双指缩放。快捷档位只展示设备支持的值，不能固定承诺 0.5×。
- 1.7× 等中间值不能仍把 1× 画成当前选中值；快捷档位只在与实际倍率匹配时高亮。
- 曝光和变焦面板互斥显示，避免两个面板遮挡取景。首次使用用短提示说明点击对焦、上下滑调曝光和双指缩放。

快门反馈继续对应现有状态：按下给触感，实际捕获回调给声音与白闪，处理/保存期间显示进度环及“显影中”，成功后更新缩略图。拍摄期间沿用现有参数冻结和控件禁用，不修改时序或重复发起捕获。

### 风格详情与购买

图 1 和图 7 合并为一套自适应详情模板：上方相机主视觉、中间简短特点与效果示例、下方固定操作。窄屏使用纵向排版，宽屏可将相机图与标题并排，不照搬图 1 过宽的标题布局。

复用现有原图/效果示例资源和说明，优先展示可对照的成像差异；如需补充同场景对比，只制作离线示例素材，不调整引擎。相机插画与示例照片应区分，示例保留说明。

保留免费试用、解锁、恢复购买及重试入口。按钮价格来自对应商店，不写死稿件中的 $9.99；加载、暂不可购买、购买中、待确认、已拥有与失败提示分别呈现。已拥有风格主操作变为使用相机。Google Play 和 Galaxy 使用同一布局，渠道归属及恢复规则沿用现有实现。

### 相册

采用图 4 的日期分组、圆角缩略图和风格角标。日期来自真实拍摄时间，并按设备本地化显示；仍只浏览本应用照片。普通手机竖屏以三列为基准，窄屏/大字体可降为两列，横屏和宽屏增加列数。

点照片立即显示回看加载状态；返回相册时保留滚动位置。空相册提供“去拍第一张”，失败时给重试。底部提供 Camera / Gallery / Settings 入口，拍摄页继续使用专用快门栏。设置返回时回到进入它的页面。

本次省去稿件中的 Select：当前没有多选及批量处理功能，先确保单张浏览、分享和删除完整可用。

### 照片回看

采用图 5 的返回、真实序号、照片主体、深色信息区域和金色 Continue shooting。初始照片完整适配，不为铺满画面裁去照片边缘；放大后才允许拖动。

已保留双指/双击放大、复位、上一张/下一张、系统分享、确认删除和加载重试。新增左右滑动切换，复用现有相邻照片回调：仅在适配屏幕倍率且本次手势没有双指/双击时识别，水平移动至少 56 dp 且明显大于垂直移动；放大时横向拖动只移动照片。保留显式上一张/下一张操作，便于无障碍使用。

底部采用分享、删除、信息三个实际入口，省去目前没有的 Favorite。信息卡及 Info 弹窗展示保存记录中的相机风格、真实尺寸/像素、画幅和文件名；回看时间由现有拍摄文件名推导，缺失时省略。当前没有增加 EXIF 字段读取，ISO、光圈省略，不使用稿件的固定 12MP / f/1.8 / ISO 50。

小屏和大字体减少常驻信息，将细节收进信息面板；横屏采用侧边操作区，空间不足时滚动，保证分享、删除、继续拍摄都可到达。删除保留现有确认及系统授权流程。

### 设置

沿用图 6 的三组卡片视觉，条目改为当前实际功能：

- Shooting：默认相机、记住上次相机、照片质量、取景网格。
- Experience：快门声音、触感反馈、拍后回看。
- App：恢复购买、关于 ToviCam、隐私政策、开源许可、帮助/反馈、重置设置。其中支持联系方式仅在实际配置后显示。

每行显示标题、当前值及短提示；大字体允许换行，选择项的值移到标题下方，详细拍摄说明通过帮助按钮展开。质量档位继续使用现有上限规则，不标成保证输出 12MP；风格和设备仍可能限制实际像素。

省去当前没有的 Save location 和 Auto rotate 开关；Save to gallery 替换为实际的拍后回看选项，不引入新的存储分支。

## 4. 明确的重构边界

允许修改控件外观、布局、页间导航、已有状态的呈现、回看手势，以及独立界面资源。MainActivity 只做界面组装和已有回调绑定，不改拍摄、预览启动/关闭或保存算法。

原样保留：Camera2 和 3A 控制、拍摄参数快照、闪光灯策略、画幅/旋转/镜像规则、照片编码与 EXIF 写入、MediaStore 存储和索引规则、设置持久化与默认值、购买授权与恢复规则、SDK 接口、native 引擎、profile、shader。

本次不增加自由画幅切换、收藏、多选、定位、照片编辑或新的温控策略。展板中的高温示例只有在现有系统状态确实提供该信息时才可显示；普通加载、忙碌、权限失败和相机不可用使用现有状态，不把错误统一包装成高温。

主要接入点：

| 文件 | 本次职责 |
|---|---|
| [ToviTheme.kt](/Users/songyuanjin/GoogleApp/PhyToyCamera/PhyToyEngine/android/sample/src/main/kotlin/com/phytoy/sample/ToviTheme.kt)、[CameraArtworkView.kt](/Users/songyuanjin/GoogleApp/PhyToyCamera/PhyToyEngine/android/sample/src/main/kotlin/com/phytoy/sample/CameraArtworkView.kt) | 黑金主题、展示名称及独立相机图片加载，均只服务界面。 |
| [CameraChrome.kt](/Users/songyuanjin/GoogleApp/PhyToyCamera/PhyToyEngine/android/sample/src/main/kotlin/com/phytoy/sample/CameraChrome.kt) | 拍摄界面、风格条、对焦呈现、快门状态外观。 |
| [CameraAdjustmentPanel.kt](/Users/songyuanjin/GoogleApp/PhyToyCamera/PhyToyEngine/android/sample/src/main/kotlin/com/phytoy/sample/CameraAdjustmentPanel.kt) | EV / 变焦面板外观，保留参数选择回调。 |
| [PageTopBar.kt](/Users/songyuanjin/GoogleApp/PhyToyCamera/PhyToyEngine/android/sample/src/main/kotlin/com/phytoy/sample/PageTopBar.kt) | 公共标题、返回和深色视觉组件。 |
| [PhotoGalleryOverlay.kt](/Users/songyuanjin/GoogleApp/PhyToyCamera/PhyToyEngine/android/sample/src/main/kotlin/com/phytoy/sample/PhotoGalleryOverlay.kt) | 相册日期分组、缩略图卡片与导航。 |
| [PhotoReviewOverlay.kt](/Users/songyuanjin/GoogleApp/PhyToyCamera/PhyToyEngine/android/sample/src/main/kotlin/com/phytoy/sample/PhotoReviewOverlay.kt)、[ZoomablePhotoView.kt](/Users/songyuanjin/GoogleApp/PhyToyCamera/PhyToyEngine/android/sample/src/main/kotlin/com/phytoy/sample/ZoomablePhotoView.kt) | 回看布局、信息面板、滑动与放大冲突处理。 |
| [CameraPurchaseOverlay.kt](/Users/songyuanjin/GoogleApp/PhyToyCamera/PhyToyEngine/android/sample/src/main/kotlin/com/phytoy/sample/CameraPurchaseOverlay.kt) | 统一风格详情和付费状态布局。 |
| [SettingsOverlay.kt](/Users/songyuanjin/GoogleApp/PhyToyCamera/PhyToyEngine/android/sample/src/main/kotlin/com/phytoy/sample/SettingsOverlay.kt) | 三组设置卡片、图标和紧凑行。 |

## 5. 已实施内容及资源核对

以下为代码/资源实施状态，不代表已经通过设备验收。

| 页面 | 已实施内容 |
|---|---|
| 拍摄 | 黑金风格胶囊、闪光灯/设置、相机图片横滑卡片、白色快门与金色选中态；短窗口/横屏采用紧凑布局；沿用实际对焦、快门与保存反馈回调。 |
| EV / 变焦 | 金色滑杆、设备真实范围、EV 方向适配、支持档位快捷入口；中间倍率不会把 1× 当作当前值高亮。 |
| 风格试用 / 购买 | 六款相机共用详情模板，原图/效果示例对照；独立试用/解锁操作区，短窗口为正文和操作各保留滚动视口；价格与 owned/pending/busy 等状态来自现有商店控制器。 |
| 相册 | 依据本地日期分组、2–6 列虚拟化照片行、圆角照片及风格角标；底部 Camera / Gallery / Settings 已接线，设置关闭回到相册；空态/失败恢复及滚动锚点保留。 |
| 回看 | 真实序号、信息卡与 Info 弹窗、分享/确认删除/继续拍摄；放大与左右切图互斥；竖屏工具可滚动，横屏及很短窗口整列滚动。 |
| 设置 | Shooting / Experience / App 三组卡片、线性图标、金色开关、紧凑说明、按需帮助；保存设置、恢复购买结果、重置确认沿用现有规则。 |

资源名称已与当前源码核对：

| 用途 | 当前资源 |
|---|---|
| 六款相机主视觉（drawable-nodpi） | camera_art_dh_color.png、camera_art_dh_mono.png、camera_art_digital.png、camera_art_plastic.png、camera_art_street.png、camera_art_fisheye.png。 |
| 原图 / 效果示例（drawable-nodpi） | style_source_{dh_color,dh_mono,digital,plastic,street,fisheye}.webp 与对应 style_sample_*.webp，共十二张。 |
| 图库 / 回看图标（drawable） | ic_gallery_camera.xml、ic_gallery_photos.xml、ic_gallery_settings.xml、ic_photo_share.xml、ic_photo_delete.xml、ic_photo_info.xml；加载占位沿用 ic_gallery_placeholder.xml。 |
| 文案 / ID | values/tovi_ui.xml、camera_controls.xml、gallery.xml、gallery_ids.xml、review.xml、photo_actions.xml、settings.xml、settings_ids.xml、billing.xml；中文使用已有 values-zh / values-zh-rCN 对应资源，渠道购买文案仍由 Play / Galaxy 资源覆盖。 |

对外名称继续为 ToviCam: Retro Digital Camera，启动器为 ToviCam；uiName 仅调整界面展示名称，保存风格标识、包名、引擎 profile 和 Pictures/PhyToy 相册路径不变。

## 6. 验收结果及剩余范围

两渠道 benchmark / release APK / AAB 已构建成功；41 项 JVM 单元测试通过，Release Lint 为 0 errors（仍有 warnings）。指定模拟器上的普通字号、200% 字体、640×320dp 短横屏与渠道文案/恢复/隐私/未配置结算状态均已验证；真实照片序号、切换、双击放大后拖动不切图、分享返回及删除取消有通过记录。各项范围、准确哈希及构建次序见验收报告。

最后增加普通字号小屏回看的操作区空间，使 Share / Delete / Info 首屏完整显示，最新 APK 已通过 >=68dp 高度检查与导航回归。完整 12 项照片/界面回归来自这次空间调整之前的构建；200% 字体和横屏分支未受该调整影响。报告明确区分最终 APK 的针对性验证与前次完整回归。

原有拍照、引擎、3A、裁剪、编码、存储及授权文件的 diff 均为空；MainActivity 仅做界面布局和接线。测试没有操作连接的真实手机，也没有拍摄、付款、发送分享或真正删除照片。

后续仍需真机成像/时序与 EXIF 验证、真实商店购买/恢复、系统删除授权、多指缩放、完整 TalkBack，以及更多语言/设备形态测试。正式商店提交包需要发布签名；当前提供的 APK 用于 UI 安装验收。

动效建议：普通按压 80–120 ms，面板展开/收起 150–200 ms；风格切换的视觉淡入淡出绑定真实切换状态，不重建预览 Surface。捕获白闪绑定现有实际捕获事件。尊重设备减少动画设置。

视觉验收按页面与设计稿比对层级、配色、圆角、卡片比例和按钮位置；以当前真实照片/风格替代稿件示意素材后，再检查普通手机、窄屏、横屏及 100% / 200% 字体。所有主操作不得被系统栏遮挡、截字或压缩到不足一个触摸目标。

功能验收覆盖 EV/变焦真实数值、对焦状态、拍摄参数冻结、前后切换、拍摄保存与画幅一致性、试用/解锁/恢复状态、回看缩放与切图、分享返回和删除取消。明确核对引擎及拍摄逻辑文件未产生本次重构修改；Google Play 与 Galaxy 分别验证界面和购买状态。具体已通过范围以验收报告为准。
