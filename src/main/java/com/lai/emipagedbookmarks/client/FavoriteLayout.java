package com.lai.emipagedbookmarks.client;

/**
 * 收藏栏「整页排布」的纯算法：把折叠后的条目按 EMI 的<b>实际行宽</b>铺开，
 * 并让换行（独占整行）分组前后各补满当前行。
 *
 * <h2>为什么必须按行宽算，而不是按每行列数取模</h2>
 *
 * <p>{@code ScreenSpace.widths} 记的是每一行<b>真正可用</b>的格数：被界面遮罩（EMI 搜索栏、
 * 按钮栏、其它模组注册的排除区）切掉的行会比 {@code tw} 短，甚至整行为 0。EMI 渲染时就是按这个
 * 剖面逐行填的，所以「补满当前行」也得按同一个剖面来算。</p>
 *
 * <p>原先按 {@code tw} 取模（{@code column = (column + 1) % tw}）会在两处算错，而且都表现为
 * <b>两行之间凭空多出一行空白</b>：</p>
 * <ul>
 *   <li>换行分组正好排在行首时，行首本来就不需要补位，却按「补满一整行」补了 {@code tw} 格；</li>
 *   <li>换行分组正好排满整行时，行尾的补位同理补出一整行空白。</li>
 * </ul>
 *
 * <p>现在补位统一走 {@link #padToRowEnd}：它补的是<b>上一个格子所在行</b>剩下的部分，偏移正好
 * 落在行首时一格都不补。于是「换行分组独占整行」严格成立 —— 组从行首开始、到行尾结束，前后不多
 * 空行，被遮罩截短的行也不会被补过头。</p>
 *
 * <p>本类不引用任何 EMI / Minecraft 类型，可以脱离游戏单独编译、用脚本核对不变量
 * （{@code temp/} 下有对拍脚本）；与 EMI 打交道的那部分留在 {@code ScreenSpaceMixin}。</p>
 */
public final class FavoriteLayout {
    /** 占位格：不属于任何收藏，EMI 侧填一个空气堆。 */
    public static final int PADDING = -1;

    private FavoriteLayout() {
    }

    /**
     * 整页排布结果。
     *
     * @param outputToVisible 显示位置 → 折叠后下标（占位格为 {@link #PADDING}），长度即整页排布长度
     * @param visibleToOutput 折叠后下标 → 显示位置
     */
    public record Result(int[] outputToVisible, int[] visibleToOutput) {
    }

    /**
     * 排布一页收藏：普通条目按顺序填格，换行分组前后各补满当前行。
     *
     * @param rowWidths  每行可用格数（即 {@code ScreenSpace.widths}），长度是行数
     * @param itemCount  折叠后剩下的条目数
     * @param breakStart 换行分组的起点（折叠后下标），与 {@code breakEnd} 一一对应
     * @param breakEnd   换行分组的终点（含）
     * @param breakCount {@code breakStart} / {@code breakEnd} 里有效项的个数
     */
    public static Result build(int[] rowWidths, int itemCount, int[] breakStart, int[] breakEnd, int breakCount) {
        IntList outputToVisible = new IntList(itemCount + rowWidths.length);
        int[] visibleToOutput = new int[itemCount];
        int pageSize = pageSize(rowWidths);

        int index = 0;
        while (index < itemCount) {
            // 以 index 开头的换行分组（分组之间不重叠，所以最多命中一个）。
            int end = index;
            boolean exclusive = false;
            for (int i = 0; i < breakCount; i++) {
                if (breakStart[i] == index) {
                    end = Math.min(breakEnd[i], itemCount - 1);
                    exclusive = true;
                    break;
                }
            }
            if (exclusive) {
                padToRowEnd(outputToVisible, rowWidths, pageSize);
            }
            for (int i = index; i <= end; i++) {
                visibleToOutput[i] = outputToVisible.size();
                outputToVisible.add(i);
            }
            if (exclusive) {
                padToRowEnd(outputToVisible, rowWidths, pageSize);
            }
            index = end + 1;
        }
        return new Result(outputToVisible.toArray(), visibleToOutput);
    }

    /**
     * 把当前行剩下的格子补满，让下一个条目从下一行行首开始。
     *
     * <p><b>补的是「上一个格子所在行」剩下的部分</b>，而不是「偏移所在行」：偏移正好落在行首时，
     * 它已经在<b>下一行</b>了，整行都不该补 —— 那正是原先多出一整行空白的根源。页内偏移为 0
     * （本页还没有格子，上一格在上一页的末行）时同样不用补。</p>
     *
     * <p>排布按页循环（每页的行结构完全相同），所以页内偏移 = 总偏移对 {@code pageSize} 取模。</p>
     */
    private static void padToRowEnd(IntList outputToVisible, int[] rowWidths, int pageSize) {
        if (pageSize <= 0) {
            return;
        }
        int offset = outputToVisible.size() % pageSize;
        if (offset == 0) {
            return;
        }
        // 上一个格子必定落在 [0, pageSize) 内，它所在行的末尾就是下一个行首。
        int end = rowEnd(rowWidths, offset - 1);
        for (int i = offset; i < end; i++) {
            outputToVisible.add(PADDING);
        }
    }

    /**
     * 页内偏移 {@code offset} 所在行的末尾（即下一个行首）。
     *
     * <p>0 宽的行会被跳过：落在这种行上的偏移属于它后面第一个非空行。偏移已经在页尾时返回
     * {@code pageSize}，调用方因此补 0 格。</p>
     */
    private static int rowEnd(int[] rowWidths, int offset) {
        int end = 0;
        for (int width : rowWidths) {
            end += width;
            if (offset < end) {
                return end;
            }
        }
        return end;
    }

    /** 一页的总格数（即 {@code ScreenSpace.pageSize}）。 */
    private static int pageSize(int[] rowWidths) {
        int total = 0;
        for (int width : rowWidths) {
            total += width;
        }
        return total;
    }
}
