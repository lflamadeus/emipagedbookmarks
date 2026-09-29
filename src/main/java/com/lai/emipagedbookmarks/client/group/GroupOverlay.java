package com.lai.emipagedbookmarks.client.group;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.joml.Matrix4f;
import org.slf4j.Logger;

import com.google.gson.JsonObject;
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

/**
 * 分组轮廓（分组框）与框选预览的绘制。
 *
 * <p>沿每个分组<b>实际占据的格子外缘</b>描一圈边线：某条边只有在「相邻格子不属于同一分组」时
 * 才画。于是换行分组自然得到一条闭合的<b>台阶状轮廓</b>——凹角、拐角、页尾不满行、被界面遮罩
 * 截短的行（宽度小于 {@code tw}、甚至为 0）全都不需要特判。</p>
 *
 * <h2>为什么是纯色轮廓</h2>
 *
 * <p>1.0.27 及以前，这里画的是「草地 + 花」两层可平铺贴图按绝对坐标取样、外实心带向内生长散点的
 * 植物纹边框。那套做法要付出：两张 512x512 贴图（约 300KB 资源）、一张抖动随机表、一张低频生长
 * 噪声、一次 8 连通泛洪、一个只为形状服务的配置段，以及一处为了避开接缝而生的「绝对坐标采样」
 * 架构。它换来的表达能力——「这里是一组」——和一圈实色边线完全相同：分组感由<b>边界</b>提供
 * （共同区域原则），纹理只是装饰。所以 1.0.28 换成纯色轮廓，绘制期只剩「遍历矩形 → 写顶点」。</p>
 *
 * <h2>画在物品之上</h2>
 *
 * <p>轮廓在 {@code SidebarPanel.render} 的 TAIL 注入，也就是压在所有物品图标之上。这是刻意的：
 * EMI 的条目是 18x18、物品贴图 16x16，四边各留 1px 透明边距，{@link #DEFAULT_OUTLINE} 的 2px
 * 轮廓正好落在图标边缘上，既完整可见、又不会盖住图标内容。反过来画在物品之下（1.0.27 的做法）
 * 只有 1px 能露出来，2px 的边线会被图标吃掉一半。厚度可由配置文件调整。</p>
 *
 * <h2>整页恒定 1 次 draw call</h2>
 *
 * <p>所有分组共用一个顶点缓冲：先按分组顺序把全部轮廓矩形拼进同一批，再一次
 * {@link BufferUploader#drawWithShader}。1.20.1 的 {@code GuiGraphics.fill} 每次调用都是一次
 * 独立 draw call，逐条边画能堆到几百次，所以这里自己拼顶点。</p>
 */
public final class GroupOverlay {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** EMI 侧栏条目边长，同时也是分组轮廓的坐标粒度。 */
    private static final int ENTRY_SIZE = 18;
    /** 分组强调色（含不透明度），按分组 UUID 稳定取色。 */
    private static final int[] ACCENTS = new int[] {
            0xFF6FD8A8, 0xFFE8B56B, 0xFF74B4EC, 0xFFC79BE8, 0xFFE8907F
    };
    private static final int SELECTION_ACCENT = 0xFFFFFF;
    /** 悬停高亮的黄色，参考 Flower Hotbar 的选中格。 */
    private static final int HOVER_TINT = 0xFFFFE34D;

    /** 轮廓厚度的代码默认值（像素）。 */
    private static final int DEFAULT_OUTLINE = 2;

    // --- 轮廓形状（可通过配置文件调整，见 borderConfigJson / applyBorderConfig）---

    /** 轮廓厚度（像素），向内生长。 */
    private static int outline = DEFAULT_OUTLINE;

    /**
     * 轮廓配置的版本号。
     *
     * <p>这个字段是必须的：{@link #borderConfigJson()} 每次存档都会把当前形状写回配置文件，
     * 于是<b>旧版本写下的值会被一直读回来、把新的代码默认值永远盖住</b>——改了默认形状却毫无
     * 效果（1.0.20 把实心 4 改成 2 时就踩过这个坑）。</p>
     *
     * <p>v1 是植物纹边框的配置（{@code solid} / {@code layers} / {@code start} / {@code decay}）。
     * 1.0.28 换成纯色轮廓后整段作废，版本号 +1：v1 的文件会被整段忽略、退回代码默认值，下次存档
     * 自动写成 v2。规则：<b>只要动了 {@link #DEFAULT_OUTLINE} 的默认值，就再把这个数 +1。</b></p>
     */
    private static final int BORDER_CONFIG_VERSION = 2;

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
     * 轮廓矩形列表：每个矩形 4 个 int（x, y, w, h），坐标是绝对像素。
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

