package com.killauraplus;

import com.killauraplus.KillauraConfig.TargetPriority;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

/**
 * 杀戮光环设置界面。
 *
 * 布局（10 行，行高 18，整体垂直居中）：
 *   1. 近战攻击距离（meleeReach，1~100）—— 滑块
 *   2. 弓箭攻击距离（bowReach，1~100）—— 滑块
 *   3. 攻击间隔（attackInterval，tick）—— 数字输入框
 *   4. 同时攻击数量（targetCount，1~100）—— 滑块
 *   5. 目标类型（ANY/PLAYER/MONSTER/ANIMAL）—— 按钮循环切换
 *   6. 摇手动画（swingHand）—— 按钮切换
 *   7. 弓箭模式（bowEnabled）—— 按钮切换
 *   8. 弓箭立即开火（bowInstantFire）—— 按钮切换，弓箭模式关闭时置灰
 *   9. 在服务器上启用（enabledInServer）—— 按钮切换
 *  10. 设置界面按键（默认 K）—— 点一下再按新键即可改，绑定存进原版 options.txt
 *
 * 设计说明（重要）：
 *  控件直接绑定 config，所见即所存，不经过 OptionInstance / OptionsList 这套在 1.20.1
 *  存在读取不同步缺陷的中间层。拖动滑块/输入数字/点按钮时立即写回 config；
 *  "完成"只负责落盘；"取消"把 config 还原为进入界面时的快照。
 */
public class KillauraConfigGUI extends Screen {

    /** 上一个界面（Mod Menu 传入），用于关闭时正确返回 */
    private final Screen previousScreen;

    /** 进入界面时的配置快照，用于"取消"时还原 */
    private final int initMeleeReach;
    private final int initBowReach;
    private final boolean initSwingHand;
    private final boolean initEnabledInServer;
    private final TargetPriority initTargetPriority;
    private final int initAttackInterval;
    private final int initTargetCount;
    private final boolean initBowEnabled;
    private final boolean initBowInstantFire;

    private static final int MIN_REACH = 1;
    private static final int MAX_REACH = 100;
    private static final int MIN_TARGET_COUNT = 1;
    private static final int MAX_TARGET_COUNT = 100;

    /** 控件宽度 */
    private static final int W = 200;
    /** 行高（9 行要放进 240 逻辑高度，用 18 而不是 20） */
    private static final int ROW_H = 18;
    /** 行数 */
    private static final int ROWS = 10;

    private ReachSlider meleeReachSlider;
    private ReachSlider bowReachSlider;
    private EditBox intervalBox;
    private TargetCountSlider countSlider;
    private Button targetValue;
    private Button swingValue;
    private Button bowValue;
    private Button bowInstantValue;
    private Button serverValue;
    private Button keyValue;

    /** 是否正在等待玩家按下新的「打开设置」按键 */
    private boolean listeningKey = false;

    /** 第一行控件的 y 坐标，init() 时按屏幕高度动态居中计算 */
    private int startY;

    public KillauraConfigGUI() {
        this(null);
    }

