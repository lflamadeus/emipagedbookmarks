package com.lai.emipagedbookmarks.mixin;

import com.lai.emipagedbookmarks.client.BookmarkUi;

import dev.emi.emi.screen.EmiScreenManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EmiScreenManager.class)
public abstract class EmiScreenManagerMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private static void emipagedbookmarks$mouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (BookmarkUi.mouseClicked(mouseX, mouseY, button)) {
            callbackInfo.setReturnValue(true);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private static void emipagedbookmarks$mouseScrolled(double mouseX, double mouseY, double amount,
            CallbackInfoReturnable<Boolean> callbackInfo) {
        if (BookmarkUi.mouseScrolled(mouseX, mouseY, amount)) {
            callbackInfo.setReturnValue(true);
        }
    }
}
