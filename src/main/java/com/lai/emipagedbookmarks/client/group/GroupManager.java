package com.lai.emipagedbookmarks.client.group;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lai.emipagedbookmarks.client.BookmarkPage;
import com.lai.emipagedbookmarks.client.BookmarkPages;
import com.lai.emipagedbookmarks.client.LayoutVersion;

/**
 * 收藏栏分组管理。分组按分页归类，索引以各分页自己的收藏顺序为准。
 */
public final class GroupManager {
    private static int[] visibleToOutput = new int[0];
    private static int[] outputToVisible = new int[0];
    private static int selectionAnchor = -1;
    private static UUID dragGroupId;

    private GroupManager() {
    }

    /**
     * 分组数据随分页一起保存在 bookmarks.json 里。
     */
    public static synchronized void save() {
        BookmarkPages.save();
    }

    public static synchronized List<FavoriteGroup> groups(UUID pageId) {
        BookmarkPage page = BookmarkPages.pageById(pageId);
        return page == null ? new ArrayList<>() : page.groups();
    }

    public static synchronized Optional<FavoriteGroup> containing(UUID pageId, int index) {
        return groups(pageId).stream().filter(group -> group.contains(index)).findFirst();
    }

    public static synchronized Optional<FavoriteGroup> byId(UUID pageId, UUID id) {
        return groups(pageId).stream().filter(group -> group.id().equals(id)).findFirst();
    }

    /**
     * 创建一个覆盖 [start, end] 的新分组，范围内已有的分组会被合并进去。
     */
    public static synchronized void createGroup(UUID pageId, int start, int end) {
        List<FavoriteGroup> groups = groups(pageId);
        groups.removeIf(group -> start <= group.endIndex() && end >= group.startIndex());
        groups.add(new FavoriteGroup(UUID.randomUUID(), start, end));
        groups.sort(Comparator.comparingInt(FavoriteGroup::startIndex));
        save();
    }

    public static synchronized void removeGroup(UUID pageId, UUID groupId) {
        if (groups(pageId).removeIf(group -> group.id().equals(groupId))) {
            save();
        }
    }

    /**
     * 折叠隐藏标记，下标即收藏索引。返回的是缓存内数组，调用方不得修改。
     */
    public static synchronized boolean[] hiddenFlags(UUID pageId) {
        return layout(pageId).hidden;
    }

    public static synchronized void setLayoutMap(int[] visibleToOutput, int[] outputToVisible) {
        GroupManager.visibleToOutput = visibleToOutput;
        GroupManager.outputToVisible = outputToVisible;
    }

    /**
     * 收藏索引 → 折叠后的下标（只去掉被折叠隐藏的条目，不含换行填充）。
     *
     * <p>原先每调用一次都要重建 {@code HashSet} 再线性扫到目标下标，而它在边框绘制里
     * 每个分组、每帧都会被调用多次。现在改成查预构建的前缀表，O(1)。</p>
     */
    public static synchronized int toFoldedIndex(UUID pageId, int index) {
        if (index < 0) {
            return index;
        }
        Layout layout = layout(pageId);
        if (index <= layout.total) {
            return layout.foldedPrefix[index];
        }
        // 越界时保持原有语义：只统计到分页末尾为止的隐藏项。
        return index - (layout.total - layout.foldedPrefix[layout.total]);
    }

    public static synchronized int toVisibleIndex(UUID pageId, int index) {
        int folded = toFoldedIndex(pageId, index);
        return folded >= 0 && folded < visibleToOutput.length ? visibleToOutput[folded] : folded;
    }

    /**
     * 收藏栏显示位置 → 收藏索引，落在填充位上时返回 -1。
     */
    public static synchronized int toOriginalIndex(UUID pageId, int visibleIndex) {
        int folded = visibleIndex >= 0 && visibleIndex < outputToVisible.length ? outputToVisible[visibleIndex] : -1;
        if (folded < 0) {
            return -1;
        }
        Layout layout = layout(pageId);
        return folded < layout.foldedToOriginal.length ? layout.foldedToOriginal[folded] : -1;
    }

    // ------------------------------------------------------------------ 布局缓存

