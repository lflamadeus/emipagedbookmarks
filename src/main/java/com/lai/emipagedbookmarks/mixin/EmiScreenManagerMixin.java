package com.lai.emipagedbookmarks.mixin;

import com.lai.emipagedbookmarks.client.BookmarkUi;
import com.lai.emipagedbookmarks.client.group.GroupInteraction;

import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.screen.EmiScreenManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EmiScreenManager.class)
public abstract class EmiScreenManagerMixin {
    @Inject(method = "mouseClicked(DDI)Z", at = @At("HEAD"), cancellable = true)
    private static void emipagedbookmarks$mouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> callbackInfo) {
        // 两个都调用（用 | 而不是 ||）：BookmarkUi 接管分页标签那一行时，GroupInteraction
        // 仍然要跑一次 —— 它发现鼠标不在格子里时会顺手清掉陈旧的框选锚点，否则「按住拖时关屏、
        // 再点分页标签」会留下一个脏 anchor，之后一次普通的点击就能误移动一个条目。
        boolean handled = BookmarkUi.mouseClicked(mouseX, mouseY, button);
        handled = GroupInteraction.mouseClicked(mouseX, mouseY, button) | handled;
        if (handled) {
            callbackInfo.setReturnValue(true);
        }
    }

    @Inject(method = "mouseScrolled(DDD)Z", at = @At("HEAD"), cancellable = true)
    private static void emipagedbookmarks$mouseScrolled(double mouseX, double mouseY, double amount,
            CallbackInfoReturnable<Boolean> callbackInfo) {
        if (BookmarkUi.mouseScrolled(mouseX, mouseY, amount)) {
            callbackInfo.setReturnValue(true);
        }
    }

    // 不拦截 mouseDragged：EMI 靠它把拖拽中的收藏物显示在鼠标上，也靠它支持拖进搜索栏。

    @Inject(method = "mouseReleased(DDI)Z", at = @At("HEAD"), cancellable = true)
    private static void emipagedbookmarks$mouseReleased(double mouseX, double mouseY, int button,
            CallbackInfoReturnable<Boolean> callbackInfo) {
        // 同上：两个都调用。GroupInteraction 在入口就清锚点，所以即使 BookmarkUi 已经接管理，
        // 也不会留下"下一次松手才发作"的陈旧状态。
        boolean handled = BookmarkUi.mouseReleased(mouseX, mouseY, button);
        handled = GroupInteraction.mouseReleased(mouseX, mouseY, button) | handled;
        if (handled) {
            // 接管后 EMI 的 mouseReleased 不会执行，它 finally 块里的状态清理也就不会发生，
            // 这里手动清掉，避免拖拽物一直粘在鼠标上。
            EmiScreenManager.pressedStack = EmiStack.EMPTY;
            EmiScreenManager.draggedStack = EmiStack.EMPTY;
            callbackInfo.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed(III)Z", at = @At("HEAD"), cancellable = true)
    private static void emipagedbookmarks$keyPressed(int keyCode, int scanCode, int modifiers,
            CallbackInfoReturnable<Boolean> callbackInfo) {
        if (GroupInteraction.keyPressed(keyCode)) {
            callbackInfo.setReturnValue(true);
        }
    }
}
