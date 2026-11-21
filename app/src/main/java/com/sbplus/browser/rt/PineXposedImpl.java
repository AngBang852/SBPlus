package com.sbplus.browser.rt;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * 「静态注入」模式下 {@link XposedInterface} 的实现：把 libxposed API 102 的调用面
 * 全部转接到 {@code top.canyie.pine} 的 inline hook 上。
 *
 * <p>LSPosed 模式下这个类**不会被实例化** —— 框架自己提供了 XposedInterface 实现，
 * {@code XposedInterfaceWrapper.attachFramework(...)} 由框架调用。只有在
 * {@link PatchBootstrap} 里（补丁后的浏览器进程、没有 LSPosed 框架）才由我们接管。
 *
 * <p>对应关系：
 * <ul>
 *   <li>{@code hook(exec)} → {@link PineHookBuilder} → {@code Pine.hook(...)}</li>
 *   <li>{@code getInvoker(method)} → {@link PineMethodInvoker} → {@code Pine.invokeOriginalMethod(...)}</li>
 *   <li>{@code deoptimize(exec)} → {@code Pine.compile(exec)}（强制 JIT，拿到独立 entry point）</li>
 * </ul>
 */
public final class PineXposedImpl implements XposedInterface {

    private static final String TAG = "SBPlus-Pine";

    /** 静态注入时模块自身没有独立的 ApplicationInfo / 远程文件，按最小实现返回。 */
    private static volatile ApplicationInfo sModuleAppInfo;
    private static volatile SharedPreferences sPrefs;

    public static void setModuleApplicationInfo(ApplicationInfo info) {
        sModuleAppInfo = info;
    }

    /** 静态注入时 SharedPreferences 落在浏览器进程自己的目录下。 */
    public static void setSharedPreferences(SharedPreferences prefs) {
        sPrefs = prefs;
    }

    @Override
    public String getFrameworkName() {
        return "Pine";
    }

    @Override
    public String getFrameworkVersion() {
        return "0.3.0-sbplus";
    }

    @Override
    public long getFrameworkVersionCode() {
        return 30000L;
    }

    @Override
    public long getFrameworkProperties() {
        // 不声称任何 capability：静态注入没有 system/remote 能力，也不做 RT API 保护。
        return 0L;
    }

    @Override
    public HookBuilder hook(Executable executable) {
        return new PineHookBuilder(executable);
    }

    @Override
    public HookBuilder hookClassInitializer(Class<?> clazz) {
        // Pine 不支持挂 <clinit>（它不是普通方法，没有可替换的 entry point）。
        // 但 libxposed 的 hookClassInitializer 语义要求能拦到类初始化 —— 退路是
        // 在类加载后立即触发一次静态字段访问，让 <clinit> 在 hook 装好后跑。
        throw new UnsupportedOperationException(
                "hookClassInitializer is not supported in static-injected mode: " + clazz);
    }

    @Override
    public boolean deoptimize(Executable executable) {
        // Pine 的 compile() 会把方法交给 JIT 编译，从而得到一个独立的 entry point，
        // 这正是 inline hook 能安全挂上去的前提。
        try {
            return Pine.compile(executable);
        } catch (Throwable t) {
            Log.w(TAG, "deoptimize failed: " + executable, t);
            return false;
        }
    }

    @Override
    public Invoker<?, Method> getInvoker(Method method) {
        return new PineMethodInvoker(method);
    }

    @Override
    public <T> CtorInvoker<T> getInvoker(Constructor<T> constructor) {
        return new PineCtorInvoker<>(constructor);
    }

    @Override
    public void log(int priority, String tag, String msg) {
        Log.println(priority, tag == null ? TAG : tag, msg);
    }

