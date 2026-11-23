package com.killauraplus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 杀戮光环配置。
 * 配置文件位于 config/killaura-config.txt，分号分隔：
 *   meleeReach;swingHand;enabledInServer;targetPriority;attackInterval;targetCount;bowEnabled;bowInstantFire;bowReach
 * 默认：3;true;false;ANY;10;1;false;false;20
 *
 * 字段说明：
 *  - meleeReach       近战攻击距离（格，1~100）
 *  - swingHand        是否摇手
 *  - enabledInServer  远程服务器是否启用
 *  - targetPriority   攻击目标类型 ANY/PLAYER/MONSTER/ANIMAL（只打所选类型，不再回退打其它）
 *  - attackInterval   攻击间隔（tick，1~无上限；20 tick = 1 秒）
 *  - targetCount      每次同时攻击的目标数量（1~100）
 *  - bowEnabled       主手为弓时是否改用弓箭输出
 *  - bowInstantFire   弓箭是否立即开火（false=等满蓄力，true=按攻击间隔射）
 *  - bowReach         弓箭攻击距离（格，1~100），与近战距离互相独立
 *
 * 容错：
 *  - 配置损坏或缺字段时降级为默认值，不抛出崩溃（容忍手动编辑出错）
 *  - 数值读取后 clamp 到合法范围，避免手写超界值
 *  - 写入采用临时文件 + ATOMIC_MOVE 原子替换，避免崩溃时写坏配置
 *  - 旧版本配置（3~8 字段）读不到新字段时自动回退默认，不会崩
 */
