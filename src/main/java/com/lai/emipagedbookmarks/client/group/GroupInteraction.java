package com.lai.emipagedbookmarks.client.group;

import java.util.Optional;
import java.util.UUID;

import com.lai.emipagedbookmarks.client.BookmarkPages;

import dev.emi.emi.config.SidebarType;
import dev.emi.emi.screen.EmiScreenManager;
import dev.emi.emi.screen.EmiScreenManager.ScreenSpace;
import dev.emi.emi.screen.EmiScreenManager.SidebarPanel;
import net.minecraft.client.gui.screens.Screen;

/**
 * 分组的鼠标与键盘操作：Alt 框选建组、Alt 右键折叠、拖拽移动、Alt+C / Alt+D / Alt+Shift+D。
 */
public final class GroupInteraction {
    private static final int KEY_C = 67;
    private static final int KEY_D = 68;
    private static final int KEY_S = 83;

    private GroupInteraction() {
    }

    public static boolean mouseClicked(double mouseX, double mouseY, int button) {
        Hover hover = hover(mouseX, mouseY);
        if (hover == null) {
            return false;
        }
        if (hover.index < 0) {
            GroupManager.clearSelectionAnchor();
            return false;
        }
        UUID pageId = BookmarkPages.currentId();
        if (button == 1 && Screen.hasAltDown()) {
            // Alt + 右键：把收藏移出所在分组，放到该分组后面。收纳中的分组不做处理。
            GroupManager.extractFromGroup(pageId, hover.index);
            repopulate();
            return true;
        }
        if (button == 0) {
            Optional<FavoriteGroup> group = GroupManager.containing(pageId, hover.index);
            boolean foldedHead = group.isPresent() && group.get().folded() && hover.index == group.get().startIndex();
            GroupManager.setSelectionAnchor(hover.index);
            GroupManager.setDragGroupId(foldedHead ? group.get().id() : null);
            // Alt + 左键用于框选，拦下 EMI 的默认处理。
            return Screen.hasAltDown();
        }
        return false;
    }

    /**
     * 松开鼠标。只有在收藏栏内产生有效位移、或按住 Alt 时才接管；
     * 其它情况一律交回 EMI，保证它自带的拖拽（显示图标、拖进搜索栏）不受影响。
     */
    public static boolean mouseReleased(double mouseX, double mouseY, int button) {
        int anchor = GroupManager.selectionAnchor();
        if (anchor == -1 || button != 0) {
            return false;
        }
        GroupManager.clearSelectionAnchor();
        UUID dragGroupId = GroupManager.dragGroupId();
        GroupManager.setDragGroupId(null);
        Hover hover = hover(mouseX, mouseY);
        if (hover == null || hover.index < 0) {
            // 拖到收藏栏之外（搜索栏、物品栏等），完全交回 EMI 处理。
            return false;
        }
        if (hover.index == anchor) {
            // 原地松开：没有位移。按住 Alt 时吞掉，避免误触发 EMI 的收藏开关。
            return Screen.hasAltDown();
        }
        UUID pageId = BookmarkPages.currentId();
        if (dragGroupId != null) {
            GroupManager.moveGroup(pageId, dragGroupId, hover.index);
            repopulate();
            return true;
        }
        if (Screen.hasAltDown()) {
            int start = Math.min(anchor, hover.index);
            int end = Math.max(anchor, hover.index);
            // 框选范围涵盖到的已有分组会被并入新分组。
            Optional<FavoriteGroup> startGroup = GroupManager.containing(pageId, start);
            if (startGroup.isPresent()) {
                start = startGroup.get().startIndex();
            }
            Optional<FavoriteGroup> endGroup = GroupManager.containing(pageId, end);
            if (endGroup.isPresent()) {
                end = endGroup.get().endIndex();
            }
            GroupManager.createGroup(pageId, start, Math.min(end, BookmarkPages.currentItemCount() - 1));
        } else {
            GroupManager.moveItem(pageId, anchor, hover.index);
        }
        repopulate();
        return true;
    }

    public static boolean keyPressed(int keyCode) {
        if (!Screen.hasAltDown() || (keyCode != KEY_C && keyCode != KEY_D && keyCode != KEY_S)) {
            return false;
        }
        Hover hover = hover(EmiScreenManager.lastMouseX, EmiScreenManager.lastMouseY);
        if (hover == null || hover.index < 0) {
            return false;
        }
        UUID pageId = BookmarkPages.currentId();
        Optional<FavoriteGroup> group = GroupManager.containing(pageId, hover.index);
        if (group.isEmpty()) {
            return false;
        }
        FavoriteGroup target = group.get();
        if (keyCode == KEY_S) {
            // Alt + S：收纳/展开该分组。
            target.folded(!target.folded());
            GroupManager.save();
        } else if (keyCode == KEY_D) {
            if (Screen.hasShiftDown()) {
                GroupManager.removeGroupWithItems(pageId, target.id());
            } else {
                GroupManager.removeGroup(pageId, target.id());
            }
        } else if (!Screen.hasShiftDown()) {
            target.lineBreak(!target.lineBreak());
            GroupManager.save();
        } else {
            return false;
        }
        repopulate();
        return true;
    }

    /**
     * 关屏时清掉框选锚点与正在拖拽的分组。
     *
     * <p>按住左键时关屏，{@code mouseReleased} 就不会来了；不清的话下次在这个界面里的
     * 一次普通点击会被当成「从旧锚点拖过来」，误移动一个条目。</p>
     */
    public static void clearInteraction() {
        GroupManager.clearSelectionAnchor();
        GroupManager.setDragGroupId(null);
    }

    private static Hover hover(double mouseX, double mouseY) {
        int mx = (int) mouseX;
        int my = (int) mouseY;
        SidebarPanel panel = EmiScreenManager.getHoveredPanel(mx, my);
        if (panel == null || panel.getType() != SidebarType.FAVORITES) {
            GroupManager.clearSelectionAnchor();
            return null;
        }
        ScreenSpace space = panel.getHoveredSpace(mx, my);
        if (space == null) {
            GroupManager.clearSelectionAnchor();
            return null;
        }
        int offset = space.getRawOffsetFromMouse(mx, my);
        if (offset == -1) {
            GroupManager.clearSelectionAnchor();
            return null;
        }
        int visible = panel.page * space.pageSize + offset;
        return new Hover(space, GroupManager.toOriginalIndex(BookmarkPages.currentId(), visible));
    }

    private static void repopulate() {
        EmiScreenManager.repopulatePanels(SidebarType.FAVORITES);
    }

    private record Hover(ScreenSpace space, int index) {
    }
}
