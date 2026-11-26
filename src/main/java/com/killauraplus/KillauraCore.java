package com.killauraplus;

import com.killauraplus.KillauraConfig.TargetPriority;
import com.killauraplus.mixin.LivingEntityAccessor;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 杀戮光环核心逻辑（Fabric 1.20.1 移植版）。
 *
 * 功能：
 *  - R 键：切换开关。开启后以玩家为中心、按距离搜索可攻击目标，
 *    每轮最多同时攻击 targetCount 个目标，两轮之间间隔 attackInterval 个 tick。
 *  - 近战与弓箭使用各自独立的搜索距离（meleeReach / bowReach）。
 *  - 主手为弓且开启「弓箭模式」时，改用弓箭自动瞄准射击。
 *  - 配置界面：Mod Menu 模组菜单中打开。
 *
 * 目标类型规则：
 *  选择「玩家 / 怪物 / 动物」时【只打该类型】，不会回退去打其它类型；
 *  选「任意」时才打范围内所有可见目标。例如选怪物时，动物和玩家都不会被攻击。
 *
 * 视线规则（重要）：
 *  本模组只取消原版的「攻击距离」限制（相当于把手变长），但保留原版最基本的可见性要求：
 *  从玩家眼睛到目标身体做方块射线检测，被方块挡住的目标一律不选。
 *  因此隔着墙、隔着一层石头、隔着一片树叶都打不到，必须真的能看见才攻击/射箭。
 *  树叶和其他方块一视同仁，都算遮挡。近战与弓箭共用同一套视线规则。
 *
 * 弓箭弹道规则：
 *  原版箭是抛物线飞行（每 tick 0.99 阻力 + 0.05 重力），20 格距离会下坠约 1 格，
 *  30 格下坠约 2.4 格，所以照直线瞄根本射不到。这里按原版物理反解仰角做弹道补偿，
 *  并对目标移动做飞行时间预判，远距离也能射中。
 *  另外箭的出生点从眼睛下移到胸口以下，第一人称看不到出生瞬间，飞出去才看得见，
 *  避免连射时一堆箭头糊在屏幕中央挡视野。
 *
 * 追踪箭：
 *  不再做弹道补偿和移动预判 —— 箭发射后每 tick 把速度方向重新指向目标头部（眼睛高度），
 *  速度恒定 3.0 格/tick 不衰减。目标跑、跳、后退都躲不掉；只露脑袋的怪也能打到。
 *  唯一会让箭落空的是中间有方块（箭不穿墙，撞住就作废并给目标记 1 秒冷却）。
 *
 * 按血量定箭数：
 *  追踪箭必中、速度恒定、没有暴击随机，单发伤害可以精确算出来。每波开火前算
 *  ceil(当前血量 / 单发伤害)，射够这个数就停，不会对快死的怪继续输出，也不会一支一支
 *  等间隔 —— 一波内把需要的箭射满，残血目标能立刻补刀。
 *
 * 性能设计（周围怪多时仍然流畅）：
 *  1. 追踪修正：每 tick 只对在飞的几支箭做一次向量运算，开火路径不再做方块轨迹检测和仰角求解。
 *  2. 分帧射击：每 tick 最多处理 MAX_SHOTS_PER_TICK 个目标，剩余目标排队到后续 tick，
 *     targetCount 的 1~100 全部有效，不会单帧卡死。
 *  3. 目标搜索优化：目标类型过滤前置到实体查询的 predicate 里（候选列表从一开始就小），
 *     距离预计算后再排序，视线检测次数按 targetCount 动态计算而不是固定 32 次。
 *
 * 攻击路径说明：
 *  - 单机（单人世界，hasSingleplayerServer）：服务器就在本地 JVM，之间【不经过网络包】，
 *    而是用 server.execute() 调度到服务端线程直接调用服务端逻辑。
 *    原因：原版服务端在收到 ServerboundInteractPacket 攻击包时会做硬编码的 6 格距离校验
 *    （handleInteract 内 distanceToSqr < 36.0），超范围直接丢弃攻击包。
 *    这导致「攻击距离 reach 调得再大也不生效、摇手开关也连带像没生效」——
 *    配置本身能保存，问题出在服务端距离校验。因此单机直接调服务端攻击，绕开 6 格限制。
 *  - 远程服务器：近战仍走 ServerboundInteractPacket / ServerboundSwingPacket 网络发包，
 *    由对方服务端正常校验（作弊距离在公共服务器上本就无效，属正常现象）。
 *    弓箭模式需要完整的拉弓-释放协议时序，无法可靠模拟，仅支持单人游戏。
 */
public class KillauraCore {

    /**
     * 强制满蓄力用的 attackStrengthTicker 值。
     * Player.attack() 在 attackStrengthTicker > 0 且 getAttackStrengthScale < 1.0 时会直接 return，
     * 导致攻击间隔快于原版攻击冷却时「摇好几次手才受一次伤」。每次攻击前把蓄力设为这个足够大的值，
     * 保证每轮都能打满伤害。
     * 该字段在原版是 protected，需通过 LivingEntityAccessor 写入。
     */
    private static final int FORCE_FULL_CHARGE_TICKS = 100;

    /** 弓满蓄力所需 tick（原版 getPowerForTime 以 20 tick 为满） */
    private static final int BOW_FULL_CHARGE_TICKS = 20;

    /** 满蓄力时箭的初速度（原版 getPowerForTime(20) = 1.0，速度 = 1.0 * 3.0） */
    private static final float BOW_ARROW_SPEED = 3.0F;

    /**
     * 射击散布。原版满蓄力的散布是 0（箭完全沿准星方向飞出）。
     * 追踪模式下直接以 0 发射（见 shootArrowAt 里的 {@code arrow.shoot(..., 0.0F)}），
     * 这个常量只是把「散布必须为 0」这条结论留在常量区备查。
     */
    private static final float BOW_INACCURACY = 0.0F;

    /**
     * 箭的出生点相对眼睛位置的下移量（格）。
     * 原版箭从眼睛位置射出，第一人称视角下箭就贴在镜头正前方，
     * 连射时一堆箭头糊在屏幕中央挡视野。把出生点下移到胸口/腰部以下，
     * 第一人称就看不到箭的出生瞬间，飞出一段距离后才进入视野。
     */
    private static final double ARROW_SPAWN_DROP = 0.8D;

    /** 箭的出生点沿瞄准方向前移量（格），避免贴着身体生成 */
    private static final double ARROW_SPAWN_FORWARD = 0.25D;

    /** 箭的每 tick 水平阻力系数（原版 AbstractArrow 固定 0.99） */
    private static final double ARROW_DRAG = 0.99D;

    /** 箭的每 tick 重力加速度（原版 AbstractArrow 固定 0.05 格/tick²） */
    private static final double ARROW_GRAVITY = 0.05D;

    /** 移动预判时目标速度的钳制上限（格/tick），防止瞬移类目标把瞄准点带飞 */
    private static final double MAX_LEAD_SPEED = 1.0D;

    /**
     * 垂直方向的预判折扣。生物跳跃/下落不规律，按满值预判容易打高，
     * 但只取一半又会让跳跃中的目标被打低，0.7 折中。
     */
    private static final double LEAD_VERTICAL_FACTOR = 0.7D;

    /**
     * 移动预判的迭代次数。
     * 迭代解的是不动点：飞行时间取决于目标未来位置，未来位置又取决于飞行时间。
     * 目标越快，这个循环收敛越慢 —— 2 次对疾跑勉强够，对鞘翅、疾跑跳的目标会残留明显误差，
     * 所以提到 4 次。只是多算几次三角函数，开销可以忽略。
     */
    private static final int LEAD_ITERATIONS = 4;

