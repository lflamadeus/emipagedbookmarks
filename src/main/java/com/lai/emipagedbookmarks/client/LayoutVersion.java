package com.lai.emipagedbookmarks.client;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 收藏栏布局版本号。
 *
 * <p>EMI 每帧会多次调用 {@code ScreenSpace.getStacks()}（hover 判定、翻页、滚动、
 * 渲染主循环等），而收藏分区的排布只会在「用户真的改了东西」时变化。这里用一个全局
 * 递增的版本号作为缓存的失效信号：任何会改变分页、收藏顺序、分组归属或折叠状态的
 * 操作都调用 {@link #bump()}，各层缓存（收藏列表、折叠映射、整页排布）随之一次性失效。</p>
 *
 * <p>不做细粒度分 key 的版本号是刻意的：变更本身是低频的用户操作，全量失效的代价
 * 远小于维护多套版本号带来的出错面。</p>
 */
public final class LayoutVersion {
    private static final AtomicLong VERSION = new AtomicLong();

    private LayoutVersion() {
    }

    /** 让所有布局缓存在下一次读取时重建。 */
    public static void bump() {
        VERSION.incrementAndGet();
    }

    public static long current() {
        return VERSION.get();
    }
}
