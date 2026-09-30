package com.sbplus.browser;

/** 审核修复用的 MainModule 桩:仅供本机 javac 编译 CronUtils,不承载任何逻辑。 */
public class MainModule {
    public static void logMsg(String msg) {
        System.out.println("[stub-log] " + msg);
    }
}