    /**
     * 追踪箭的恒定飞行速度（格/tick）。原版满蓄力初速就是 3.0，追踪时每 tick 重新指向目标、
     * 速度保持不变，所以箭不会因为重力和阻力衰减，伤害也就完全可预测。
     */
    private static final float HOMING_SPEED = 3.0F;

    /**
     * 追踪箭离目标多近时开始清零目标的受伤无敌帧（格，取平方比较）。
     *
     * 原版 {@code LivingEntity.hurt} 在命中后把 invulnerableTime 设为 20，并且只要
     * {@code invulnerableTime > 10} 且这次伤害不高于上次，就直接丢弃 —— 也就是说一箭命中后
     * 有 10 tick 的窗口，期间同伤害的箭全部无效。不清零的话「两箭射死」根本不成立，第二箭会白飞。
     *
     * 箭速 3 格/tick，取 4 格（16）能保证在命中前一两个 tick 就清掉，及时且不影响远处目标。
     */
    private static final double HOMING_INVULN_CLEAR_DIST_SQR = 16.0D;

    // 附魔注册名（用注册表按名字查，避免不同映射下字段名差异导致编译失败）
    private static final ResourceLocation ENCH_POWER = new ResourceLocation("minecraft", "power");
    private static final ResourceLocation ENCH_PUNCH = new ResourceLocation("minecraft", "punch");
    private static final ResourceLocation ENCH_FLAME = new ResourceLocation("minecraft", "flame");
    private static final ResourceLocation ENCH_INFINITY = new ResourceLocation("minecraft", "infinity");

    /**
     * 每轮视线检测次数的上限（硬上限）。
     * 实际使用的次数会按 targetCount 动态计算，见 pickTargets。
     */
    private static final int MAX_LOS_CHECKS = 32;

    /**
     * 弹道避让的候选仰角偏移（度）。
     * 只保留三个候选：原解、小幅下压、小幅上抬。
     * 候选越多越不容易卡住，但每个候选都要跑一遍方块检测，是卡顿的主要来源，
     * 所以这里只留最有效的三个 —— 实测压低一点往往就能从树冠下沿穿过去。
     */
    private static final float[] ELEVATION_OFFSETS = {0.0F, -4.0F, 4.0F};

    /** 弹道检测沿轨迹最多切多少段（每段一次方块射线检测） */
    private static final int MAX_TRAJECTORY_STEPS = 8;

    /** 弹道检测时每段大约覆盖多少格水平距离（段数 = 距离 / 这个值） */
    private static final double TRAJECTORY_STEP_SPAN = 3.0D;

    /**
     * 每 tick 最多射出几支箭。
     * 目标很多时不会一帧内全部射出去，而是排队分摊到后续 tick，
     * 避免瞬时创建大量箭实体造成卡顿。targetCount 仍然全部有效，只是节奏被拉长。
     */
    private static final int MAX_SHOTS_PER_TICK = 6;


    /**
     * 箭撞上方块后的重试冷却（tick）。
     *
     * 原来设 20，近距离目标（尤其是脚下、隔着一个台阶的）箭容易先擦到身前的方块，
     * 一撞就要等满 1 秒才敢再射，手感上就是「怪就在脚底下却半天不出箭」。
     * 缩到 5 tick：撞了很快重试，配合瞄准点改到头部，通常一两下就能打中。
     */
    private static final int BLOCKED_COOLDOWN_TICKS = 5;

    /** 仰角求解的扫描步长（度）。2° 配合 12 次二分，最终精度约 0.0005° */
    private static final double ELEVATION_SCAN_STEP = 2.0D;

    /** 仰角求解的二分细化次数 */
    private static final int ELEVATION_BISECT_STEPS = 12;

    /**
     * 模组射出的箭最长存活 tick（含飞行时间）。
     * 原版落地箭要累计 1200 tick（60 秒）才消失，持续射箭会在地上堆几百支，
     * 每支都要 tick 和渲染，越打越卡。这里改成 5 秒回收，最多只会留几十支。
     */
    private static final int MOD_ARROW_LIFETIME_TICKS = 100;

    /** 箭矢回收的检查间隔（tick） */
    private static final int ARROW_CLEANUP_INTERVAL = 20;

    /**
     * 搜索不到可见目标时的重查间隔（tick）。
     * 之前写 1 等于没冷却 —— 递减后当帧就归零，实际上每 tick 都在做全量实体扫描 + 视线检测。
     */
    private static final int EMPTY_TARGET_RECHECK_TICKS = 5;

    public static final KillauraConfig config =
            new KillauraConfig(FabricLoader.getInstance().getConfigDir().resolve("killaura-config.txt"));

    private static int cooldownRemaining = 0;
    /** 用于防止"服务器未启用"提示刷屏 */
    private static int warnCooldown = 0;

    /** 杀戮光环是否启用（切换模式：按一次 R 开启，再按一次关闭） */
    private static boolean enabled = false;

    // ==================== 分帧射击队列 ====================

    /** 待射目标队列（存实体 ID）。跨线程访问，必须用并发容器 */
    private static final Deque<Integer> pendingShots = new ConcurrentLinkedDeque<>();

    /** 队列所属维度，切换维度时清空，避免拿旧 ID 误伤同 ID 的其他实体 */
    private static ResourceKey<Level> pendingDim = null;

    /** 目标 UUID → 弹道冷却到期的游戏时间 */
    private static final Map<UUID, Long> blockedUntil = new ConcurrentHashMap<>();

    /** 目标 UUID → 下一次允许对它射箭的游戏时间（按「攻击间隔」算） */
    private static final Map<UUID, Long> nextShotAt = new ConcurrentHashMap<>();

    /** 是否已有一个射箭任务排在服务端线程队列里（防重复提交堆积） */
    private static volatile boolean bowTaskQueued = false;

    /**
     * 弓箭模式「本轮」结束后，下一轮最早可以开始的时间（游戏刻）。
     *
     * 为什么需要它：一轮的目标全部处理完（打死 / 跑出射程 / 箭够了）时队列会变空。
     * 如果这时立刻重新搜索目标，就会出现「上一个怪刚死、下一 tick 就去射下一个」，
     * 攻击间隔设置等于完全没生效，看起来像在同时打一堆目标 —— 也就是
     * 「同时攻击目标设 1、间隔设 10，却还在连续打不同目标」的那个现象。
     * 由服务端线程在队列清空时写入，客户端线程只读。
     */
    private static volatile long bowNextRoundAt = 0L;

    /** 本轮开始的游戏刻；0 表示当前没有进行中的一轮。 */
    private static volatile long bowRoundStartAt = 0L;

    /** 本轮已经射出的箭数（服务端线程维护），用来识别「一支箭都射不出去的卡死」。 */
    private static volatile int bowRoundShots = 0;

    /**
     * 一轮里「一支箭都没射出去」最多允许持续多少 tick。
     * 超过就强制结束本轮换目标 —— 典型场景是目标躲到墙后，箭反复撞墙，
     * 它却一直占着队列位置，把别的怪全挡在外面。
     */
    private static final int BOW_STUCK_TICKS = 60;

    /**
     * 单轮最长持续时间（tick）。超过就强制结束本轮重新开始。
     * 主要防「目标在墙后、箭反复撞墙」这类打不到又不肯放手的情况；
     * 血厚的目标正常打也会碰到这个上限，但重新开始后会立刻选回同一个目标继续射，
     * 只是中间停顿一个攻击间隔，不影响实际输出。
     */
    private static final int BOW_ROUND_MAX_TICKS = 400;