    @Override
    public void log(int priority, String tag, String msg, Throwable tr) {
        Log.println(priority, tag == null ? TAG : tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    @Override
    public ApplicationInfo getModuleApplicationInfo() {
        return sModuleAppInfo;
    }

    /**
     * 返回与 {@code name} 对应的 SharedPreferences。
     *
     * <p><b>2026-11-20 修复</b>：原实现忽略 {@code name}、永远返回同一个实例
     * （构造时传进来的 {@code sbplus_prefs}）。而 MainHook 的 UI 侧读写的名字是
     * {@code samsung_download_bridge} —— 两者指向<b>不同的文件</b>，
     * 结果是「界面里改了开关，引擎读到的还是旧值」，所有功能开关在补丁版上形同虚设。
     *
     * <p>现在按名字返回真正的那个文件，语义与 LSPosed 的远程 prefs 一致：
     * 调用方给什么名字，就拿到什么名字的存储。
     */
    @Override
    public SharedPreferences getRemotePreferences(String name) {
        if (name == null || name.length() == 0) {
            return sPrefs;
        }
        android.content.Context ctx = sAppContext;
        if (ctx == null) {
            return sPrefs;   // 还没拿到 Context（极早期调用），退回默认实例
        }
        return ctx.getSharedPreferences(name, android.content.Context.MODE_PRIVATE);
    }

    /** 浏览器进程的 Application Context（用于按名打开 prefs 文件）。 */
    private static volatile android.content.Context sAppContext;

    /** 由 {@link PatchBootstrap} 在安装运行时的时候注入。 */
    public static void setAppContext(android.content.Context ctx) {
        sAppContext = ctx;
    }

    @Override
    public String[] listRemoteFiles() {
        return new String[0];
    }

    @Override
    public ParcelFileDescriptor openRemoteFile(String name) throws FileNotFoundException {
        throw new FileNotFoundException(name);
    }

    // ───────────────────────── HookBuilder ─────────────────────────

    private static final class PineHookBuilder implements HookBuilder {
        private final Executable executable;
        private int priority = PRIORITY_DEFAULT;
        private ExceptionMode exceptionMode = ExceptionMode.DEFAULT;
        private String id;

        PineHookBuilder(Executable executable) {
            this.executable = executable;
        }

        @Override
        public HookBuilder setPriority(int priority) {
            this.priority = priority;
            return this;
        }

        @Override
        public HookBuilder setExceptionMode(ExceptionMode mode) {
            this.exceptionMode = mode == null ? ExceptionMode.DEFAULT : mode;
            return this;
        }

        @Override
        public HookBuilder setId(String id) {
            this.id = id;
            return this;
        }

        @Override
        public HookHandle intercept(final Hooker hooker) {
            final ExceptionMode mode = exceptionMode;
            final int prio = priority;
            final String hookId = id;

            MethodHook callback = new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame frame) throws Throwable {
                    PineChain chain = new PineChain(executable, frame);
                    Object result;
                    try {
                        result = hooker.intercept(chain);
                    } catch (Throwable t) {
                        if (chain.proceeded) {
                            // 原方法已经跑过了，异常直接向上抛（与 LSPosed 语义一致）。
                            throw t;
                        }
                        // 原方法还没跑：把异常作为方法抛出的异常交给框架。
                        frame.setThrowable(t);
                        return;
                    }
                    if (!chain.proceeded) {
                        frame.setResult(result);
                    }
                }
            };

            // 先确保目标方法有独立 entry point：被解释执行的方法会共用
            // art_quick_invoke_stub，直接改它的 entry_point 会波及全进程。
            try {
                Pine.compile(executable);
            } catch (Throwable t) {
                Log.w(TAG, "compile before hook failed: " + executable, t);
            }

            MethodHook.Unhook unhook = Pine.hook(executable, callback);
            return new PineHookHandle(executable, unhook, hookId, mode, prio);
        }
    }

    // ───────────────────────── Chain ─────────────────────────

    private static final class PineChain implements Chain {
        private final Executable executable;
        private final Pine.CallFrame frame;
        /** 原方法是否已经执行过（决定异常该上抛还是转成方法抛出）。 */
        boolean proceeded;

        PineChain(Executable executable, Pine.CallFrame frame) {
            this.executable = executable;
            this.frame = frame;
        }

        @Override
        public Executable getExecutable() {
            return executable;
        }

        @Override
        public Object getThisObject() {
            return frame.thisObject;
        }

        @Override
        public List<Object> getArgs() {
            return Arrays.asList(frame.args);
        }

        @Override
        public Object getArg(int index) throws IndexOutOfBoundsException, ClassCastException {
            return frame.args[index];
        }

        @Override
        public Object proceed() throws Throwable {
            proceeded = true;
            return invokeOriginal(frame.thisObject, frame.args);
        }

        @Override
        public Object proceed(Object[] args) throws Throwable {
            proceeded = true;
            // 把改过的参数回写给 frame：Pine 在 beforeCall 返回后会用 frame.args
            // 调用原方法，所以这里改 frame.args 就是「改参生效」。
            frame.args = args;
            return invokeOriginal(frame.thisObject, args);
        }

