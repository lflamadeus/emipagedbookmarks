package com.lai.emipagedbookmarks.client;

import com.lai.emipagedbookmarks.EmiPagedBookmarksMod;
import com.lai.emipagedbookmarks.client.group.GroupInteraction;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 界面关闭时清理交互中间态。
 *
 * <p>拖分页 / 按住左键框选时按 ESC 关屏，鼠标松开事件就不会再送达，残留的下标与锚点会让
 * 下一次打开这个界面时出现「分页一直是选中态」或「一次普通点击就移动了条目」。这里在
 * 任何界面关闭时统一清掉。</p>
 */
@Mod.EventBusSubscriber(modid = EmiPagedBookmarksMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientScreenEvents {
    private ClientScreenEvents() {
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        BookmarkUi.clearDragState();
        GroupInteraction.clearInteraction();
    }
}
