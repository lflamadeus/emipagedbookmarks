package com.lai.emipagedbookmarks.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.lai.emipagedbookmarks.client.BookmarkPages;
import com.lai.emipagedbookmarks.client.LayoutVersion;
import com.lai.emipagedbookmarks.client.group.FavoriteGroup;
import com.lai.emipagedbookmarks.client.group.GroupManager;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.config.SidebarType;
import dev.emi.emi.screen.EmiScreenManager.ScreenSpace;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在收藏栏上应用分组效果：折叠分组只保留组内第一个条目，
 * 换行分组前后补空位使其独占整行。
 *
 * <p>EMI 每帧会从多处调用 {@code getStacks()}（hover 判定、翻页、滚动、渲染主循环），
 * 而结果只随「分页 / 布局版本 / 每行列数 / 临时收藏条数」变化。因此这里按这四个条件
 * 缓存整页排布，命中时直接返回上次构建的列表与下标映射，不再重建 —— 这是收藏越多
 * 越占显卡那条曲线的主要来源（原先每次调用都要对全部收藏重建 HashSet 并逐项装箱）。</p>
 */
@Mixin(ScreenSpace.class)
public class ScreenSpaceMixin {
    @Inject(method = "getStacks()Ljava/util/List;", at = @At("RETURN"), cancellable = true)
    private void emipagedbookmarks$applyGroupLayout(CallbackInfoReturnable<List<? extends EmiIngredient>> callbackInfo) {
        ScreenSpace space = (ScreenSpace) (Object) this;
        // 搜索侧栏自己也有 type（isSearch() = side == EmiConfig.searchSidebar）。如果用户把
        // ui.search-sidebar-focus 设成 favorites，搜索栏会是「search && type == FAVORITES」——
        // 不排掉的话我们会把搜索结果整个换成整页排布，搜索就"没反应"了。
        if (space.getType() != SidebarType.FAVORITES || space.search) {
            return;
        }
        // tw 是网格列数（final），pageSize 是各行实际可用格数之和 —— 界面遮罩变化时
        // pageSize 会变而 tw 不变，两个一起做 key 才能保证排布缓存不会用错。
        int perRow = Math.max(1, space.tw);
        int pageSize = space.pageSize;
        UUID pageId = BookmarkPages.currentId();
        int synthetic = BookmarkPages.syntheticFavorites().size();
        long version = LayoutVersion.current();

        synchronized (LayoutCache.LOCK) {
            if (LayoutCache.cachedPage != null && LayoutCache.cachedPage.equals(pageId)
                    && LayoutCache.cachedPerRow == perRow
                    && LayoutCache.cachedPageSize == pageSize && LayoutCache.cachedVersion == version
                    && LayoutCache.cachedSynthetic == synthetic) {
                // 下标映射是全局的，可能已被别的分页的构建覆盖，命中时一并复位。
                GroupManager.setLayoutMap(LayoutCache.cachedVisibleToOutput, LayoutCache.cachedOutputToVisible);
                callbackInfo.setReturnValue(LayoutCache.cachedStacks);
                return;
            }
        }

        List<? extends EmiIngredient> items = callbackInfo.getReturnValue();
        boolean[] hidden = GroupManager.hiddenFlags(pageId);
        List<FavoriteGroup> groups = GroupManager.groups(pageId);

        List<EmiIngredient> folded = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            if (i >= hidden.length || !hidden[i]) {
                folded.add(items.get(i));
            }
        }

        // 换行分组的 [起点, 终点] 折叠后区间，数量与分组数同阶（通常个位数）。
        int[] breakStart = new int[groups.size()];
        int[] breakEnd = new int[groups.size()];
        int breakCount = 0;
        for (FavoriteGroup group : groups) {
            // 收纳起来的分组按普通物品排布，不再独占整行。
            if (!group.lineBreak() || group.folded()) {
                continue;
            }
            int start = GroupManager.toFoldedIndex(pageId, group.startIndex());
            int end = GroupManager.toFoldedIndex(pageId, group.endIndex());
            if (start >= 0 && start <= end && end < folded.size()) {
                breakStart[breakCount] = start;
                breakEnd[breakCount] = end;
                breakCount++;
            }
        }

