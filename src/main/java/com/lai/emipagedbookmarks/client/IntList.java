package com.lai.emipagedbookmarks.client;

import java.util.Arrays;

/**
 * 避免 {@code ArrayList<Integer>} 逐项装箱的极简 int 列表（结果会被缓存，只用于构建期）。
 *
 * <p>放在这里而不是 {@code ScreenSpaceMixin} 的嵌套类里，原因是嵌套类仍在 mixin 包下、
 * 被 Mixin 禁止跨包引用（详见 {@link LayoutCache} 的说明）。</p>
 *
 * <p>刻意不引用 EMI / Minecraft 类型：排布算法（{@link FavoriteLayout}）因此可以脱离游戏
 * 单独编译验证。</p>
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

    public int size() {
        return size;
    }

    public int[] toArray() {
        return Arrays.copyOf(data, size);
    }
}