    private static Layout cachedLayout;

    /**
     * 一个分页在某个布局版本下的折叠映射。所有字段都是纯派生数据，版本号一变即整体重建。
     */
    private static final class Layout {
        final UUID page;
        final long version;
        final int total;
        /** 是否被折叠隐藏，下标 = 收藏索引。 */
        final boolean[] hidden;
        /** 前缀可见数：{@code foldedPrefix[i]} = 索引 [0, i) 中未被隐藏的条目数。长度 total + 1。 */
        final int[] foldedPrefix;
        /** 折叠后下标 → 收藏索引。 */
        final int[] foldedToOriginal;

        Layout(UUID page, long version, int total, boolean[] hidden, int[] foldedPrefix, int[] foldedToOriginal) {
            this.page = page;
            this.version = version;
            this.total = total;
            this.hidden = hidden;
            this.foldedPrefix = foldedPrefix;
            this.foldedToOriginal = foldedToOriginal;
        }
    }

    /**
     * 取（并在必要时构建）指定分页在当前布局版本下的折叠映射。
     */
    private static synchronized Layout layout(UUID pageId) {
        long version = LayoutVersion.current();
        Layout cached = cachedLayout;
        if (cached != null && cached.version == version
                && (cached.page == null ? pageId == null : cached.page.equals(pageId))) {
            return cached;
        }
        BookmarkPage page = pageId == null ? null : BookmarkPages.pageById(pageId);
        if (page == null) {
            page = BookmarkPages.current();
        }
        int total = page.bookmarkKeys().size();
        boolean[] hidden = new boolean[total];
        for (FavoriteGroup group : page.groups()) {
            if (!group.folded() || group.size() <= 1) {
                continue;
            }
            int to = Math.min(group.endIndex(), total - 1);
            for (int i = Math.max(group.startIndex() + 1, 0); i <= to; i++) {
                hidden[i] = true;
            }
        }
        int[] foldedPrefix = new int[total + 1];
        for (int i = 0; i < total; i++) {
            foldedPrefix[i + 1] = foldedPrefix[i] + (hidden[i] ? 0 : 1);
        }
        int[] foldedToOriginal = new int[foldedPrefix[total]];
        int cursor = 0;
        for (int i = 0; i < total; i++) {
            if (!hidden[i]) {
                foldedToOriginal[cursor++] = i;
            }
        }
        Layout layout = new Layout(pageId, version, total, hidden, foldedPrefix, foldedToOriginal);
        cachedLayout = layout;
        return layout;
    }

    /**
     * 移动单个条目后修正分组边界：先按移除处理，再按插入处理。
     */
    public static synchronized void moveItem(UUID pageId, int from, int to) {
        BookmarkPages.moveKey(from, to);
        List<FavoriteGroup> groups = groups(pageId);
        adjustOnRemove(groups, from);
        adjustOnInsert(groups, to);
        save();
    }

    /**
     * 整体移动一个分组，插入点 newStart 以移动前的索引为准。
     */
    public static synchronized void moveGroup(UUID pageId, UUID groupId, int newStart) {
        Optional<FavoriteGroup> optional = byId(pageId, groupId);
        if (optional.isEmpty()) {
            return;
        }
        FavoriteGroup group = optional.get();
        int size = group.size();
        int insertAt = newStart > group.startIndex() ? newStart - size : newStart;
        insertAt = Math.max(0, Math.min(insertAt, BookmarkPages.currentItemCount() - size));
        if (insertAt == group.startIndex()) {
            return;
        }
        BookmarkPages.moveKeyRange(group.startIndex(), size, insertAt);
        List<FavoriteGroup> groups = groups(pageId);
        groups.remove(group);
        for (int i = 0; i < size; i++) {
            adjustOnRemove(groups, group.startIndex());
        }
        for (int i = 0; i < size; i++) {
            adjustOnInsert(groups, insertAt + i);
        }
        group.startIndex(insertAt);
        group.endIndex(insertAt + size - 1);
        groups.add(group);
        groups.sort(Comparator.comparingInt(FavoriteGroup::startIndex));
        // 落点与别的分组重叠时合并为一个分组，并以展开状态呈现。
        FavoriteGroup overlapped = overlapping(groups, group);
        if (overlapped != null) {
            int start = Math.min(group.startIndex(), overlapped.startIndex());
            int end = Math.max(group.endIndex(), overlapped.endIndex());
            groups.remove(group);
            groups.remove(overlapped);
            FavoriteGroup merged = new FavoriteGroup(UUID.randomUUID(), start, end);
            merged.lineBreak(group.lineBreak() || overlapped.lineBreak());
            groups.add(merged);
            groups.sort(Comparator.comparingInt(FavoriteGroup::startIndex));
        }
        save();
    }

