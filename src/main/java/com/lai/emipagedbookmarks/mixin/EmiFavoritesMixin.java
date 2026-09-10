package com.lai.emipagedbookmarks.mixin;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.lai.emipagedbookmarks.client.BookmarkPages;
import com.lai.emipagedbookmarks.client.LayoutVersion;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.runtime.EmiFavorites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EmiFavorites.class)
public abstract class EmiFavoritesMixin {
    /**
     * 注入点的方法描述符。
     *
     * <p>这些常量、下面的快照状态、以及两个快照辅助方法，都是<b>会被加到目标类上的成员</b>。
     * Mixin 要求这类成员带 {@code @Unique}（名字里含 {@code _} 或 {@code $} 也不冲突），
     * 否则可能和 EMI 自己的成员撞名，IDEA 的 Mixin 检查也会一直报 Missing @Unique annotation。</p>
     */
    @Unique
    private static final String ADD_FAVORITE =
            "addFavorite(Ldev/emi/emi/api/stack/EmiIngredient;Ldev/emi/emi/api/recipe/EmiRecipe;)V";
    @Unique
    private static final String ADD_FAVORITE_AT =
            "addFavoriteAt(Ldev/emi/emi/api/stack/EmiIngredient;I)V";
    @Unique
    private static final String REMOVE_FAVORITE =
            "removeFavorite(Ldev/emi/emi/api/stack/EmiIngredient;)Z";
    @Unique
    private static final String LOAD_JSON = "load(Lcom/google/gson/JsonArray;)V";
    @Unique
    private static final String UPDATE_SYNTHETIC =
            "updateSynthetic(Ldev/emi/emi/api/recipe/EmiPlayerInventory;)V";

    /** 调用前快照，按线程暂存，由对应的 RETURN 注入点取走。 */
    @Unique
    private static final ThreadLocal<List<EmiFavorite>> PENDING_SNAPSHOT = new ThreadLocal<>();

    // 单参版本内部会转调两参版本，因此只需要拦截两参版本即可覆盖全部新增路径。
    @Inject(method = ADD_FAVORITE, at = @At("HEAD"), cancellable = true)
    private static void emipagedbookmarks$beforeAdd(EmiIngredient stack, EmiRecipe recipe, CallbackInfo callbackInfo) {
        if (BookmarkPages.isBatching()) {
            // 批量导入自己会按文件里记录的键值归位，不必每条都做一次全表快照（那是 O(N) 分配）。
            return;
        }
        PENDING_SNAPSHOT.set(emipagedbookmarks$snapshot());
        // 取消收藏时，如果其他分页仍然收藏着它，就只从当前分页摘掉，不动 EMI 的收藏表。
        if (stack instanceof EmiFavorite favorite && BookmarkPages.detachFromCurrentPageOnly(favorite)) {
            PENDING_SNAPSHOT.remove();
            callbackInfo.cancel();
        }
    }

    @Inject(method = ADD_FAVORITE, at = @At("RETURN"))
    private static void emipagedbookmarks$afterAdd(EmiIngredient stack, EmiRecipe recipe, CallbackInfo callbackInfo) {
        List<EmiFavorite> before = emipagedbookmarks$takeSnapshot();
        if (BookmarkPages.isBatching()) {
            return;
        }
        BookmarkPages.applyAdd(stack, recipe, before);
    }

    @Inject(method = ADD_FAVORITE_AT, at = @At("HEAD"))
    private static void emipagedbookmarks$beforeInsert(EmiIngredient stack, int offset, CallbackInfo callbackInfo) {
        if (BookmarkPages.isBatching()) {
            return;
        }
        PENDING_SNAPSHOT.set(emipagedbookmarks$snapshot());
    }

    @Inject(method = ADD_FAVORITE_AT, at = @At("RETURN"))
    private static void emipagedbookmarks$afterInsert(EmiIngredient stack, int offset, CallbackInfo callbackInfo) {
        List<EmiFavorite> before = emipagedbookmarks$takeSnapshot();
        if (BookmarkPages.isBatching()) {
            return;
        }
        BookmarkPages.applyAdd(stack, null, before);
    }

    /**
     * EMI 的 {@code addFavorite} / {@code addFavoriteAt} 末尾各有一句 {@code EmiPersistentData.save()}，
     * 也就是每加一条收藏就写一次 EMI 的 favorites.json。导入几百上千条时这是最大的一笔开销，
     * 这里改成走 {@link BookmarkPages#saveEmiData()}：批量模式下攒着，最后统一写一次。
     */
    @Redirect(method = { ADD_FAVORITE, ADD_FAVORITE_AT },
            at = @At(value = "INVOKE", target = "Ldev/emi/emi/runtime/EmiPersistentData;save()V"))
    private static void emipagedbookmarks$coalesceEmiSave() {
        BookmarkPages.saveEmiData();
    }

    @Inject(method = REMOVE_FAVORITE, at = @At("HEAD"))
    private static void emipagedbookmarks$beforeRemove(EmiIngredient stack, CallbackInfoReturnable<Boolean> callbackInfo) {
        PENDING_SNAPSHOT.set(emipagedbookmarks$snapshot());
    }

    @Inject(method = REMOVE_FAVORITE, at = @At("RETURN"))
    private static void emipagedbookmarks$afterRemove(EmiIngredient stack, CallbackInfoReturnable<Boolean> callbackInfo) {
        List<EmiFavorite> before = emipagedbookmarks$takeSnapshot();
        if (Boolean.TRUE.equals(callbackInfo.getReturnValue())) {
            BookmarkPages.applyRemoval(stack, before);
        }
    }

    @Inject(method = LOAD_JSON, at = @At("RETURN"))
    private static void emipagedbookmarks$afterLoad(JsonArray array, CallbackInfo callbackInfo) {
        BookmarkPages.onEmiFavoritesLoaded();
    }

    /**
     * BoM 合成模式会先把 {@code syntheticFavorites} 清空再重填。排布缓存的 key 里只记了它的
     * <b>条数</b>，所以「条数不变而内容变了」时会继续用旧对象；这里补一次失效。
     */
    @Inject(method = UPDATE_SYNTHETIC, at = @At("RETURN"))
    private static void emipagedbookmarks$afterSynthetic(EmiPlayerInventory inventory, CallbackInfo callbackInfo) {
        LayoutVersion.bump();
    }

    @Unique
    private static List<EmiFavorite> emipagedbookmarks$snapshot() {
        return new ArrayList<>(EmiFavorites.favorites);
    }

    @Unique
    private static List<EmiFavorite> emipagedbookmarks$takeSnapshot() {
        List<EmiFavorite> before = PENDING_SNAPSHOT.get();
        PENDING_SNAPSHOT.remove();
        return before;
    }
}
