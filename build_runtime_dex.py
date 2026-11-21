#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
构建 SBPlus 运行时包（供打补丁器注入浏览器 APK）。

做的事：
  1. 从 app/build 的 javac 产物里挑出**运行时需要**的 class（排除模块 UI 与 patcher）
  2. 用 d8 编译成 sbplus-runtime.dex
  3. 连同 libpine.so 一起放进 app/src/main/assets/

为什么需要单独一个 dex：
  模块自己是 LSPosed 模块，dex 里有 MainActivity / R / patcher 等只在模块进程用的类。
  补丁版浏览器进程只需要「hook 运行时」那部分（MainHook + 22 个功能 + Pine + libxposed API），
  把它们单独打成 dex 注入，体积约 1MB，不带上模块 UI 的资源引用。

用法：
  python build_runtime_dex.py           # 全流程
  python build_runtime_dex.py --check   # 只检查现有产物
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APP = ROOT / "app"
CLASSES = APP / "build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
ASSETS = APP / "src/main/assets"
BUILD = ROOT / "_build/runtime"

# 不注入浏览器的类：模块 UI、资源引用者、打补丁器自身
EXCLUDE_PREFIXES = [
    "com/sbplus/browser/patcher/",
]
EXCLUDE_EXACT = [
    "com/sbplus/browser/MainActivity",
    "com/sbplus/browser/LogManagerActivity",
    "com/sbplus/browser/ModuleStatus",
]

# 需要一起打进 dex 的外部依赖（libxposed API 是 compileOnly，运行时必须自带）
EXTRA_JARS = [
    APP / "libs/libxposed-api-102.jar",
]

D8 = r"D:\Android\sdk\build-tools\37.0.0\d8.bat"
ANDROID_JAR = r"D:\Android\sdk\platforms\android-37.0\android.jar"


def log(msg):
    print(msg, flush=True)


def collect_classes():
    """收集要注入的 class 文件路径。"""
    if not CLASSES.is_dir():
        sys.exit(f"找不到编译产物: {CLASSES}\n先跑 gradlew :app:compileDebugJavaWithJavac")

    picked = []
    for f in CLASSES.rglob("*.class"):
        rel = f.relative_to(CLASSES).as_posix()
        stem = rel[:-6]  # 去掉 .class
        # 内部类跟着外部类一起判断
        base = stem.split("$")[0]
        if any(base.startswith(p.rstrip("/") + "/") or base.startswith(p.rstrip("/"))
               for p in EXCLUDE_PREFIXES):
            continue
        if base in EXCLUDE_EXACT:
            continue
        picked.append(f)
    return picked


def check_ascii_safe(paths):
    """d8 在含非 ASCII 的路径上会挂（InvalidPathException）。

    这里检测一遍，若命中就把 class 树复制到 ASCII 临时目录再编译。
    """
    for p in paths:
        if any(ord(c) > 127 for c in str(p)):
            return False
    return True


def stage_to_ascii(classes):
    """把 class 树复制到纯 ASCII 路径，返回新的根目录与 class 列表。"""
    stage = Path(os.environ.get("TEMP", "/tmp")) / "sbplus-runtime-cls"
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)
    out = []
    for f in classes:
        rel = f.relative_to(CLASSES)
        dst = stage / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(f, dst)
        out.append(dst)
    return stage, out


