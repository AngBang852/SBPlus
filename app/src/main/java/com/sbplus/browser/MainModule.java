package com.sbplus.browser;

import android.os.Build;
import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;

/**
 * SBPlus 的 Xposed 模块入口。
 *
 * <p><b>⚠️ 热重载功能状态：已实现但**未完整验证**（2026-10-04）</b>
 *
 * <p>请不要因为看到下面两个回调就认为热重载"可用"。当前事实：
 * <ul>
 *   <li><b>触发路径未实测</b> —— 热重载需经 {@code XposedService.hotReloadModule()}
 *       触发，而模块界面<b>没有提供入口</b>。也就是说现在没有任何按钮能触发它。</li>
 *   <li><b>状态恢复未经真机验证</b> —— 热重载后新一代的静态字段全为初始值
 *       （{@code sAppContext} 为 null、{@code HookRegistry} 已撤销集合为空），
 *       已实现 {@link MainHook#restoreProcessStateAfterReload()} 去恢复，
 *       但该路径从未在真机上跑通过。</li>
 *   <li><b>{@code autoHotReload} 有意未开启</b>（见 module.prop），
 *       所以"安装新 APK 自动重载"这条路径也不通。</li>
 * </ul>
 *
 * <p><b>它的价值本身也有限</b>：热重载只省"开发迭代时间"（改代码后不必强停浏览器），
 * 对最终用户没有任何可感功能。本项目日常验证的都是"启动即生效"的东西，
 * 重启浏览器本来就不痛，因此实际收益低于预期。
 *
 * <p><b>以下为实现要点（供将来验证/补完时参考）</b>
 *
 * <p>实现依据 libxposed 官方文档（{@code XposedModuleInterface} 的 javadoc），
 * 有两个**必须遵守的约束**，否则会出问题：
 *
 * <ol>
 *   <li><b>同意重载前必须停掉模块自有的全部线程</b>（{@code onHotReloading} 返回 true 之前）。
 *       官方原文：modules must "stop all module-owned Java and native threads,
 *       unregister native hooks and external callbacks..."。
 *       原因：这些线程持有**旧模块 classloader**，而框架只会释放它自己持有的引用 ——
 *       "模块代码持有的引用"不会释放，于是每次热重载泄漏一整个 classloader。
 *       本项目有 3 个线程池（见 {@link SbExecutors}），故此处调用
 *       {@link SbExecutors#shutdownAll()}。</li>
 *   <li><b>生命周期回调不会自动重放</b>。官方原文：Package lifecycle callbacks are
 *       not automatically replayed after hot reload。且默认实现只做 "unhook all old hooks"，
 *       <b>什么都不重挂</b>。因此 {@code onHotReloaded} 里必须显式重新注册 hook，
 *       否则热重载后模块变成"完全不工作"——这是最容易踩的坑。</li>
 * </ol>
 *
 * <p>关于 {@code getOldHookHandles()}：官方推荐用
 * {@code HookHandle.replaceHook()} 做原子替换。本项目选择**重新走一遍 doHooks**，
 * 因为：① 各功能的注册逻辑本身带幂等检查；② doHooks 会重新计算功能可用性
 * （浏览器更新后某些类/方法可能变化），而 replaceHook 只是换实现、不重新探测。
 * 旧 handle 由 {@link HookRegistry} 统一管理，重挂前先全部撤销。
 */
public class MainModule extends XposedModule {

    public static volatile MainModule sInstance;

    public MainModule() {
        super();
        sInstance = this;
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        MainHook.initPrefs();
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (param.isFirstPackage()) {
            MainHook.doHooks(param.getPackageName(), param.getDefaultClassLoader());
        }
    }

