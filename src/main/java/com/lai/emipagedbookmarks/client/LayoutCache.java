package com.lai.emipagedbookmarks.client;

import java.util.List;
import java.util.UUID;

import dev.emi.emi.api.stack.EmiIngredient;

/**
 * {@code ScreenSpaceMixin} 里「整页排布缓存」的全部状态。
 *
 * <p>EMI 每帧会从多处调用 {@code ScreenSpace.getStacks()}（hover 判定、翻页、滚动、渲染主循环），
 * 而结果只随「分页 / 布局版本 / 行宽剖面 / 临时收藏条数」变化，所以整页排布按这四项缓存。
 * 行宽剖面（{@code ScreenSpace.widths} 的内容）同时决定了每行列数与每页容量，界面遮罩一变它就变，
 * 因此不必再单独记 {@code tw} 与 {@code pageSize}。
 * 缓存逻辑留在 mixin 里（它要拦截 {@code getStacks}），状态放在这里。</p>
 *
 * <h2>⚠️ 为什么不能写成 mixin 的嵌套类</h2>
 *
 * <p>先前试过把状态塞进 {@code ScreenSpaceMixin} 的 <b>嵌套静态类</b>，思路是「嵌套类不算 mixin
 * 新增成员，所以不必 {@code @Unique}、不用改名」。<b>这个思路是错的，会导致实机崩溃</b>：</p>
 *
 * <pre>
 * IllegalClassLoadError: com.lai.emipagedbookmarks.mixin.ScreenSpaceMixin$LayoutCache
 *   is in a defined mixin package com.lai.emipagedbookmarks.mixin.*
 *   owned by emipagedbookmarks.mixins.json and cannot be referenced directly
 * </pre>
 *
 * <p>原因：{@code mixins.json} 声明了 {@code "package": "...mixin"}，该包下的<b>任何类</b>
 * （含嵌套类）都被 Mixin 游戏包管理器接管，禁止被 mixin 包之外的代码直接引用。而注入的方法体
 * 会被<b>合并进目标类</b> {@code EmiScreenManager$ScreenSpace}，它位于 EMI 的包里 —— 于是方法体里
 * 对 {@code LayoutCache} 的引用就成了一次「外部直接引用」，加载时直接抛异常。</p>
 *
 * <p>所以放在普通包（{@code client}）里才是正解：它既不是 mixin 包、也不是 {@code @Mixin} 类，
 * 因此不是「会被灌注进目标类的成员」，不需要 {@code @Unique}，也不会有包可见性限制。</p>
 *
 * <p><b>结论：mixin 包下只能有 {@code @Mixin} 类本身，任何被普通代码或注入方法体引用的辅助类型
 * 都必须放到别的包里。</b></p>
 */
public final class LayoutCache {
    /** 缓存命中与重建共用同一把锁（渲染线程单线程访问，但保证可见性）。 */
    public static final Object LOCK = new Object();
    public static UUID cachedPage;
    /** 上一次排布用的行宽剖面（{@code ScreenSpace.widths} 的副本），逐项相等才复用缓存。 */
    public static int[] cachedRowWidths = new int[0];
    public static long cachedVersion = Long.MIN_VALUE;
    public static int cachedSynthetic = -1;
    public static List<EmiIngredient> cachedStacks = List.of();
    public static int[] cachedVisibleToOutput = new int[0];
    public static int[] cachedOutputToVisible = new int[0];

    private LayoutCache() {
    }
}
