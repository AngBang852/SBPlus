package com.sbplus.browser;

import java.lang.reflect.Field;

/**
 * Hook 回调基类。before/after 两个回调，适配 LSPosed 新 API 的 intercept。
 */
public abstract class XC_MethodHook {

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        private Object result = null;
        public java.lang.reflect.Method method;
        public Throwable throwable;
        // 2026-09-17:删除 public boolean hasThrowable 字段。
        // 它与下面的 hasThrowable() **方法**同名,是一个易踩的陷阱:
        // 字段全项目无人读写(唯一的"使用"是 MainHook 里 6 处
        // param.hasThrowable() 调用,那些调用的是**方法**),
        // 但字段是 public 且永远为 false,一旦有人误写成
        // `if (param.hasThrowable) throw param.getThrowable();`(漏掉括号)
        // 就会编译通过、判断恒为 false —— 表现为"宿主方法抛出的异常被静默吞掉",
        // 极难排查。删掉字段后这种笔误会直接编译失败,反而更安全。

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }

        public Object getObjectField(Object obj, String fieldName) {
            try {
                Field f = null;
                Class<?> cls = obj.getClass();
                while (cls != null) {
                    try {
                        f = cls.getDeclaredField(fieldName);
                        break;
                    } catch (NoSuchFieldException e) {
                        cls = cls.getSuperclass();
                    }
                }
                if (f == null) throw new NoSuchFieldException(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public void setObjectField(Object obj, String fieldName, Object value) {
            try {
                Field f = null;
                Class<?> cls = obj.getClass();
                while (cls != null) {
                    try {
                        f = cls.getDeclaredField(fieldName);
                        break;
                    } catch (NoSuchFieldException e) {
                        cls = cls.getSuperclass();
                    }
                }
                if (f == null) throw new NoSuchFieldException(fieldName);
                f.setAccessible(true);
                f.set(obj, value);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public Object[] getArgs() {
            return args;
        }

        public void setArgs(Object[] args) {
            this.args = args;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public Throwable getThrowable() {
            return throwable;
        }
    }
}