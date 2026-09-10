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
    /**
     * 支持的 EMI 主版本前缀。
     *
     * <p>EMI 打的是 {@code 1.1.24+1.20.1+forge} 这种复合版本号，补丁号一变就完全对不上，
     * 所以这里只比主次版本前缀。真实兼容性判定只能靠实机；一旦版本对不上，宁可全部关掉
     * （并在日志里明确写出来），也不要半开半关地留下难以排查的怪现象。</p>
     */
    private static final String EMI_VERSION_PREFIX = "1.1.24";
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
                boolean detected = actualVersion != null && actualVersion.startsWith(EMI_VERSION_PREFIX);
                if (actualVersion == null) {
                    log("EMI was not found in the loading mod list; skipping all EMI mixins", false);
                } else if (detected) {
                    log("Detected EMI version " + actualVersion + "; mixins enabled", false);
                } else {
                    log("Detected EMI version " + actualVersion + " but this build only supports "
                            + EMI_VERSION_PREFIX + ".x; ALL EMI mixins are disabled, so paged bookmarks, "
                            + "groups and the flower borders will not appear. Update this mod or downgrade EMI.",
                            true);
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

    private static void log(String message, boolean warn) {
        if (DIAGNOSTIC_LOGGED.compareAndSet(false, true)) {
            if (warn) {
                LOGGER.warn("[EMI Paged Bookmarks] {}", message);
            } else {
                LOGGER.info("[EMI Paged Bookmarks] {}", message);
            }
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
