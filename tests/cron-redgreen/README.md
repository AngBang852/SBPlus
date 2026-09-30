# SBPlus 测试

本目录是**唯一进版本控制**的测试资产。两类内容性质不同，分开说明。

---

## 一、`cron-redgreen/` —— 自动回归测试（推荐日常使用）

纯 Java 红绿测试，**零依赖**：不需要 Android SDK、不需要 Gradle，只要一个 JDK。
每个文件顶部的类注释都记录了「修复前会 FAIL 的预期」与「修复后应 PASS」，即红-绿对照。

| 文件 | 覆盖 |
|---|---|
| `CronT.java` | `CronUtils.parseCronField` —— 步长 0/负数不死循环、区间越界截断、合法步长不变（7 条） |
| `CronMatchesT.java` | `CronUtils.cronMatches` —— 日/周 OR 语义、段数必须恰为 5、once 段级语法、正常 5 段回归（17 条） |
| `MainModule.java` | `MainModule.logMsg` 的桩实现（把日志转 `System.out`），让 `CronUtils` 能脱离 Android 编译 |

### 为什么没有 `CronUtils.java`

早先这里放的是主源码 `app/src/main/java/com/sbplus/browser/CronUtils.java` 的**副本**，
好让测试能单独编译。但副本会与主源码漂移（改完主文件忘了同步，测试就在跑旧实现，
且没有任何机制能发现），因此**已从版本控制移除**，改为编译时直接从主源码目录取该类。

### 编译与运行

在 `tests/cron-redgreen/` 目录下执行（`%JAVA_HOME%` 换成实际 JDK 路径）：

```bat
javac -encoding UTF-8 -d out ^
  ..\..\app\src\main\java\com\sbplus\browser\CronUtils.java ^
  CronT.java CronMatchesT.java MainModule.java

java -cp out com.sbplus.browser.CronT
java -cp out com.sbplus.browser.CronMatchesT
```

两个入口都以 `ALL PASS` 结尾即通过；有失败会打印 `FAILED: N case(s)` 并以退出码 1 结束。

> 注意：`-d out` 会在本目录生成 `out/` 编译产物，已被 `.gitignore` 忽略。

---

## 二、`sbplus-full-test.user.js` —— 油猴功能自测脚本（手动）

装进浏览器手动点的集成自测脚本，逐项验证 GM API（`@grant` 列了 16 个）、
设置面板、`@resource` 渲染、配置存取是否可用。

- `@match *://*/*`，即所有网站生效
- `@resource configPage` 指向 `raw.githubusercontent.com/1012127092/SBPlus/main/assets/config-page.html`
  （该地址实测 HTTP 200 可用）
- 用法：把本文件作为用户脚本导入浏览器，在任意页面打开脚本菜单执行自测

这一项**不是自动化测试**，需要人工观察结果，故不纳入日常回归流程。
