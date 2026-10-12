import java.lang.reflect.Method;

/**
 * FlipClockView 的 XML 属性解析逻辑 —— JVM 级验证（不需要 Android 运行时）。
 *
 * <p>为什么单独测这三个方法：FlipClockView 的属性解析刻意**不用 R.styleable**，
 * 而是按属性名逐条匹配 + 手工解析字面量（原因见类注释：宿主进程加载不到模块资源表）。
 * 手工解析是自写代码，必须有测试兜住；而它恰好是纯函数，能在 JVM 上直接跑。
 *
 * <p>编译运行（classpath 用 android.jar 的 stub + 模块编译产物）：
 * <pre>
 *   javac -cp "&lt;sdk&gt;/platforms/android-37.0/android.jar;&lt;classes&gt;" -d out FlipClockAttrTest.java
 *   java  -cp "&lt;sdk&gt;/platforms/android-37.0/android.jar;&lt;classes&gt;;out" FlipClockAttrTest
 * </pre>
 */
public class FlipClockAttrTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("com.sbplus.browser.widget.FlipClockView");

        Method parseColor = c.getDeclaredMethod("parseColor", String.class);
        parseColor.setAccessible(true);
        Method parseBool = c.getDeclaredMethod("parseBool", String.class, boolean.class);
        parseBool.setAccessible(true);
        Method parseInt = c.getDeclaredMethod("parseInt", String.class);
        parseInt.setAccessible(true);

        System.out.println("== parseColor ==");
        eq("字面量 #CCCCCC", 0xFFCCCCCC, parseColor.invoke(null, "#CCCCCC"));
        eq("短写 #ccc", 0xFFCCCCCC, parseColor.invoke(null, "#ccc"));
        eq("带 alpha #66000000", 0x66000000, parseColor.invoke(null, "#66000000"));
        eq("资源引用 @color/x 被忽略", -1, parseColor.invoke(null, "@color/x"));
        eq("主题引用 ?attr/x 被忽略", -1, parseColor.invoke(null, "?attr/x"));
        eq("空串", -1, parseColor.invoke(null, ""));
        eq("非法值", -1, parseColor.invoke(null, "not-a-color"));

        System.out.println("== parseBool ==");
        eq("true", Boolean.TRUE, parseBool.invoke(null, "true", Boolean.FALSE));
        eq("false", Boolean.FALSE, parseBool.invoke(null, "false", Boolean.TRUE));
        eq("1", Boolean.TRUE, parseBool.invoke(null, "1", Boolean.FALSE));
        eq("0", Boolean.FALSE, parseBool.invoke(null, "0", Boolean.TRUE));
        eq("无法识别时用兜底值", Boolean.TRUE, parseBool.invoke(null, "maybe", Boolean.TRUE));

        System.out.println("== parseInt ==");
        eq("500", 500, parseInt.invoke(null, "500"));
        eq("带空白", 300, parseInt.invoke(null, " 300 "));
        eq("非法 -> -1", -1, parseInt.invoke(null, "abc"));
        eq("null -> -1", -1, parseInt.invoke(null, (Object) null));

        System.out.println();
        System.out.println(fail == 0 ? ("全部通过（" + pass + " 项）") : ("有 " + fail + " 项失败 / 共 " + (pass + fail) + " 项"));
        if (fail != 0) System.exit(1);
    }

    private static void eq(String label, Object want, Object got) {
        boolean ok = String.valueOf(want).equals(String.valueOf(got));
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  OK  " : "  FAIL") + "  " + label
                + (ok ? "" : "   want=" + want + "  got=" + got));
    }
}
