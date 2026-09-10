package com.lai.emipagedbookmarks.client.group;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.joml.Matrix4f;
import org.slf4j.Logger;

import com.google.gson.JsonObject;
import com.lai.emipagedbookmarks.EmiPagedBookmarksMod;
import com.lai.emipagedbookmarks.client.BookmarkPages;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.emi.emi.config.SidebarType;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.screen.EmiScreenManager.ScreenSpace;
import dev.emi.emi.screen.EmiScreenManager.SidebarPanel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * 分组边框与框选预览的绘制。
 *
 * <p>分两趟画：边框在物品之下（{@link #renderBackground}），框选标记在物品之上
 * （{@link #renderForeground}）。边框压在图标下层，就永远不会糊住图标。</p>
 *
 * <h2>纹样从「整页草地贴图」按绝对坐标取样</h2>
 *
 * <p>早先的做法是给每个分组<b>合成</b>一个边框环：一张 140x280 的环图集、按分组 UUID 取相位、
 * 四边四角分别取样、长边按 TILE 分段循环、再检测「哪条边和邻组共用」并把外沿换成别的深度……
 * 机制多、约束多，而且相邻分组各算各的相位，交界处会拼出一条明显的双缝。</p>
 *
 * <p>现在改成：整页铺一张<b>可平铺的草地贴图</b>（{@code group_field.png}），边框直接从这张图
 * 按<b>绝对像素坐标</b>取样。于是「同一个绝对位置永远采到同一个像素」—— 相邻分组在交界处采到
 * 的是同一批像素，<b>接缝在原理上不存在</b>，相位 / 分段 / 共用边检测 / seam 换行 / 角片这一整套
 * 全部删掉。轮廓描边与换行机制保持不变（这是「边框包裹分组四周」的本体）。</p>
 *
 * <p>花单独一张贴图（{@code group_bloom.png}，彩色、其余透明）：草地是灰阶的，绘制时按分组
 * 强调色染色；花用白色顶点色，保留本身的黄白。之所以要两张而不是一张图放两半 —— 两层必须按
 * <b>同一个 uv</b> 取样才能叠在一起，而同一张贴图里两块不同的图不可能共享 uv（除非按贴图周期
 * 把每条边再切开），所以两张各自可平铺的图反而最简单、也最省。</p>
 *
 * <h2>边框带：外侧实心 + 向内生长的散点</h2>
 *
 * <p>外侧 {@link #solidRows} 像素实心，再往里 {@link #ditherLayers} 层按概率保留、每层乘
 * {@link #ditherDecay}（默认每层降 25%），像素从<b>同一位置的背景</b>取 —— 就是用户要的
 * 「向内延伸出一些像素点」的感觉。</p>
 *
 * <p>散点不是各自独立掷骰子：它们<b>从实心带连出来生长</b>（8 连通泛洪，见
 * {@link #buildDitherRects}），所以不会出现孤零零的一个像素点；阈值还叠了一层 16px 尺度的
 * 低频噪声，使毛边有厚有薄而不是一圈均匀渐隐。掩码不写进配置文件，也不每帧现算：一张
 * {@link #LUT_SIZE}x{@link #LUT_SIZE} 的全局随机表在类加载时建一次，每个分组只用自己
 * {@code seed} 换一个固定偏移去查表。绘制期是纯表查询；整条边框的矩形列表按「几何签名」缓存，
 * 布局没变就直接复用（见 {@link #buildRects}）。</p>
 *
 * <p><b>整页恒定 2 次 draw call</b>（草地一趟 + 花一趟），与分组数量、碎片程度无关。1.20.1 的
 * {@code GuiGraphics.blit} 每次调用都是一次独立 draw call（还会重新 apply 一遍 shader），逐块
 * 平铺画分组边框能堆到几百次，所以这里自己拼顶点。</p>
 */
public final class GroupOverlay {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int ENTRY_SIZE = 18;
    /** 分组强调色，按分组 UUID 稳定取色。 */
    private static final int[] ACCENTS = new int[] { 0x6FD8A8, 0xE8B56B, 0x74B4EC, 0xC79BE8, 0xE8907F };
    private static final int SELECTION_ACCENT = 0xFFFFFF;
    /** 悬停高亮的黄色，参考 Flower Hotbar 的选中格。 */
    private static final int HOVER_TINT = 0xFFE34D;

    /** 可平铺的灰阶草地：按绝对坐标取样，再乘分组强调色。 */
    private static final ResourceLocation FIELD_TEXTURE = texture("textures/gui/group_field.png");
    /** 可平铺的彩色花层：白色顶点色，保留黄白花。 */
    private static final ResourceLocation BLOOM_TEXTURE = texture("textures/gui/group_bloom.png");
    /** 贴图边长，同时也是可平铺周期（两张图都是它）。 */
    private static final int FIELD_SIZE = 512;

    /** 抖动全局随机表的边长；查表下标对它的幂取模，所以必须是 2 的幂。 */
    private static final int LUT_SIZE = 64;
    private static final int LUT_MASK = LUT_SIZE - 1;
    /** 类加载时建一次：LUT_SIZE^2 个均匀随机字节。 */
    private static final byte[] DITHER_LUT = buildLut();

    /**
     * 「生长欲望」的低频调制：{@link #FIELD_SIZE}/{@code NOISE_LATTICE} = 16px 的环绕点阵，
     * 双线性 + smoothstep 插值。
     *
     * <p>如果每层的保留概率只跟层号有关，一圈边框就是一个<b>处处一样均匀</b>的毛边，看着像
     * 用橡皮擦蹭了一圈。真实草地不会这样：有的地方厚、有的地方薄。所以再叠一层低频噪声去
     * 缩放阈值，让「向内伸出去多深」沿边框有起伏（{@link #GROWTH_MIN} ~ {@link #GROWTH_MAX}，
     * 伸满和只伸一层的地方交替出现）。</p>
     *
     * <p>周期与草地贴图一致（512），所以不会引入新的可见重复；而且它只参与<b>建矩形列表</b>
     * 时的一次性计算，绘制期完全不碰。</p>
     */
    private static final int NOISE_LATTICE = 32;
    private static final int NOISE_CELL = FIELD_SIZE / NOISE_LATTICE;
    /** 调制下限 / 上限（再乘上 start*decay^layer 得到实际阈值）。 */
    private static final float GROWTH_MIN = 0.55F;
    private static final float GROWTH_MAX = 1.45F;
    private static final int[] GROWTH_NOISE = buildGrowthNoise();

    // --- 边框带形状（可通过配置文件调整，见 borderConfigJson / applyBorderConfig）---

    /** 外侧实心厚度（像素）。 */
    private static int solidRows = 2;
    /** 向内延伸的层数，每层按概率保留、且必须连回边框。 */
    private static int ditherLayers = 2;
    /** 第 1 个抖动层的基准保留概率（还会乘位置调制，见 growthScale）。 */
    private static float ditherStart = 0.75F;
    /** 每往里一层概率乘这个系数（0.75 = 降 25%）。 */
    private static float ditherDecay = 0.75F;

    /**
     * 每帧复用的行缓冲：每行 3 个 int（x, y, width），下标 = 行序号 * 3。
     *
     * <p>一个分组在本页占若干行，用 {@link #groupRowStart}/{@link #groupRowCount} 定位。
     * 渲染线程单线程访问，所以复用是安全的；好处是每帧不再 new 一堆 int[] / ArrayList。</p>
     */
    private static int[] rowData = new int[0];
    private static int[] groupRowStart = new int[0];
    private static int[] groupRowCount = new int[0];
    private static int[] groupBorderColor = new int[0];

    /**
     * 边框矩形列表：每个矩形 4 个 int（x, y, w, h），坐标是绝对像素。
     *
     * <p>实心带与散点带都放在这一个列表里、不再区分 —— 因为两层（草地 / 花）都要按同一批矩形
     * 取样，边框只是<b>背景的一扇窗</b>（见 {@link #drawBorders}）。</p>
     *
     * <p>按分组切片：{@link #groupRectStart}/{@link #groupRectCount}。整体由
     * {@link #buildRects} 在「几何签名」变化时重建，之后每帧只做遍历 + 写顶点。</p>
     */
    private static int[] rectData = new int[0];
    private static int[] groupRectStart = new int[0];
    private static int[] groupRectCount = new int[0];
    private static int rectCursor;
    private static long rectSignature = Long.MIN_VALUE;
    private static int rectRowCount = -1;
    private static int rectGroupCount = -1;

    /** {@link #exposedSpans} 的结果缓冲（最多两段），避免每行都分配数组。 */
    private static final int[] SPAN_FROM = new int[2];
    private static final int[] SPAN_TO = new int[2];

    /**
     * 抖动的临时缓冲，全部按「分组的包围盒」复用（见 {@link #buildDitherRects}）：
     * {@code insideBuf} 1 = 在分组区域内；{@code depthBuf} 到区域外的最少步数；
     * {@code maskBuf} 0 空 / 2 实心（生长种子）/ 1 候选 / 3 保留。
     * 只在重建矩形时用，热路径（每帧绘制）完全不碰。
     */
    private static byte[] insideBuf = new byte[0];
    private static byte[] depthBuf = new byte[0];
    private static byte[] maskBuf = new byte[0];
    /** 泛洪用的显式栈（每个像素最多入栈一次）。 */
    private static int[] floodStack = new int[0];

    /** 绘制出过一次错就彻底关掉（见 {@link #renderBackground} 的 catch），后半程不再重试。 */
    private static boolean broken;

    private GroupOverlay() {
    }

    /**
     * 拼出本模组自己的贴图 id。
     *
     * <p>必须用 {@code fromNamespaceAndPath}，不能写 {@code new ResourceLocation(命名空间, 路径)}：
     * 后者在 Forge 1.20.1 里被标成 {@code @Deprecated(forRemoval = true)}。这不是 IDEA 误报 ——
     * <b>Forge 47.4.0</b> 把 1.20.6 的 ResourceLocation 工厂方法 backport 回了 1.20.1，
     * 同时把三个旧构造器标成待删除（官方给的迁移对照：两参构造器 → {@code fromNamespaceAndPath}、
     * 单参 → {@code parse}、{@code of} → {@code bySeparator}）。两者字节码完全一样，只差一个告警。</p>
     *
     * <p>⚠️ 正因为它来自 47.4 的 backport，{@code mods.toml} 里的 {@code forge_version_range}
     * 必须 ≥ 47.4，否则在更老的 47.x 上会 {@code NoSuchMethodError}。</p>
     */
    private static ResourceLocation texture(String path) {
        return ResourceLocation.fromNamespaceAndPath(EmiPagedBookmarksMod.MOD_ID, path);
    }

    /** 物品之下：植物纹边框（压在图标下面，不挡视线）。 */
    public static void renderBackground(EmiDrawContext context, SidebarPanel panel, int mouseX, int mouseY) {
        if (broken) {
            return;
        }
        ScreenSpace space = spaceOf(panel);
        if (space == null) {
            return;
        }
        GuiGraphics graphics = context.raw();
        int pageStart = currentPage(space, panel) * space.pageSize;
        int pageEnd = pageStart + space.pageSize - 1;
        int hover = hoveredVisibleIndex(space, panel, mouseX, mouseY);
        UUID pageId = BookmarkPages.currentId();
        List<FavoriteGroup> groups = GroupManager.groups(pageId);

        try {
            int rows = prepare(groups, pageId, space, pageStart, pageEnd, hover);
            if (rows == 0) {
                return;
            }
            drawBorders(graphics, groups);
        } catch (RuntimeException e) {
            // 一层花纹画错不该把整个游戏带崩：Tesselator 的 builder 是全局共享的，batch 中途抛异常
            // 会让它一直停在 building 状态，之后每一次 GUI 绘制都 "Already building!"（1.0.13 就是
            // 这么把客户端炸掉的）。所以这里复位 builder、本次运行内停用本模组的绘制，并留一行日志。
            broken = true;
            discardPendingBatch();
            LOGGER.error("[emipagedbookmarks] 分组边框绘制失败，本次运行内已停用该功能", e);
        }
    }

    /** 物品之上：只保留 Alt 框选预览，避免任何常驻图案压住图标。 */
    public static void renderForeground(EmiDrawContext context, SidebarPanel panel, int mouseX, int mouseY) {
        ScreenSpace space = spaceOf(panel);
        if (space == null) {
            return;
        }
        GuiGraphics graphics = context.raw();
        int pageStart = currentPage(space, panel) * space.pageSize;
        int pageEnd = pageStart + space.pageSize - 1;
        UUID pageId = BookmarkPages.currentId();

        int anchor = GroupManager.selectionAnchor();
        if (anchor == -1 || !Screen.hasAltDown()) {
            return;
        }
        int hovered = hoveredVisibleIndex(space, panel, mouseX, mouseY);
        if (hovered == -1) {
            return;
        }
        int from = Math.min(GroupManager.toVisibleIndex(pageId, anchor), hovered);
        int to = Math.min(Math.max(GroupManager.toVisibleIndex(pageId, anchor), hovered), pageEnd);
        int border = selectionColor(0xE0);
        int fill = selectionColor(0x18);
        for (int[] row : rowsOf(space, pageStart, Math.max(from, pageStart), to)) {
            int x = row[0];
            int y = row[1];
            int right = x + row[2] - 1;
            int bottom = y + ENTRY_SIZE - 1;
            graphics.fill(x, y, right + 1, bottom + 1, fill);
            graphics.hLine(x + 1, right - 1, y, border);
            graphics.hLine(x + 1, right - 1, bottom, border);
            graphics.vLine(x, y + 1, bottom - 1, border);
            graphics.vLine(right, y + 1, bottom - 1, border);
        }
    }

    /**
     * 当前鼠标位置在收藏栏中的显示下标，不在收藏栏内则为 -1。
     */
    public static int hoveredVisibleIndex(ScreenSpace space, SidebarPanel panel, int mouseX, int mouseY) {
        int offset = space.getRawOffsetFromMouse(mouseX, mouseY);
        return offset == -1 ? -1 : currentPage(space, panel) * space.pageSize + offset;
    }

    // -----------------------------------------------------------------------
    // 配置（留在 bookmarks.json 的顶层 "border" 对象里）
    // -----------------------------------------------------------------------

    /**
     * 边框形状配置的版本号。
     *
     * <p>这个字段是必须的：{@link #borderConfigJson()} 每次存档都会把当前形状写回配置文件，
     * 于是<b>旧版本写下的值会被一直读回来、把新的代码默认值永远盖住</b> —— 改了默认形状却
     * 毫无效果（1.0.20 把实心 4 改成 2 时就踩了这个坑，用户直到 1.0.23 还看着 4px 实心）。</p>
     *
     * <p>规则：<b>只要动了 {@link #solidRows} / {@link #ditherLayers} / {@link #ditherStart} /
     * {@link #ditherDecay} 的默认值，就把这个数 +1。</b>版本对不上的配置文件会被整段忽略，
     * 退回代码默认值（用户自己手改过的临时调参也会一起丢，属预期）。</p>
     */
    private static final int BORDER_CONFIG_VERSION = 1;

    /** 当前边框带形状，写进配置文件。 */
    public static JsonObject borderConfigJson() {
        JsonObject json = new JsonObject();
        json.addProperty("v", BORDER_CONFIG_VERSION);
        json.addProperty("solid", solidRows);
        json.addProperty("layers", ditherLayers);
        json.addProperty("start", ditherStart);
        json.addProperty("decay", ditherDecay);
        return json;
    }

    /**
     * 读配置文件里的边框带形状；缺字段就保持默认值，越界值夹到安全范围。
     *
     * <p>配置版本与 {@link #BORDER_CONFIG_VERSION} 不一致（含没有 {@code v} 的旧文件）时
     * <b>整段忽略</b>，直接沿用代码默认值。</p>
     */
    public static void applyBorderConfig(JsonObject json) {
        if (json == null) {
            return;
        }
        int version = json.has("v") ? json.get("v").getAsInt() : 0;
        if (version != BORDER_CONFIG_VERSION) {
            LOGGER.info("边框形状配置版本 v{} != v{}，忽略旧值、改用当前默认（solid={}, layers={}）",
                    version, BORDER_CONFIG_VERSION, solidRows, ditherLayers);
            return;
        }
        if (json.has("solid")) {
            solidRows = clampThickness(json.get("solid").getAsInt());
        }
        if (json.has("layers")) {
            ditherLayers = clampThickness(json.get("layers").getAsInt());
        }
        if (json.has("start")) {
            ditherStart = clampFloat(json.get("start").getAsFloat(), 0.0F, 1.0F);
        }
        if (json.has("decay")) {
            // 0 会让所有层都消失、超大值等于不衰减，都夹到 [0.05, 1.5]
            ditherDecay = clampFloat(json.get("decay").getAsFloat(), 0.05F, 1.5F);
        }
        // 形状变了，矩形缓存作废
        rectSignature = Long.MIN_VALUE;
    }

    /**
     * 边框厚度（实心行数 / 抖动层数）夹到安全范围。
     *
     * <p>上下界是固定的：负值没意义，而一旦超过一个条目的高度，内层图案就会盖住图标。</p>
     */
    private static int clampThickness(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, ENTRY_SIZE - 1);
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.min(Math.max(value, min), max);
    }

    /**
     * 先把一页里每个分组的可见区间拆成行、算好配色，再把边框矩形整批建好（带缓存）。
     *
     * <p>返回总行数；没有任何可见分组时返回 0。</p>
     */
    private static int prepare(List<FavoriteGroup> groups, UUID pageId, ScreenSpace space,
            int pageStart, int pageEnd, int hover) {
        int total = groups.size();
        if (groupRowCount.length < total) {
            int capacity = Math.max(8, total);
            groupRowStart = new int[capacity];
            groupRowCount = new int[capacity];
            groupBorderColor = new int[capacity];
            groupRectStart = new int[capacity];
            groupRectCount = new int[capacity];
        }
        int slot = -1;
        int rows = 0;
        for (int i = 0; i < total; i++) {
            groupRowCount[i] = 0;
            FavoriteGroup group = groups.get(i);
            // 相邻分组撞色时顺延一位：同一个分组的颜色稳定，增删其它分组也不会串色。
            int accent = Math.floorMod(group.id().hashCode(), ACCENTS.length);
            if (accent == slot) {
                accent = (accent + 1) % ACCENTS.length;
            }
            slot = accent;

            int from = Math.max(GroupManager.toVisibleIndex(pageId, group.startIndex()), pageStart);
            int to = Math.min(group.folded() ? from
                    : GroupManager.toVisibleIndex(pageId, group.endIndex()), pageEnd);
            if (from > to) {
                continue;
            }
            int next = collectRows(space, pageStart, from, to, rows);
            if (next == rows) {
                continue;
            }
            groupRowStart[i] = rows;
            groupRowCount[i] = next - rows;
            rows = next;

            boolean hot = hover >= from && hover <= to;
            // 边框的透明度在纹理里，顶点色只负责 RGB。
            groupBorderColor[i] = hot ? HOVER_TINT : ACCENTS[accent];
        }
        buildRects(rows, total, groups);
        return rows;
    }

    // -----------------------------------------------------------------------
    // 边框矩形：轮廓描边 + 抖动散点，整批缓存
    // -----------------------------------------------------------------------

    /**
     * 建好整页所有分组的边框矩形；「几何签名」没变就直接复用上次的结果。
     *
     * <p>签名覆盖了行排布与分组切片（{@link #rowData} / {@code groupRowStart} / {@code groupRowCount}）
     * 以及每个分组的 {@code seed} —— 这些不变，画出来就一模一样。签名一算一遍是 O(行数)，
     * 而重建要遍历整条边框带的所有像素，所以缓存收益很大：<b>散点掩码只在布局变化时算一次，
     * 不是每帧算</b>。</p>
     */
    private static void buildRects(int rows, int total, List<FavoriteGroup> groups) {
        long signature = signature(rows, total, groups);
        if (signature == rectSignature && rows == rectRowCount && total == rectGroupCount) {
            return;
        }
        rectSignature = signature;
        rectRowCount = rows;
        rectGroupCount = total;
        rectCursor = 0;
        if (groupRectStart.length < total) {
            groupRectStart = new int[Math.max(8, total)];
            groupRectCount = new int[Math.max(8, total)];
        }
        for (int g = 0; g < total; g++) {
            groupRectStart[g] = rectCursor;
            if (groupRowCount[g] == 0) {
                groupRectCount[g] = 0;
                continue;
            }
            buildGroupRects(g, groups.get(g).seed());
            groupRectCount[g] = rectCursor - groupRectStart[g];
        }
    }

    /** 几何签名：行排布 + 分组切片 + 每组 seed；任何一项变了都要重建矩形。 */
    private static long signature(int rows, int total, List<FavoriteGroup> groups) {
        long h = 0x9E3779B97F4A7C15L ^ solidRows ^ ((long) ditherLayers << 8)
                ^ ((long) Float.floatToIntBits(ditherStart) << 16)
                ^ ((long) Float.floatToIntBits(ditherDecay) << 32);
        int limit = rows * 3;
        for (int i = 0; i < limit; i++) {
            h = (h ^ rowData[i]) * 0x100000001B3L;
        }
        for (int g = 0; g < total; g++) {
            h = (h ^ groupRowStart[g] ^ ((long) groupRowCount[g] << 20)) * 0x100000001B3L;
            h = (h ^ groups.get(g).seed()) * 0x100000001B3L;
        }
        return h;
    }

    /**
     * 一个分组的边框矩形：按「各行区间并集」的轮廓描边（实心带）+ 向内生长的散点（抖动带）。
     *
     * <p>实心部分逐行看：顶边只画上一行没盖住的那些段，底边只画下一行没盖住的那些段，左右竖边只画
     * 该侧上下没有被同分组盖住的部分（被盖住的那端让出 {@code solidRows} 的厚度，这样角上不会画两遍）。
     * 换行分组因此得到一条闭合的台阶状轮廓。</p>
     *
     * <p>散点部分交给 {@link #buildDitherRects}（它要先把整个分组的深度图算出来，不能逐边做）。</p>
     */
    private static void buildGroupRects(int group, int seed) {
        int start = groupRowStart[group];
        int count = groupRowCount[group];
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            int row = start + i;
            int above = i > 0 ? row - 1 : -1;
            int below = i + 1 < count ? row + 1 : -1;
            int x = rowData[row * 3];
            int y = rowData[row * 3 + 1];
            int right = x + rowData[row * 3 + 2];
            int bottom = y + ENTRY_SIZE;
            if (x < minX) {
                minX = x;
            }
            if (right > maxX) {
                maxX = right;
            }
            if (y < minY) {
                minY = y;
            }
            if (bottom > maxY) {
                maxY = bottom;
            }

            // 顶边：上一行没盖住的部分（首行则整条）。跨行的台阶就在这里补上。
            int spans = exposedSpans(x, right, above);
            for (int s = 0; s < spans; s++) {
                int from = SPAN_FROM[s];
                int to = SPAN_TO[s];
                // 空段：上一行盖到最后。空段什么都不画，台阶交给竖边补满。
                if (from >= to) {
                    continue;
                }
                addRect(from, y, to - from, solidRows);
            }

            // 底边：下一行没盖住的部分（末行则整条）。
            spans = exposedSpans(x, right, below);
            for (int s = 0; s < spans; s++) {
                int from = SPAN_FROM[s];
                int to = SPAN_TO[s];
                if (from >= to) {
                    continue;
                }
                addRect(from, bottom - solidRows, to - from, solidRows);
            }

            // 左右竖边：贴着同分组的上一/下一行时不做让位（两条带子接上），否则让出 solidRows
            // —— 让出的那一个角由横带（它铺满整行宽）负责，于是「外沿那一圈」在角上是连续的
            // 实心，不会被抖动层啃掉。让位量必须正好是 solidRows 而不是整条带子的厚度：
            // 用后者会留下一个 solidRows x ditherLayers 的缺口。
            int top = y + (coversRow(above, x) ? 0 : solidRows);
            int lower = bottom - (coversRow(below, x) ? 0 : solidRows);
            if (lower > top) {
                addRect(x, top, solidRows, lower - top);
            }
            top = y + (coversRow(above, right - ENTRY_SIZE) ? 0 : solidRows);
            lower = bottom - (coversRow(below, right - ENTRY_SIZE) ? 0 : solidRows);
            if (lower > top) {
                addRect(right - solidRows, top, solidRows, lower - top);
            }
        }
        if (ditherLayers > 0) {
            buildDitherRects(start, count, minX, minY, maxX, maxY, seed);
        }
    }

    /**
     * 抖动带 = <b>深度锚定的连通生长</b>。
     *
     * <p>两步：</p>
     * <ol>
     *   <li>算出分组区域内每个像素的<b>深度</b>（= 往四个轴向走到区域外所需的最少步数，最外一圈为 0）。
     *       {@code depth < solidRows} 就是实心带，{@code solidRows <= depth < solidRows + ditherLayers}
     *       是抖动带。用一张深度图统一描述，换行台阶、凹角、拐角全部自动成立，不需要为每条边单独让位。</li>
     *   <li>按 {@code start * decay^layer * 位置调制} 的概率筛出候选，然后<b>从实心带做 8 连通泛洪</b>：
     *       只有能沿着「已保留像素」连回边框的候选才留下来。</li>
     * </ol>
     *
     * <p>第二步就是「避免出现单独的像素点」：纸面印刷里这叫 <b>clustered-dot（聚集点）抖动</b> ——
     * 阈值按「新像素紧挨着已经点亮的像素」排列，于是点只会成簇长大、不会孤零零撒开。这里把「已经点亮的
     * 像素」换成「边框实心带」，等价于让毛边从边框<b>长出来</b>而不是糊上去；同层之间也允许横向连着长，
     * 所以边缘是不规则的团块状，而不是一圈等厚的渐隐。</p>
     *
     * <p>每层还额外乘一个 16px 尺度的低频噪声（见 {@link #GROWTH_NOISE}）：有的地方伸得深、有的地方
     * 几乎不长，避免出现"一圈均匀毛边"。这一步只是让阈值沿边框起伏，连通性仍由泛洪保证。</p>
     */
    private static void buildDitherRects(int start, int count, int minX, int minY, int maxX, int maxY, int seed) {
        int w = maxX - minX;
        int h = maxY - minY;
        if (w <= 0 || h <= 0) {
            return;
        }
        int need = w * h;
        if (insideBuf.length < need) {
            int capacity = Math.max(need, Math.max(4096, insideBuf.length * 2));
            insideBuf = new byte[capacity];
            depthBuf = new byte[capacity];
            maskBuf = new byte[capacity];
        }
        if (floodStack.length < need) {
            floodStack = new int[Math.max(need, 4096)];
        }
        Arrays.fill(insideBuf, 0, need, (byte) 0);
        Arrays.fill(maskBuf, 0, need, (byte) 0);

        // ---- 1) 区域掩码 ----
        for (int i = 0; i < count; i++) {
            int row = start + i;
            int x = rowData[row * 3] - minX;
            int y = rowData[row * 3 + 1];
            int width = rowData[row * 3 + 2];
            for (int py = 0; py < ENTRY_SIZE; py++) {
                int base = (y - minY + py) * w + x;
                Arrays.fill(insideBuf, base, base + width, (byte) 1);
            }
        }

        // ---- 2) 深度图：min(水平游程, 垂直游程)，最外一圈 = 0 ----
        for (int py = 0; py < h; py++) {
            int base = py * w;
            int px = 0;
            while (px < w) {
                if (insideBuf[base + px] == 0) {
                    px++;
                    continue;
                }
                int from = px;
                while (px < w && insideBuf[base + px] != 0) {
                    px++;
                }
                int to = px - 1;
                for (int q = from; q <= to; q++) {
                    depthBuf[base + q] = (byte) Math.min(q - from, to - q);
                }
            }
        }
        for (int px = 0; px < w; px++) {
            int py = 0;
            while (py < h) {
                if (insideBuf[py * w + px] == 0) {
                    py++;
                    continue;
                }
                int from = py;
                while (py < h && insideBuf[py * w + px] != 0) {
                    py++;
                }
                int to = py - 1;
                for (int q = from; q <= to; q++) {
                    int index = q * w + px;
                    int d = Math.min(q - from, to - q);
                    if (d < (depthBuf[index] & 0xFF)) {
                        depthBuf[index] = (byte) d;
                    }
                }
            }
        }

        // ---- 3) 掩码：2 = 实心（种子），1 = 候选（概率通过），0 = 不画 ----
        int bandTop = solidRows + ditherLayers;
        int ox = mix32(seed) & LUT_MASK;
        int oy = mix32(seed ^ 0x5BF03635) & LUT_MASK;
        for (int py = 0; py < h; py++) {
            int base = py * w;
            int ay = minY + py;
            for (int px = 0; px < w; px++) {
                int index = base + px;
                if (insideBuf[index] == 0) {
                    continue;
                }
                int d = depthBuf[index] & 0xFF;
                if (d < solidRows) {
                    maskBuf[index] = 2;
                } else if (d < bandTop && ditherOn(minX + px, ay, d - solidRows, ox, oy)) {
                    maskBuf[index] = 1;
                }
            }
        }

        // ---- 4) 从实心带泛洪（8 连通）：连不回来的候选直接丢掉 ----
        int top = 0;
        for (int i = 0; i < need; i++) {
            if (maskBuf[i] == 2) {
                floodStack[top++] = i;
            }
        }
        while (top > 0) {
            int index = floodStack[--top];
            int cx = index % w;
            int cy = index / w;
            int y0 = cy > 0 ? cy - 1 : 0;
            int y1 = cy + 1 < h ? cy + 1 : h - 1;
            int x0 = cx > 0 ? cx - 1 : 0;
            int x1 = cx + 1 < w ? cx + 1 : w - 1;
            for (int ny = y0; ny <= y1; ny++) {
                int rowBase = ny * w;
                for (int nx = x0; nx <= x1; nx++) {
                    int index2 = rowBase + nx;
                    if (maskBuf[index2] == 1) {
                        maskBuf[index2] = 3;
                        floodStack[top++] = index2;
                    }
                }
            }
        }

        // ---- 5) 保留的散点按行并成矩形（与实心带一样，坐标是绝对像素）----
        for (int py = 0; py < h; py++) {
            int base = py * w;
            int px = 0;
            while (px < w) {
                if (maskBuf[base + px] != 3) {
                    px++;
                    continue;
                }
                int from = px;
                while (px < w && maskBuf[base + px] == 3) {
                    px++;
                }
                addRect(minX + from, minY + py, px - from, 1);
            }
        }
    }

    /**
     * 该像素这一层是否保留（连通性是后面泛洪才保证的，这里只看概率）。
     *
     * <p>查全局随机表（类加载时建好）而不是现算随机数；偏移由分组的 seed 决定，所以每个分组的
     * 散点形状都不一样，但同一个分组永远一样。阈值 = {@code start * decay^layer * growthScale}。</p>
     */
    private static boolean ditherOn(int px, int py, int layer, int ox, int oy) {
        int sample = DITHER_LUT[((py + oy) & LUT_MASK) * LUT_SIZE + ((px + ox) & LUT_MASK)] & 0xFF;
        float threshold = ditherStart;
        for (int i = 0; i < layer; i++) {
            threshold *= ditherDecay;
        }
        threshold *= growthScale(px, py);
        return threshold >= 1.0F || sample < threshold * 256.0F;
    }

    /**
     * 该位置的「生长欲望」系数（{@link #GROWTH_MIN} ~ {@link #GROWTH_MAX}）。
     *
     * <p>16px 环绕点阵上的 value noise：双线性插值 + smoothstep 缓和，所以看不到格子感；
     * 点阵边长 32 正好整除贴图边长 512，因此左右上下都接得上，不会出现可见的重复边界。</p>
     */
    private static float growthScale(int px, int py) {
        float fx = Math.floorMod(px, FIELD_SIZE) / (float) NOISE_CELL;
        float fy = Math.floorMod(py, FIELD_SIZE) / (float) NOISE_CELL;
        int x0 = (int) fx;
        int y0 = (int) fy;
        float tx = fx - x0;
        float ty = fy - y0;
        x0 &= NOISE_LATTICE - 1;
        y0 &= NOISE_LATTICE - 1;
        int x1 = (x0 + 1) & (NOISE_LATTICE - 1);
        int y1 = (y0 + 1) & (NOISE_LATTICE - 1);
        tx = tx * tx * (3.0F - 2.0F * tx);
        ty = ty * ty * (3.0F - 2.0F * ty);
        float a = GROWTH_NOISE[y0 * NOISE_LATTICE + x0];
        float b = GROWTH_NOISE[y0 * NOISE_LATTICE + x1];
        float c = GROWTH_NOISE[y1 * NOISE_LATTICE + x0];
        float d = GROWTH_NOISE[y1 * NOISE_LATTICE + x1];
        float upper = a + (b - a) * tx;
        float lower = c + (d - c) * tx;
        float v = (upper + (lower - upper) * ty) / 65535.0F;
        return GROWTH_MIN + (GROWTH_MAX - GROWTH_MIN) * v;
    }

    /** 往矩形列表追加一块；不足时按倍长扩容。 */
    private static void addRect(int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int need = (rectCursor + 1) * 4;
        if (rectData.length < need) {
            rectData = Arrays.copyOf(rectData, Math.max(need, Math.max(1024, rectData.length * 2)));
        }
        int offset = rectCursor * 4;
        rectData[offset] = x;
        rectData[offset + 1] = y;
        rectData[offset + 2] = w;
        rectData[offset + 3] = h;
        rectCursor++;
    }

    /** 行 {@code index} 是否覆盖了像素列 {@code column}；{@code index < 0} 表示没有这一行。 */
    private static boolean coversRow(int index, int column) {
        if (index < 0) {
            return false;
        }
        int base = index * 3;
        return column >= rowData[base] && column < rowData[base] + rowData[base + 2];
    }

    /**
     * 区间 {@code [x, right)} 减去行 {@code index} 覆盖的部分，把结果写进 {@link #SPAN_FROM} /
     * {@link #SPAN_TO}，返回段数（0 ~ 2）。
     */
    private static int exposedSpans(int x, int right, int index) {
        if (index < 0) {
            SPAN_FROM[0] = x;
            SPAN_TO[0] = right;
            return 1;
        }
        int base = index * 3;
        int start = rowData[base];
        int end = start + rowData[base + 2];
        if (end <= x || start >= right) {
            SPAN_FROM[0] = x;
            SPAN_TO[0] = right;
            return 1;
        }
        if (start <= x && end >= right) {
            return 0;
        }
        if (start > x) {
            SPAN_FROM[0] = x;
            SPAN_TO[0] = start;
            SPAN_FROM[1] = end;
            SPAN_TO[1] = right;
            return 2;
        }
        SPAN_FROM[0] = end;
        SPAN_TO[0] = right;
        return 1;
    }

    // -----------------------------------------------------------------------
    // 绘制
    // -----------------------------------------------------------------------

    /**
     * 分组边框：所有分组共用一个缓冲，分两趟画完（草地一趟、花一趟），共 2 次 draw call。
     *
     * <p><b>边框带就是背景的一扇窗</b>：不管外侧实心带还是向内延伸的散点，每个像素都按
     * <b>绝对坐标</b>从两张背景贴图取样（{@code u = x / 512}，可以超过 1 → 由 GL_REPEAT 环绕）。
     * 那一格背景是花就是花、是地就是地 —— 两层各画各的，对"实心 / 延伸"不做任何区别对待，
     * 所以延伸出来的像素和它旁边的背景永远对得上。</p>
     *
     * <p>两趟遍历的是<b>同一批矩形、同一个顺序</b>：先铺草地（顶点色 = 分组强调色，草地被染成
     * 分组色），再按同样顺序铺花（顶点色给白，颜色完全来自花层贴图、不被染色）。</p>
     */
    private static void drawBorders(GuiGraphics graphics, List<FavoriteGroup> groups) {
        if (rectGroupCount <= 0) {
            return;
        }
        Matrix4f pose = graphics.pose().last().pose();
        int total = Math.min(groups.size(), groupRectCount.length);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // 染色已经写进顶点，这里只是防止别的渲染留下带色的全局值。
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        drawPass(graphics, pose, FIELD_TEXTURE, total, true);
        drawPass(graphics, pose, BLOOM_TEXTURE, total, false);
    }

    /**
     * 把整个矩形列表按一张贴图铺一遍。
     *
     * @param tinted true = 顶点色取分组强调色（草地层）；false = 顶点色给白、保留贴图原色（花层）
     */
    private static void drawPass(GuiGraphics graphics, Matrix4f pose, ResourceLocation texture,
            int total, boolean tinted) {
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        BufferBuilder buffer = beginQuads(graphics);
        for (int g = 0; g < total; g++) {
            int color = tinted ? groupBorderColor[g] : 0xFFFFFF;
            int count = groupRectCount[g];
            int base = groupRectStart[g];
            for (int r = 0; r < count; r++) {
                int offset = (base + r) * 4;
                blitQuad(buffer, pose, color, rectData[offset], rectData[offset + 1],
                        rectData[offset + 2], rectData[offset + 3]);
            }
        }
        BufferUploader.drawWithShader(buffer.end());
    }

    /**
     * 拿 Tesselator 的共享 builder 开一批四边形。
     *
     * <p>先 {@code flush()} 保证没有别的批次还开着（EMI / GuiGraphics 用的是同一个 builder），
     * 否则 {@code begin} 会抛 Already building。顶点格式固定为
     * {@link DefaultVertexFormat#POSITION_TEX_COLOR}，所以这里不再收格式参数。</p>
     */
    private static BufferBuilder beginQuads(GuiGraphics graphics) {
        graphics.flush();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        return buffer;
    }

    /** batch 中途出事后复位共享 builder（{@code end()} 会丢弃这半批并把 building 标志清掉）。 */
    private static void discardPendingBatch() {
        try {
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            if (buffer.building()) {
                buffer.end();
            }
        } catch (Throwable ignored) {
            // 复位都失败就没别的办法了，至少不要再往上抛
        }
    }

    /**
     * 画一块纹理，染色直接写进顶点。UV 由<b>绝对像素坐标</b>除以贴图边长得到，可以大于 1 ——
     * 贴图的 wrap 是 OpenGL 默认的 {@code GL_REPEAT}（{@code TextureUtil.prepareImage} 只设
     * mipmap 相关参数，不设 WRAP_S/T），所以越界会自然环绕。
     *
     * <p>这件事是新架构的关键：相邻矩形、相邻分组在交界处算出的 UV <b>逐位相同</b>，采样落在
     * 同一个 texel 上 —— 接缝根本不存在，也不需要按贴图周期分段。</p>
     *
     * <p><b>⚠️ 元素顺序必须与顶点格式一致</b>：{@code position_tex_color} 在 JSON 里声明的是
     * {@code attributes: [Position, UV0, Color]}，正好对应
     * {@link DefaultVertexFormat#POSITION_TEX_COLOR}，所以这里必须 <b>先 uv 后 color</b>。
     * 写成「先 color 后 uv」（那是 {@code POSITION_COLOR_TEX} 的顺序）会让 {@code endVertex()}
     * 抛 {@code IllegalStateException: Not filled all elements of the vertex} —— 而且失败时半批
     * 还留在全局共享的 Tesselator builder 里，之后每帧都 "Already building!"。1.0.13 首次实机
     * 就是栽在这里。</p>
     */
    private static void blitQuad(BufferBuilder buffer, Matrix4f pose, int color,
            int x, int y, int width, int height) {
        float u0 = (float) x / FIELD_SIZE;
        float u1 = (float) (x + width) / FIELD_SIZE;
        float v0 = (float) y / FIELD_SIZE;
        float v1 = (float) (y + height) / FIELD_SIZE;
        float r = (color >> 16 & 0xFF) / 255.0F;
        float g = (color >> 8 & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        buffer.vertex(pose, (float) x, (float) y, 0.0F).uv(u0, v0).color(r, g, b, 1.0F).endVertex();
        buffer.vertex(pose, (float) x, (float) (y + height), 0.0F).uv(u0, v1).color(r, g, b, 1.0F).endVertex();
        buffer.vertex(pose, (float) (x + width), (float) (y + height), 0.0F).uv(u1, v1).color(r, g, b, 1.0F).endVertex();
        buffer.vertex(pose, (float) (x + width), (float) y, 0.0F).uv(u1, v0).color(r, g, b, 1.0F).endVertex();
    }

    // -----------------------------------------------------------------------
    // 抖动随机表
    // -----------------------------------------------------------------------

    /** 类加载时建一次：{@code LUT_SIZE^2} 个均匀随机字节。 */
    private static byte[] buildLut() {
        byte[] lut = new byte[LUT_SIZE * LUT_SIZE];
        for (int i = 0; i < lut.length; i++) {
            lut[i] = (byte) (mix32(i * 0x9E3779B9 + 0x1234567) & 0xFF);
        }
        return lut;
    }

    /** 类加载时建一次：{@code NOISE_LATTICE^2} 个 0~65535 的点阵值（生长调制用）。 */
    private static int[] buildGrowthNoise() {
        int[] grid = new int[NOISE_LATTICE * NOISE_LATTICE];
        for (int i = 0; i < grid.length; i++) {
            grid[i] = mix32(i * 0x27D4EB2D + 0x165667B1) & 0xFFFF;
        }
        return grid;
    }

    /**
     * lowbias32：一个便宜的整数散列。离屏预览（{@code temp/preview_field.py}）里有一份逐位
     * 相同的实现，两边的散点因此完全一致 —— 预览图看到什么，游戏里就是什么。
     */
    private static int mix32(int x) {
        x ^= x >>> 16;
        x *= 0x7FEB352D;
        x ^= x >>> 15;
        x *= 0x846CA68B;
        x ^= x >>> 16;
        return x;
    }

    // -----------------------------------------------------------------------
    // 行排布
    // -----------------------------------------------------------------------

    /**
     * 把一段连续区间按行拆开写进 {@link #rowData}，返回写入后的行游标。
     *
     * <p>收藏栏可以换行：一个分组会横跨多行，且每行的起止列未必相同（页尾不满行）。</p>
     */
    private static int collectRows(ScreenSpace space, int pageStart, int from, int to, int cursor) {
        int x = space.getRawX(from - pageStart);
        int y = space.getRawY(from - pageStart);
        int endX = x + ENTRY_SIZE;
        for (int index = from + 1; index <= to; index++) {
            int offset = index - pageStart;
            int currentY = space.getRawY(offset);
            if (currentY == y) {
                endX = space.getRawX(offset) + ENTRY_SIZE;
            } else {
                cursor = putRow(cursor, x, y, endX - x);
                x = space.getRawX(offset);
                y = currentY;
                endX = x + ENTRY_SIZE;
            }
        }
        return putRow(cursor, x, y, endX - x);
    }

    private static int putRow(int cursor, int x, int y, int width) {
        int need = (cursor + 1) * 3;
        if (rowData.length < need) {
            rowData = Arrays.copyOf(rowData, Math.max(need, Math.max(48, rowData.length * 2)));
        }
        rowData[cursor * 3] = x;
        rowData[cursor * 3 + 1] = y;
        rowData[cursor * 3 + 2] = width;
        return cursor + 1;
    }

    /** 框选标记的颜色（白），透明度由调用方给。 */
    private static int selectionColor(int alpha) {
        return (SELECTION_ACCENT & 0x00FFFFFF) | (alpha << 24);
    }

    /**
     * EMI 真正会渲染的页码。
     *
     * <p>{@code SidebarPanel.scroll} 先无条件下 {@code page += delta}，只有确认多页后才夹回范围；
     * 单页时它直接 return，页码就停在越界值上。夹回是 render 中途的 {@code wrapPage} 做的，
     * 而边框画在 render 的 HEAD —— 拿到的还是越界页码，于是边框和稍后画的东西落在不同页上，
     * 表现为滚动时分组闪烁。这里按 wrapPage 同样的规则换算，保证两趟绘制一致。</p>
     *
     * <p>注意不能把结果写回 {@code panel.page}：wrapPage 依赖越界值来触发翻页与 batcher 重绘。</p>
     */
    private static int currentPage(ScreenSpace space, SidebarPanel panel) {
        int totalPages = (space.getStacks().size() - 1) / space.pageSize + 1;
        if (panel.page >= totalPages) {
            return 0;
        }
        return panel.page < 0 ? Math.max(totalPages - 1, 0) : panel.page;
    }

    private static ScreenSpace spaceOf(SidebarPanel panel) {
        return panel.getType() != SidebarType.FAVORITES || panel.space == null || panel.space.pageSize <= 0
                ? null
                : panel.space;
    }

    /** 把一段连续区间按行拆开，每行返回 {@code {x, y, width}}（只给 Alt 框选预览用）。 */
    private static List<int[]> rowsOf(ScreenSpace space, int pageStart, int from, int to) {
        if (from > to) {
            return List.of();
        }
        List<int[]> rows = new java.util.ArrayList<>();
        int x = space.getRawX(from - pageStart);
        int y = space.getRawY(from - pageStart);
        int endX = x + ENTRY_SIZE;
        for (int index = from + 1; index <= to; index++) {
            int offset = index - pageStart;
            int currentY = space.getRawY(offset);
            if (currentY == y) {
                endX = space.getRawX(offset) + ENTRY_SIZE;
            } else {
                rows.add(new int[] { x, y, endX - x });
                x = space.getRawX(offset);
                y = currentY;
                endX = x + ENTRY_SIZE;
            }
        }
        rows.add(new int[] { x, y, endX - x });
        return rows;
    }
}
