package com.lai.emipagedbookmarks.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class RenamePageScreen extends Screen {
    private final Screen parent;
    private EditBox nameBox;

    public RenamePageScreen(Screen parent) {
        super(Component.translatable("screen.emipagedbookmarks.rename"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int width = 180;
        int left = (this.width - width) / 2;
        nameBox = new EditBox(font, left, this.height / 2 - 24, width, 20, Component.translatable("screen.emipagedbookmarks.page_name"));
        nameBox.setValue(BookmarkPages.current().name());
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> finish())
                .bounds(left, this.height / 2 + 4, width, 20).build());
        setInitialFocus(nameBox);
    }

    private void finish() {
        BookmarkPages.renameCurrent(nameBox.getValue());
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, this.height / 2 - 52, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, delta);
    }
}