        List<EmiIngredient> output = new ArrayList<>(folded.size() + breakCount * perRow);
        IntList outputToVisible = new IntList(folded.size());
        int[] visibleToOutput = new int[folded.size()];
        int column = 0;
        int index = 0;
        while (index < folded.size()) {
            int end = index;
            boolean lineBreak = false;
            for (int i = 0; i < breakCount; i++) {
                if (breakStart[i] == index) {
                    end = breakEnd[i];
                    lineBreak = true;
                    break;
                }
            }
            if (lineBreak) {
                outputToVisible.padRowTo(column, perRow, output);
                column = 0;
            }
            for (int i = index; i <= end; i++) {
                visibleToOutput[i] = output.size();
                outputToVisible.add(i);
                output.add(folded.get(i));
                column = (column + 1) % perRow;
            }
            if (lineBreak) {
                outputToVisible.padRowTo(column, perRow, output);
                column = 0;
            }
            index = end + 1;
        }

        int[] outputToVisibleArray = outputToVisible.toArray();
        GroupManager.setLayoutMap(visibleToOutput, outputToVisibleArray);
        output.addAll(BookmarkPages.syntheticFavorites());
        // 存进缓存的是不可变副本：返回给 EMI 的列表被外部改动也不会污染缓存。
        List<EmiIngredient> result = List.copyOf(output);

        synchronized (LayoutCache.LOCK) {
            LayoutCache.cachedPage = pageId;
            LayoutCache.cachedPerRow = perRow;
            LayoutCache.cachedPageSize = pageSize;
            LayoutCache.cachedVersion = version;
            LayoutCache.cachedSynthetic = synthetic;
            LayoutCache.cachedStacks = result;
            LayoutCache.cachedVisibleToOutput = visibleToOutput;
            LayoutCache.cachedOutputToVisible = outputToVisibleArray;
        }
        callbackInfo.setReturnValue(result);
    }

    /**
     * 整页排布缓存的全部状态。
     *
     * <p>特意放进嵌套类：Mixin 会把 mixin 类<b>新增的成员</b>灌注进目标类，所以直接写在
     * {@code ScreenSpaceMixin} 里的字段必须带 {@code @Unique}、名字里还得有 {@code _} 或 {@code $}，
     * 否则既可能与 EMI 自己的成员撞名，IDEA 的 Mixin 检查也会一直报
     * "Missing @Unique annotation" / "Name does not match the pattern for added mixin members"。
     * 放进嵌套类后它们属于另一个类，既不污染 {@code ScreenSpace}，也不必为检查而改名。</p>
     */
    private static final class LayoutCache {
        /** 缓存命中与重建共用同一把锁（渲染线程单线程访问，但保证可见性）。 */
        private static final Object LOCK = new Object();
        private static UUID cachedPage;
        private static int cachedPerRow = -1;
        private static int cachedPageSize = -1;
        private static long cachedVersion = Long.MIN_VALUE;
        private static int cachedSynthetic = -1;
        private static List<EmiIngredient> cachedStacks = List.of();
        private static int[] cachedVisibleToOutput = new int[0];
        private static int[] cachedOutputToVisible = new int[0];
    }

    /**
     * 避免 {@code ArrayList<Integer>} 逐项装箱的极简 int 列表（结果会被缓存，只用于构建期）。
     */
    private static final class IntList {
        private int[] data;
        private int size;

        IntList(int capacity) {
            this.data = new int[Math.max(8, capacity)];
        }

        void add(int value) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size * 2);
            }
            data[size++] = value;
        }

        int[] toArray() {
            return Arrays.copyOf(data, size);
        }

        /**
         * 把当前行补满到 {@code perRow} 格：本表补 -1（占位，不指向任何收藏），
         * 并行的物品表补一个空气堆，让换行分组从下一行开头开始排。
         */
        void padRowTo(int column, int perRow, List<EmiIngredient> output) {
            for (int i = column; i < perRow; i++) {
                add(-1);
                output.add(EmiStack.of(ItemStack.EMPTY));
            }
        }
    }
}