def run_d8(class_files, out_dir, extra_jars):
    out_dir.mkdir(parents=True, exist_ok=True)
    for f in out_dir.glob("*.dex"):
        f.unlink()

    # d8 的 @list 文件只接受 ASCII 路径；jar 若在非 ASCII 目录下要先复制出来。
    tmp = Path(os.environ.get("TEMP", "/tmp"))
    staged_jars = []
    for j in extra_jars:
        if any(ord(c) > 127 for c in str(j)):
            dst = tmp / j.name
            shutil.copy2(j, dst)
            staged_jars.append(dst)
        else:
            staged_jars.append(j)

    list_file = tmp / "sbplus-runtime-inputs.txt"
    with open(list_file, "w", encoding="ascii") as fh:
        for c in class_files:
            fh.write(str(c) + "\n")
        for j in staged_jars:
            fh.write(str(j) + "\n")

    cmd = [D8, "--release", "--min-api", "30", "--output", str(out_dir),
           "--lib", ANDROID_JAR, "@" + str(list_file)]
    log("d8: " + " ".join(cmd[:6]) + f" ... ({len(class_files)} classes)")
    proc = subprocess.run(cmd, capture_output=True, text=True, shell=False)
    if proc.returncode != 0:
        log(proc.stdout)
        log(proc.stderr)
        sys.exit("d8 失败")
    dexes = sorted(out_dir.glob("*.dex"))
    if not dexes:
        sys.exit("d8 没有产出 dex")
    return dexes


def merge_dexes(dexes, target):
    """d8 可能按方法数拆成多个 dex；合并成一个（简单拼接不合法）。

    这里走最稳的路：如果只有一个就直接用；多个则报错要求人工处理
    （实测 667 个类只有 1 个 dex）。
    """
    if len(dexes) == 1:
        shutil.copy2(dexes[0], target)
        return target
    sys.exit(f"d8 产出 {len(dexes)} 个 dex，需要先调 --main-dex-list 或减少类数")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只检查现有产物")
    args = ap.parse_args()

    runtime_dex = ASSETS / "sbplus-runtime.dex"
    pine_so = ASSETS / "libpine.so"

    if args.check:
        for p in (runtime_dex, pine_so):
            if p.is_file():
                log(f"OK   {p.name}  {p.stat().st_size:,} bytes")
            else:
                log(f"MISS {p.name}")
        return

    ASSETS.mkdir(parents=True, exist_ok=True)
    BUILD.mkdir(parents=True, exist_ok=True)

    classes = collect_classes()
    log(f"收集到 {len(classes)} 个 class")

    if not check_ascii_safe(classes):
        log("路径含非 ASCII，先复制到临时目录")
        _, classes = stage_to_ascii(classes)

    dexes = run_d8(classes, BUILD, [j for j in EXTRA_JARS if j.is_file()])
    merge_dexes(dexes, runtime_dex)
    log(f"OK   {runtime_dex.name}  {runtime_dex.stat().st_size:,} bytes")

    # 复制 libpine.so
    #
    # 优先从 Pine 的 CMake 构建目录取最新产物；找不到就复用 assets 里已有的那份。
    # 理由：libpine.so 只有在**改了 Pine native 源码**时才需要重编，
    # 而日常改的是 Java 侧的 hook 逻辑（MainHook 等）。要求每次都重编 1.4MB 的
    # native 库纯属浪费 —— 尤其 CMake 构建目录常被当临时文件清掉。
    src_so = ROOT / "_build/pine/b64b/libpine.so"
    if src_so.is_file():
        shutil.copy2(src_so, pine_so)
        log(f"OK   {pine_so.name}  {pine_so.stat().st_size:,} bytes (从 CMake 产物更新)")
    elif pine_so.is_file() and pine_so.stat().st_size > 0:
        log(f"SKIP {pine_so.name}  {pine_so.stat().st_size:,} bytes (复用 assets 里已有的)")
    else:
        sys.exit(f"找不到 {src_so}，也没有现成的 {pine_so}，先编译 Pine")

    # 自检：确认关键类都在（dex 是原始字节，直接搜类型描述符）
    data = runtime_dex.read_bytes()
    for name in (b"Lcom/sbplus/browser/MainModule;",
                 b"Lcom/sbplus/browser/rt/PatchBootstrap;",
                 b"Lcom/sbplus/browser/rt/PatchInitProvider;",
                 b"Lcom/sbplus/browser/rt/PineXposedImpl;",
                 b"Ltop/canyie/pine/Pine;",
                 b"Lio/github/libxposed/api/XposedModule;"):
        if name not in data:
            sys.exit(f"自检失败：dex 里缺少 {name.decode()}")
    log("自检通过：关键类齐全")


if __name__ == "__main__":
    main()