    /**
     * 把 index 处的收藏移出它所在的分组，放到该分组后面。
     * 收纳中的分组不参与，分组只剩这一个条目时直接删掉分组标记。
     */
    public static synchronized void extractFromGroup(UUID pageId, int index) {
        Optional<FavoriteGroup> optional = containing(pageId, index);
        if (optional.isEmpty() || optional.get().folded()) {
            return;
        }
        FavoriteGroup group = optional.get();
        if (group.size() <= 1) {
            removeGroup(pageId, group.id());
            return;
        }
        // 移到组尾（移动后原位置空出，下标正好落到该分组之后），等价于移动单个条目的通用逻辑。
        moveItem(pageId, index, group.endIndex());
    }

    private static FavoriteGroup overlapping(List<FavoriteGroup> groups, FavoriteGroup target) {
        for (FavoriteGroup group : groups) {
            if (group != target && target.startIndex() <= group.endIndex() && target.endIndex() >= group.startIndex()) {
                return group;
            }
        }
        return null;
    }

    /**
     * 删除分组及其包含的所有条目。
     */
    public static synchronized void removeGroupWithItems(UUID pageId, UUID groupId) {
        Optional<FavoriteGroup> optional = byId(pageId, groupId);
        if (optional.isEmpty()) {
            return;
        }
        FavoriteGroup group = optional.get();
        removeGroup(pageId, groupId);
        BookmarkPages.removeRange(group.startIndex(), group.endIndex());
    }

    /**
     * 某个条目被移出时修正分组边界（<b>不加锁、不落盘</b>）。
     *
     * <p>只给 {@code BookmarkPages} 在持有自身锁时调用：它需要就地把分组边界跟着条目一起改掉，
     * 而这里如果再进 {@code GroupManager} 的锁、再回调 {@code BookmarkPages}（{@code save()} 里要读
     * 分页），就会出现 {@code BookmarkPages → GroupManager} 与 {@code GroupManager → BookmarkPages}
     * 两条方向相反的加锁路径。留成无锁工具方法后，锁序只剩一个方向。</p>
     */
    public static void adjustOnRemove(List<FavoriteGroup> groups, int index) {
        for (Iterator<FavoriteGroup> iterator = groups.iterator(); iterator.hasNext(); ) {
            FavoriteGroup group = iterator.next();
            if (group.contains(index)) {
                group.endIndex(group.endIndex() - 1);
                if (group.startIndex() > group.endIndex()) {
                    iterator.remove();
                }
            } else if (index < group.startIndex()) {
                group.startIndex(group.startIndex() - 1);
                group.endIndex(group.endIndex() - 1);
            }
        }
    }

    /** 某个条目被插入时修正分组边界（<b>不加锁、不落盘</b>，同上）。 */
    public static void adjustOnInsert(List<FavoriteGroup> groups, int index) {
        for (FavoriteGroup group : groups) {
            if (index <= group.startIndex()) {
                group.startIndex(group.startIndex() + 1);
                group.endIndex(group.endIndex() + 1);
            } else if (index <= group.endIndex()) {
                group.endIndex(group.endIndex() + 1);
            }
        }
    }

    public static int selectionAnchor() {
        return selectionAnchor;
    }

    public static void setSelectionAnchor(int index) {
        selectionAnchor = index;
    }

    public static void clearSelectionAnchor() {
        selectionAnchor = -1;
    }

    public static UUID dragGroupId() {
        return dragGroupId;
    }

    public static void setDragGroupId(UUID id) {
        dragGroupId = id;
    }
}