public class KillauraConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("killaura-config");

    private static final int DEFAULT_MELEE_REACH = 3;
    private static final boolean DEFAULT_SWING_HAND = true;
    private static final boolean DEFAULT_IN_SERVER = false;
    private static final TargetPriority DEFAULT_TARGET_PRIORITY = TargetPriority.ANY;
    /** 攻击间隔，单位 tick（20 tick = 1 秒）。10 tick = 每 0.5 秒攻击一次 */
    private static final int DEFAULT_ATTACK_INTERVAL = 10;
    /** 每次同时攻击的目标数量 */
    private static final int DEFAULT_TARGET_COUNT = 1;
    /** 主手为弓时是否改用弓箭输出 */
    private static final boolean DEFAULT_BOW_ENABLED = false;
    /** 弓箭是否立即开火（false = 等满蓄力再射） */
    private static final boolean DEFAULT_BOW_INSTANT_FIRE = false;
    /** 弓箭默认射程：比近战远，符合弓的实际使用习惯 */
    private static final int DEFAULT_BOW_REACH = 20;

    private static final int MIN_REACH = 1;
    private static final int MAX_REACH = 100;
    /** 攻击间隔下限；上限不设，可填 1 ~ 任意大（输入框最多 6 位） */
    private static final int MIN_INTERVAL = 1;
    private static final int MIN_TARGET_COUNT = 1;
    private static final int MAX_TARGET_COUNT = 100;

    /** 攻击目标类型（只打所选类型，不再回退打其它） */
    public enum TargetPriority {
        /** 任意：范围内所有可见目标 */
        ANY,
        /** 只打玩家 */
        PLAYER,
        /** 只打敌对怪物（僵尸、骷髅等） */
        MONSTER,
        /** 只打动物（牛、猪、羊等被动生物） */
        ANIMAL
    }

    private final Path filePath;
    private int meleeReach;
    private boolean swingHand;
    private boolean enabledInServer;
    private TargetPriority targetPriority;
    private int attackInterval;
    private int targetCount;
    private boolean bowEnabled;
    private boolean bowInstantFire;
    private int bowReach;

    public KillauraConfig(Path path) {
        this.filePath = path;
        load();
    }

    private void load() {
        // 先加载默认值，任何解析失败都只回退默认，绝不抛异常
        this.meleeReach = DEFAULT_MELEE_REACH;
        this.swingHand = DEFAULT_SWING_HAND;
        this.enabledInServer = DEFAULT_IN_SERVER;
        this.targetPriority = DEFAULT_TARGET_PRIORITY;
        this.attackInterval = DEFAULT_ATTACK_INTERVAL;
        this.targetCount = DEFAULT_TARGET_COUNT;
        this.bowEnabled = DEFAULT_BOW_ENABLED;
        this.bowInstantFire = DEFAULT_BOW_INSTANT_FIRE;
        this.bowReach = DEFAULT_BOW_REACH;

        if (!Files.exists(this.filePath)) {
            saveToFile(); // 不存在时仅落盘默认值，忽略写入失败
            return;
        }

        try {
            List<String> parts = new ArrayList<>();
            for (String s : Files.readString(this.filePath).split(";")) {
                parts.add(s.trim());
            }

            if (parts.size() >= 1 && !parts.get(0).isEmpty()) {
                try {
                    this.meleeReach = clampReach(Integer.parseInt(parts.get(0)));
                } catch (NumberFormatException ignored) {
                    // 距离非法不解析，保留默认
                }
            }
            if (parts.size() >= 2) {
                this.swingHand = parseBool(lenient(parts.get(1)), DEFAULT_SWING_HAND);
            }
            if (parts.size() >= 3) {
                this.enabledInServer = parseBool(lenient(parts.get(2)), DEFAULT_IN_SERVER);
            }
            if (parts.size() >= 4) {
                this.targetPriority = parseEnum(lenient(parts.get(3)), DEFAULT_TARGET_PRIORITY);
            }
            if (parts.size() >= 5) {
                try {
                    this.attackInterval = clampInterval(Integer.parseInt(lenient(parts.get(4))));
                } catch (NumberFormatException ignored) {
                    // 间隔非法保留默认
                }
            }
            if (parts.size() >= 6) {
                try {
                    this.targetCount = clampTargetCount(Integer.parseInt(lenient(parts.get(5))));
                } catch (NumberFormatException ignored) {
                    // 数量非法保留默认
                }
            }
            if (parts.size() >= 7) {
                this.bowEnabled = parseBool(lenient(parts.get(6)), DEFAULT_BOW_ENABLED);
            }
            if (parts.size() >= 8) {
                this.bowInstantFire = parseBool(lenient(parts.get(7)), DEFAULT_BOW_INSTANT_FIRE);
            }
            if (parts.size() >= 9) {
                try {
                    this.bowReach = clampReach(Integer.parseInt(lenient(parts.get(8))));
                } catch (NumberFormatException ignored) {
                    // 弓箭距离非法保留默认
                }
            }
        } catch (IOException e) {
            // 读取失败不崩溃，保留默认值
        }
    }

    private static TargetPriority parseEnum(String s, TargetPriority def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return TargetPriority.valueOf(s.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    private static int clampReach(int v) {
        return Math.max(MIN_REACH, Math.min(MAX_REACH, v));
    }

    private static int clampInterval(int v) {
        // 不设上限：攻击间隔可以填 1 ~ 任意大
        return Math.max(MIN_INTERVAL, v);
    }

    private static int clampTargetCount(int v) {
        return Math.max(MIN_TARGET_COUNT, Math.min(MAX_TARGET_COUNT, v));
    }

    /** 兼容布尔大小写：True/TRUE/1 等 */
    private static boolean parseBool(String s, boolean def) {
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) return true;
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) return false;
        return def;
    }

    /** 去掉既定布尔值之外的脏字符干扰，如 "true\n" */
    private static String lenient(String s) {
        return s == null ? "" : s.trim();
    }

    public int getMeleeReach() {
        return meleeReach;
    }

    public int getBowReach() {
        return bowReach;
    }

    public boolean isSwingHand() {
        return swingHand;
    }

    public boolean isEnabledInServer() {
        return enabledInServer;
    }

    public TargetPriority getTargetPriority() {
        return targetPriority;
    }

    public int getAttackInterval() {
        return attackInterval;
    }

    public int getTargetCount() {
        return targetCount;
    }

    public boolean isBowEnabled() {
        return bowEnabled;
    }

    public boolean isBowInstantFire() {
        return bowInstantFire;
    }

    public void setMeleeReach(int reach) {
        this.meleeReach = clampReach(reach);
    }

    public void setBowReach(int reach) {
        this.bowReach = clampReach(reach);
    }

    public void setSwingHand(boolean swing) {
        this.swingHand = swing;
    }

    public void setEnabledInServer(boolean enabledInServer) {
        this.enabledInServer = enabledInServer;
    }

    public void setTargetPriority(TargetPriority priority) {
        this.targetPriority = priority == null ? DEFAULT_TARGET_PRIORITY : priority;
    }

    public void setAttackInterval(int interval) {
        this.attackInterval = clampInterval(interval);
    }

    public void setTargetCount(int count) {
        this.targetCount = clampTargetCount(count);
    }

    public void setBowEnabled(boolean enabled) {
        this.bowEnabled = enabled;
    }

    public void setBowInstantFire(boolean instant) {
        this.bowInstantFire = instant;
    }

    public void saveToFile() {
        try {
            // 确保 config/ 目录存在（某些环境下 .minecraft/config 目录原生不存在，直接写会失败）
            Files.createDirectories(this.filePath.getParent());
            // 先写入同目录临时文件，再原子替换，保证写入过程中崩溃不会残留半截配置
            Path tmp = this.filePath.resolveSibling(this.filePath.getFileName() + ".tmp");
            Files.writeString(tmp, getMeleeReach() + ";" + isSwingHand() + ";" + isEnabledInServer() + ";"
                    + getTargetPriority() + ";" + getAttackInterval() + ";" + getTargetCount() + ";"
                    + isBowEnabled() + ";" + isBowInstantFire() + ";" + getBowReach());
            try {
                Files.move(tmp, this.filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                // 文件系统不支持原子移动时退化为普通替换
                Files.move(tmp, this.filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // 保存失败不抛到上层以避免把崩溃扩散到游戏主线程，但输出日志便于排查
            LOGGER.error("Failed to save killaura config to {}", this.filePath, e);
        }
    }
}