    /** shootArrowAt 的三种结果 */
    private static final int SHOT_DONE = -1;   // 不需要再射（目标死了 / 在飞的箭已经够 / 没箭矢）
    private static final int SHOT_WAIT = 0;    // 还没到时候（攻击间隔未到 / 撞墙冷却中），下一 tick 再看
    private static final int SHOT_FIRED = 1;   // 射出去了

    // ==================== 箭矢回收 ====================

    /** 模组射出的箭：实体 ID → 生成时的游戏时间，用于定期回收，避免地面上堆积 */
    private static final Map<Integer, Long> modArrows = new ConcurrentHashMap<>();

    /** 目标 UUID → 正在飞行（未命中、未落地）的箭实体 ID。用来算「这一轮还差几箭」 */
    private static final Map<UUID, List<Integer>> inflightArrows = new ConcurrentHashMap<>();

    // ==================== 追踪箭 ====================

    /**
     * 正在追踪的箭：箭实体 ID → 追踪目标。
     * 箭生成时登记，命中/落地/目标死亡后自动摘掉。每 tick 在服务端线程修正一次方向。
     */
    private static final Map<Integer, HomingTarget> homingArrows = new ConcurrentHashMap<>();

    /** 追踪目标：箭在哪个维度、追哪个实体 */
    private record HomingTarget(ResourceKey<Level> dim, int targetId) {
    }

    /** 箭矢回收计时器（tick 倒数） */
    private static int arrowCleanupTimer = 0;

