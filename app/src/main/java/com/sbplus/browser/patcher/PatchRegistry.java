package com.sbplus.browser.patcher;

import com.sbplus.browser.patcher.patches.BlockUpdatePatch;
import com.sbplus.browser.patcher.patches.CleanSettingsPatch;
import com.sbplus.browser.patcher.patches.UaOverridePatch;

import java.util.ArrayList;
import java.util.List;

/**
 * 补丁注册表。
 *
 * <p>新增补丁只需在这里加一行 —— UI 会自动列出来。
 *
 * <p><b>补丁分级</b>（按维护成本，不是按能力）：
 * <ul>
 *   <li><b>稳定</b>：改返回值 / 改常量。浏览器更新时定位成本低，不易失效。</li>
 *   <li><b>中等</b>：注入类 + 在原方法里调用。要处理寄存器与类型。</li>
 *   <li><b>脆弱</b>：依赖具体 UI 构建流程或混淆类名。浏览器一更新就要重做。</li>
 * </ul>
 */
public final class PatchRegistry {

    private PatchRegistry() {
    }

    /** 全部补丁（顺序即 UI 顺序）。 */
    public static List<Patch> all() {
        List<Patch> list = new ArrayList<Patch>();
        list.add(new BlockUpdatePatch());
        list.add(new UaOverridePatch());
        list.add(new CleanSettingsPatch());
        return list;
    }

    /** 按 id 查补丁。 */
    public static Patch byId(String id) {
        for (Patch p : all()) {
            if (p.id().equals(id)) return p;
        }
        return null;
    }
}
