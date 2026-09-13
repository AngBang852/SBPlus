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
        public boolean hasThrowable = false;

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