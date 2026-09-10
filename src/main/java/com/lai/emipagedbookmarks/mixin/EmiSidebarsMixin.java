package com.lai.emipagedbookmarks.mixin;

import java.util.List;

import com.lai.emipagedbookmarks.client.BookmarkPages;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.config.SidebarType;
import dev.emi.emi.runtime.EmiSidebars;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EmiSidebars.class)
public abstract class EmiSidebarsMixin {
    @Inject(method = "getStacks(Ldev/emi/emi/config/SidebarType;)Ljava/util/List;",
            at = @At("RETURN"), cancellable = true)
    private static void emipagedbookmarks$filterFavorites(SidebarType type, CallbackInfoReturnable<List<? extends EmiIngredient>> callbackInfo) {
        if (type == SidebarType.FAVORITES) {
            callbackInfo.setReturnValue(BookmarkPages.visibleFavorites());
        }
    }
}