    public static void init() {
        // 仅客户端注册，纯客户端模组
        KeyBindingHelper.registerKeyBinding(ModKeys.KILLAURA_KEY);
        // 设置界面按键（默认 K，可在原版「按键绑定」或设置界面里改）
        KeyBindingHelper.registerKeyBinding(ModKeys.CONFIG_KEY);

        // 追踪箭修正：每 tick 在服务端线程把飞行中的箭重新指向目标。
        // 注册在通用事件上（不是 client 事件）——单机时集成服务端会触发它。
        ServerTickEvents.END_SERVER_TICK.register(KillauraCore::tickHomingArrows);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (cooldownRemaining > 0) cooldownRemaining--;
            if (warnCooldown > 0) warnCooldown--;

            Player player = client.player;
            if (player == null) return;

            // 定期回收模组射出的箭。不依赖开关状态：关掉光环后地上残留的箭也要清掉。
            if (!modArrows.isEmpty() && --arrowCleanupTimer <= 0) {
                arrowCleanupTimer = ARROW_CLEANUP_INTERVAL;
                MinecraftServer server = client.getSingleplayerServer();
                if (server != null) {
                    ResourceKey<Level> dim = player.level().dimension();
                    server.execute(() -> {
                        ServerLevel level = server.getLevel(dim);
                        if (level != null) recycleArrows(level);
                    });
                }
            }

            // 设置界面按键（默认 K）：已经在设置界面里就不再重复打开
            while (ModKeys.CONFIG_KEY.consumeClick()) {
                if (!(client.screen instanceof KillauraConfigGUI)) {
                    client.setScreen(new KillauraConfigGUI(client.screen));
                }
            }

            // 切换模式：仅在"按键被按下一次"的边沿切换开关，避免按住时反复切换
            while (ModKeys.KILLAURA_KEY.consumeClick()) {
                enabled = !enabled;
                if (!enabled) {
                    // 关闭时把这些临时状态一起清掉，重新开启等于干净重启：
                    // 不会因为上一轮残留的排队目标、撞墙冷却、射箭间隔而卡住不出手。
                    pendingShots.clear();
                    nextShotAt.clear();
                    blockedUntil.clear();
                    bowNextRoundAt = 0L;
                    bowRoundStartAt = 0L;
                    bowRoundShots = 0;
                }
                player.displayClientMessage(
                        Component.translatable(enabled
                                ? "chat.killauraplus.enabled"
                                : "chat.killauraplus.disabled"),
                        true);
            }

            // 已开启时持续攻击（冷却内跳过），未开启则什么都不做
            if (enabled && cooldownRemaining <= 0) {
                doKillaura(client, player);
            }
        });
    }

    private static void doKillaura(Minecraft mc, Player player) {
        Level world = player.level();
        Vec3 center = new Vec3(player.getX(), player.getY(), player.getZ());

        // 弹道冷却表偶尔清理一次，避免长期运行后堆积无用的 UUID
        if (blockedUntil.size() > 256) {
            long now = world.getGameTime();
            blockedUntil.entrySet().removeIf(en -> en.getValue() <= now);
        }

        // 「在飞箭」表偶尔清一次，避免打过的目标一直挂在表里
        if (inflightArrows.size() > 128) {
            inflightArrows.clear(); // 只是丢掉计数，最坏情况多射一箭，无副作用
        }

        if (nextShotAt.size() > 256) {
            long t = world.getGameTime();
            nextShotAt.entrySet().removeIf(en -> en.getValue() <= t);
        }

        // 先确定本轮走哪套模式：主手为弓且弓箭模式开启时才走弓箭，并使用独立的弓箭距离
        boolean bowMode = config.isBowEnabled()
                && player.getMainHandItem().getItem() instanceof BowItem;

        // 队列里还有没射完的目标：直接继续射，不重复做一遍目标搜索
        if (!pendingShots.isEmpty()) {
            if (!bowMode) {
                pendingShots.clear();
            } else {
                ResourceKey<Level> dim = world.dimension();
                MinecraftServer server = mc.getSingleplayerServer();
                if (pendingDim != null && !pendingDim.equals(dim)) {
                    pendingShots.clear();
                    modArrows.clear();
                    inflightArrows.clear();
                    homingArrows.clear();
                } else if (server != null) {
                    shootPending(mc, player, dim, server, config.getBowReach());
                    // 队列非空 → 下一 tick 继续处理。这里不能读 pendingShots.isEmpty()：
                    // 队列的取出/放回都在服务端线程异步做，客户端线程这一刻读到的是「已被取走、
                    // 还没放回」的空队列，会误判成「射完了」，把冷却设成整整一个攻击间隔，
                    // 目标就卡在那里不动 —— 也就是「不重启就不射」的那个现象。
                    cooldownRemaining = 1;
                    // 本轮还没结束：只处理队列，不再搜索新目标。
                    // 之前这里不 return，每 tick 都往队列里塞「当前最近的怪」，
                    // 即使 targetCount 设成 1 也会不断换人，看起来像同时在打很多目标。
                    // 队列里的目标死了 / 跑出射程 / 射够了，服务端会把它踢出队列，
                    // 下一 tick 这里就不会再进来，自然切换到新目标。
                    return;
                }
            }
        }

        // 队列空着、但上一轮刚结束还没到时间 → 老老实实等满「攻击间隔」。
        // 这一条就是「同时攻击目标设 1、间隔设 10，却还在连着打不同目标」的修复点：
        // 上一个目标一死队列就空了，如果不在这里挡住，下一 tick 就会去射下一个，
        // 攻击间隔设置形同虚设。
        if (bowMode) {
            long now = world.getGameTime();
            if (now < bowNextRoundAt) {
                cooldownRemaining = (int) Math.max(1L, bowNextRoundAt - now);
                return;
            }
        }

        double reach = bowMode ? config.getBowReach() : config.getMeleeReach();

        // 目标类型过滤直接放进 predicate —— 选「只打怪物」时，动物和玩家根本不会进候选列表，
        // 省掉后续的过滤、排序和距离计算
        TargetPriority pri = config.getTargetPriority();
        List<LivingEntity> candidates = world.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(reach),
                e -> e != player && e.isAlive() && matchesPriority(e, pri));

        // 按距离排序 + 视线检测，最多取 targetCount 个
        List<LivingEntity> targets = pickTargets(player, candidates, center, config.getTargetCount());
        if (targets.isEmpty()) {
            // 没有可见目标：隔几 tick 再查，避免每 tick 都做一轮全量实体扫描 + 视线检测。
            // 但队列里还有没射完的目标时不能拉长间隔，否则它们的补射会被一起拖慢。
            cooldownRemaining = pendingShots.isEmpty() ? EMPTY_TARGET_RECHECK_TICKS : 1;
            return;
        }

        // 弓箭模式：改用弓箭输出，不走近战
        if (bowMode) {
            doBowKillaura(mc, player, targets);
            return;
        }

        // 攻击路径按环境分流
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            // 单机（集成服务器）：调度到服务端线程直接调 ServerPlayer.attack，绕开 6 格距离校验
            attackSingleplayer(mc, player, targets);
        } else if (config.isEnabledInServer()) {
            // 远程服务器：发网络攻击包与摇手包，由对方服务端正常校验
            ClientPacketListener conn = mc.getConnection();
            if (conn == null) return;
            for (LivingEntity target : targets) {
                conn.getConnection().send(ServerboundInteractPacket.createAttackPacket(target, true));
            }
            if (config.isSwingHand()) {
                conn.getConnection().send(new ServerboundSwingPacket(player.getUsedItemHand()));
            }
        } else {
            // 服务器未启用：提示用户去 Mod Menu 配置，并限制提示频率（避免刷屏）
            if (warnCooldown <= 0) {
                player.sendSystemMessage(Component.translatable("chat.killauraplus.in_server_warning"));
                warnCooldown = 60; // 3 秒最多提示一次
            }
            return; // 未启用则不打，不重置冷却，避免频繁覆盖
        }

        // 攻击间隔可配置：1 ~ 无上限 tick（20 tick = 1 秒）。至少 1 tick，避免同一 tick 内无限循环
        cooldownRemaining = Math.max(1, config.getAttackInterval());
    }

    /** 弓箭模式一轮射完之后的下次冷却 */
    private static int nextBowCooldown() {
        return config.isBowInstantFire()
                ? Math.max(1, config.getAttackInterval())
                : BOW_FULL_CHARGE_TICKS;
    }

    // ==================== 目标筛选 ====================

    /**
     * 挑选本轮要攻击的目标列表。
     *
     * 三步：
     *  1. 按距离由近到远排序（距离先算好再排，避免比较时反复调用 distanceToSqr）。
     *  2. 只对最近的若干个做视线检测 —— 检测次数按 targetCount 动态算，
     *     要 1 个目标就查 8 个，要 5 个就查 10 个，不再固定查满 32 个。
     *  3. 可见的收进结果，凑够 max 个就停。
     *
     * 目标类型过滤已经在实体查询的 predicate 里做完了，这里不再重复。
     */
    private static List<LivingEntity> pickTargets(Player player, List<LivingEntity> candidates,
                                                  Vec3 center, int max) {
        if (candidates.isEmpty() || max <= 0) return Collections.emptyList();

        int n = candidates.size();
        // 距离预计算：排序比较时不再重复做坐标运算
        double[] dist = new double[n];
        for (int i = 0; i < n; i++) {
            dist[i] = candidates.get(i).distanceToSqr(center);
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(dist[a], dist[b]));

        // 视线检测预算：只需要 max 个目标，多查几个备用即可
        int losBudget = Math.min(MAX_LOS_CHECKS, Math.max(8, max * 2));

        List<LivingEntity> result = new ArrayList<>(Math.min(max, n));
        for (int i = 0; i < order.length && i < losBudget; i++) {
            LivingEntity e = candidates.get(order[i]);
            if (canSee(player, e)) {
                result.add(e);
                if (result.size() >= max) break;
            }
        }
        return result;
    }

    /** 实体是否属于当前选定的目标类型（选什么只打什么） */
    private static boolean matchesPriority(LivingEntity e, TargetPriority pri) {
        return switch (pri) {
            case PLAYER -> e instanceof Player;
            // 用 Enemy 而不是 Monster：Monster 只覆盖僵尸/骷髅那类，
            // 幻翼(Phantom)、史莱姆、岩浆怪、恶魂、潜影贝都是 implements Enemy 但
            // 不继承 Monster，用 Monster 判会把它们漏掉 —— 表现就是「幻翼怎么不打」。
            case MONSTER -> e instanceof Enemy;
            case ANIMAL -> e instanceof Animal;
            case ANY -> true;
        };
    }

    /**
     * 玩家是否真的能看见目标（中间没有方块阻挡）。
     *
     * 做法：从箭的实际出生点（眼睛下方一点）向目标做方块射线检测（只测有碰撞箱的方块，
     * 液体不阻挡）。分别向目标「眼睛」和「躯干中点」各打一条射线，任意一条不被挡住就算可见——
     * 这样矮个子目标（蹲下的玩家、小动物）不会因为眼睛被矮墙挡住而误判。
     *
     * 树叶和其他方块一样算遮挡：躲在树冠后面的目标不会被选中。
     * 与视线无关的因素（距离、目标类型）由调用方处理；此处只回答「挡没挡住」。
     */
    private static boolean canSee(Player player, LivingEntity target) {
        if (target.level() != player.level()) return false;

        // 贴脸（3 格内）直接算可见，不做方块射线检测。
        // 这种距离上射线起点在玩家身体里，很容易从自己脚下的方块边缘擦过去被判成「看不见」，
        // 表现就是怪贴在脚边、或者站在你正下方时一直不出箭。这个距离内追踪箭几乎不会被地形挡住。
        if (player.distanceToSqr(target) < 9.0D) return true;

        // 起点用「箭的实际出生点」（眼睛下移一点），不是眼睛本身 —— 判定的是「箭能不能飞过去」。
        // 用眼睛当起点时，站在台阶/方块边缘打脚下的怪，射线会先擦到自己的脚下方块，
        // 被判成不可见，表现就是怪贴在脚边却一直不出箭。
        Vec3 eye = new Vec3(player.getX(), player.getEyeY() - ARROW_SPAWN_DROP, player.getZ());
        Vec3 eyeOfTarget = new Vec3(target.getX(), target.getEyeY(), target.getZ());
        Vec3 bodyOfTarget = new Vec3(target.getX(),
                target.getY() + target.getBbHeight() * 0.5, target.getZ());

        return isClear(player, eye, eyeOfTarget) || isClear(player, eye, bodyOfTarget);
    }

    /** 两点之间是否没有方块阻挡（树叶、石头、木头一律算遮挡） */
    private static boolean isClear(Player player, Vec3 from, Vec3 to) {
        BlockHitResult hit = player.level().clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS;
    }

    // ==================== 弓箭模式 ====================

    /**
     * 弓箭模式入口。
     * 本轮目标先入队，再由 shootPending 按每 tick 的预算分批射出。
     *  - 立即开火 = 是：每支箭之间隔「攻击间隔」tick 连射，每箭都按满蓄力结算
     *  - 立即开火 = 否：每支箭之间隔满蓄力时间（20 tick），节奏更接近原版拉弓
     *
     * 一个目标需要的箭数会在同一轮里连续射完（按上面的间隔），射满才轮到下一轮，
     * 所以「一次没打死就要等好久」的情况不会再出现。
     */
    private static void doBowKillaura(Minecraft mc, Player player, List<LivingEntity> targets) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (!(mc.hasSingleplayerServer() && server != null)) {
            // 远程服务器：需要完整的 START_USE_ITEM / RELEASE_USE_ITEM 时序与视角同步，无法可靠模拟
            pendingShots.clear();
            if (warnCooldown <= 0) {
                player.sendSystemMessage(Component.translatable("chat.killauraplus.bow_server_unsupported"));
                warnCooldown = 60;
            }
            return;
        }

        ResourceKey<Level> dim = player.level().dimension();
        if (pendingDim != null && !pendingDim.equals(dim)) {
            pendingShots.clear();
            modArrows.clear();        // 旧维度的实体 ID 在新维度可能对应别的实体
            inflightArrows.clear();
            homingArrows.clear();
        }
        pendingDim = dim;

        // 新一轮开始：记下时间，供「卡死兜底」判断用（== 0 表示当前没有进行中的一轮）
        if (bowRoundStartAt == 0L) {
            bowRoundStartAt = player.level().getGameTime();
            bowRoundShots = 0;
        }

        // 本轮目标入队。队列长度必须受「同时攻击目标数」约束 —— 否则每 tick 都搜索一次，
        // 最近的目标一换人，旧目标还留在队列里、新目标又加进来，队列会越滚越长，
        // 表现出来就是「设成 1 却在同时打一堆目标」。
        int maxTargets = Math.max(1, config.getTargetCount());
        while (pendingShots.size() > maxTargets) {
            pendingShots.pollLast();   // 设置被调小过，把多余的从队尾裁掉
        }
        for (LivingEntity t : targets) {
            if (pendingShots.size() >= maxTargets) break;
            int id = t.getId();
            if (!pendingShots.contains(id)) {
                pendingShots.addLast(id);
            }
        }

        shootPending(mc, player, dim, server, config.getBowReach());

        // 下一 tick 继续处理队列。这里同样不读 pendingShots.isEmpty()（异步竞态），
        // 队列真的空了的时候，doKillaura 里搜不到目标会自然落到 EMPTY_TARGET_RECHECK_TICKS，
        // 有目标则继续按 nextShotAt 的间隔射 —— 节奏不会失控。
        cooldownRemaining = 1;
    }

    /**
     * 从队列里取最多 MAX_SHOTS_PER_TICK 个目标，每个目标把「这一波需要的箭数」射满。
     *
     * 分帧的意义：targetCount 设到 100 时，如果一帧内射 100 箭，光是实体创建和碰撞注册
     * 就会卡一下。分摊到十几个 tick 之后，帧率平稳，用户看到的是连续射箭。
     *
     * 单个目标需要的箭数会在同一轮里连续射完，每支之间隔一个「攻击间隔」（由
     * {@code shootArrowAt} 里的 nextShotAt 控制），所以残血目标能被立刻补刀，
     * 不会出现「射了两箭没死，然后干等好几秒」的情况。
     */
    private static void shootPending(Minecraft mc, Player player,
                                     ResourceKey<Level> dim, MinecraftServer server,
                                     double reach) {
        if (pendingShots.isEmpty()) return;
        // 同一时刻只允许一个任务在服务端排队：否则每 tick 都提交一个，
        // 服务端一旦慢下来任务就会堆积，队列被反复取出又放回，节奏彻底乱掉。
        if (bowTaskQueued) return;
        bowTaskQueued = true;

        server.execute(() -> {
            try {
                ServerLevel level = server.getLevel(dim);
                if (level == null) return;

                ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player.getUUID());
                if (serverPlayer == null) return;

                long now = level.getGameTime();

                // 取出和放回都在服务端线程完成，客户端线程不去读队列状态
                int budget = Math.min(MAX_SHOTS_PER_TICK, pendingShots.size());
                for (int i = 0; i < budget; i++) {
                    Integer boxed = pendingShots.pollFirst();
                    if (boxed == null) break;
                    int id = boxed;

                    if (!(level.getEntity(id) instanceof LivingEntity target)) continue;
                    if (!target.isAlive()) continue;
                    // 跑出弓箭射程就出队（这里 continue 就是不把它放回队列），
                    // 否则它会一直占着队列位置，挡住刚进入范围的怪
                    if (target.distanceToSqr(serverPlayer) > reach * reach) continue;

                    // 中途切换物品就停止本轮，避免用错武器发射
                    ItemStack bowStack = serverPlayer.getMainHandItem();
                    if (!(bowStack.getItem() instanceof BowItem)) {
                        pendingShots.clear();
                        break;
                    }

                    // 每支箭之间隔一个「攻击间隔」（由 shootArrowAt 里的 nextShotAt 控制），
                    // 射出一支就把目标放回队尾，下一轮继续，直到它需要的箭数射满。
                    //
                    // 为什么不把需要的箭一次性塞进同一个 tick：原版受伤后有 10 tick 无敌判定窗口，
                    // 两支箭如果同一 tick 命中，第二支会被直接丢弃、等于白射。按间隔逐支射，
                    // 命中自然错开，配合追踪时的无敌帧清零，每一箭都真正结算。
                    int result = shootArrowAt(level, serverPlayer, bowStack, target);
                    if (result == SHOT_FIRED) bowRoundShots++;
                    // 射出去了、或者在等攻击间隔/撞墙冷却 → 都留在队列里，下一 tick 再看；
                    // 只有「不需要再射」才真正出队
                    if (result != SHOT_DONE && !pendingShots.contains(id)) {
                        pendingShots.addLast(id);
                    }
                }

                // 队列空了 = 本轮结束：记下下一轮最早开始时间，
                // 让「这一轮」和「下一轮」之间真的隔满攻击间隔。
                if (pendingShots.isEmpty()) {
                    bowRoundStartAt = 0L;
                    bowRoundShots = 0;
                    bowNextRoundAt = now + nextBowCooldown();
                } else if (bowRoundStartAt != 0L
                        && ((bowRoundShots == 0 && now - bowRoundStartAt > BOW_STUCK_TICKS)
                            || now - bowRoundStartAt > BOW_ROUND_MAX_TICKS)) {
                    // 一轮里一支箭都没射出去、又卡了很久：目标多半在墙后打不到。
                    // 强制结束本轮把机会让给别的目标，免得队列被它一个人占死。
                    pendingShots.clear();
                    bowRoundStartAt = 0L;
                    bowNextRoundAt = now + nextBowCooldown();
                }
            } finally {
                bowTaskQueued = false;
            }
        });
    }

    /**
     * 回收模组射出的过期箭矢。
     *
     * 原版 AbstractArrow 落地后要累计 1200 tick（60 秒）才 discard，弓箭模式连续开火时
     * 地面上会堆几百支箭，每支都要 tick、参与碰撞和渲染 —— 这是实测掉帧的主要来源之一。
     * 这里把模组自己射出的箭限制在 MOD_ARROW_LIFETIME_TICKS 内，到点直接 discard。
     *
     * 只在服务端线程调用；箭已经消失（命中后 discard / 掉出世界）的记录顺手清掉。
     */
    private static void recycleArrows(ServerLevel level) {
        if (modArrows.isEmpty()) return;
        long now = level.getGameTime();
        modArrows.entrySet().removeIf(en -> {
            if (!(level.getEntity(en.getKey()) instanceof AbstractArrow arrow) || !arrow.isAlive()) {
                return true;  // 箭已不在这个世界，记录作废
            }
            if (now - en.getValue() >= MOD_ARROW_LIFETIME_TICKS) {
                arrow.discard();
                return true;
            }
            return false;
        });
    }

    /**
     * 朝目标射出一发追踪箭。
     *
     * 发射流程照抄原版（取箭、附魔、耐久、音效），区别在于弹道：不做弹道补偿、不做移动预判，
     * 直接朝目标头部（眼睛高度）射出去，之后由 {@link #tickHomingArrows} 每 tick 把方向重新指向目标，
     * 所以目标怎么移动都会被打中。
     *
     * 射几发由目标血量决定：{@code ceil(血量 / 单箭伤害)} 是杀死它需要的箭数（单箭伤害已扣掉
     * 目标护甲），减去已经在飞的，就是这一轮还该补的。射够了就停，不会过量输出。
     *
     * 每支箭之间隔一个「攻击间隔」（{@link #nextShotAt} 记录下次可射时间），
     * 所以需要的几支箭会在接下来几个间隔内连续射出，不用等一整轮 —— 一轮就能把目标打死。
     *
     * @return {@link #SHOT_FIRED} 射了 / {@link #SHOT_WAIT} 还没到时候 / {@link #SHOT_DONE} 不用再射
     */
    private static int shootArrowAt(ServerLevel level, ServerPlayer player,
                                    ItemStack bowStack, LivingEntity target) {
        long now = level.getGameTime();
        UUID targetId = target.getUUID();

        if (!target.isAlive()) return SHOT_DONE;

        // 这个目标的下一箭还没到时间：每支箭之间的间隔完全由「攻击间隔」设置项决定。
        // 设 1 就是每 tick 一支，设 10 就是每半秒一支。
        Long readyAt = nextShotAt.get(targetId);
        if (readyAt != null && now < readyAt) return SHOT_WAIT;

        // 刚撞过方块，冷却期内不重复射
        Long until = blockedUntil.get(targetId);
        if (until != null) {
            if (now < until) return SHOT_WAIT;
            blockedUntil.remove(targetId);
        }

        // 已经有足够多的箭在飞向这个目标：等它们命中或落地再补射，避免一堆箭追同一个点
        List<Integer> flying = inflightArrows.computeIfAbsent(targetId, k -> new ArrayList<>(4));
        flying.removeIf(id -> {
            if (!(level.getEntity(id) instanceof AbstractArrow a) || !a.isAlive()) {
                return true;   // 已命中或已消失
            }
            // 落地插住后原版会把速度直接清零，据此判断它已经不再飞向目标。
            // 注意：AbstractArrow.inGround 在 1.20.1 是 protected，外部读不到，只能用速度判断。
            return a.getDeltaMovement().lengthSqr() < 0.01D;
        });

        // ---------- 0. 按生命值算「还该射几箭」----------
        // 追踪箭每发必中、速度恒定、没有暴击随机加成，所以单发伤害可以精确算出来。
        // 目标当前血量 ÷ 单发伤害 向上取整，就是杀死它还需要几箭；再减去已经在飞的，
        // 剩下的才是这一轮该补的。射够了就停 —— 不会对着快死的目标继续输出，
        // 也不会被「最多几支在飞」的硬上限卡住节奏。攻击间隔设多少，实际射速就是多少。
        int perArrow = arrowDamage(bowStack, target);
        int needArrows = (int) Math.ceil(target.getHealth() / (double) perArrow);
        // 在飞的箭已经够打死它了：先不射，但目标留在队列里 —— 万一那些箭撞墙作废、
        // 或者命中后它还活着（抗性、护甲算得不够准），下一 tick 就能立刻补，不用等一整轮。
        if (flying.size() >= needArrows) return SHOT_WAIT;

        // ---------- 1. 取箭矢（与原版一致：创造模式/无限附魔时无箭也能射）----------
        boolean creative = player.getAbilities().instabuild;
        boolean infinity = enchantLevel(bowStack, ENCH_INFINITY) > 0;
        boolean infinite = creative || infinity;

        ItemStack ammo = player.getProjectile(bowStack);
        if (ammo.isEmpty()) {
            if (!infinite) return SHOT_DONE;   // 没箭了，不射
            ammo = new ItemStack(Items.ARROW); // 无限附魔/创造模式的虚拟箭
        }

        // ---------- 2. 创建箭实体 ----------
        ArrowItem arrowItem = ammo.getItem() instanceof ArrowItem ai ? ai : (ArrowItem) Items.ARROW;
        AbstractArrow arrow = arrowItem.createArrow(level, ammo, player);
        if (bowStack.hasCustomHoverName()) {
            arrow.setCustomName(bowStack.getHoverName());
        }

        // ---------- 3. 出生点：从眼睛下移，避免第一人称被箭头挡视野 ----------
        // 瞄准点是目标头部（眼睛高度），不是躯干中心。有些怪只露出一个脑袋，瞄身子的话箭
        // 会先撞在它身前的方块上；眼睛高度始终落在碰撞箱内部，瞄这里既能打到头，也不会从头顶擦过去。
        Vec3 aimCenter = new Vec3(target.getX(), target.getEyeY(), target.getZ());

        double toX = aimCenter.x - player.getX();
        double toZ = aimCenter.z - player.getZ();
        double horiz0 = Math.sqrt(toX * toX + toZ * toZ);
        double ux = horiz0 > 1.0E-4 ? toX / horiz0 : 0.0D;
        double uz = horiz0 > 1.0E-4 ? toZ / horiz0 : 1.0D;

        Vec3 spawn = new Vec3(
                player.getX() + ux * ARROW_SPAWN_FORWARD,
                player.getEyeY() - ARROW_SPAWN_DROP,
                player.getZ() + uz * ARROW_SPAWN_FORWARD);

        // ---------- 4. 追踪直射 ----------
        // 不做弹道补偿、不做移动预判、不做轨迹避让 —— 箭飞出去之后每 tick 都会被重新指向
        // 目标的头部，所以这里只需要给一个朝目标的初始方向就够了。目标怎么跑都没用，
        // 箭会自己拐过去。
        Vec3 dir = aimCenter.subtract(spawn);
        if (dir.lengthSqr() < 1.0E-4D) return SHOT_DONE;   // 贴到无法定向，跳过
        dir = dir.normalize();

        arrow.setPos(spawn.x, spawn.y, spawn.z);
        // 直接用方向向量发射，不改玩家视角 —— 因此不会出现任何视角抖动
        arrow.shoot(dir.x, dir.y, dir.z, HOMING_SPEED, 0.0F);
        // 关掉暴击标记：它会沿箭的轨迹持续喷白色 CRIT 粒子，连射时像一条粒子柱从身前拖出去，
        // 糊视野又耗性能。只影响粒子表现，伤害和弹道完全不变。
        arrow.setCritArrow(false);

        // ---------- 6. 附魔效果 ----------
        int power = enchantLevel(bowStack, ENCH_POWER);
        if (power > 0) {
            arrow.setBaseDamage(arrow.getBaseDamage() + (double) power * 0.5D + 0.5D);
        }
        int punch = enchantLevel(bowStack, ENCH_PUNCH);
        if (punch > 0) {
            arrow.setKnockback(punch);
        }
        if (enchantLevel(bowStack, ENCH_FLAME) > 0) {
            arrow.setSecondsOnFire(100);
        }

        // ---------- 7. 消耗耐久与箭矢 ----------
        bowStack.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(InteractionHand.MAIN_HAND));
        // 无限附魔只有射普通箭才不消耗（光灵箭、药箭照常消耗），与原版一致
        if (!creative && !(infinity && ammo.is(Items.ARROW))) {
            ammo.shrink(1);
            if (ammo.isEmpty()) player.getInventory().removeItem(ammo);
        }

        // ---------- 8. 音效（满蓄力 pitch 加 0.5，与原版一致）----------
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1.0F,
                1.0F / (level.getRandom().nextFloat() * 0.4F + 1.2F) + 0.5F);

        // ---------- 9. 生成 ----------
        level.addFreshEntity(arrow);
        // 登记到回收表：原版落地箭要 60 秒才消失，持续射箭会堆几百支
        modArrows.put(arrow.getId(), now);
        // 登记到「在飞」列表：下一轮算 need 时要知道已经有几支在路上了
        flying.add(arrow.getId());
        // 登记到追踪表：服务端每 tick 会把它的方向重新指向目标
        homingArrows.put(arrow.getId(), new HomingTarget(level.dimension(), target.getId()));
        // 记下这个目标下一次可以再射的时间：每支箭之间隔一个「攻击间隔」
        nextShotAt.put(targetId, now + nextBowCooldown());
        return SHOT_FIRED;
    }

    /**
     * 估算一发追踪箭能造成多少伤害。
     *
     * 原版箭的伤害是 {@code ceil(命中瞬间速度 × baseDamage)}。追踪箭速度恒为 {@link #HOMING_SPEED}
     * 不衰减，且我们关掉了暴击标记（没有暴击的随机加成），所以这个值可以精确算出来，
     * 用它去除目标血量就能得到「还差几箭」。
     *
     * baseDamage 默认 2.0；力量附魔每级 +0.5，另外还有固定 +0.5，与 {@code shootArrowAt}
     * 里 setBaseDamage 的算法保持一致。
     */
    private static int arrowDamage(ItemStack bowStack, LivingEntity target) {
        double base = 2.0D;
        int power = enchantLevel(bowStack, ENCH_POWER);
        if (power > 0) {
            base += power * 0.5D + 0.5D;
        }
        double raw = Math.ceil(HOMING_SPEED * base);

        // 目标穿盔甲时实际掉血比 raw 少一大截。不算这一步「还差几箭」就会算少，
        // 表现出来就是「公式上够了却打不死，得等下一轮补刀」。这里直接用原版
        // LivingEntity.hurt 里同一个减伤公式，保证算出来的箭数和实战一致。
        int armor = target.getArmorValue();
        if (armor > 0) {
            double toughness = target.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
            raw = CombatRules.getDamageAfterAbsorb((float) raw, (float) armor, (float) toughness);
        }
        return Math.max(1, (int) Math.ceil(raw));
    }

    /**
     * 每 tick 把所有飞行中的追踪箭重新指向目标。
     *
     * 只改速度方向、速度大小保持 {@link #HOMING_SPEED}，等于让箭走直线飞向目标 —— 目标跑、
     * 跳、后退都不影响。箭撞上方块插住后速度会被原版清零，这里检测到就摘掉，并给目标记一次
     * 冷却，避免一直朝同一堵墙狂射。
     */
    private static void tickHomingArrows(MinecraftServer server) {
        if (homingArrows.isEmpty()) return;

        homingArrows.entrySet().removeIf(en -> {
            HomingTarget h = en.getValue();

            ServerLevel level = server.getLevel(h.dim());
            if (level == null) return true;

            if (!(level.getEntity(h.targetId()) instanceof LivingEntity target) || !target.isAlive()) {
                return true;   // 目标没了，箭自由飞
            }
            if (!(level.getEntity(en.getKey()) instanceof AbstractArrow arrow) || !arrow.isAlive()) {
                return true;   // 已命中或已消失
            }

            // 速度归零 = 撞上方块插住了，这一箭作废。记一次冷却，别对着墙连续射。
            if (arrow.getDeltaMovement().lengthSqr() < 0.01D) {
                blockedUntil.put(target.getUUID(), level.getGameTime() + BLOCKED_COOLDOWN_TICKS);
                return true;
            }

            // 瞄头部（眼睛高度），与发射时一致：只有脑袋露出来的怪也能打到，
            // 瞄躯干中点的话箭会先撞在它身前的方块上
            Vec3 aim = new Vec3(target.getX(), target.getEyeY(), target.getZ());
            Vec3 dir = aim.subtract(arrow.position());
            double len = dir.length();
            if (len < 0.35D) {
                // 已经贴上了，下一 tick 就会被原版碰撞判定命中。这里先清零无敌帧，
                // 保证这一箭的伤害不被上一箭留下的无敌窗口吃掉。
                target.invulnerableTime = 0;
                return true;
            }

            // 每 tick 把方向重新指向目标头部，速度恒定 → 箭一定落在目标身上
            arrow.setDeltaMovement(dir.scale(HOMING_SPEED / len));

            // 快命中时清零受伤无敌帧：否则第二箭会落在原版 10 tick 的判定窗口里被丢弃，
            // 「两箭射死」就不成立。4 格内才清，远处目标不受影响。
            if (len * len < HOMING_INVULN_CLEAR_DIST_SQR) {
                target.invulnerableTime = 0;
            }
            return false;
        });
    }

    /**
     * 找一条不会被方块挡住的发射仰角。
     *
     * 先试弹道补偿算出的原解；如果这条轨迹会撞到树叶、树枝、墙壁，就小幅下压 / 上抬再试
     * （见 ELEVATION_OFFSETS）。压低一点往往就能从树冠下沿穿过去。
     *
     * @return 可用仰角；所有候选都被挡住时返回 NaN，调用方应跳过该目标
     */
    private static float findClearElevation(ServerLevel level, ServerPlayer player,
                                            Vec3 spawn, Vec3 aim, float baseDeg, double distance) {
        for (float offset : ELEVATION_OFFSETS) {
            float deg = baseDeg + offset;
            if (deg < -80.0F || deg > 60.0F) continue;
            if (isTrajectoryClear(level, player, spawn, aim, deg, distance)) {
                return deg;
            }
        }
        return Float.NaN;
    }

    /**
     * 按仰角 deg 发射的箭，在到达目标位置之前会不会撞到方块。
     *
     * 沿抛物线把轨迹切成若干小段，逐段做方块射线检测（只测有碰撞箱的方块，液体不算）。
     * 段数是按距离算的，最多 MAX_TRAJECTORY_STEPS 段，每段大约 TRAJECTORY_STEP_SPAN 格，
     * 这样近距离不会浪费检测次数，远距离也不会漏掉遮挡。
     * 树叶也算方块 —— 箭不穿树叶，所以要绕开它。
     */
    private static boolean isTrajectoryClear(ServerLevel level, ServerPlayer player,
                                             Vec3 spawn, Vec3 aim, float deg, double distance) {
        if (distance < 0.5D) return true;

        int steps = (int) Math.min(MAX_TRAJECTORY_STEPS,
                Math.max(2.0D, Math.ceil(distance / TRAJECTORY_STEP_SPAN)));
        Vec3 prev = spawn;
        for (int i = 1; i <= steps; i++) {
            double f = (double) i / steps;
            double height = predictedHeight(distance * f, deg);
            if (!Double.isFinite(height)) return false; // 这个角度水平方向到不了这么远
            Vec3 point = new Vec3(
                    spawn.x + (aim.x - spawn.x) * f,
                    spawn.y + height,
                    spawn.z + (aim.z - spawn.z) * f);
            BlockHitResult hit = level.clip(new ClipContext(prev, point,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.MISS) return false;
            prev = point;
        }
        return true;
    }

    /**
     * 按注册名读取物品上的附魔等级。
     *
     * 为什么不直接写 Enchantments.XXX 常量：附魔字段名在不同映射（Mojang / Yarn）和不同版本间
     * 差异很大（力量附魔可能叫 POWER、POWER_ARROW，火矢可能叫 FLAME、FLAMING_ARROW），
     * 写死常量极易编译失败。而注册名 "minecraft:power" 这类是游戏数据的一部分，
     * 跨版本跨映射都稳定，因此这里统一用注册表按名字查。
     *
     * @param stack 物品
     * @param id    附魔注册名，如 minecraft:power
     * @return 附魔等级，没有则为 0
     */
    private static int enchantLevel(ItemStack stack, ResourceLocation id) {
        Enchantment enchantment = BuiltInRegistries.ENCHANTMENT.get(id);
        if (enchantment == null) return 0;
        return EnchantmentHelper.getItemEnchantmentLevel(enchantment, stack);
    }

    /** 预判速度钳制，避免末影人瞬移之类的极端速度把瞄准点带飞 */
    private static double clampLead(double v) {
        return Math.max(-MAX_LEAD_SPEED, Math.min(MAX_LEAD_SPEED, v));
    }

    /**
     * 按仰角 deg 发射、飞过水平距离 distance 需要多少个 tick。
     * 与 predictedHeight 同源：由 x(n) = vh * 100 * (1 - 0.99^n) 反解 n。
     */
    private static double estimateFlightTicks(double distance, double deg) {
        double rad = Math.toRadians(deg);
        double vh = BOW_ARROW_SPEED * Math.cos(rad);
        if (vh < 1.0E-3) return 40.0D;
        double decay = 1.0D - distance / (100.0D * vh);
        if (decay <= 1.0E-9) return 40.0D;
        return Math.log(decay) / Math.log(ARROW_DRAG);
    }

    /**
     * 求命中指定水平距离、指定高度差所需的发射仰角（度，正数=向上）。
     *
     * 原版箭每 tick 的处理顺序是：先按 0.99 衰减速度，再减 0.05 重力。
     * 据此可以解析推导出飞 n 个 tick 后的位移：
     *   水平：x(n) = vh * 100 * (1 - 0.99^n)
     *   垂直：y(n) = (100 * vv + 500) * (1 - 0.99^n) - 5n
     * 其中 vh/vv 是初速度的水平/垂直分量。
     *
     * 由于 vh 与 vv 都随角度变化，这里按 ELEVATION_SCAN_STEP 步长扫描 [-80°, 45°]，
     * 找到第一个「能打到该高度」的角度区间，再二分细化 —— 取的是低弹道解
     * （角度小的那个），射得更平、更快命中，也更像正常射箭。
     * 若整个角度范围都够不到（目标超出射程），返回能达到最大高度的角度，尽力而为。
     */
    private static float solveElevation(double distance, double heightDiff) {
        if (distance < 0.05D) {
            return heightDiff >= 0 ? 45.0F : -45.0F;
        }

        double prevDeg = -80.0D;
        double prevY = predictedHeight(distance, prevDeg);
        for (double deg = -80.0D + ELEVATION_SCAN_STEP; deg <= 45.0D; deg += ELEVATION_SCAN_STEP) {
            double y = predictedHeight(distance, deg);
            // 找到第一个从"打不到"跨越到"能打到"的区间，低弹道解就在这里面
            if (prevY < heightDiff && y >= heightDiff) {
                double lo = prevDeg;
                double hi = deg;
                for (int i = 0; i < ELEVATION_BISECT_STEPS; i++) {
                    double mid = (lo + hi) * 0.5D;
                    if (predictedHeight(distance, mid) >= heightDiff) {
                        hi = mid;
                    } else {
                        lo = mid;
                    }
                }
                return (float) hi;
            }
            prevDeg = deg;
            prevY = y;
        }

        // 射程不足：取能达到最高点的角度
        double bestDeg = 0.0D;
        double bestY = Double.NEGATIVE_INFINITY;
        for (double deg = -80.0D; deg <= 45.0D; deg += ELEVATION_SCAN_STEP) {
            double y = predictedHeight(distance, deg);
            if (y > bestY) {
                bestY = y;
                bestDeg = deg;
            }
        }
        return (float) bestDeg;
    }

    /**
     * 按仰角 deg 发射时，箭飞到水平距离 distance 处的高度（相对出生点）。
     * 用于弹道求解，公式见 solveElevation 注释。
     */
    private static double predictedHeight(double distance, double deg) {
        double rad = Math.toRadians(deg);
        double vh = BOW_ARROW_SPEED * Math.cos(rad);
        double vv = BOW_ARROW_SPEED * Math.sin(rad);
        if (vh < 1.0E-3) return Double.NEGATIVE_INFINITY; // 几乎垂直，水平方向到不了

        // 0.99^n，由水平位移公式反解
        double decay = 1.0D - distance / (100.0D * vh);
        if (decay <= 1.0E-9) return Double.NEGATIVE_INFINITY; // 水平速度不足以飞这么远

        double ticks = Math.log(decay) / Math.log(ARROW_DRAG);
        // 重力项系数：5 = ARROW_GRAVITY / (1 - ARROW_DRAG)
        double gravityCoef = ARROW_GRAVITY / (1.0D - ARROW_DRAG);
        return (100.0D * vv + 100.0D * gravityCoef) * (1.0D - decay) - gravityCoef * ticks;
    }

    // ==================== 近战模式 ====================

    /**
     * 单机攻击：直接调度到本地集成服务器的服务端线程，逐个调用 ServerPlayer.attack()。
     * 这样绕开原版服务端对 ServerboundInteractPacket 的 6 格距离校验，让 reach / 摇手配置真正生效。
     */
    private static void attackSingleplayer(Minecraft mc, Player player, List<LivingEntity> clientTargets) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;

        // 摇手动画是玩家本地第一人称渲染的，必须在客户端线程直接播，服务端广播不一定能让自己看到
        if (config.isSwingHand()) {
            player.swing(player.getUsedItemHand());
        }

        // 在客户端线程先取好所有目标实体 ID 与维度 key，避免在服务端线程里触碰客户端实体对象
        ResourceKey<Level> dim = player.level().dimension();
        int[] ids = new int[clientTargets.size()];
        for (int i = 0; i < clientTargets.size(); i++) {
            ids[i] = clientTargets.get(i).getId();
        }

        server.execute(() -> {
            ServerLevel serverLevel = server.getLevel(dim);
            if (serverLevel == null) return;

            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player.getUUID());
            if (serverPlayer == null) return;

            boolean swing = config.isSwingHand();
            // 关闭摇手时统一用武器攻击力造成伤害，不进入 ServerPlayer.attack 以免播放摇手
            float dmg = (float) serverPlayer.getAttributeValue(Attributes.ATTACK_DAMAGE);

            for (int id : ids) {
                if (!(serverLevel.getEntity(id) instanceof LivingEntity serverTarget)) continue;
                if (!serverTarget.isAlive()) continue;

                // 清空目标无敌帧：原版受伤后有 10 tick（0.5 秒）无敌时间，
                // 攻击间隔快于此值时后续攻击会被丢弃，同样表现为「摇好几次手才受一次伤」。
                serverTarget.invulnerableTime = 0;

                if (swing) {
                    // 开启摇手：走完整近战攻击（伤害/击退/附魔表现与原版一致）。
                    // 先强制满蓄力，否则攻击间隔快于原版攻击冷却时 attack() 会直接 return，
                    // 表现为「摇了好几次手才受一次伤」。该字段是 protected，通过 Accessor 写入。
                    ((LivingEntityAccessor) serverPlayer)
                            .killauraplus$setAttackStrengthTicker(FORCE_FULL_CHARGE_TICKS);
                    serverPlayer.attack(serverTarget);
                } else {
                    serverTarget.hurt(serverPlayer.damageSources().playerAttack(serverPlayer), dmg);
                }
            }
        });
    }
}
