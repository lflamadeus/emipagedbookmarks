package com.lai.emipagedbookmarks.client;

import java.util.Arrays;
import java.util.List;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.world.item.ItemStack;

/**
 * 避免 {@code ArrayList<Integer>} 逐项装箱的极简 int 列表（结果会被缓存，只用于构建期）。
 *
 * <p>放在这里而不是 {@code ScreenSpaceMixin} 的嵌套类里，原因是嵌套类仍在 mixin 包下、
 * 被 Mixin 禁止跨包引用（详见 {@link LayoutCache} 的说明）。</p>
 */
public final class IntList {
    private int[] data;
    private int size;

    public IntList(int capacity) {
        this.data = new int[Math.max(8, capacity)];
    }

    public void add(int value) {
        if (size == data.length) {
            data = Arrays.copyOf(data, size * 2);
        }
        data[size++] = value;
    }

    public int[] toArray() {
        return Arrays.copyOf(data, size);
    }

    /**
     * 把当前行补满到 {@code perRow} 格：本表补 -1（占位，不指向任何收藏），
     * 并行的物品表补一个空气堆，让换行分组从下一行开头开始排。
     */
    public void padRowTo(int column, int perRow, List<EmiIngredient> output) {
        for (int i = column; i < perRow; i++) {
            add(-1);
            output.add(EmiStack.of(ItemStack.EMPTY));
        }
    }
}
