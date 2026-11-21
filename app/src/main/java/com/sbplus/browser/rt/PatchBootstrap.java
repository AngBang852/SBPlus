package com.sbplus.browser.rt;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.libxposed.api.XposedInterfaceWrapper;
import io.github.libxposed.api.XposedModuleInterface;

import com.sbplus.browser.MainModule;

import top.canyie.pine.Pine;
import top.canyie.pine.PineConfig;

/**
 * 补丁版浏览器进程的运行时入口。
 *
 * <p>LSPosed 模式下由框架反射构造 {@code MainModule} 并调用生命周期回调；
 * 静态注入后没有框架，需要我们自己把这条路走一遍：
 *
 * <ol>
 *   <li>加载 {@code libpine.so}（APK 里 {@code extractNativeLibs=false} 时
 *       {@code System.loadLibrary} 找不到，得先解压到私有目录）；</li>
 *   <li>{@code Pine.ensureInitialized()} 初始化 inline hook 引擎；</li>
 *   <li>构造 {@code MainModule}，用 {@link PineXposedImpl} 接管
 *       {@code XposedInterfaceWrapper.attachFramework}；</li>
 *   <li>按 LSPosed 的顺序依次触发 {@code onModuleLoaded} → {@code onPackageLoaded}。</li>
 * </ol>
 *
 * <p>入口是浏览器 {@code Application.attachBaseContext} 里插入的一行
 * {@code invoke-static {ctx}, Lcom/sbplus/browser/rt/PatchBootstrap;->install(Landroid/content/Context;)V}。
 */
public final class PatchBootstrap {

    private static final String TAG = "SBPlus-Bootstrap";

    private static volatile boolean sInstalled;

    private PatchBootstrap() {
    }

    /** 幂等：多次调用只执行一次。 */
    public static void install(Context context) {
        if (sInstalled || context == null) {
            return;
        }
        synchronized (PatchBootstrap.class) {
            if (sInstalled) {
                return;
            }
            sInstalled = true;
        }

        try {
            Context app = context.getApplicationContext();
            if (app == null) {
                app = context;
            }

            loadPine(app);

            PineConfig.debug = false;
            PineConfig.debuggable = false;
            Pine.ensureInitialized();
            Log.i(TAG, "Pine initialized, arch=" + (Pine.is64Bit() ? "64" : "32"));

            MainModule module = new MainModule();
            XposedInterfaceWrapper wrapper = module;
            wrapper.attachFramework(new PineXposedImpl(), null);

            PineXposedImpl.setSharedPreferences(
                    app.getSharedPreferences("sbplus_prefs", Context.MODE_PRIVATE));
            // 2026-11-20：同时把 Application Context 交给 PineXposedImpl，
            // 让 getRemotePreferences(name) 能按名字打开**真正的**那个 prefs 文件。
            // 不设这个的话，所有 getRemotePreferences 调用都会退回同一个实例，
            // 导致 MainHook 的 UI 侧（samsung_download_bridge）与引擎侧读到的
            // 不是同一个文件，功能开关全部失效。
            PineXposedImpl.setAppContext(app);
            PineXposedImpl.setModuleApplicationInfo(app.getApplicationInfo());

            module.onModuleLoaded(new ModuleLoadedParamImpl(app.getPackageName()));
            module.onPackageLoaded(new PackageLoadedParamImpl(
                    app.getPackageName(),
                    app.getApplicationInfo(),
                    true,
                    PatchBootstrap.class.getClassLoader()));

            Log.i(TAG, "SBPlus runtime installed for " + app.getPackageName());
        } catch (Throwable t) {
            Log.e(TAG, "SBPlus runtime install failed", t);
        }
    }

    /**
     * 优先 {@code System.loadLibrary}；失败（APK 未解压 native 库）时从 APK 里
     * 把 {@code lib/<abi>/libpine.so} 抽到私有目录再 {@code System.load}。
     */
    private static void loadPine(Context context) {
        try {
            System.loadLibrary("pine");
            Log.i(TAG, "libpine.so loaded via loadLibrary");
            return;
        } catch (Throwable ignored) {
            Log.w(TAG, "loadLibrary(pine) failed, falling back to APK extraction");
        }

        try {
            String abi = android.os.Build.SUPPORTED_ABIS.length > 0
                    ? android.os.Build.SUPPORTED_ABIS[0] : "arm64-v8a";
            File dir = new File(context.getFilesDir(), "sbplus-native");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IllegalStateException("cannot create " + dir);
            }
            File so = new File(dir, "libpine.so");
            if (!so.isFile() || so.length() == 0) {
                String apk = context.getApplicationInfo().sourceDir;
                try (ZipFile zip = new ZipFile(apk)) {
                    ZipEntry entry = zip.getEntry("lib/" + abi + "/libpine.so");
                    if (entry == null) {
                        throw new IllegalStateException(
                                "lib/" + abi + "/libpine.so missing in " + apk);
                    }
                    try (InputStream in = zip.getInputStream(entry);
                         FileOutputStream out = new FileOutputStream(so)) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                    }
                }
            }
            System.load(so.getAbsolutePath());
            Log.i(TAG, "libpine.so loaded from " + so.getAbsolutePath());
        } catch (Throwable t) {
            throw new RuntimeException("failed to load libpine.so", t);
        }
    }

    // ───────────────────────── lifecycle params ─────────────────────────

    private static final class ModuleLoadedParamImpl
            implements XposedModuleInterface.ModuleLoadedParam {
        private final String processName;

        ModuleLoadedParamImpl(String processName) {
            this.processName = processName;
        }

        @Override
        public boolean isSystemServer() {
            return false;
        }

        @Override
        public String getProcessName() {
            return processName;
        }
    }

    private static final class PackageLoadedParamImpl
            implements XposedModuleInterface.PackageLoadedParam {
        private final String packageName;
        private final ApplicationInfo applicationInfo;
        private final boolean firstPackage;
        private final ClassLoader classLoader;

        PackageLoadedParamImpl(String packageName, ApplicationInfo applicationInfo,
                               boolean firstPackage, ClassLoader classLoader) {
            this.packageName = packageName;
            this.applicationInfo = applicationInfo;
            this.firstPackage = firstPackage;
            this.classLoader = classLoader;
        }

        @Override
        public String getPackageName() {
            return packageName;
        }

        @Override
        public ApplicationInfo getApplicationInfo() {
            return applicationInfo;
        }

        @Override
        public boolean isFirstPackage() {
            return firstPackage;
        }

        @Override
        public ClassLoader getDefaultClassLoader() {
            return classLoader;
        }
    }
}
