package com.lai.emipagedbookmarks.mixin;

import com.lai.emipagedbookmarks.client.BookmarkPages;

import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EmiFavorites.class)
public abstract class EmiFavoritesMixin {
    @Inject(method = "addFavorite(Ldev/emi/emi/api/stack/EmiIngredient;)V", at = @At("RETURN"))
    private static void emipagedbookmarks$afterAdd(EmiIngredient ingredient, CallbackInfo callbackInfo) {
        BookmarkPages.syncFavorites();
        BookmarkPages.save();
    }

    @Inject(method = "addFavorite(Ldev/emi/emi/api/stack/EmiIngredient;Ldev/emi/emi/api/recipe/EmiRecipe;)V", at = @At("RETURN"))
    private static void emipagedbookmarks$afterAddWithContext(EmiIngredient ingredient, EmiRecipe recipe, CallbackInfo callbackInfo) {
        BookmarkPages.syncFavorites();
        BookmarkPages.save();
    }

    @Inject(method = "addFavoriteAt", at = @At("RETURN"))
    private static void emipagedbookmarks$afterInsert(EmiIngredient ingredient, int offset, CallbackInfo callbackInfo) {
        BookmarkPages.syncFavorites();
        BookmarkPages.save();
    }

    @Inject(method = "removeFavorite", at = @At("RETURN"))
    private static void emipagedbookmarks$afterRemove(EmiIngredient ingredient, CallbackInfoReturnable<Boolean> callbackInfo) {
        BookmarkPages.syncFavorites();
        BookmarkPages.save();
    }

    @Inject(method = "load", at = @At("RETURN"))
    private static void emipagedbookmarks$afterLoad(com.google.gson.JsonArray array, CallbackInfo callbackInfo) {
        BookmarkPages.syncFavorites();
    }
}
