package com.lai.emipagedbookmarks.client;

import com.lai.emipagedbookmarks.EmiPagedBookmarksMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = EmiPagedBookmarksMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        if (ModList.get().isLoaded("emi")) {
            // FMLClientSetupEvent 跑在模组加载线程上；initialize() 会读 EMI 的收藏表、必要时还会
            // 写 bookmarks.json，按 FML 约定丢回客户端主线程再跑。
            event.enqueueWork(BookmarkPages::initialize);
        }
    }
}
