package com.sbplus.browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

/**
 * Hook 登记表（2026-10-04 新增）。
 *
 * <p><b>解决的问题</b>：SBPlus 的 hook 注册后<b>无法撤销</b>。用户在一个功能开关上
 * 把它关掉后，已注册的 hook 仍在运行，必须**重启浏览器**才真正停止。而
 * libxposed 的 {@code HookBuilder.intercept()} 返回 {@link XposedInterface.HookHandle}，
 * 它提供 {@code unhook()} —— 有了它，"关闭功能"可以立即生效。
 *
 * <p><b>为什么需要单独一张表</b>：{@code MainHook} 的注册入口分散在
 * {@code hookMethod} / {@code findAndHookMethod} / {@code findAndHookMethodInHierarchy}
 * 等 5 处，且都在匿名 lambda 里 return。要支持"按功能批量撤销"，
 * 必须把每个 handle 与它所属的功能名关联起来存下来。
 *
 * <p><b>设计取舍</b>：按"功能名 → handle 列表"分组，而不是给每个 handle 单独命名。
 * 因为撤销的粒度是功能（用户在设置里关掉的是功能），不是单个方法。
 *
 * <p>线程安全：所有操作都加锁 —— hook 注册发生在目标进程启动期（可能多线程），
 * 撤销发生在用户点击设置项时（主线程），两者会并发。
 */
final class HookRegistry {

    private HookRegistry() {}

    /** 功能名 → 该功能注册的 handle 列表。 */
    private static final Map<String, List<XposedInterface.HookHandle>> BY_FEATURE =
            new LinkedHashMap<String, List<XposedInterface.HookHandle>>();

    /** 当前正在注册的功能名（由 safeFeature 设置，供注册入口读取）。 */
    private static final ThreadLocal<String> CURRENT_FEATURE = new ThreadLocal<String>();

    private static final Object LOCK = new Object();

    /** 已被撤销的功能名（撤销后若又有 hook 注册进来，立即撤销它，避免"撤销后又活过来"）。 */
    private static final java.util.Set<String> REVOKED = new java.util.HashSet<String>();

    /**
     * 设置当前正在注册的功能名。
     *
     * <p>用 ThreadLocal 而非全局字段：hook 注册可能并发进行（不同功能的注册在不同线程），
     * 全局字段会导致 A 功能的 handle 被记到 B 功能名下，撤销时错杀。
     */
    static void beginFeature(String feature) {
        CURRENT_FEATURE.set(feature);
    }

    static void endFeature() {
        CURRENT_FEATURE.remove();
    }

    /**
     * 登记一个 handle。
     *
     * <p>若当前功能已被撤销，则**立即撤销**这个新 handle 并返回 false —— 否则会出现
     * "用户已关闭该功能，但某个延迟注册的 hook 又把它激活了"的诡异现象。
     *
     * @return true = 已登记且生效；false = 该功能已被撤销，handle 已被就地撤销
     */
    static boolean register(XposedInterface.HookHandle handle) {
        if (handle == null) return false;
        String feature = CURRENT_FEATURE.get();
        if (feature == null) {
            // 不在任何 safeFeature 上下文里注册的 hook（少数工具性 hook）——
            // 不做功能级追踪，但也不丢：挂到哨兵名上，仍可被"全部撤销"覆盖。
            feature = "__ungrouped__";
        }
        synchronized (LOCK) {
            if (REVOKED.contains(feature)) {
                try { handle.unhook(); } catch (Throwable ignored) {}
                return false;
            }
            List<XposedInterface.HookHandle> list = BY_FEATURE.get(feature);
            if (list == null) {
                list = new ArrayList<XposedInterface.HookHandle>();
                BY_FEATURE.put(feature, list);
            }
            list.add(handle);
            return true;
        }
    }

    /**
     * 撤销某个功能注册的全部 hook。
     *
     * <p>撤销后该功能名进入"已撤销"集合：即使之后还有延迟注册的 hook 进来，
     * 也会被 {@link #register} 就地撤销，保证"关闭"是终态。
     *
     * @return 实际撤销的 hook 数量
     */
    static int revokeFeature(String feature) {
        if (feature == null) return 0;
        List<XposedInterface.HookHandle> list;
        synchronized (LOCK) {
            REVOKED.add(feature);
            list = BY_FEATURE.remove(feature);
        }
        if (list == null) return 0;
        int n = 0;
        for (XposedInterface.HookHandle h : list) {
            try {
                h.unhook();
                n++;
            } catch (Throwable t) {
                android.util.Log.w("SBPlus", "unhook failed for feature " + feature, t);
            }
        }
        android.util.Log.i("SBPlus", "revoked " + n + " hook(s) for feature " + feature);
        return n;
    }

    /** 撤销所有已登记的 hook（用于"全部关闭"或诊断）。 */
    static int revokeAll() {
        List<XposedInterface.HookHandle> all;
        synchronized (LOCK) {
            all = new ArrayList<XposedInterface.HookHandle>();
            for (List<XposedInterface.HookHandle> l : BY_FEATURE.values()) all.addAll(l);
            for (String f : BY_FEATURE.keySet()) REVOKED.add(f);
            BY_FEATURE.clear();
        }
        int n = 0;
        for (XposedInterface.HookHandle h : all) {
            try { h.unhook(); n++; } catch (Throwable ignored) {}
        }
        return n;
    }

    /** 该功能是否已被撤销。 */
    static boolean isRevoked(String feature) {
        if (feature == null) return false;
        synchronized (LOCK) { return REVOKED.contains(feature); }
    }

    /** 某功能当前登记的 hook 数量（诊断用）。 */
    static int countOf(String feature) {
        if (feature == null) return 0;
        synchronized (LOCK) {
            List<XposedInterface.HookHandle> l = BY_FEATURE.get(feature);
            return l == null ? 0 : l.size();
        }
    }

    /** 全部功能名与各自 hook 数（诊断用，返回快照副本）。 */
    static Map<String, Integer> snapshot() {
        synchronized (LOCK) {
            Map<String, Integer> m = new LinkedHashMap<String, Integer>();
            for (Map.Entry<String, List<XposedInterface.HookHandle>> e : BY_FEATURE.entrySet()) {
                m.put(e.getKey(), e.getValue().size());
            }
            return Collections.unmodifiableMap(m);
        }
    }
}