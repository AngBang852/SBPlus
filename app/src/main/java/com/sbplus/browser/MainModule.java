package com.sbplus.browser;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

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
