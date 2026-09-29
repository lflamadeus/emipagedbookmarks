package com.lai.emipagedbookmarks.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.lai.emipagedbookmarks.client.BookmarkPages;
import com.lai.emipagedbookmarks.client.FavoriteLayout;
import com.lai.emipagedbookmarks.client.LayoutCache;
import com.lai.emipagedbookmarks.client.LayoutVersion;
import com.lai.emipagedbookmarks.client.group.FavoriteGroup;
import com.lai.emipagedbookmarks.client.group.GroupManager;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.config.SidebarType;
import dev.emi.emi.screen.EmiScreenManager.ScreenSpace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在收藏栏上应用分组效果：折叠分组只保留组内第一个条目，
 * 换行分组前后补空位使其独占整行。
 *
 * <p>排布本身是纯算法，放在 {@link FavoriteLayout}（它按 {@code ScreenSpace.widths} 逐行补位，
 * 行首 / 行尾不会再补出整行空白）；这里只负责把 EMI 的收藏列表、分组数据和缓存串起来。</p>
 *
 * <p>EMI 每帧会从多处调用 {@code getStacks()}（hover 判定、翻页、滚动、渲染主循环），
 * 而结果只随「分页 / 布局版本 / 行宽剖面 / 临时收藏条数」变化。因此这里按这四项缓存整页排布，
 * 命中时直接返回上次构建的列表与下标映射，不再重建 —— 这是收藏越多越占显卡那条曲线的主要来源
 * （原先每次调用都要对全部收藏重建 HashSet 并逐项装箱）。</p>
 *
 * <p><b>辅助类型一律不放在本包。</b>{@code mixins.json} 声明的 {@code package} 下的<b>任何类</b>
 * （含嵌套类）都归 Mixin 游戏包管理器管，<b>不允许被该包之外的代码直接引用</b>。
 * 而本 mixin 的方法体会被合并进目标类 {@code EmiScreenManager$ScreenSpace}（EMI 的包），
 * 方法体里对嵌套类的引用就成了跨包引用，加载时抛
 * {@code IllegalClassLoadError: ... is in a defined mixin package ... and cannot be referenced directly}。
 * 因此排布缓存的状态（{@link com.lai.emipagedbookmarks.client.LayoutCache}）、
 * 排布算法（{@link FavoriteLayout}）与它用的极简 int 列表
 * （{@link com.lai.emipagedbookmarks.client.IntList}）都放在 {@code client} 包 ——
 * 本包下只能有 {@code @Mixin} 类本身。</p>
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
        // 行宽剖面（widths 的内容）同时决定了每行列数与每页容量：界面遮罩一变，这个剖面就跟着变，
        // 所以缓存键里记它一个就够，不必再单独记 tw / pageSize。
        int[] rowWidths = space.widths;
        UUID pageId = BookmarkPages.currentId();
        int synthetic = BookmarkPages.syntheticFavorites().size();
        long version = LayoutVersion.current();

        synchronized (LayoutCache.LOCK) {
            if (LayoutCache.cachedPage != null && LayoutCache.cachedPage.equals(pageId)
                    && Arrays.equals(LayoutCache.cachedRowWidths, rowWidths)
                    && LayoutCache.cachedVersion == version && LayoutCache.cachedSynthetic == synthetic) {
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

        FavoriteLayout.Result layout = FavoriteLayout.build(rowWidths, folded.size(), breakStart, breakEnd, breakCount);
        int[] outputToVisible = layout.outputToVisible();
        List<EmiIngredient> output = new ArrayList<>(outputToVisible.length + synthetic);
        for (int visible : outputToVisible) {
            // 占位格用 EMI 的空气堆：它是共享单例，EMI 对它不渲染也不响应 hover。
            output.add(visible == FavoriteLayout.PADDING ? EmiStack.EMPTY : folded.get(visible));
        }

        GroupManager.setLayoutMap(layout.visibleToOutput(), outputToVisible);
        output.addAll(BookmarkPages.syntheticFavorites());
        // 存进缓存的是不可变副本：返回给 EMI 的列表被外部改动也不会污染缓存。
        List<EmiIngredient> result = List.copyOf(output);

        synchronized (LayoutCache.LOCK) {
            LayoutCache.cachedPage = pageId;
            LayoutCache.cachedRowWidths = Arrays.copyOf(rowWidths, rowWidths.length);
            LayoutCache.cachedVersion = version;
            LayoutCache.cachedSynthetic = synthetic;
            LayoutCache.cachedStacks = result;
            LayoutCache.cachedVisibleToOutput = layout.visibleToOutput();
            LayoutCache.cachedOutputToVisible = outputToVisible;
        }
        callbackInfo.setReturnValue(result);
    }
}