    /**
     * 即将热重载（运行在**旧代码**里）。
     *
     * @return true 允许重载；false 拒绝。
     *         返回 false 时，通过 service 触发的一方会收到
     *         {@code HotReloadResult.Status.FAILED}。
     */
    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        try {
            logSelf(Log.INFO, "[SBPlus] hot reload requested, preparing old generation");

            // ① 停掉模块自有线程（官方硬性要求，见类注释）。
            //    必须在返回 true 之前完成，否则旧 classloader 会被这些线程钉住。
            //    注：关闭后**本代（旧代码）**无法再用池 —— 这是预期行为，
            //    新一代的 SbExecutors 是全新的类、会重新初始化出新的池。
            SbExecutors.shutdownAll();

            // ② 停掉用户脚本的常驻调度循环。
            //    它不在线程池里（见 SbExecutors 的"不收编的线程"说明），需单独处理。
            MainHook.stopBackgroundLoopsForReload();

            // ③ 撤销本代注册的全部 hook。
            //    虽然框架在 onHotReloaded 里的默认实现也会 unhook 旧 hook，但那是
            //    "新代码"里做的事；这里主动撤一遍可以保证旧代码在等待切换期间
            //    不再被调用（旧代码此刻可能已被冻结，但已挂的 hook 仍可能触发）。
            int n = HookRegistry.revokeAll();
            logSelf(Log.INFO, "[SBPlus] hot reload: revoked " + n + " hook(s), threads stopped");

            logSelf(Log.INFO, "[SBPlus] hot reload allowed");
            return true;
        } catch (Throwable t) {
            // 任何异常都拒绝重载 —— 半清理状态比不重载更危险（旧代码已残、新代码未上）。
            logSelf(Log.ERROR, "[SBPlus] hot reload prep failed, rejecting", t);
            return false;
        }
    }

    /**
     * 热重载完成（运行在**新代码**里）。
     *
     * <p><b>必须在这里重新挂 hook</b>：官方明确"生命周期回调不会自动重放"，
     * 且默认实现只 unhook、不重挂。不重挂 = 热重载后模块完全不工作。
     */
    @Override
    public void onHotReloaded(HotReloadedParam param) {
        try {
            logSelf(Log.INFO, "[SBPlus] hot reloaded, re-installing hooks");
            MainHook.initPrefs();
            // 用新代码的 classloader 重新走一遍注册流程。
            // getClassLoader() 来自 HotReloadedParam（它继承 ModuleLoadedParam 的进程信息，
            // 另提供 getClassLoader —— 与 PackageReadyParam 同名但语义是"当前代的加载器"）。
            MainHook.doHooks(param.getProcessName(), MainHook.moduleClassLoaderForReload());
            // **关键**：恢复进程级状态。新一代的静态字段全是初始值 ——
            // sAppContext 为 null（而官方明确生命周期回调不会重放，即
            // SBrowserApplication.onCreate 不会再触发，靠那个 hook 永远拿不到 Context），
            // HookRegistry 的已撤销集合为空（用户关掉的功能会全部复活）。
            // 本文件 250 处依赖 sAppContext，不恢复等于热重载后模块大部分功能失效。
            MainHook.restoreProcessStateAfterReload();
            logSelf(Log.INFO, "[SBPlus] hot reload: hooks re-installed");
        } catch (Throwable t) {
            logSelf(Log.ERROR, "[SBPlus] hot reload re-install failed", t);
        }
    }

    /**
     * 模块自身日志（避免与 {@code XposedInterface.log(int,String,String)} 同名 ——
     * 同名静态方法会被编译器当成"试图实现接口方法"，签名不匹配直接报错）。
     */
    private static void logSelf(int prio, String msg) {
        try {
            if (sInstance != null) sInstance.log(prio, "SBPlus", msg);
        } catch (Throwable ignored) {}
    }

    private static void logSelf(int prio, String msg, Throwable t) {
        try {
            if (sInstance != null) sInstance.log(prio, "SBPlus", msg, t);
        } catch (Throwable ignored) {}
    }

    public static void logMsg(String msg) {
        if (sInstance != null) {
            sInstance.log(Log.INFO, "SBPlus", msg);
        }
    }

    public static void logErr(String msg, Throwable t) {
        if (sInstance != null) {
            sInstance.log(Log.ERROR, "SBPlus", msg, t);
        }
    }
}