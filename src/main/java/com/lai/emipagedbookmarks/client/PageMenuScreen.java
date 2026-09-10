package com.lai.emipagedbookmarks.client;

import java.io.File;
import java.util.List;

import javax.annotation.Nonnull;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 右键分页按钮弹出的操作菜单：重命名、导出、导入。
 */
public final class PageMenuScreen extends Screen {
    private static final int WIDTH = 200;

    private final Screen parent;

    public PageMenuScreen(Screen parent) {
        super(Component.translatable("screen.emipagedbookmarks.menu"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = (width - WIDTH) / 2;
        int y = height / 2 - 60;
        addRenderableWidget(Button.builder(Component.translatable("screen.emipagedbookmarks.rename"),
                button -> Minecraft.getInstance().setScreen(new RenamePageScreen(parent)))
                .bounds(left, y, WIDTH, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.translatable("screen.emipagedbookmarks.export_page"),
                button -> export(BookmarkPages.current().name(), List.of(BookmarkPages.current())))
                .bounds(left, y, WIDTH, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.translatable("screen.emipagedbookmarks.export_all"),
                button -> export("all", BookmarkPages.pages()))
                .bounds(left, y, WIDTH, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.translatable("screen.emipagedbookmarks.import"),
                button -> Minecraft.getInstance().setScreen(new ImportScreen(parent)))
                .bounds(left, y, WIDTH, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
                button -> Minecraft.getInstance().setScreen(parent))
                .bounds(left, y, WIDTH, 20).build());
    }

    private void export(String label, List<BookmarkPage> targets) {
        File file = BookmarkPages.exportPages(targets, label);
        if (file == null) {
            BookmarkUi.notify(Component.translatable("message.emipagedbookmarks.export_failed"));
        } else {
            BookmarkUi.notify(Component.translatable("message.emipagedbookmarks.exported", file.getName()));
        }
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 80, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, delta);
    }
}
