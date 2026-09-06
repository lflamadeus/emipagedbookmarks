package com.lai.emipagedbookmarks.mixin;

import com.lai.emipagedbookmarks.client.BookmarkUi;

import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.screen.EmiScreenManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EmiScreenManager.SidebarPanel.class)
public abstract class SidebarPanelMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void emipagedbookmarks$render(EmiDrawContext context, int mouseX, int mouseY, float delta, CallbackInfo callbackInfo) {
        BookmarkUi.render(context, (EmiScreenManager.SidebarPanel) (Object) this, mouseX, mouseY);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void emipagedbookmarks$hideNativeButtons(EmiDrawContext context, int mouseX, int mouseY, float delta, CallbackInfo callbackInfo) {
        EmiScreenManager.SidebarPanel panel = (EmiScreenManager.SidebarPanel) (Object) this;
        if (panel.getType() == dev.emi.emi.config.SidebarType.FAVORITES) {
            panel.pageLeft.visible = false;
            panel.cycle.visible = false;
            panel.pageRight.visible = false;
        }
    }

    @Inject(method = "updateWidgetVisibility", at = @At("TAIL"))
    private void emipagedbookmarks$hideNativeButtons(CallbackInfo callbackInfo) {
        EmiScreenManager.SidebarPanel panel = (EmiScreenManager.SidebarPanel) (Object) this;
        if (panel.getType() == dev.emi.emi.config.SidebarType.FAVORITES) {
            panel.pageLeft.visible = false;
            panel.cycle.visible = false;
            panel.pageRight.visible = false;
        }
    }

    @Inject(method = "drawHeader", at = @At("HEAD"), cancellable = true)
    private void emipagedbookmarks$hideHeader(EmiDrawContext context, int mouseX, int mouseY, float delta, int page, int totalPages, CallbackInfo callbackInfo) {
        EmiScreenManager.SidebarPanel panel = (EmiScreenManager.SidebarPanel) (Object) this;
        if (panel.getType() == dev.emi.emi.config.SidebarType.FAVORITES) {
            callbackInfo.cancel();
        }
    }
}