    public KillauraConfigGUI(Screen previous) {
        super(Component.translatable("key.killauraplus.killaura_config"));
        this.previousScreen = previous;
        this.initMeleeReach = KillauraCore.config.getMeleeReach();
        this.initBowReach = KillauraCore.config.getBowReach();
        this.initSwingHand = KillauraCore.config.isSwingHand();
        this.initEnabledInServer = KillauraCore.config.isEnabledInServer();
        this.initTargetPriority = KillauraCore.config.getTargetPriority();
        this.initAttackInterval = KillauraCore.config.getAttackInterval();
        this.initTargetCount = KillauraCore.config.getTargetCount();
        this.initBowEnabled = KillauraCore.config.isBowEnabled();
        this.initBowInstantFire = KillauraCore.config.isBowInstantFire();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int buttonY = this.height - 32;

        // 9 行整体在「标题下方 ~ 按钮上方」之间垂直居中，保证不同 GUI 缩放下都不重叠
        this.startY = Math.max(26, (buttonY - ROW_H * ROWS) / 2 + 4);

        // —— 第 1 行：近战攻击距离 ——
        this.meleeReachSlider = this.addRenderableWidget(
                new ReachSlider(cx - W / 2, startY, W, ROW_H,
                        KillauraCore.config.getMeleeReach(), false));

        // —— 第 2 行：弓箭攻击距离 ——
        this.bowReachSlider = this.addRenderableWidget(
                new ReachSlider(cx - W / 2, startY + ROW_H, W, ROW_H,
                        KillauraCore.config.getBowReach(), true));

        // —— 第 3 行：攻击间隔（数字输入框，单位 tick）——
        // 左侧画文字标签（见 render），右侧放输入框，只允许 1~6 位数字（上限不设）
        this.intervalBox = this.addRenderableWidget(
                new EditBox(this.font, cx, startY + ROW_H * 2, W / 2, ROW_H, Component.literal("")));
        this.intervalBox.setMaxLength(6);
        this.intervalBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,6}"));
        this.intervalBox.setResponder(s -> {
            if (s != null && !s.isEmpty()) {
                try {
                    KillauraCore.config.setAttackInterval(Integer.parseInt(s));
                } catch (NumberFormatException ignored) {
                    // 非法数字忽略，保留上一次有效值
                }
            }
        });
        this.intervalBox.setValue(String.valueOf(KillauraCore.config.getAttackInterval()));

        // —— 第 4 行：同时攻击数量 ——
        this.countSlider = this.addRenderableWidget(
                new TargetCountSlider(cx - W / 2, startY + ROW_H * 3, W, ROW_H,
                        KillauraCore.config.getTargetCount()));

        // —— 第 5 行：目标类型（循环：任意→玩家→怪物→动物）——
        this.targetValue = this.addRenderableWidget(
                Button.builder(Component.literal(""), b -> {
                    KillauraCore.config.setTargetPriority(next(KillauraCore.config.getTargetPriority()));
                    refreshTexts();
                }).bounds(cx - W / 2, startY + ROW_H * 4, W, ROW_H).build());

        // —— 第 6 行：摇手动画 ——
        this.swingValue = this.addRenderableWidget(
                Button.builder(Component.literal(""), b -> {
                    KillauraCore.config.setSwingHand(!KillauraCore.config.isSwingHand());
                    refreshTexts();
                }).bounds(cx - W / 2, startY + ROW_H * 5, W, ROW_H).build());

        // —— 第 7 行：弓箭模式 ——
        this.bowValue = this.addRenderableWidget(
                Button.builder(Component.literal(""), b -> {
                    KillauraCore.config.setBowEnabled(!KillauraCore.config.isBowEnabled());
                    refreshTexts();
                }).bounds(cx - W / 2, startY + ROW_H * 6, W, ROW_H).build());

        // —— 第 8 行：弓箭立即开火（依赖弓箭模式）——
        this.bowInstantValue = this.addRenderableWidget(
                Button.builder(Component.literal(""), b -> {
                    KillauraCore.config.setBowInstantFire(!KillauraCore.config.isBowInstantFire());
                    refreshTexts();
                }).bounds(cx - W / 2, startY + ROW_H * 7, W, ROW_H).build());

        // —— 第 9 行：在服务器上启用 ——
        this.serverValue = this.addRenderableWidget(
                Button.builder(Component.literal(""), b -> {
                    KillauraCore.config.setEnabledInServer(!KillauraCore.config.isEnabledInServer());
                    refreshTexts();
                }).bounds(cx - W / 2, startY + ROW_H * 8, W, ROW_H).build());

        // —— 第 10 行：设置界面按键（点一下进入监听，再按任意键绑定）——
        this.keyValue = this.addRenderableWidget(
                Button.builder(Component.literal(""), b -> {
                    this.listeningKey = true;
                    refreshTexts();
                }).bounds(cx - W / 2, startY + ROW_H * 9, W, ROW_H).build());

