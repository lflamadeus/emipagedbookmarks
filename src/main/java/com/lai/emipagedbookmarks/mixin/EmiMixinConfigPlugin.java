package com.lai.emipagedbookmarks.mixin;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class EmiMixinConfigPlugin implements IMixinConfigPlugin {
    private static final String EMI_MOD_ID = "emi";
    private static final String EMI_VERSION = "1.1.24+1.20.1+forge";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean DIAGNOSTIC_LOGGED = new AtomicBoolean();
    private static volatile Boolean compatible;

    private static boolean isCompatible() {
        Boolean cached = compatible;
        if (cached != null) {
            return cached;
        }
        synchronized (EmiMixinConfigPlugin.class) {
            if (compatible == null) {
                String actualVersion = findEmiVersion();
                boolean detected = EMI_VERSION.equals(actualVersion);
                if (actualVersion == null) {
                    logDiagnostic("EMI was not found in the loading mod list; skipping all EMI mixins");
                } else {
                    logDiagnostic("Detected EMI version " + actualVersion + "; required " + EMI_VERSION
                            + "; mixins enabled=" + detected);
                }
                compatible = detected;
            }
            return compatible;
        }
    }

    private static String findEmiVersion() {
        try {
            LoadingModList loadingModList = FMLLoader.getLoadingModList();
            if (loadingModList == null) {
                return null;
            }
            for (ModInfo modInfo : loadingModList.getMods()) {
                if (EMI_MOD_ID.equals(modInfo.getModId())) {
                    return modInfo.getVersion().toString();
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.debug("Unable to inspect Forge loading mod list while checking EMI", exception);
        }
        return null;
    }

    private static void logDiagnostic(String message) {
        if (DIAGNOSTIC_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("[EMI Paged Bookmarks] {}", message);
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return isCompatible();
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
