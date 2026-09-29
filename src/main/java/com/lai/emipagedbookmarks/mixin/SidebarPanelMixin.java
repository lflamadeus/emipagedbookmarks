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
    private void emipagedbookmarks$hideNativeButtons(EmiDrawContext context, int mouseX, int mouseY, float delta,
            CallbackInfo callbackInfo) {
        EmiScreenManager.SidebarPanel panel = (EmiScreenManager.SidebarPanel) (Object) this;
        if (panel.getType() == dev.emi.emi.config.SidebarType.FAVORITES) {
            panel.pageLeft.visible = false;
            panel.cycle.visible = false;
            panel.pageRight.visible = false;
        }
    }

    /**
     * 分组轮廓与框选预览画在 TAIL，也就是压在物品图标之上。
     *
     * <p>EMI 的条目是 18x18、物品贴图 16x16，四边各留 1px 透明边距，2px 的轮廓正好落在图标
     * 边缘上：既完整可见、又不会盖住图标内容。画在 HEAD（物品之下）的话只有 1px 能露出来。</p>
     */
    @Inject(method = "render(Ldev/emi/emi/runtime/EmiDrawContext;IIF)V", at = @At("TAIL"))
    private void emipagedbookmarks$renderOverlay(EmiDrawContext context, int mouseX, int mouseY, float delta,
            CallbackInfo callbackInfo) {
        EmiScreenManager.SidebarPanel panel = (EmiScreenManager.SidebarPanel) (Object) this;
        GroupOverlay.render(context, panel, mouseX, mouseY);
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
