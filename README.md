# SBPlus

Samsung Browser 增强模块（**LSPosed** 模块）。

以 LSPosed 为运行框架，为三星浏览器（Samsung Internet，包名 `com.sec.android.app.sbrowser`）
补充官方未提供的功能。

---

## English

A **LSPosed** module that enhances Samsung Internet Browser (package `com.sec.android.app.sbrowser`) with features not provided officially.

### Features

1. **Download Bridge** — forward downloads to a third-party manager (customizable package), passing Cookie / UA / Referer; optionally block the native download.
2. **Settings Integration + Logging** — inject an SBPlus submenu into browser settings, with a built-in log viewer.
3. **Grid Menu** — turn the "More" menu into a multi-column grid, drag-to-reorder, add/remove icons.
4. **Region Switch** — switch the browser region to one of 17 countries.
5. **UA Spoofing** — fully replace the User-Agent (Desktop Chrome / Mobile / iPhone / custom).
6. **Streamlined Settings** — master switch + multi-select hiding for unneeded items.
7. **Block Updates & Red Dots** — block browser updates, clear update notifications / popups / red dots.
8. **Homepage Video Background** — play a video on the homepage (loop, muted).
9. **Userscript Manager** — full built-in manager: list / add / save / delete / toggle, `.user.js` interception, source management, "update all", lightweight GM API, detail page + share import.
10. **Bookmark Management** — import / export bookmarks (Chrome / Edge / Firefox HTML format), tree checkbox dialog.
11. **Random UA** — 55 real UA strings with random rotation; supports platform/browser multi-select and custom parameters.
12. **Media Sniffer & Download Engine** — multi-threaded download, tab-based by type (video/audio/image). Supports Bilibili DASH merge (4K/1080P+/HDR), Douyin/Kuaishou/Xiaohongshu/Weibo/AcFun/Toutiao, YouTube/TikTok/X/Instagram/Facebook, auto-remux to MP4.
13. **Homepage Beautification** — personalize the homepage search box and other elements.
14. **Version + Project URL + Auto Update Check** — version and project URL shown in module home and browser menu, with auto update checking.

### Requirements

1. Rooted device with **LSPosed** installed (via Magisk).
2. Install the module APK, then enable it in LSPosed Manager.
3. Set scope to **Samsung Internet** (`com.sec.android.app.sbrowser`) or Beta (`com.sec.android.app.sbrowser.beta`).
4. Restart the browser (or device).

### Supported Versions

| Channel | Package | Version |
|---------|---------|---------|
| Stable | `com.sec.android.app.sbrowser` | 30.0.0.67, 30.1.0.67 |
| Beta | `com.sec.android.app.sbrowser.beta` | 30.0.0.67 |

The module resolves obfuscated class/method names at runtime, so it generally adapts to newer browser versions automatically without a module update.

### Build

```bat
call <path-to-android-sdk>/env.bat
gradle assembleDebug --no-daemon
```

Output: `app\build\outputs\apk\debug\app-debug.apk`


### Self-adaptation

The module resolves Samsung's obfuscated / version-dependent class and method names **at runtime**, instead of hardcoding them:

