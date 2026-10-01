package com.sbplus.browser;

import android.content.Context;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块激活状态检测（2026-10-04 新增）。
 *
 * <p><b>为什么需要它</b>：用户装上模块后最常见的问题是"怎么没反应" —— 而原因往往
 * 只有三种：① 没在 LSPosed 里启用；② 启用了但没勾选三星浏览器的作用域；
 * ③ 都对了但浏览器还没重启。这三种状态原先在界面上完全看不出来，
 * 用户只能靠"功能有没有生效"去猜。
 *
 * <p><b>实现方式（官方正路）</b>：LSPosed 会把 {@link XposedService} 绑定到模块进程，
 * 从中可直接读到框架信息、作用域列表与正在运行的目标。参考实现见 WeKit
 * （github.com/Ujhhgtg/WeKit）的 HookStatus —— 同样是注册 {@link XposedServiceHelper}
 * 的监听器，而不是靠"hook 写标记再由界面读日志"这类自研信号。
 *
 * <p><b>三个状态的含义</b>：
 * <ul>
 *   <li>{@link State#NOT_ACTIVE} —— service 为空：模块未启用，或框架未绑定服务。</li>
 *   <li>{@link State#ACTIVE_NO_SCOPE} —— 有 service 但作用域里没有三星浏览器：
 *       模块启用了却没勾选目标，功能不会生效。</li>
 *   <li>{@link State#ACTIVE_READY} —— 有 service 且作用域包含三星浏览器：
 *       配置正确；{@link #isBrowserRunning()} 进一步说明浏览器当前是否已加载模块。</li>
 * </ul>
 *
 * <p>本类只做只读查询，不修改任何框架状态。
 */
final class ModuleStatus {

    private ModuleStatus() {}

    /** 模块在框架中的激活状态。 */
    enum State {
        /** 未激活：拿不到 XposedService。 */
        NOT_ACTIVE,
        /** 已激活但作用域未包含三星浏览器。 */
        ACTIVE_NO_SCOPE,
        /** 已激活且作用域包含三星浏览器（配置正确）。 */
        ACTIVE_READY,
    }

    /** 本模块关心的目标包名（与 xposed 元数据里的 scope.list 一致）。 */
    private static final String[] TARGETS = {
            "com.sec.android.app.sbrowser",
            "com.sec.android.app.sbrowser.beta",
    };

    /** 本模块自身的包名（用于确认"当前确实在模块进程里"）。 */
    private static final String MODULE_PACKAGE = "com.sbplus.browser";

    private static volatile XposedService sService;
    private static volatile boolean sListenerRegistered;

    /** 已注册监听器的回调（界面可注册，服务绑定/解绑时收到通知）。 */
    public interface Listener {
        void onStatusChanged();
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<Listener>();

    /**
     * 诊断日志。
     *
     * <p>2026-10-04 修正：**不能**用 {@code MainModule.logMsg}。本类运行在
     * **模块自己的进程**（MainActivity 里），而 MainModule 是 Xposed 模块类，
     * 只在被 hook 的目标进程里被框架实例化 —— 在模块进程里
     * {@code MainModule.sInstance} 恒为 null，日志会被静默丢弃。
     * 因此这里直接用 android.util.Log（任何进程都可用）。
     */
    private static void log(String msg) {
        try { android.util.Log.i("SBPlus", msg); } catch (Throwable ignored) {}
    }

    /** 注册服务监听（幂等）。应在模块进程的 Activity 创建时调用一次。 */
    static void init(Context context) {
        if (context == null) return;
        try {
            // 只在模块自己的进程里注册：LSPosed 只向模块进程绑定 service，
            // 在其它进程注册没有意义（且可能因类加载问题报错）。
            if (!MODULE_PACKAGE.equals(context.getPackageName())) return;
            if (sListenerRegistered) return;
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService service) {
                    sService = service;
                    log("ModuleStatus: service bound, framework=" + getFrameworkName());
                    notifyChanged();
                }

                @Override
                public void onServiceDied(XposedService service) {
                    sService = null;
                    log("ModuleStatus: service died");
                    notifyChanged();
                }
            });
            sListenerRegistered = true;
        } catch (Throwable t) {
            // 未装框架 / 框架不提供 service 时会走到这里 —— 属正常情况，
            // 不抛异常（界面会据此显示"未激活"）。
            log("ModuleStatus.init failed: " + t);
        }
    }

    static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
    }

    static void removeListener(Listener l) {
        if (l != null) LISTENERS.remove(l);
    }

    private static void notifyChanged() {
        for (Listener l : LISTENERS) {
            try { l.onStatusChanged(); } catch (Throwable ignored) {}
        }
    }

    /** 当前状态。 */
    static State getState() {
        XposedService svc = sService;
        if (svc == null) return State.NOT_ACTIVE;
        try {
            List<String> scope = svc.getScope();
            if (scope != null) {
                for (String s : scope) {
                    for (String t : TARGETS) {
                        if (t.equals(s)) return State.ACTIVE_READY;
                    }
                }
            }
        } catch (Throwable t) {
            // 读不到作用域时不轻易判为"已就绪" —— 保守返回"未勾选"，
            // 让用户去检查，而不是给出可能错误的"一切正常"。
            return State.ACTIVE_NO_SCOPE;
        }
        return State.ACTIVE_NO_SCOPE;
    }

    /** 框架名（如 "LSPosed"）；未激活返回空串。 */
    static String getFrameworkName() {
        try {
            XposedService svc = sService;
            if (svc == null) return "";
            String n = svc.getFrameworkName();
            return n == null ? "" : n;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 框架版本；未激活返回空串。 */
    static String getFrameworkVersion() {
        try {
            XposedService svc = sService;
            if (svc == null) return "";
            String v = svc.getFrameworkVersion();
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 浏览器当前是否已被框架加载（即模块真的在浏览器里跑着）。 */
    static boolean isBrowserRunning() {
        return getRunningTargetsQuery() == Boolean.TRUE;
    }

    /**
     * 查询浏览器是否在运行，返回三态。
     *
     * <p>2026-10-04 复核反编译后修正：{@code getRunningTargets()} 内部会先调
     * {@code requireApi(102)} —— 框架 API 低于 102 时**抛异常**，而不是返回空列表。
     * 因此"查询失败"与"查到了但浏览器没在跑"是两种不同情况：
     * 前者应显示"未知"（框架太旧），后者才能显示"未运行"。
     * 若都笼统返回 false，旧框架用户会看到"浏览器未运行"这个错误结论。
     *
     * @return TRUE=在运行，FALSE=确认不在运行，null=查询不可用（框架 API 过低）
     */
    private static Boolean getRunningTargetsQuery() {
        XposedService svc = sService;
        if (svc == null) return Boolean.FALSE;
        try {
            List<HookedTarget> targets = svc.getRunningTargets();
            if (targets == null) return Boolean.FALSE;
            for (HookedTarget ht : targets) {
                if (ht == null) continue;
                // 注意用 getProcessName 而非包名：libxposed 的 HookedTarget 暴露的是
                // 进程名（实测其 API 无 getPackageName）。浏览器主进程名即包名本身，
                // 子进程形如 "com.sec.android.app.sbrowser:privileged_process0"，
                // 故用前缀匹配同时覆盖主进程与子进程。
                String proc = ht.getProcessName();
                if (proc == null) continue;
                for (String t : TARGETS) {
                    if (proc.equals(t) || proc.startsWith(t + ":")) return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        } catch (Throwable t) {
            // 多半是 requireApi(102) 未满足 —— 框架版本过低，无法查询。
            log("getRunningTargets unavailable: " + t);
            return null;
        }
    }

    /** 浏览器运行状态是否**可查询**（框架 API 是否够新）。 */
    static boolean canQueryRunningTargets() {
        return getRunningTargetsQuery() != null;
    }
}