    /** 绘制出过一次错就彻底关掉（见 {@link #render} 的 catch），后半程不再重试。 */
    private static boolean broken;

    private GroupOverlay() {
    }

    /**
     * 收藏栏的分组轮廓 + 框选预览，在 {@code SidebarPanel.render} 的 TAIL 调用。
     *
     * <p>框选预览不放在 {@link #broken} 的保护里：轮廓那套顶点拼装万一出问题，也不该连带
     * 让 Alt 框选看不见。</p>
     */
    public static void render(EmiDrawContext context, SidebarPanel panel, int mouseX, int mouseY) {
        ScreenSpace space = spaceOf(panel);
        if (space == null) {
            return;
        }
        GuiGraphics graphics = context.raw();
        if (!broken) {
            try {
                drawOutline(graphics, panel, space, mouseX, mouseY);
            } catch (RuntimeException e) {
                // 一条边线画错不该把整个游戏带崩：Tesselator 的 builder 是全局共享的，batch 中途抛异常
                // 会让它一直停在 building 状态，之后每一次 GUI 绘制都 "Already building!"（1.0.13 就是
                // 这么把客户端炸掉的）。所以这里复位 builder、本次运行内停用本模组的绘制，并留一行日志。
                broken = true;
                discardPendingBatch();
                LOGGER.error("[emipagedbookmarks] 分组轮廓绘制失败，本次运行内已停用该功能", e);
            }
        }
        drawSelection(graphics, panel, space, mouseX, mouseY);
    }

    /**
     * 当前鼠标位置在收藏栏中的显示下标，不在收藏栏内则为 -1。
     */
    private static int hoveredVisibleIndex(ScreenSpace space, SidebarPanel panel, int mouseX, int mouseY) {
        int offset = space.getRawOffsetFromMouse(mouseX, mouseY);
        return offset == -1 ? -1 : currentPage(space, panel) * space.pageSize + offset;
    }

    // -----------------------------------------------------------------------
    // 配置（留在 bookmarks.json 的顶层 "border" 对象里）
    // -----------------------------------------------------------------------

    /** 当前轮廓形状，写进配置文件。 */
    public static JsonObject borderConfigJson() {
        JsonObject json = new JsonObject();
        json.addProperty("v", BORDER_CONFIG_VERSION);
        json.addProperty("outline", outline);
        return json;
    }

    /**
     * 读配置文件里的轮廓形状；缺字段就保持默认值，越界值夹到安全范围。
     *
     * <p>配置版本与 {@link #BORDER_CONFIG_VERSION} 不一致（含没有 {@code v} 的旧文件、以及
     * 植物纹边框时代的 v1）时<b>整段忽略</b>，直接沿用代码默认值。</p>
     */
    public static void applyBorderConfig(JsonObject json) {
        if (json == null) {
            return;
        }
        int version = json.has("v") ? json.get("v").getAsInt() : 0;
        if (version != BORDER_CONFIG_VERSION) {
            LOGGER.info("边框配置版本 v{} != v{}，忽略旧值、改用当前默认（outline={}）",
                    version, BORDER_CONFIG_VERSION, outline);
            return;
        }
        if (json.has("outline")) {
            outline = clampOutline(json.get("outline").getAsInt());
        }
        // 形状变了，矩形缓存作废
        rectSignature = Long.MIN_VALUE;
    }