        // —— 底部：取消 / 完成 ——
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> doClose(false))
                .bounds((this.width - 210) / 2, buttonY, 100, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> doClose(true))
                .bounds((this.width - 210) / 2 + 110, buttonY, 100, 20).build());

        refreshTexts();
    }

    /** 重绘开关按钮的文字，并同步「弓箭立即开火」的可用状态 */
    private void refreshTexts() {
        if (this.targetValue != null) {
            this.targetValue.setMessage(label("gui.killauraplus.killaura_config_target_priority",
                    targetText(KillauraCore.config.getTargetPriority())));
        }
        if (this.swingValue != null) {
            this.swingValue.setMessage(label("gui.killauraplus.killaura_config_swing_hand",
                    onOff(KillauraCore.config.isSwingHand())));
        }
        if (this.bowValue != null) {
            this.bowValue.setMessage(label("gui.killauraplus.killaura_config_bow",
                    onOff(KillauraCore.config.isBowEnabled())));
        }
        if (this.bowInstantValue != null) {
            this.bowInstantValue.setMessage(label("gui.killauraplus.killaura_config_bow_instant",
                    onOff(KillauraCore.config.isBowInstantFire())));
            // 弓箭模式关闭时，「立即开火」没有意义，置灰不可点
            this.bowInstantValue.active = KillauraCore.config.isBowEnabled();
        }
        if (this.serverValue != null) {
            this.serverValue.setMessage(label("gui.killauraplus.killaura_config_enabled",
                    onOff(KillauraCore.config.isEnabledInServer())));
        }
        if (this.keyValue != null) {
            this.keyValue.setMessage(label("gui.killauraplus.killaura_config_open_key",
                    this.listeningKey
                            ? Component.translatable("gui.killauraplus.press_key")
                            : ModKeys.CONFIG_KEY.getTranslatedKeyMessage()));
        }
    }

    /** 「标签: 值」形式的按钮文案 */
    private static Component label(String key, Component value) {
        return Component.translatable(key).append(": ").append(value);
    }

    private static Component targetText(TargetPriority p) {
        return switch (p) {
            case PLAYER -> Component.translatable("gui.killauraplus.target_priority.player");
            case MONSTER -> Component.translatable("gui.killauraplus.target_priority.monster");
            case ANIMAL -> Component.translatable("gui.killauraplus.target_priority.animal");
            default -> Component.translatable("gui.killauraplus.target_priority.any");
        };
    }

    /** 目标类型循环切换顺序 */
    private static TargetPriority next(TargetPriority cur) {
        return switch (cur) {
            case ANY -> TargetPriority.PLAYER;
            case PLAYER -> TargetPriority.MONSTER;
            case MONSTER -> TargetPriority.ANIMAL;
            default -> TargetPriority.ANY;
        };
    }

    private static Component onOff(boolean b) {
        return Component.translatable(b ? "gui.killauraplus.state_on" : "gui.killauraplus.state_off");
    }

    /**
     * 距离滑块（近战 / 弓箭共用一套实现）。
     * value 为 0.0 ~ 1.0（进度），实际格数 = min + value*(max-min)。
     * 拖动或键盘调整时，applyValue() 会把格数写回对应配置（所见即所存）。
     */
    private class ReachSlider extends AbstractSliderButton {

        /** true = 弓箭距离，false = 近战距离 */
        private final boolean bow;

        ReachSlider(int x, int y, int width, int height, int currentReach, boolean bow) {
            super(x, y, width, height, Component.empty(), toProgress(currentReach));
            this.bow = bow;
            updateMessage();
        }

        private static int fromProgress(double p) {
            return (int) Math.round(MIN_REACH + p * (MAX_REACH - MIN_REACH));
        }

        private static double toProgress(int reach) {
            int clamped = Math.max(MIN_REACH, Math.min(MAX_REACH, reach));
            return (double) (clamped - MIN_REACH) / (double) (MAX_REACH - MIN_REACH);
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.translatable(bow
                            ? "gui.killauraplus.killaura_config_bow_reach"
                            : "gui.killauraplus.killaura_config_reach")
                    .append(": ")
                    .append(Component.translatable("gui.killauraplus.killaura_config_reach_text",
                            fromProgress(this.value))));
        }

        @Override
        protected void applyValue() {
            // 关键：拖动到哪就写到哪，界面显示与 config 始终一致
            if (bow) {
                KillauraCore.config.setBowReach(fromProgress(this.value));
            } else {
                KillauraCore.config.setMeleeReach(fromProgress(this.value));
            }
        }
    }

    /**
     * 同时攻击数量滑块（1~100）。拖动时实时写回 config。
     */
    private class TargetCountSlider extends AbstractSliderButton {
        TargetCountSlider(int x, int y, int width, int height, int currentCount) {
            super(x, y, width, height, Component.empty(), toProgress(currentCount));
            updateMessage();
        }

        private static int fromProgress(double p) {
            return (int) Math.round(MIN_TARGET_COUNT + p * (MAX_TARGET_COUNT - MIN_TARGET_COUNT));
        }

        private static double toProgress(int count) {
            int clamped = Math.max(MIN_TARGET_COUNT, Math.min(MAX_TARGET_COUNT, count));
            return (double) (clamped - MIN_TARGET_COUNT) / (double) (MAX_TARGET_COUNT - MIN_TARGET_COUNT);
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.translatable("gui.killauraplus.killaura_config_target_count")
                    .append(": ")
                    .append(Component.literal(String.valueOf(fromProgress(this.value)))));
        }

        @Override
        protected void applyValue() {
            KillauraCore.config.setTargetCount(fromProgress(this.value));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(graphics);
        graphics.drawCenteredString(this.font, this.title.getString(),
                this.width / 2, Math.max(2, startY - 14), 16777215);
        // 第 3 行输入框左侧的文字标签
        graphics.drawString(this.font, Component.translatable("gui.killauraplus.killaura_config_interval"),
                this.width / 2 - W / 2, startY + ROW_H * 2 + 5, 16777215);
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    /**
     * 改键监听：进入监听后按任意键，就把「打开设置」的按键换成它。
     * Esc 只取消监听，不关闭界面。
     * 绑定直接写进原版 options.txt，下次进游戏依然有效。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.listeningKey) {
            if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
                ModKeys.CONFIG_KEY.setKey(InputConstants.getKey(keyCode, scanCode));
                if (this.minecraft != null) {
                    this.minecraft.options.save();
                }
            }
            this.listeningKey = false;
            refreshTexts();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        // Esc 关闭也视为保存
        this.listeningKey = false;
        doClose(true);
    }

    /**
     * 统一关闭逻辑。
     * @param save true=保存配置后关闭；false=还原为进入界面时的状态后关闭（取消）
     */
    private void doClose(boolean save) {
        if (save) {
            // 滑块/按钮/输入框操作已实时写回 config，这里只需落盘
            KillauraCore.config.saveToFile();
        } else {
            // 取消：还原为进入界面时的快照
            KillauraCore.config.setMeleeReach(this.initMeleeReach);
            KillauraCore.config.setBowReach(this.initBowReach);
            KillauraCore.config.setSwingHand(this.initSwingHand);
            KillauraCore.config.setEnabledInServer(this.initEnabledInServer);
            KillauraCore.config.setTargetPriority(this.initTargetPriority);
            KillauraCore.config.setAttackInterval(this.initAttackInterval);
            KillauraCore.config.setTargetCount(this.initTargetCount);
            KillauraCore.config.setBowEnabled(this.initBowEnabled);
            KillauraCore.config.setBowInstantFire(this.initBowInstantFire);
        }
        if (this.previousScreen != null) {
            this.minecraft.setScreen(this.previousScreen);
        } else {
            super.onClose();
        }
    }
}
