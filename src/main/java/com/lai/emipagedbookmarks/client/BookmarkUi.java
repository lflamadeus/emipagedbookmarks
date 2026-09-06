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
import net.minecraft.network.chat.Component;

public final class BookmarkUi {
    private static final int BUTTON_SIZE = 18;
    private static final int GAP = 0;

    private BookmarkUi() {
    }

    public static void render(EmiDrawContext context, EmiScreenManager.SidebarPanel panel, int mouseX, int mouseY) {
        if (!isBookmarkPanel(panel) || panel.space == null) {
            return;
        }
        int x = panel.space.tx;
        int y = panel.space.ty - BUTTON_SIZE;
        int right = panel.space.tx + panel.space.tw * BUTTON_SIZE;
        int deleteX = right - BUTTON_SIZE;
        int addX = deleteX - BUTTON_SIZE - GAP;

        // 顶部按钮固定贴在书签栏右侧，分页按钮从左侧依次排列。
        drawButton(context, addX, y, "+", isHovered(addX, y, mouseX, mouseY));
        drawButton(context, deleteX, y, "-", isHovered(deleteX, y, mouseX, mouseY));

        int tabX = x;
        List<BookmarkPage> pages = BookmarkPages.pages();
        for (int i = 0; i < pages.size() && tabX + BUTTON_SIZE <= addX; i++) {
            boolean hovered = isHovered(tabX, y, mouseX, mouseY);
            drawButton(context, tabX, y, pages.get(i).name(), hovered || i == BookmarkPages.currentIndex());
            tabX += BUTTON_SIZE + GAP;
        }

        GuiGraphics graphics = context.raw();
        Component tooltip = tooltipAt(panel, mouseX, mouseY);
        if (tooltip != null) {
            graphics.renderTooltip(Minecraft.getInstance().font, tooltip, mouseX, mouseY);
        }
    }

    public static boolean mouseClicked(double mouseX, double mouseY, int button) {
        EmiScreenManager.SidebarPanel panel = EmiScreenManager.getPanelFor(SidebarType.FAVORITES);
        if (!isBookmarkPanel(panel) || panel.space == null) {
            return false;
        }
        int mx = (int) mouseX;
        int my = (int) mouseY;
        int x = panel.space.tx;
        int y = panel.space.ty - BUTTON_SIZE;
        int right = panel.space.tx + panel.space.tw * BUTTON_SIZE;
        int deleteX = right - BUTTON_SIZE;
        int addX = deleteX - BUTTON_SIZE - GAP;

        if (isHovered(deleteX, y, mx, my)) {
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
        if (isHovered(addX, y, mx, my)) {
            BookmarkPages.addPage();
            panel.page = 0;
            return true;
        }

        int tabX = x;
        List<BookmarkPage> pages = BookmarkPages.pages();
        for (int i = 0; i < pages.size() && tabX + BUTTON_SIZE <= addX; i++) {
            if (isHovered(tabX, y, mx, my)) {
                if (button == 1) {
                    Minecraft.getInstance().setScreen(new RenamePageScreen(Minecraft.getInstance().screen));
                } else if (button == 0) {
                    panel.page = 0;
                    BookmarkPages.selectPage(i);
                }
                return true;
            }
            tabX += BUTTON_SIZE + GAP;
        }
        return false;
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

    private static Component tooltipAt(EmiScreenManager.SidebarPanel panel, int mouseX, int mouseY) {
        int x = panel.space.tx;
        int y = panel.space.ty - BUTTON_SIZE;
        int right = panel.space.tx + panel.space.tw * BUTTON_SIZE;
        int deleteX = right - BUTTON_SIZE;
        int addX = deleteX - BUTTON_SIZE - GAP;
        if (isHovered(deleteX, y, mouseX, mouseY)) {
            return Component.translatable("tooltip.emipagedbookmarks.delete_page", Component.translatable("key.sneak"));
        }
        if (isHovered(addX, y, mouseX, mouseY)) {
            return Component.translatable("tooltip.emipagedbookmarks.add_page");
        }
        int tabX = x;
        List<BookmarkPage> pages = BookmarkPages.pages();
        for (int i = 0; i < pages.size() && tabX + BUTTON_SIZE <= addX; i++) {
            if (isHovered(tabX, y, mouseX, mouseY)) {
                return Component.translatable("tooltip.emipagedbookmarks.page");
            }
            tabX += BUTTON_SIZE + GAP;
        }
        return null;
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
}
