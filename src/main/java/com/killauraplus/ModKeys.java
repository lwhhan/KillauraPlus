package com.killauraplus;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * 杀戮光环按键定义。
 *  R = 触发杀戮光环
 *  K = 打开设置界面
 *
 * 两个键都注册成 KeyMapping，因此都能在「选项 → 控制 → 按键绑定 → 杀戮光环」里改；
 * 设置界面里也有一行「设置界面按键」，点一下再按新键即可改，改完自动写进 options.txt。
 */
public class ModKeys {
    public static final String KEY_CATEGORY_KL = "key.category.killauraplus.kl";
    public static final String KEY_KILLAURA = "key.killauraplus.killaura";
    public static final String KEY_CONFIG = "key.killauraplus.killaura_config";

    public static final KeyMapping KILLAURA_KEY = new KeyMapping(KEY_KILLAURA,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, KEY_CATEGORY_KL);

    /** 打开设置界面（默认 K） */
    public static final KeyMapping CONFIG_KEY = new KeyMapping(KEY_CONFIG,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, KEY_CATEGORY_KL);
}
