package com.lai.emipagedbookmarks.client;

import java.util.List;

import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.config.SidebarType;
import dev.emi.emi.registry.EmiExclusionAreas;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.screen.EmiScreenBase;
import dev.emi.emi.screen.EmiScreenManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

public final class BookmarkUi {
    private static final int BUTTON_SIZE = 18;
    private static final int GAP = 0;
    private static final int STEP = BUTTON_SIZE + GAP;

    /** 正在被拖动的分页下标，无拖动时为 -1。 */
    private static int dragPageIndex = -1;

    private BookmarkUi() {
    }

    public static void render(EmiDrawContext context, EmiScreenManager.SidebarPanel panel, int mouseX, int mouseY) {
        Layout layout = layout(panel);
        if (layout == null) {
            return;
        }
        drawButton(context, layout.addX, layout.y, "+", isHovered(layout.addX, layout.y, mouseX, mouseY));
        drawButton(context, layout.deleteX, layout.y, "-", isHovered(layout.deleteX, layout.y, mouseX, mouseY));

        List<BookmarkPage> pages = BookmarkPages.pages();
        for (int i = layout.start; i < pages.size() && layout.tabX(i) + BUTTON_SIZE <= layout.addX; i++) {
            boolean active = i == BookmarkPages.currentIndex() || i == dragPageIndex;
            drawButton(context, layout.tabX(i), layout.y, pages.get(i).name(),
                    active || isHovered(layout.tabX(i), layout.y, mouseX, mouseY));
        }
        // 左右箭头提示还有未显示的分页。
        if (layout.start > 0) {
            drawArrow(context, layout.tabX(layout.start), layout.y, true);
        }
        if (layout.start + layout.maxTabs < pages.size()) {
            drawArrow(context, layout.tabX(layout.start + layout.maxTabs - 1), layout.y, false);
        }

        GuiGraphics graphics = context.raw();
        Component tooltip = tooltipAt(layout, mouseX, mouseY);
        if (tooltip != null) {
            graphics.renderTooltip(Minecraft.getInstance().font, tooltip, mouseX, mouseY);
        }
    }

    public static boolean mouseClicked(double mouseX, double mouseY, int button) {
        EmiScreenManager.SidebarPanel panel = EmiScreenManager.getPanelFor(SidebarType.FAVORITES);
        if (panel == null) {
            return false;
        }
        Layout layout = layout(panel);
        if (layout == null) {
            return false;
        }
        int mx = (int) mouseX;
        int my = (int) mouseY;

        if (isHovered(layout.deleteX, layout.y, mx, my)) {
            if (Screen.hasShiftDown()) {
                BookmarkPages.removeCurrentPage();
            } else if (BookmarkPages.pages().size() > 1) {
                Screen parent = Minecraft.getInstance().screen;
                Minecraft.getInstance().setScreen(new ConfirmScreen(yes -> {
                    if (yes) {
                        BookmarkPages.removeCurrentPage();
                    }
                    Minecraft.getInstance().setScreen(parent);
                }, Component.translatable("screen.emipagedbookmarks.delete_title"),
                        Component.translatable("screen.emipagedbookmarks.delete_message")));
            }
            return true;
        }
        if (isHovered(layout.addX, layout.y, mx, my)) {
            BookmarkPages.addPage();
            panel.page = 0;
            return true;
        }

        Integer tab = tabAt(layout, mx, my);
        if (tab == null) {
            return false;
        }
        if (button == 1) {
            // 右键先选中该分页，再打开操作菜单，菜单里的操作都针对它。
            panel.page = 0;
            BookmarkPages.selectPage(tab);
            Minecraft.getInstance().setScreen(new PageMenuScreen(Minecraft.getInstance().screen));
        } else if (button == 0) {
            panel.page = 0;
            BookmarkPages.selectPage(tab);
            dragPageIndex = tab;
        }
        return true;
    }

    /**
     * 松开鼠标时把拖动的分页落到目标位置。
     */
    public static boolean mouseReleased(double mouseX, double mouseY, int button) {
        int dragged = dragPageIndex;
        dragPageIndex = -1;
        if (dragged == -1 || button != 0) {
            return false;
        }
        EmiScreenManager.SidebarPanel panel = EmiScreenManager.getPanelFor(SidebarType.FAVORITES);
        Layout layout = layout(panel);
        if (layout == null) {
            return false;
        }
        Integer target = tabAt(layout, (int) mouseX, (int) mouseY);
        if (target == null || target == dragged) {
            return false;
        }
        BookmarkPages.movePage(dragged, target);
        panel.page = 0;
        return true;
    }