- **Dynamic parent resolution** — obtains the parent (obfuscated androidx class) of `PreferenceFragmentCustom` at launch, so it adapts to whichever obfuscation name the browser version uses.
- **Multi-candidate method fallback** — for obfuscated methods (e.g. `PreferenceManager`'s `createPreferenceScreen`), tries candidate names in order, standard first then obfuscated names.
- **Isolated error handling** — each hook is independently guarded; a failure in one does not break others or the browser itself.

Re-resolution happens on every browser start, so after a browser update the module adapts automatically without a module update.

---

## 中文

### 框架说明

- **目标框架：LSPosed**（基于 LSPosed 新 API `io.github.libxposed.api`，封装传统 Xposed 风格兼容层 `XC_MethodHook`）
- 模块入口：`resources/META-INF/xposed/java_init.list` → `MainModule` → `MainHook`
- 架构：`MainModule`（入口）→ `MainHook`（单核巨类，21 个功能隔离注册）+ 17 个辅助类

### 使用前提

1. 手机已 root，并安装 **LSPosed**（Magisk 模块方式）
2. 安装本模块 APK 后，打开 LSPosed Manager：
   - 启用本模块
   - **作用域（Scope）勾选「三星浏览器」**（`com.sec.android.app.sbrowser`）
3. 重启三星浏览器（或重启系统）

> 作用域需要时可在 LSPosed 界面手动勾选。

### 支持版本

| 渠道 | 包名 | 版本 |
|------|------|------|
| 正式版 | `com.sec.android.app.sbrowser` | 30.0.0.67, 30.1.0.67 |
| Beta 版 | `com.sec.android.app.sbrowser.beta` | 30.0.0.67 |

模块通过运行时自适应解析混淆类名/方法名，通常浏览器更新后无需更新模块即可自动适配。

### 已实现功能

### 1. 下载桥接
下载请求转交第三方下载器（ADM / IDM+ / 1DM，包名可自定义），传递 Cookie / UA / Referer，可选阻断原生下载。

### 2. 设置集成 + 日志
浏览器设置页注入 SBPlus 子菜单，内置日志查看。

### 3. 网格菜单
「更多」菜单改多列网格，拖拽排序、添加/删除图标。

### 4. 改区
切换浏览器地区到 17 国之一。

### 5. UA 伪装
完整替换 User-Agent（桌面 / 手机 / iPhone / 自定义），需重启浏览器。

### 6. 精简设置页
多选隐藏不需要的设置项。

### 7. 屏蔽更新
屏蔽浏览器更新通知 / 弹窗 / 红点，阻断更新检查和商店网络。

### 8. 主页视频背景
主页背景循环静音播放视频，支持选择/清除/删除。

### 9. 油猴脚本管理
脚本列表 / 添加 / 删除 / 开关，拦截 `.user.js` 安装，源管理 + 批量更新，精简 GM API，分享导入。

### 10. 书签管理
导入 / 导出书签（Chrome / Edge / Firefox HTML 格式），树形勾选对话框。

### 11. 随机 UA
内置 55 条真实 UA 随机轮换，支持平台/浏览器多选和自定义参数。

### 12. 资源嗅探与下载引擎
多线程下载，按视频/音频/图片分 tab 显示。支持 B站 DASH 合并（4K/1080P+/HDR）、抖音/快手/小红书/微博/AcFun/头条、YouTube/TikTok/X/Instagram/Facebook，分片自动 remux 转 MP4。

### 13. 主页美化
主页搜索框等元素个性化设置。

### 14. 自动更新
显示版本号 + 项目地址，自动检测 GitHub 新版本并提示更新。

### 构建

```bat
call <path-to-android-sdk>\env.bat
gradle assembleDebug --no-daemon
```

产物：`app\build\outputs\apk\debug\app-debug.apk`


### 自适应说明

模块对三星浏览器内部被混淆/随版本变化的类名与方法名做了**运行时自适应解析**，而非硬编码：

- **动态父类解析**：通过明文类 `PreferenceFragmentCustom` 动态获取其父类（androidx 混淆类），
  无论浏览器版本把混淆名改成 `H2.A` 还是其他，都能自动找到。
- **多候选方法回退**：对被混淆的方法（如 `PreferenceManager` 的 `createPreferenceScreen`）
  按候选名列表依次尝试，标准名优先、混淆名兜底。
- **独立容错**：每个 hook 点单独保护，单个失败不影响其他功能，也不影响浏览器自身。

浏览器每次启动时都会重新解析适配，浏览器更新后无需模块更新即可自动适配新版本。


### 开发环境

- Windows AMD64
- Java 17（Temurin）
- Gradle 8.7
- Android SDK 34+
- 依赖仓库：阿里云镜像（解决国内拉取 AGP 依赖超时问题）
- 测试设备：三星 Galaxy（Android 16 / SDK 36）

### 项目结构

```
SBPlus/
├── app/
│   ├── build.gradle              (namespace/applicationId = com.sbplus.browser)
│   ├── libs/libxposed-api-102.jar (Xposed API 编译期依赖)
│   └── src/main/
│       ├── AndroidManifest.xml   (xposedmodule 声明)
│       ├── resources/META-INF/xposed/  (LSPosed 模块元数据: 入口/属性/作用域)
│       ├── res/values/           (app_name=SBPlus, xposedscope)
│       └── java/com/sbplus/browser/
│           ├── MainHook.java               (核心 hook，全部功能)
│           ├── MainModule.java             (LSPosed 模块入口)
│           └── ...                         (辅助类: 菜单/字体/主题/日志等)
├── build.gradle
├── settings.gradle               (rootProject.name=SBPlus)
└── gradle.properties
```
