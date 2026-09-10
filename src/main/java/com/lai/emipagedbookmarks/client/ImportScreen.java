package com.lai.emipagedbookmarks.client;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nonnull;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 扫描 bookmark_pages 目录中的 json 文件并列出供选择导入。
 */
public final class ImportScreen extends Screen {
    private static final int WIDTH = 260;
    private static final int ROWS = 10;
    private static final int ROW_HEIGHT = 20;

    private final Screen parent;
    private final List<File> files;
    private final List<Button> rows = new ArrayList<>();
    private int offset;

    public ImportScreen(Screen parent) {
        super(Component.translatable("screen.emipagedbookmarks.import_title"));
        this.parent = parent;
        File[] found = BookmarkPages.exportDir().listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
        this.files = found == null ? List.of()
                : Arrays.stream(found).sorted(Comparator.comparingLong(File::lastModified).reversed()).toList();
    }

    @Override
    protected void init() {
        int left = (width - WIDTH) / 2;
        int top = height / 2 - 70;
        rows.clear();
        for (int i = 0; i < ROWS; i++) {
            final int row = i;
            Button button = Button.builder(Component.empty(), pressed -> importFile(files.get(offset + row)))
                    .bounds(left, top + row * ROW_HEIGHT, WIDTH, 18).build();
            rows.add(button);
            addRenderableWidget(button);
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
                pressed -> Minecraft.getInstance().setScreen(parent))
                .bounds(left, top + ROWS * ROW_HEIGHT + 6, WIDTH, 20).build());
        updateRows();
    }

    private void updateRows() {
        for (int i = 0; i < rows.size(); i++) {
            int index = offset + i;
            Button button = rows.get(i);
            boolean has = index >= 0 && index < files.size();
            button.visible = has;
            button.active = has;
            if (has) {
                String name = files.get(index).getName();
                button.setMessage(Component.literal(font.plainSubstrByWidth(name, WIDTH - 10)));
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int max = Math.max(0, files.size() - ROWS);
        int next = Math.max(0, Math.min(offset + (amount > 0 ? -1 : 1), max));
        if (next == offset) {
            return false;
        }
        offset = next;
        updateRows();
        return true;
    }

    private void importFile(File file) {
        int count = BookmarkPages.importFrom(file);
        if (count < 0) {
            BookmarkUi.notify(Component.translatable("message.emipagedbookmarks.import_failed", file.getName()));
        } else {
            BookmarkUi.notify(Component.translatable("message.emipagedbookmarks.imported", count));
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
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 90, 0xFFFFFF);
        if (files.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.emipagedbookmarks.import_empty"),
                    width / 2, height / 2 - 60, 0xAAAAAA);
        } else {
            int left = (width - WIDTH) / 2;
            int top = height / 2 - 70;
            graphics.drawString(font, (offset / ROWS + 1) + " / " + ((files.size() - 1) / ROWS + 1),
                    left, top + ROWS * ROW_HEIGHT + 30, 0x888888);
            if (files.size() > ROWS) {
                int trackHeight = ROWS * ROW_HEIGHT;
                int barHeight = Math.max(8, trackHeight * ROWS / files.size());
                int barTop = top + (trackHeight - barHeight) * offset / Math.max(1, files.size() - ROWS);
                graphics.fill(left + WIDTH + 4, barTop, left + WIDTH + 7, barTop + barHeight, 0xFF888888);
            }
        }
        super.render(graphics, mouseX, mouseY, delta);
    }
}