    /**
     * 在聊天栏提示一条消息，用于导入导出结果反馈。
     */
    public static void notify(Component message) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(message, false);
        }
    }

    public static boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!Screen.hasShiftDown() || amount == 0) {
            return false;
        }
        int mx = (int) mouseX;
        int my = (int) mouseY;
        EmiScreenBase base = EmiScreenBase.getCurrent();
        if (base.isEmpty()) {
            return false;
        }
        EmiScreenManager.SidebarPanel panel = EmiScreenManager.getHoveredPanel(mx, my);
        if (!isBookmarkPanel(panel)) {
            return false;
        }
        // 仅在EMI原生侧栏可滚动区域内拦截Shift滚轮，避免影响其他界面操作。
        for (Bounds bounds : EmiExclusionAreas.getExclusion(base)) {
            if (bounds.contains(mx, my)) {
                return false;
            }
        }
        if (!BookmarkPages.scrollBy(amount > 0 ? -1 : 1)) {
            return false;
        }
        panel.page = 0;
        return true;
    }

    /**
     * 关屏时清掉拖动状态：拖分页途中按 ESC 关屏，{@code mouseReleased} 不会再到达，
     * 残留的下标会让那个分页在下次打开时一直显示成选中态。
     */
    public static void clearDragState() {
        dragPageIndex = -1;
    }

    private static Layout layout(EmiScreenManager.SidebarPanel panel) {
        // 条件正着写：mouseScrolled 那边是取反用的，这里保持一次正用，
        // 否则 IDEA 会报「布尔方法 isBookmarkPanel() 的调用总是被取反」。
        // 也不再额外判 panel != null —— isBookmarkPanel 自己就带 null 检查，
        // 短路求值会挡住后面的 panel.space（多写一次反而被报「多余」）。
        if (isBookmarkPanel(panel) && panel.space != null) {
            int x = panel.space.tx;
            int y = panel.space.ty - BUTTON_SIZE;
            int right = panel.space.tx + panel.space.tw * BUTTON_SIZE;
            int deleteX = right - BUTTON_SIZE;
            int addX = deleteX - BUTTON_SIZE - GAP;
            int maxTabs = Math.max(0, (addX - x) / STEP);
            int pageCount = BookmarkPages.pages().size();
            // 分页放不下时滚动显示窗口，保证当前分页始终可见。
            int start = pageCount <= maxTabs ? 0
                    : Math.max(0, Math.min(BookmarkPages.currentIndex() - maxTabs / 2, pageCount - maxTabs));
            return new Layout(x, y, addX, deleteX, maxTabs, start);
        }
        return null;
    }

    /**
     * 鼠标所在的分页下标，不在任何分页按钮上时为 null。
     */
    private static Integer tabAt(Layout layout, int mouseX, int mouseY) {
        if (mouseY < layout.y || mouseY >= layout.y + BUTTON_SIZE) {
            return null;
        }
        List<BookmarkPage> pages = BookmarkPages.pages();
        for (int i = layout.start; i < pages.size() && layout.tabX(i) + BUTTON_SIZE <= layout.addX; i++) {
            int tabX = layout.tabX(i);
            if (mouseX >= tabX && mouseX < tabX + BUTTON_SIZE) {
                return i;
            }
        }
        return null;
    }

    private static boolean isBookmarkPanel(EmiScreenManager.SidebarPanel panel) {
        return panel != null && panel.getType() == SidebarType.FAVORITES && panel.isVisible();
    }

    private static void drawButton(EmiDrawContext context, int x, int y, String text, boolean active) {
        int background = active ? 0xAA555555 : 0x88404040;
        context.fill(x, y, BUTTON_SIZE, BUTTON_SIZE, background);
        context.fill(x, y, BUTTON_SIZE, 1, 0xFFAAAAAA);
        context.fill(x, y + BUTTON_SIZE - 1, BUTTON_SIZE, 1, 0xFF202020);
        context.drawCenteredText(Component.literal(trimToButton(text)), x + BUTTON_SIZE / 2, y + 4, 0xFFFFFFFF);
    }

    /**
     * 在分页按钮边缘画一个小三角，提示该方向还有分页。
     */
    private static void drawArrow(EmiDrawContext context, int x, int y, boolean left) {
        int top = y + (BUTTON_SIZE - 5) / 2;
        for (int row = 0; row < 5; row++) {
            int width = row < 3 ? row + 1 : 5 - row;
            int leftX = left ? x + 1 : x + BUTTON_SIZE - 1 - width;
            context.fill(leftX, top + row, width, 1, 0xCCDDDDDD);
        }
    }

    private static Component tooltipAt(Layout layout, int mouseX, int mouseY) {
        if (isHovered(layout.deleteX, layout.y, mouseX, mouseY)) {
            return Component.translatable("tooltip.emipagedbookmarks.delete_page", Component.translatable("key.sneak"));
        }
        if (isHovered(layout.addX, layout.y, mouseX, mouseY)) {
            return Component.translatable("tooltip.emipagedbookmarks.add_page");
        }
        return tabAt(layout, mouseX, mouseY) == null ? null : Component.translatable("tooltip.emipagedbookmarks.page");
    }

    private static boolean isHovered(int x, int y, int mouseX, int mouseY) {
        return mouseX >= x && mouseX < x + BUTTON_SIZE && mouseY >= y && mouseY < y + BUTTON_SIZE;
    }

    private static String trimToButton(String text) {
        var font = Minecraft.getInstance().font;
        if (font.width(text) <= BUTTON_SIZE - 2) {
            return text;
        }
        return font.plainSubstrByWidth(text, BUTTON_SIZE - 2);
    }

    private record Layout(int x, int y, int addX, int deleteX, int maxTabs, int start) {
        int tabX(int index) {
            return x + (index - start) * STEP;
        }
    }
}
