package com.lai.emipagedbookmarks.mixin;

import com.lai.emipagedbookmarks.client.BookmarkUi;
import com.lai.emipagedbookmarks.client.group.GroupOverlay;

import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.screen.EmiScreenManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EmiScreenManager.SidebarPanel.class)
public abstract class SidebarPanelMixin {
    @Inject(method = "render(Ldev/emi/emi/runtime/EmiDrawContext;IIF)V", at = @At("HEAD"))
    private void emipagedbookmarks$renderBackground(EmiDrawContext context, int mouseX, int mouseY, float delta,
            CallbackInfo callbackInfo) {
        EmiScreenManager.SidebarPanel panel = (EmiScreenManager.SidebarPanel) (Object) this;
        if (panel.getType() == dev.emi.emi.config.SidebarType.FAVORITES) {
            panel.pageLeft.visible = false;
            panel.cycle.visible = false;
            panel.pageRight.visible = false;
        }
        GroupOverlay.renderBackground(context, panel, mouseX, mouseY);
    }

    @Inject(method = "render(Ldev/emi/emi/runtime/EmiDrawContext;IIF)V", at = @At("TAIL"))
    private void emipagedbookmarks$renderForeground(EmiDrawContext context, int mouseX, int mouseY, float delta,
            CallbackInfo callbackInfo) {
        EmiScreenManager.SidebarPanel panel = (EmiScreenManager.SidebarPanel) (Object) this;
        GroupOverlay.renderForeground(context, panel, mouseX, mouseY);
        BookmarkUi.render(context, panel, mouseX, mouseY);
    }

    @Inject(method = "updateWidgetVisibility()V", at = @At("TAIL"))
    private void emipagedbookmarks$hideNativeButtons(CallbackInfo callbackInfo) {
        EmiScreenManager.SidebarPanel panel = (EmiScreenManager.SidebarPanel) (Object) this;
        if (panel.getType() == dev.emi.emi.config.SidebarType.FAVORITES) {
            panel.pageLeft.visible = false;
            panel.cycle.visible = false;
            panel.pageRight.visible = false;
        }
    }

    @Inject(method = "drawHeader(Ldev/emi/emi/runtime/EmiDrawContext;IIFII)V",
            at = @At("HEAD"), cancellable = true)
    private void emipagedbookmarks$hideHeader(EmiDrawContext context, int mouseX, int mouseY, float delta, int page, int totalPages, CallbackInfo callbackInfo) {
        EmiScreenManager.SidebarPanel panel = (EmiScreenManager.SidebarPanel) (Object) this;
        if (panel.getType() == dev.emi.emi.config.SidebarType.FAVORITES) {
            callbackInfo.cancel();
        }
    }
}
