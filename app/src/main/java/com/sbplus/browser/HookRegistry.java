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

    /**
     * 已被撤销的功能名。
     *
     * <p>2026-10-04：改为**持久化 + 内存缓存**双层。
     * 起初只用内存集合，但那样"用户关掉功能 → 重启浏览器 → 功能又活了" ——
     * 与用户的选择相反，是明显的缺陷。撤销状态必须跨进程重启保持。
     *
     * <p>持久化介质用浏览器进程自己的 prefs（与 {@code processPrefs} 同一份），
     * 因为撤销判断发生在<b>浏览器进程</b>里（hook 注册期），必须用该进程可读的存储。
     * 写成逗号分隔的功能名列表（功能名只含小写字母与连字符，无歧义）。
     */
    private static final java.util.Set<String> REVOKED = new java.util.HashSet<String>();

    private static final String PREFS_NAME = "sbplus_config";
    private static final String KEY_REVOKED = "sbplus_revoked_features";

    /** 持久化上下文（由 MainHook 在捕获到 Application Context 后注入）。 */
    private static volatile android.content.Context sPrefsCtx;

    /** 从 prefs 载入已撤销集合（进程启动时调一次）。 */
    static void loadRevoked(android.content.Context ctx) {
        if (ctx == null) return;
        sPrefsCtx = ctx;
        try {
            String raw = ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .getString(KEY_REVOKED, "");
            synchronized (LOCK) {
                REVOKED.clear();
                if (raw != null && !raw.isEmpty()) {
                    for (String s : raw.split(",")) {
                        if (!s.isEmpty()) REVOKED.add(s);
                    }
                }
            }
            if (!REVOKED.isEmpty()) {
                android.util.Log.i("SBPlus", "loaded revoked features: " + REVOKED);
            }
        } catch (Throwable t) {
            android.util.Log.w("SBPlus", "loadRevoked failed", t);
        }
    }

    /** 把已撤销集合写回 prefs。 */
    private static void persistRevoked() {
        android.content.Context ctx = sPrefsCtx;
        if (ctx == null) return;   // 上下文未就绪：仅内存生效，下次启动会丢（可接受降级）
        try {
            StringBuilder sb = new StringBuilder();
            synchronized (LOCK) {
                for (String s : REVOKED) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(s);
                }
            }
            ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .edit().putString(KEY_REVOKED, sb.toString()).apply();
        } catch (Throwable t) {
            android.util.Log.w("SBPlus", "persistRevoked failed", t);
        }
    }

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
     * 为 hook 生成稳定且唯一的 id（2026-10-04 新增）。
     *
     * <p>格式：{@code sbplus:<功能名>:<类名>#<方法名>}。
     *
     * <p><b>为什么要"稳定"</b>：官方说明同一模块在同一方法上用**相同 id** 注册的新 hook
     * 会原子替换旧 hook。热重载时新代码用同样的 id 重挂，就能做到"无空窗替换"；
     * 若 id 每次不同（例如含随机数/时间戳），就退化成"新旧并存"或需要先撤后挂
     * （后者在两步之间存在 hook 不生效的间隙）。
     *
     * <p><b>为什么要"唯一"</b>：id 只需在同一模块 + 同一 executable 上唯一，
     * 而"功能名 + 类名 + 方法名"已满足该约束（同一功能不会对同一方法挂两次，
     * 若真挂了两次，那正是应当被替换的场景）。
     *
     * <p>不在 {@code safeFeature} 上下文里注册的 hook（{@code __ungrouped__}）
     * 仍会得到 id，只是功能名段为哨兵值。
     */
    static String buildHookId(java.lang.reflect.Executable exec) {
        if (exec == null) return null;
        String feature = CURRENT_FEATURE.get();
        if (feature == null) feature = "__ungrouped__";
        try {
            String cls = exec.getDeclaringClass() == null ? "?" : exec.getDeclaringClass().getName();
            String name = exec.getName();
            return "sbplus:" + feature + ":" + cls + "#" + name;
        } catch (Throwable t) {
            return null;
        }
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
        return register(handle, null);
    }

    /**
     * 登记一个 handle 并**同时保存它的 hooker**（2026-10-04 新增）。
     *
     * <p><b>为什么需要保存 hooker</b>：这是"功能可被重新打开"的关键。
     *
     * <p>早先的实现是"关闭 = unhook"，于是**无法恢复** —— libxposed 没有"把 hook
     * 重新挂回已被 unhook 的方法"的接口，用户重开开关后必须重启浏览器
     * （主人实测反馈："开关关闭后重开没有实现功能重新打开"）。
     *
     * <p>改用 libxposed 的 {@code HookHandle.replaceHook(Hooker)}：官方说明它是
     * "Atomically replaces this hook with a new hooker"，且**保留** executable、
     * priority、异常模式与 id。因此正确的做法是：
     * <ul>
     *   <li><b>关闭</b>：不 unhook，而是把 hooker 替换成**直通实现**（原样 proceed）；</li>
     *   <li><b>打开</b>：再 replaceHook 回**原实现</b>。</li>
     * </ul>
     * 两个方向都**即时生效、无需重启**。保存 hooker 就是为了"打开"那一步能拿回原实现。
     *
     * @param handle 已创建的 hook handle
     * @param hooker 该 handle 对应的原始 hooker（可为 null，表示不支持重新打开）
     */
    static boolean register(XposedInterface.HookHandle handle,
                            XposedInterface.Hooker hooker) {
        if (handle == null) return false;
        String feature = CURRENT_FEATURE.get();
        if (feature == null) {
            // 不在任何 safeFeature 上下文里注册的 hook（少数工具性 hook）——
            // 不做功能级追踪，但也不丢：挂到哨兵名上，仍可被"全部撤销"覆盖。
            feature = "__ungrouped__";
        }
        synchronized (LOCK) {
            if (REVOKED.contains(feature)) {
                // 该功能已被用户关闭：立刻把 hooker 换成直通实现，
                // 而不是 unhook —— 保留 handle 才能在用户重新打开时 replaceHook 回来。
                if (hooker != null) {
                    XposedInterface.HookHandle nh = replaceWithPassthrough(handle, hooker);
                    if (nh != null) {
                        List<XposedInterface.HookHandle> l = BY_FEATURE.get(feature);
                        if (l == null) { l = new ArrayList<XposedInterface.HookHandle>(); BY_FEATURE.put(feature, l); }
                        l.add(nh);
                        HOOKER_BY_HANDLE.put(nh, hooker);
                        return false;
                    }
                }
                try { handle.unhook(); } catch (Throwable ignored) {}
                return false;
            }
            List<XposedInterface.HookHandle> list = BY_FEATURE.get(feature);
            if (list == null) {
                list = new ArrayList<XposedInterface.HookHandle>();
                BY_FEATURE.put(feature, list);
            }
            list.add(handle);
            if (hooker != null) HOOKER_BY_HANDLE.put(handle, hooker);
            return true;
        }
    }

    /** handle → 原始 hooker（用于"重新打开"时 replaceHook 回来）。 */
    private static final Map<XposedInterface.HookHandle, XposedInterface.Hooker> HOOKER_BY_HANDLE =
            new java.util.concurrent.ConcurrentHashMap<XposedInterface.HookHandle, XposedInterface.Hooker>();

    /** 直通 hooker：原样调用原方法，不做任何修改（功能"关闭"时的替身）。 */
    private static final XposedInterface.Hooker PASSTHROUGH = new XposedInterface.Hooker() {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            return chain.proceed();
        }
    };

    /** 把 handle 的实现换成直通；成功返回新 handle，失败返回 null。 */
    private static XposedInterface.HookHandle replaceWithPassthrough(
            XposedInterface.HookHandle handle, XposedInterface.Hooker originalHooker) {
        try {
            return handle.replaceHook(PASSTHROUGH);
        } catch (Throwable t) {
            android.util.Log.w("SBPlus", "replaceHook(passthrough) failed", t);
            return null;
        }
    }

    /**
     * 重新启用某功能（用户把开关打开时调用）（2026-10-04 新增）。
     *
     * <p><b>这是"功能可被重新打开"的实现</b>：早先的实现只做 unhook，
     * 而 libxposed 没有"把 hook 重新挂回已 unhook 的方法"的接口，故用户重开后
     * 必须重启浏览器（主人实测反馈的问题）。改用 {@code replaceHook} 后，
     * 关闭只是把实现换成直通、**handle 仍然有效**，因此可以再 replaceHook 回原实现，
     * **两个方向都即时生效**。
     *
     * @return 实际恢复的 hook 数量
     */
    static int reEnableFeature(String feature) {
        if (feature == null) return 0;
        List<XposedInterface.HookHandle> list;
        synchronized (LOCK) {
            REVOKED.remove(feature);
            sRuntimeDead.remove(feature);   // 若本次是"直通替换"而非 unhook，则运行期并未真死
            list = BY_FEATURE.get(feature);
        }
        persistRevoked();
        if (list == null || list.isEmpty()) return 0;
        int n = 0;
        List<XposedInterface.HookHandle> updated = new ArrayList<XposedInterface.HookHandle>();
        for (XposedInterface.HookHandle h : new ArrayList<XposedInterface.HookHandle>(list)) {
            XposedInterface.Hooker orig = HOOKER_BY_HANDLE.get(h);
            if (orig == null) continue;   // 无原实现可恢复（旧版本登记的 handle）
            try {
                XposedInterface.HookHandle nh = h.replaceHook(orig);
                updated.add(nh);
                HOOKER_BY_HANDLE.remove(h);
                HOOKER_BY_HANDLE.put(nh, orig);
                n++;
            } catch (Throwable t) {
                android.util.Log.w("SBPlus", "replaceHook(restore) failed", t);
            }
        }
        synchronized (LOCK) {
            if (!updated.isEmpty()) BY_FEATURE.put(feature, updated);
        }
        android.util.Log.i("SBPlus", "re-enabled feature " + feature + ", restored=" + n);
        return n;
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
        // 持久化：否则用户重启浏览器后该功能会"又活过来"，与用户选择相反。
        persistRevoked();
        if (list == null) return 0;
        int n = 0;
        // 2026-10-04 关键改动：优先用**直通替换**而不是 unhook。
        //
        // 为什么：unhook 是**不可逆**的（libxposed 没有"重新挂回已 unhook 方法"的接口），
        // 于是用户重开开关后必须重启浏览器才能恢复 —— 这正是主人实测反馈的问题
        // （"开关关闭后重开没有实现功能重新打开"）。
        // 而 replaceHook 官方说明是"原子替换且保留 executable/priority/异常模式/id"，
        // 因此把实现换成直通（原样 proceed）后，handle 仍然有效、可以再替换回原实现，
        // 两个方向都即时生效。
        List<XposedInterface.HookHandle> replaced = new ArrayList<XposedInterface.HookHandle>();
        for (XposedInterface.HookHandle h : list) {
            XposedInterface.Hooker orig = HOOKER_BY_HANDLE.get(h);
            if (orig != null) {
                XposedInterface.HookHandle nh = replaceWithPassthrough(h, orig);
                if (nh != null) {
                    HOOKER_BY_HANDLE.remove(h);
                    HOOKER_BY_HANDLE.put(nh, orig);   // 记住原实现，供重新打开时恢复
                    replaced.add(nh);
                    n++;
                    continue;
                }
            }
            // 无原实现（旧登记方式）或替换失败 → 退回 unhook（此时确实不可恢复）。
            try {
                h.unhook();
                synchronized (LOCK) { sRuntimeDead.add(feature); }
                n++;
            } catch (Throwable t) {
                android.util.Log.w("SBPlus", "unhook failed for feature " + feature, t);
            }
        }
        // 把替换后的新 handle 放回登记表 —— 它们仍然有效，重新打开时要用。
        synchronized (LOCK) {
            if (!replaced.isEmpty()) BY_FEATURE.put(feature, replaced);
        }
        android.util.Log.i("SBPlus", "revoked " + n + " hook(s) for feature " + feature
                + " (passthrough=" + replaced.size() + ")");
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
        persistRevoked();
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

    /**
     * 本进程内 hook 确实已被卸下的功能（2026-10-04 新增）。
     *
     * <p><b>为什么必须与 REVOKED 分开</b>：两者生命周期不同 ——
     * <ul>
     *   <li>{@code REVOKED} 是**持久化**的：用户关闭功能时写入、重新打开时清除，
     *       用于决定"下次启动要不要注册"；</li>
     *   <li>{@code sRuntimeDead} 是**进程级且不可逆**的：一旦某功能的 hook 在本进程内
     *       被 unhook，就永远不会被清除 —— libxposed 没有"重新挂上已撤销 handle"的
     *       接口，被卸下的方法在本进程内不可能恢复。</li>
     * </ul>
     *
     * <p>分开的必要性：用户关闭功能后**又打开**时，{@code REVOKED} 会被清掉
     * （让下次启动能正常注册），但**此刻 hook 并没有回来**。
     * 若 {@code MainHook.isFeatureAvailable} 只看 REVOKED，就会误报"可用"，
     * 业务逻辑继续走依赖该 hook 的路径 → 静默失效。
     * 用本集合如实回答"本进程内 hook 到底还在不在"。
     */
    private static final java.util.Set<String> sRuntimeDead = new java.util.HashSet<String>();

    /** 该功能在本进程内的 hook 是否已被卸下（不可恢复）。 */
    static boolean isRuntimeDead(String feature) {
        if (feature == null) return false;
        synchronized (LOCK) { return sRuntimeDead.contains(feature); }
    }

    /**
     * 清除某功能的"已撤销"标记（用户重新打开该功能时调用）。
     *
     * <p><b>注意：这不会让功能立即恢复。</b>libxposed 没有"重新挂上已撤销 handle"
     * 的接口，被 unhook 的方法无法在原进程内重新 hook。因此清除标记只是让
     * <b>下次浏览器启动</b>时该功能能被正常注册。调用方必须把这一点告知用户
     * （界面提示"需要重启浏览器生效"）。
     *
     * <p>同时会标记 {@link #sRuntimeDead}：让"本进程内是否可用"的查询如实返回 false，
     * 避免业务逻辑基于"开关已打开"就以为 hook 回来了。
     *
     * @return true = 确实清除了标记（此前处于已撤销状态）
     */
    static boolean clearRevoked(String feature) {
        if (feature == null) return false;
        boolean removed;
        synchronized (LOCK) {
            removed = REVOKED.remove(feature);
            // 只要本进程内曾卸下过，就记为"运行期已死"（不可逆）。
            if (removed) sRuntimeDead.add(feature);
        }
        if (removed) persistRevoked();
        return removed;
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