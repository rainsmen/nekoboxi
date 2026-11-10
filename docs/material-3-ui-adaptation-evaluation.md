# Material 3 界面实现与验收

更新时间：2026-11-10。适用范围：`feat/reliability-and-ui-20261110`，不能据此推断已发布的 1.6.0 具有全部行为。

## 已实现的范围

- 主主题、启动主题和对话框使用 Material 3；保留原有 1–21 主题编号，避免已有偏好变成另一种颜色。
- 21 套浅色、深色调色板采用 Material 1.12.0 的 `SchemeTonalSpot` 生成。生成器在 `buildScript/ui/GeneratePalettes.java`，结果在 `values/m3-palettes.xml` 和 `values-night/m3-palettes.xml`。
- 设置中提供系统动态配色开关；仅在平台支持时启用，不支持时使用选定的静态主题。切换配色后重建界面。
- 节点卡片采用 16dp 圆角、表面色和边框；选中状态同时更新背景与描边。工具栏、标签和流量栏使用表面色，连接按钮使用对应的容器前景色。
- Activity 基类处理状态栏、导航栏、刘海和输入法边距；抽屉单独处理顶部边距。列表保留自身的初始 padding。
- 配置编辑器的正文与 JSON 语法配色使用主题表面/前景语义色；工具按钮与字符键使用对应的 primary 前景色。Robolectric 检查浅深色正文对比度及键盘显示后工具条位置。

这里的 Material 3 指主题、配色和已列出的控件改造，不意味着所有自定义控件都已替换，也不保证所有设备的返回动画、导航方式和动态壁纸颜色都经过验证。

## 代码入口

| 范围 | 入口 |
|---|---|
| 主题选择与动态配色 | `utils/Theme.kt`、`ui/SettingsPreferenceFragment.kt` |
| 色彩语义、组件主题 | `res/values/themes.xml`、两套 `m3-palettes.xml` |
| 页面安全边距 | `ui/ThemedActivity.kt`、`widget/WindowInsetsListeners.kt` |
| 节点选中状态 | `ui/ConfigurationFragment.kt`、`layout_profile.xml` |
| 编辑器工具条 | `layout_edit_config.xml`、`ExtendedKeyboard.kt` |
| 资产列表与使用说明 | `ui/AssetsActivity.kt`、`layout_asset_item.xml` |

## 验收方法

自动检查应覆盖主题资源加载、前景/背景配对、重复分发 insets 后不累加边距、带 appbar 时抽屉仍保留顶部安全区。界面渲染截图用于发现截断、重叠和层次问题，不替代设备交互测试。

设备矩阵至少包括 Android 10、12、15；浅色/深色；静态色/动态色；手势导航/三键导航；刘海屏、横屏和大字体。页面包括节点列表、抽屉、配置编辑器、设置、资产列表和错误对话框。

当前环境没有连接 Android 设备或可启动的模拟器。不能把 Robolectric 渲染、资源测试或编译通过写成“全部设备已验收”。实际命令、测试结果和构建证据统一记录于 [可靠性改进记录](reliability-improvement-2026-11.md)。

## 后续增强

预测性返回交互、全部 Preference 开关替换、平板双栏和专门的无障碍交互测试应单独设计与验证，避免把依赖升级当作这些功能已经完成的证明。
