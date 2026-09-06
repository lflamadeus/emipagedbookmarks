package com.lai.emipagedbookmarks.client;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.serializer.EmiIngredientSerializer;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.screen.EmiScreenManager;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

public final class BookmarkPages {
    private static final File FILE = new File("emipagedbookmarks.json");
    private static final Gson GSON = new Gson().newBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final CopyOnWriteArrayList<BookmarkPage> pages = new CopyOnWriteArrayList<>();
    private static final AtomicInteger currentPage = new AtomicInteger();

    private BookmarkPages() {
    }

    public static synchronized void initialize() {
        if (pages.isEmpty()) {
            pages.add(new BookmarkPage("1"));
        }
        load();
        syncFavorites();
    }

    public static List<BookmarkPage> pages() {
        initializeIfNeeded();
        return List.copyOf(pages);
    }

    public static BookmarkPage current() {
        initializeIfNeeded();
        int index = Math.max(0, Math.min(currentPage.get(), pages.size() - 1));
        currentPage.set(index);
        return pages.get(index);
    }

    public static int currentIndex() {
        return currentPage.get();
    }

    public static List<EmiIngredient> visibleFavorites() {
        initializeIfNeeded();
        List<EmiIngredient> visible = new ArrayList<>();
        BookmarkPage page = current();
        Set<String> visibleKeys = ConcurrentHashMap.newKeySet();
        visibleKeys.addAll(page.bookmarkKeys());
        for (EmiFavorite favorite : EmiFavorites.favorites) {
            if (visibleKeys.contains(keyOf(favorite))) {
                visible.add(favorite);
            }
        }
        if (currentPage.get() == 0) {
            visible.addAll(EmiFavorites.syntheticFavorites);
        }
        return visible;
    }

    public static synchronized void addPage() {
        initializeIfNeeded();
        pages.add(new BookmarkPage(Integer.toString(pages.size() + 1)));
        currentPage.set(pages.size() - 1);
        save();
        refresh();
    }

    public static synchronized boolean removeCurrentPage() {
        initializeIfNeeded();
        if (pages.size() <= 1) {
            return false;
        }
        int removedIndex = currentPage.get();
        BookmarkPage removed = pages.remove(removedIndex);
        int targetIndex = Math.max(0, Math.min(removedIndex - 1, pages.size() - 1));
        BookmarkPage target = pages.get(targetIndex);
        removed.bookmarkKeys().forEach(target.bookmarkKeys()::addIfAbsent);
        currentPage.set(targetIndex);
        save();
        refresh();
        return true;
    }

    public static void selectPage(int index) {
        initializeIfNeeded();
        if (index >= 0 && index < pages.size()) {
            currentPage.set(index);
            refresh();
        }
    }

    public static boolean scrollBy(int delta) {
        initializeIfNeeded();
        if (delta == 0 || pages.size() <= 1) {
            return false;
        }
        // 使用取模循环分页，让滚轮在第一页和最后一页之间连续切换。
        selectPage(Math.floorMod(currentPage.get() + delta, pages.size()));
        return true;
    }

    public static void renameCurrent(String name) {
        current().rename(name);
        save();
    }

    public static synchronized void syncFavorites() {
        initializeIfNeededWithoutLoad();
        Set<String> favoriteKeys = ConcurrentHashMap.newKeySet();
        Set<String> assignedKeys = ConcurrentHashMap.newKeySet();
        for (BookmarkPage page : pages) {
            assignedKeys.addAll(page.bookmarkKeys());
        }
        for (EmiFavorite favorite : EmiFavorites.favorites) {
            String key = keyOf(favorite);
            favoriteKeys.add(key);
            if (assignedKeys.add(key)) {
                current().bookmarkKeys().addIfAbsent(key);
            }
        }
        for (BookmarkPage page : pages) {
            page.bookmarkKeys().removeIf(key -> !favoriteKeys.contains(key));
        }
    }

    public static String keyOf(EmiIngredient ingredient) {
        EmiRecipe recipe = ingredient instanceof EmiFavorite favorite ? favorite.getRecipe() : null;
        EmiIngredient serializedIngredient = ingredient instanceof EmiFavorite favorite ? favorite.getStack() : ingredient;
        String stack = String.valueOf(EmiIngredientSerializer.getSerialized(serializedIngredient));
        return stack + "|" + (recipe == null || recipe.getId() == null ? "" : recipe.getId());
    }

    public static synchronized void save() {
        try (FileWriter writer = new FileWriter(FILE)) {
            JsonObject root = new JsonObject();
            root.addProperty("current_page", currentPage.get());
            JsonArray savedPages = new JsonArray();
            for (BookmarkPage page : pages) {
                JsonObject savedPage = new JsonObject();
                savedPage.addProperty("name", page.name());
                JsonArray keys = new JsonArray();
                page.bookmarkKeys().forEach(keys::add);
                savedPage.add("bookmarks", keys);
                savedPages.add(savedPage);
            }
            root.add("pages", savedPages);
            GSON.toJson(root, writer);
        } catch (Exception exception) {
            LOGGER.error("Failed to save bookmark pages", exception);
        }
    }

    private static synchronized void load() {
        if (!FILE.exists()) {
            return;
        }
        try (FileReader reader = new FileReader(FILE)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            JsonArray savedPages = root == null ? null : root.getAsJsonArray("pages");
            if (savedPages != null && !savedPages.isEmpty()) {
                pages.clear();
                for (int i = 0; i < savedPages.size(); i++) {
                    JsonObject savedPage = savedPages.get(i).getAsJsonObject();
                    BookmarkPage page = new BookmarkPage(savedPage.has("name") ? savedPage.get("name").getAsString() : Integer.toString(i + 1));
                    JsonArray keys = savedPage.getAsJsonArray("bookmarks");
                    if (keys != null) {
                        keys.forEach(key -> page.bookmarkKeys().add(key.getAsString()));
                    }
                    pages.add(page);
                }
                currentPage.set(root.has("current_page") ? root.get("current_page").getAsInt() : 0);
            }
        } catch (Exception exception) {
            LOGGER.error("Failed to load bookmark pages", exception);
            pages.clear();
            pages.add(new BookmarkPage("1"));
            currentPage.set(0);
        }
    }

    private static void initializeIfNeeded() {
        if (pages.isEmpty()) {
            initialize();
        }
    }

    private static void initializeIfNeededWithoutLoad() {
        if (pages.isEmpty()) {
            pages.add(new BookmarkPage("1"));
        }
    }

    private static void refresh() {
        if (Minecraft.getInstance().screen != null) {
            EmiScreenManager.forceRecalculate();
        }
    }
}
