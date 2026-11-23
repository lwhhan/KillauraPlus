package com.killauraplus;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Killaura++（杀戮光环++）主入口，Fabric 1.20.1 纯客户端模组。
 * 功能概述：
 *  - 按 R 键自动搜索周围的可攻击实体（生物/玩家），并按距离由近到远自动攻击。
 *  - 按 K 键（可改）打开设置界面，可调整攻击距离、摇手动画、目标类型、弓箭模式等。
 *  - 不依赖 Mod Menu 等任何前置模组。
 *  - 配置保存于 config/killaura-config.txt，按键绑定保存于原版 options.txt。
 */
public class KillauraMod implements ClientModInitializer {

    public static final String MOD_ID = "killauraplus";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("Killaura++（杀戮光环++）Fabric 1.20.1 已加载。R 键开关，K 键打开设置（均可在按键绑定里改）。");
        KillauraCore.init();
    }
}