    /**
     * 轮廓厚度夹到安全范围。
     *
     * <p>下界 0 = 关掉轮廓（合法用法）；上界取半个条目高，再多就不是「描边」而是把整格填满了。</p>
     */
    private static int clampOutline(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, ENTRY_SIZE / 2);
    }

    // -----------------------------------------------------------------------
    // 行排布与轮廓矩形
    // -----------------------------------------------------------------------

    /**
     * 先把一页里每个分组的可见区间拆成行、算好配色，再把轮廓矩形整批建好（带缓存）。
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
            groupBorderColor[i] = hot ? HOVER_TINT : ACCENTS[accent];
        }
        buildRects(rows, total);
        return rows;
    }

    /**
     * 建好整页所有分组的轮廓矩形；「几何签名」没变就直接复用上次的结果。
     *
     * <p>签名覆盖行排布、分组切片与轮廓厚度——这些不变，画出来就一模一样。签名一算一遍是
     * O(行数)，而重建要遍历整条轮廓，所以缓存仍然划算：拖动分组、滚动翻页时只重建一次。</p>
     */
    private static void buildRects(int rows, int total) {
        long signature = signature(rows, total);
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
            buildGroupRects(g);
            groupRectCount[g] = rectCursor - groupRectStart[g];
        }
    }

    /** 几何签名：行排布 + 分组切片 + 轮廓厚度；任何一项变了都要重建矩形。 */
    private static long signature(int rows, int total) {
        long h = 0x9E3779B97F4A7C15L ^ outline;
        int limit = rows * 3;
        for (int i = 0; i < limit; i++) {
            h = (h ^ rowData[i]) * 0x100000001B3L;
        }
        for (int g = 0; g < total; g++) {
            h = (h ^ groupRowStart[g] ^ ((long) groupRowCount[g] << 20)) * 0x100000001B3L;
        }
        return h;
    }

    /**
     * 一个分组的轮廓矩形：按「各行区间的并集」描边。
     *
     * <p>逐行看：顶边只画上一行没盖住的那些段，底边只画下一行没盖住的那些段，左右竖边只画该侧
     * 上下没有被同分组盖住的部分。被盖住的那端让出 {@link #outline} 的厚度，这样角上不会画两遍。
     * 换行分组因此得到一条闭合的台阶状轮廓。</p>
     */
    private static void buildGroupRects(int group) {
        int start = groupRowStart[group];
        int count = groupRowCount[group];
        for (int i = 0; i < count; i++) {
            int row = start + i;
            int above = i > 0 ? row - 1 : -1;
            int below = i + 1 < count ? row + 1 : -1;
            int x = rowData[row * 3];
            int y = rowData[row * 3 + 1];
            int right = x + rowData[row * 3 + 2];
            int bottom = y + ENTRY_SIZE;

            // 顶边：上一行没盖住的部分（首行则整条）。跨行的台阶就在这里补上。
            int spans = exposedSpans(x, right, above);
            for (int s = 0; s < spans; s++) {
                int from = SPAN_FROM[s];
                int to = SPAN_TO[s];
                // 空段：上一行盖到最后。空段什么都不画，台阶交给竖边补满。
                if (from < to) {
                    addRect(from, y, to - from, outline);
                }
            }

            // 底边：下一行没盖住的部分（末行则整条）。
            spans = exposedSpans(x, right, below);
            for (int s = 0; s < spans; s++) {
                int from = SPAN_FROM[s];
                int to = SPAN_TO[s];
                if (from < to) {
                    addRect(from, bottom - outline, to - from, outline);
                }
            }

            // 左右竖边：贴着同分组的上一/下一行时不做让位（两条带子接上），否则让出 outline
            // ——让出的那一个角由横带（它铺满整行宽）负责，于是外沿那一圈在角上是连续的实心。
            // 让位量必须正好是 outline 而不是整条带子的厚度，用后者会留下一个缺口。
            int top = y + (coversRow(above, x) ? 0 : outline);
            int lower = bottom - (coversRow(below, x) ? 0 : outline);
            if (lower > top) {
                addRect(x, top, outline, lower - top);
            }
            top = y + (coversRow(above, right - ENTRY_SIZE) ? 0 : outline);
            lower = bottom - (coversRow(below, right - ENTRY_SIZE) ? 0 : outline);
            if (lower > top) {
                addRect(right - outline, top, outline, lower - top);
            }
        }
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

    /** 分组轮廓：所有分组共用一个缓冲，1 次 draw call。 */
    private static void drawOutline(GuiGraphics graphics, SidebarPanel panel, ScreenSpace space,
            int mouseX, int mouseY) {
        if (outline <= 0) {
            return;
        }
        int pageStart = currentPage(space, panel) * space.pageSize;
        int pageEnd = pageStart + space.pageSize - 1;
        UUID pageId = BookmarkPages.currentId();
        List<FavoriteGroup> groups = GroupManager.groups(pageId);
        int hover = hoveredVisibleIndex(space, panel, mouseX, mouseY);
        int rows = prepare(groups, pageId, space, pageStart, pageEnd, hover);
        if (rows == 0) {
            return;
        }

        Matrix4f pose = graphics.pose().last().pose();
        int total = Math.min(groupBorderColor.length, groupRectCount.length);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // 颜色已经写进顶点，这里只是防止别的渲染留下带色的全局值。
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buffer = beginQuads(graphics);
        for (int g = 0; g < total; g++) {
            int color = groupBorderColor[g];
            int count = groupRectCount[g];
            int base = groupRectStart[g];
            for (int r = 0; r < count; r++) {
                int offset = (base + r) * 4;
                addQuad(buffer, pose, color,
                        rectData[offset], rectData[offset + 1], rectData[offset + 2], rectData[offset + 3]);
            }
        }
        BufferUploader.drawWithShader(buffer.end());
    }

    /** Alt 框选预览：压在物品之上，让被框住的范围一眼可见。 */
    private static void drawSelection(GuiGraphics graphics, SidebarPanel panel, ScreenSpace space,
            int mouseX, int mouseY) {
        int anchor = GroupManager.selectionAnchor();
        if (anchor == -1 || !Screen.hasAltDown()) {
            return;
        }
        int hovered = hoveredVisibleIndex(space, panel, mouseX, mouseY);
        if (hovered == -1) {
            return;
        }
        int pageStart = currentPage(space, panel) * space.pageSize;
        int pageEnd = pageStart + space.pageSize - 1;
        UUID pageId = BookmarkPages.currentId();
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
     * 拿 Tesselator 的共享 builder 开一批四边形。
     *
     * <p>先 {@code flush()} 保证没有别的批次还开着（EMI / GuiGraphics 用的是同一个 builder），
     * 否则 {@code begin} 会抛 Already building。顶点格式固定为
     * {@link DefaultVertexFormat#POSITION_COLOR}，所以这里不再收格式参数。</p>
     */
    private static BufferBuilder beginQuads(GuiGraphics graphics) {
        graphics.flush();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
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
     * 往缓冲里写一块实色四边形，颜色直接写进顶点。
     *
     * <p><b>⚠️ 元素顺序必须与顶点格式一致</b>：{@link DefaultVertexFormat#POSITION_COLOR} 声明的是
     * {@code attributes: [Position, Color]}，所以这里必须<b>先 position 后 color</b>。写反会让
     * {@code endVertex()} 抛 {@code IllegalStateException: Not filled all elements of the vertex}
     * ——而且失败时半批还留在全局共享的 Tesselator builder 里，之后每帧都 "Already building!"。
     * 1.0.13 首次实机就是栽在这个顺序上（当时是 POSITION_TEX_COLOR 的 uv/color 顺序）。</p>
     */
    private static void addQuad(BufferBuilder buffer, Matrix4f pose, int color,
            int x, int y, int width, int height) {
        float r = (color >> 16 & 0xFF) / 255.0F;
        float g = (color >> 8 & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        float a = (color >>> 24) / 255.0F;
        buffer.vertex(pose, (float) x, (float) y, 0.0F).color(r, g, b, a).endVertex();
        buffer.vertex(pose, (float) x, (float) (y + height), 0.0F).color(r, g, b, a).endVertex();
        buffer.vertex(pose, (float) (x + width), (float) (y + height), 0.0F).color(r, g, b, a).endVertex();
        buffer.vertex(pose, (float) (x + width), (float) y, 0.0F).color(r, g, b, a).endVertex();
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
     * 而轮廓画在 render 的 TAIL —— 拿到的还是越界页码，于是轮廓和稍后画的东西落在不同页上，
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
        List<int[]> rows = new ArrayList<>();
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