        @Override
        public Object proceedWith(Object result) throws Throwable {
            proceeded = true;
            return result;
        }

        @Override
        public Object proceedWith(Object thisObject, Object[] args) throws Throwable {
            proceeded = true;
            frame.thisObject = thisObject;
            frame.args = args;
            return invokeOriginal(thisObject, args);
        }

        private Object invokeOriginal(Object thisObject, Object[] args) throws Throwable {
            try {
                return frame.invokeOriginalMethod(thisObject, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    // ───────────────────────── HookHandle ─────────────────────────

    private static final class PineHookHandle implements HookHandle {
        private final Executable executable;
        private final String id;
        private final ExceptionMode mode;
        private final int priority;
        private volatile MethodHook.Unhook unhook;

        PineHookHandle(Executable executable, MethodHook.Unhook unhook,
                       String id, ExceptionMode mode, int priority) {
            this.executable = executable;
            this.unhook = unhook;
            this.id = id;
            this.mode = mode;
            this.priority = priority;
        }

        @Override
        public Executable getExecutable() {
            return executable;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public void unhook() {
            MethodHook.Unhook u = unhook;
            if (u != null) {
                try {
                    u.unhook();
                } catch (Throwable t) {
                    Log.w(TAG, "unhook failed: " + executable, t);
                }
            }
        }

        @Override
        public HookHandle replaceHook(Hooker hooker) {
            // 先挂新的再撤旧的，避免中间出现「无 hook」窗口。
            HookHandle replacement = new PineHookBuilder(executable)
                    .setPriority(priority)
                    .setExceptionMode(mode)
                    .setId(id)
                    .intercept(hooker);
            unhook();
            return replacement;
        }
    }

    // ───────────────────────── Invoker ─────────────────────────

    private static final class PineMethodInvoker
            implements Invoker<PineMethodInvoker, Method> {
        private final Method method;
        private Type type = Type.ORIGIN;

        PineMethodInvoker(Method method) {
            this.method = method;
        }

        @Override
        public PineMethodInvoker setType(Type type) {
            this.type = type == null ? Type.ORIGIN : type;
            return this;
        }

        @Override
        public Object invoke(Object thisObject, Object... args)
                throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            // Type.ORIGIN：明确跳过 hook 链，拿宿主真实行为。
            // Pine.invokeOriginalMethod 走 backup 方法，语义正好对应。
            try {
                return Pine.invokeOriginalMethod(method, thisObject, args);
            } catch (InvocationTargetException | IllegalArgumentException
                     | IllegalAccessException e) {
                throw e;
            } catch (Throwable t) {
                throw new InvocationTargetException(t);
            }
        }

        @Override
        public Object invokeSpecial(Object thisObject, Object... args)
                throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            // invokeSpecial 的语义是「跳过虚分派直接调本实现」；Pine 的 backup
            // 已经是绑定到具体 Method 的，行为一致。
            return invoke(thisObject, args);
        }
    }

    private static final class PineCtorInvoker<T>
            implements CtorInvoker<T> {
        private final Constructor<T> constructor;
        private Type type = Type.ORIGIN;

        PineCtorInvoker(Constructor<T> constructor) {
            this.constructor = constructor;
        }

        @Override
        public CtorInvoker<T> setType(Type type) {
            this.type = type == null ? Type.ORIGIN : type;
            return this;
        }

        @Override
        public T newInstance(Object... args) throws InvocationTargetException,
                IllegalArgumentException, IllegalAccessException, InstantiationException {
            try {
                return constructor.newInstance(args);
            } catch (InvocationTargetException | IllegalArgumentException
                     | IllegalAccessException | InstantiationException e) {
                throw e;
            } catch (Throwable t) {
                throw new InvocationTargetException(t);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <U> U newInstanceSpecial(Class<U> cls, Object... args)
                throws InvocationTargetException, IllegalArgumentException,
                IllegalAccessException, InstantiationException {
            return (U) newInstance(args);
        }

        @Override
        public Object invoke(Object thisObject, Object... args)
                throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            throw new UnsupportedOperationException("constructor invoker has no invoke()");
        }

        @Override
        public Object invokeSpecial(Object thisObject, Object... args)
                throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            throw new UnsupportedOperationException("constructor invoker has no invokeSpecial()");
        }
    }
}
