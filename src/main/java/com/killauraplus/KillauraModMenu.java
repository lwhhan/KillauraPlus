package com.killauraplus;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu 集成：在「模组」列表里给 Killaura++ 加一个配置按钮。
 *
 * 只是多一个入口，和模组自己的 K 键（可改）打开的是同一个界面。
 * Mod Menu 在 fabric.mod.json 里是 recommends，不是 depends —— 没装 Mod Menu
 * 时这个入口点不会被加载，模组照常运行，K 键也照常能用。
 */
public class KillauraModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // KillauraConfigGUI(Screen parent) 正好匹配 ConfigScreenFactory 的函数式签名
        return KillauraConfigGUI::new;
    }
}
