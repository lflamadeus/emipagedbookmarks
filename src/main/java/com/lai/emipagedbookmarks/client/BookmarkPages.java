package com.lai.emipagedbookmarks.client;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lai.emipagedbookmarks.client.group.FavoriteGroup;
import com.lai.emipagedbookmarks.client.group.GroupManager;
import com.lai.emipagedbookmarks.client.group.GroupOverlay;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.serializer.EmiIngredientSerializer;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.runtime.EmiPersistentData;
import dev.emi.emi.screen.EmiScreenManager;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/**
 * 分页书签数据，全部保存在 config/emipagedbookmarks/bookmarks.json。
 *
 * <p>EMI 自己的收藏表 {@link EmiFavorites#favorites} 仍然是所有条目的实际存放处，
 * 本模组只维护"每条收藏属于哪个分页、在分页里的顺序"这一层归属关系。
 * 同一个条目可以同时属于多个分页，因此各分页的收藏互相独立。</p>
 *
 * <p>文件里额外记录了每个条目对应的物品序列化结果，导入时才能把收藏重新写回 EMI。</p>
 */
public final class BookmarkPages {
    private static final int FORMAT_VERSION = 1;
    private static final Gson GSON = new Gson().newBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final List<BookmarkPage> pages = new ArrayList<>();
    private static final AtomicInteger currentPage = new AtomicInteger();
    /** 条目键值 → 物品序列化结果，用于导出与导入。 */
    private static final Map<String, JsonObject> stacks = new HashMap<>();
    private static volatile boolean loaded;
    /** {@link #visibleFavorites()} 的缓存，三个条件任一变化才重建。 */
    private static UUID cachedVisiblePage;
    private static long cachedVisibleVersion = Long.MIN_VALUE;
    private static int cachedVisibleFavorites = -1;
    private static List<EmiIngredient> cachedVisibleList = List.of();

    private BookmarkPages() {
    }

    public static File dataDir() {
        return new File(new File(Minecraft.getInstance().gameDirectory, "config"), "emipagedbookmarks");
    }

    public static File dataFile() {
        return new File(dataDir(), "bookmarks.json");
    }

    /** 导入导出文件存放目录。 */
    public static File exportDir() {
        return new File(dataDir(), "bookmark_pages");
    }

    public static synchronized void initialize() {
        loadIfNeeded();
        adoptUnassignedFavorites();
    }

    /**
     * EMI 重新载入收藏数据后调用：保证本模组已初始化，并把 EMI 里
     * 还没有归属的收藏收编到第一页（例如首次启用本模组时的历史数据）。
     */
    public static synchronized void onEmiFavoritesLoaded() {
        loadIfNeeded();
        // EMI 重载收藏表会换掉 EmiFavorite 实例，即使键值没变也要丢弃缓存。
        LayoutVersion.bump();
        adoptUnassignedFavorites();
    }

    public static synchronized List<BookmarkPage> pages() {
        initializeIfNeeded();
        return List.copyOf(pages);
    }

    public static synchronized BookmarkPage current() {
        initializeIfNeeded();
        int index = Math.max(0, Math.min(currentPage.get(), pages.size() - 1));
        currentPage.set(index);
        return pages.get(index);
    }

    public static int currentIndex() {
        return currentPage.get();
    }

    public static synchronized UUID currentId() {
        return current().id();
    }

    public static synchronized int currentItemCount() {
        return current().bookmarkKeys().size();
    }

    public static synchronized BookmarkPage pageById(UUID id) {
        initializeIfNeeded();
        for (BookmarkPage page : pages) {
            if (page.id().equals(id)) {
                return page;
            }
        }
        return null;
    }

    /**
     * 当前分页显示的收藏，顺序即分页自己的收藏顺序。
     * 折叠与换行填充由 {@code ScreenSpaceMixin} 在此基础上处理。
     *
     * <p>EMI 每帧会多次取这份列表，而构造它需要对全部收藏跑一遍 {@link #keyOf} 并建 HashMap，
     * 这里按「分页 + 布局版本 + 收藏条数」缓存；三个条件任一变化才重建。</p>
     */
    public static synchronized List<EmiIngredient> visibleFavorites() {
        initializeIfNeeded();
        BookmarkPage page = current();
        long version = LayoutVersion.current();
        int favoriteCount = EmiFavorites.favorites.size();
        if (cachedVisiblePage != null && cachedVisiblePage.equals(page.id())
                && cachedVisibleVersion == version && cachedVisibleFavorites == favoriteCount) {
            return cachedVisibleList;
        }
        Map<String, EmiIngredient> resolved = new HashMap<>();
        for (EmiFavorite favorite : EmiFavorites.favorites) {
            resolved.putIfAbsent(keyOf(favorite), favorite);
        }
        List<String> keys = page.bookmarkKeys();
        List<EmiIngredient> visible = new ArrayList<>(keys.size());
        for (String key : keys) {
            EmiIngredient ingredient = resolved.get(key);
            visible.add(ingredient == null ? EmiStack.EMPTY : ingredient);
        }
        cachedVisiblePage = page.id();
        cachedVisibleVersion = version;
        cachedVisibleFavorites = favoriteCount;
        // 缓存的是不可变副本，外部拿到后即使改动也不会污染缓存。
        cachedVisibleList = List.copyOf(visible);
        return cachedVisibleList;
    }

    /**
     * EMI 的合成树等临时收藏，只挂在第一页末尾，不参与分组索引。
     */
    public static List<? extends EmiIngredient> syntheticFavorites() {
        return currentIndex() == 0 ? EmiFavorites.syntheticFavorites : List.of();
    }

    public static synchronized void addPage() {
        initializeIfNeeded();
        pages.add(new BookmarkPage(uniqueName(Integer.toString(pages.size() + 1))));
        currentPage.set(pages.size() - 1);
        save();
        refresh();
    }

    /**
     * 追加一个指定名字的分页并选中它，不触发重绘，供批量导入使用。
     */
    private static synchronized BookmarkPage appendPage(String name) {
        initializeIfNeeded();
        BookmarkPage page = new BookmarkPage(uniqueName(name));
        pages.add(page);
        currentPage.set(pages.size() - 1);
        return page;
    }

    /**
     * 删除当前分页，页内收藏并入相邻分页。只剩一页时什么也不做。
     */
    public static synchronized void removeCurrentPage() {
        initializeIfNeeded();
        if (pages.size() <= 1) {
            return;
        }
        int removedIndex = currentPage.get();
        BookmarkPage removed = pages.remove(removedIndex);
        int targetIndex = Math.max(0, Math.min(removedIndex - 1, pages.size() - 1));
        BookmarkPage target = pages.get(targetIndex);
        // 并入目标分页，addKey 内部去重，重复部分自动消除。
        for (String key : removed.bookmarkKeys()) {
            target.addKey(key);
        }
        currentPage.set(targetIndex);
        save();
        refresh();
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

    public static synchronized void renameCurrent(String name) {
        current().rename(name);
        save();
    }

    /**
     * 拖动调整分页顺序，被拖动的分页落到 target 处并成为当前分页。
     */
    public static synchronized void movePage(int from, int to) {
        initializeIfNeeded();
        if (from < 0 || from >= pages.size()) {
            return;
        }
        int target = Math.max(0, Math.min(to, pages.size() - 1));
        if (from == target) {
            return;
        }
        pages.add(target, pages.remove(from));
        currentPage.set(target);
        save();
        refresh();
    }

    /**
     * 把 from 处的条目移动到 to 处（to 为移动后的最终下标）。
     */
    public static synchronized void moveKey(int from, int to) {
        List<String> keys = current().bookmarkKeys();
        if (from < 0 || from >= keys.size()) {
            return;
        }
        int target = Math.max(0, Math.min(to, keys.size() - 1));
        if (from == target) {
            return;
        }
        keys.add(target, keys.remove(from));
        save();
    }

    /**
     * 把 [start, start + size) 这一段整体移动到 insertAt 之前。
     */
    public static synchronized void moveKeyRange(int start, int size, int insertAt) {
        List<String> keys = current().bookmarkKeys();
        if (start < 0 || size <= 0 || start + size > keys.size()) {
            return;
        }
        List<String> block = new ArrayList<>(keys.subList(start, start + size));
        keys.subList(start, start + size).clear();
        keys.addAll(Math.max(0, Math.min(insertAt, keys.size())), block);
        save();
    }

    /**
     * 删除 [start, end] 范围内的收藏，不再被任何分页引用的条目会一并从 EMI 收藏表移除。
     */
    public static synchronized void removeRange(int start, int end) {
        BookmarkPage page = current();
        List<String> keys = page.bookmarkKeys();
        // 先统计每个键值被几个分页引用，循环里就是 O(1) 判断（原来每删一条都要把全部分页扫一遍）。
        Map<String, Integer> holders = pageRefCounts();
        boolean emiChanged = false;
        for (int index = Math.min(end, keys.size() - 1); index >= start; index--) {
            String key = keys.remove(index);
            // 分组边界就地修正：不要再进 GroupManager 的锁（那会形成两把锁方向相反的两条路径）。
            GroupManager.adjustOnRemove(page.groups(), index);
            if (holders.getOrDefault(key, 0) <= 1) {
                if (EmiFavorites.favorites.removeIf(favorite -> keyOf(favorite).equals(key))) {
                    emiChanged = true;
                }
            }
        }
        // EMI 那边也只落一次盘（原来是每条都写一遍 emi 的 favorites.json）。
        if (emiChanged) {
            EmiPersistentData.save();
        }
        save();
    }

    /** 每个键值被几个分页引用；分页内部会去重，所以计数就等于分页数。 */
    private static Map<String, Integer> pageRefCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (BookmarkPage page : pages) {
            for (String key : page.bookmarkKeys()) {
                counts.merge(key, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * EMI 新增/移动收藏后调用：把受影响的条目归入当前分页。
     *
     * @param before 调用前 EMI 收藏表的快照，用于判断哪些条目被新增或移除
     */
    public static synchronized void applyAdd(EmiIngredient stack, EmiRecipe recipe, List<EmiFavorite> before) {
        initializeIfNeeded();
        BookmarkPage page = current();
        boolean changed = false;
        boolean added = false;
        if (before != null) {
            Set<EmiFavorite> previous = identitySet(before);
            Set<EmiFavorite> now = identitySet(EmiFavorites.favorites);
            for (EmiFavorite favorite : before) {
                if (!now.contains(favorite)) {
                    changed |= removeKeyFromPage(page, keyOf(favorite));
                }
            }
            for (EmiFavorite favorite : EmiFavorites.favorites) {
                if (!previous.contains(favorite)) {
                    added = true;
                    changed |= page.addKey(keyOf(favorite));
                }
            }
        }
        if (!added) {
            // EMI 可能因为条目已存在而没有改动收藏表（该物品已被别的分页收藏），
            // 此时按内容把已有条目归入当前分页，从而实现各分页独立收藏同一个物品。
            EmiFavorite match = findFavorite(stack, recipe);
            if (match != null) {
                changed |= page.addKey(keyOf(match));
            }
        }
        if (changed) {
            save();
        }
    }

    /**
     * EMI 移除收藏后调用：只从当前分页移除；若其他分页仍在收藏，
     * 则把条目放回 EMI 总表，避免影响别的分页。
     */
    public static synchronized void applyRemoval(EmiIngredient stack, List<EmiFavorite> before) {
        initializeIfNeeded();
        EmiFavorite removed = stack instanceof EmiFavorite favorite ? favorite : null;
        if (removed == null && before != null) {
            Set<EmiFavorite> now = identitySet(EmiFavorites.favorites);
            for (EmiFavorite favorite : before) {
                if (!now.contains(favorite)) {
                    removed = favorite;
                    break;
                }
            }
        }
        if (removed == null) {
            return;
        }
        String key = keyOf(removed);
        boolean changed = removeKeyFromPage(current(), key);
        if (anyPageHasKey(key)) {
            EmiFavorites.favorites.add(removed);
            EmiPersistentData.save();
        }
        if (changed) {
            save();
        }
    }

    /**
     * 当前分页收藏着该条目，且其他分页也收藏着它时，取消 EMI 的移除操作，
     * 只把它从当前分页摘掉。
     *
     * @return 是否已处理（调用方应取消 EMI 原方法）
     */
    public static synchronized boolean detachFromCurrentPageOnly(EmiFavorite favorite) {
        initializeIfNeeded();
        String key = keyOf(favorite);
        if (!current().hasKey(key) || !otherPagesContain(key)) {
            return false;
        }
        removeKeyFromPage(current(), key);
        save();
        return true;
    }

    public static String keyOf(EmiIngredient ingredient) {
        return keyOf(ingredient, null);
    }

    public static String keyOf(EmiIngredient ingredient, EmiRecipe recipe) {
        EmiRecipe context = recipe;
        EmiIngredient stack = ingredient;
        if (stack instanceof EmiFavorite favorite) {
            context = favorite.getRecipe();
            stack = favorite.getStack();
        }
        String serialized = String.valueOf(EmiIngredientSerializer.getSerialized(stack));
        return serialized + "|" + (context == null || context.getId() == null ? "" : context.getId());
    }

    public static EmiFavorite findFavorite(EmiIngredient ingredient, EmiRecipe recipe) {
        EmiRecipe context = recipe;
        EmiIngredient stack = ingredient;
        if (stack instanceof EmiFavorite favorite) {
            context = favorite.getRecipe();
            stack = favorite.getStack();
        }
        for (EmiFavorite favorite : EmiFavorites.favorites) {
            if (favorite.getRecipe() == context && favorite.strictEquals(stack)) {
                return favorite;
            }
        }
        if (context != null) {
            // EMI 对标签类收藏不保存配方上下文，退化成无配方条目再找一次。
            for (EmiFavorite favorite : EmiFavorites.favorites) {
                if (favorite.getRecipe() == null && favorite.strictEquals(stack)) {
                    return favorite;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 批量模式

    /**
     * 批量深度。&gt;0 时 {@link #save()} 只让缓存失效、不落盘，由 {@link #endBatch()} 统一写一次。
     *
     * <p>导入一个含 M 条目的文件时，逐条走 {@code EmiFavorites.addFavorite} 会触发 M 次
     * 「缓存全失效 + 全量序列化 + 写 bookmarks.json」，再叠上 EMI 自己每条一次的
     * {@code EmiPersistentData.save()}。上千条就足以卡到像是卡死。批量模式把这两边都收敛成一次。</p>
     */
    private static volatile int batchDepth;
    /** 批量模式下被推迟的 EMI 侧落盘。 */
    private static boolean emiDirty;

    /** 进入批量模式（可嵌套，最外层结束时才真正落盘）。 */
    public static synchronized void beginBatch() {
        batchDepth++;
    }

    /** 退出批量模式；最外层结束时把攒下的改动一次性写掉（EMI 一次 + 本模组一次）。 */
    public static synchronized void endBatch() {
        if (batchDepth == 0) {
            return;
        }
        if (--batchDepth > 0) {
            return;
        }
        if (emiDirty) {
            emiDirty = false;
            EmiPersistentData.save();
        }
        save();
    }

    /** 批量模式是否生效。{@code EmiFavoritesMixin} 用它跳过多余的逐条快照与归位。 */
    public static boolean isBatching() {
        return batchDepth > 0;
    }

    /**
     * EMI 收藏表落盘。{@code EmiFavoritesMixin} 把 {@code EmiFavorites.addFavorite} 里那句
     * {@code EmiPersistentData.save()} 重定向到这里：批量模式下攒着，否则原样立刻写。
     */
    public static synchronized void saveEmiData() {
        if (batchDepth > 0) {
            emiDirty = true;
            return;
        }
        EmiPersistentData.save();
    }

    // ------------------------------------------------------------------ 持久化

    public static synchronized void save() {
        // 所有会改变分页 / 收藏顺序 / 分组归属的操作最终都会走到这里，
        // 因此在这里统一让布局缓存失效，比在每个入口单独 bump 更不容易漏。
        LayoutVersion.bump();
        if (batchDepth > 0) {
            // 批量模式：缓存照样失效，但落盘攒到 endBatch() 一次性做。
            return;
        }
        try {
            File dir = dataDir();
            // createDirectories 失败会抛 IOException（由下面的 catch 记录），
            // 比忽略 mkdirs() 的返回值安全。
            Files.createDirectories(dir.toPath());
            refreshStacks();
            JsonObject root = new JsonObject();
            root.addProperty("version", FORMAT_VERSION);
            root.addProperty("current_page", currentPage.get());
            // 边框带形状（实心厚度 / 抖动层数 / 概率衰减）就存在这份配置里，用户可以直接改。
            root.add("border", GroupOverlay.borderConfigJson());
            JsonObject stacksJson = new JsonObject();
            stacks.forEach(stacksJson::add);
            root.add("stacks", stacksJson);
            JsonArray savedPages = new JsonArray();
            for (BookmarkPage page : pages) {
                savedPages.add(page.toJson());
            }
            root.add("pages", savedPages);
            writeJson(dataFile(), root);
        } catch (Exception exception) {
            LOGGER.error("Failed to save bookmark pages", exception);
        }
    }

    private static synchronized void load() {
        File file = dataFile();
        if (!file.exists()) {
            return;
        }
        try {
            JsonObject root = GSON.fromJson(readFile(file), JsonObject.class);
            if (root != null) {
                // 边框带形状先于分页数据生效：哪怕这份文件还没有任何分页，配置也要读进来。
                GroupOverlay.applyBorderConfig(root.getAsJsonObject("border"));
            }
            JsonArray savedPages = root == null ? null : root.getAsJsonArray("pages");
            if (savedPages == null || savedPages.isEmpty()) {
                return;
            }
            pages.clear();
            for (int i = 0; i < savedPages.size(); i++) {
                pages.add(BookmarkPage.fromJson(savedPages.get(i).getAsJsonObject()));
            }
            currentPage.set(root.has("current_page") ? root.get("current_page").getAsInt() : 0);
            stacks.clear();
            JsonObject savedStacks = root.getAsJsonObject("stacks");
            if (savedStacks != null) {
                savedStacks.entrySet().forEach(entry -> {
                    if (entry.getValue().isJsonObject()) {
                        stacks.put(entry.getKey(), entry.getValue().getAsJsonObject());
                    }
                });
            }
        } catch (Exception exception) {
            LOGGER.error("Failed to load bookmark pages", exception);
            pages.clear();
            pages.add(new BookmarkPage("1"));
            currentPage.set(0);
        }
    }

    /**
     * 用 EMI 当前的收藏表刷新条目快照，解析不到的条目沿用上次记录的内容。
     */
    private static void refreshStacks() {
        Map<String, EmiFavorite> resolved = new HashMap<>();
        for (EmiFavorite favorite : EmiFavorites.favorites) {
            resolved.putIfAbsent(keyOf(favorite), favorite);
        }
        Set<String> used = new HashSet<>();
        for (BookmarkPage page : pages) {
            used.addAll(page.bookmarkKeys());
        }
        stacks.keySet().retainAll(used);
        for (String key : used) {
            EmiFavorite favorite = resolved.get(key);
            if (favorite == null) {
                continue;
            }
            try {
                JsonObject entry = new JsonObject();
                entry.add("stack", EmiIngredientSerializer.getSerialized(favorite.getStack()));
                EmiRecipe recipe = favorite.getRecipe();
                entry.addProperty("recipe", recipe == null || recipe.getId() == null ? "" : recipe.getId().toString());
                stacks.put(key, entry);
            } catch (Exception exception) {
                LOGGER.warn("Failed to serialize bookmark {}", key);
            }
        }
    }

    private static void writeJson(File file, JsonObject root) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            // createDirectories 失败会抛 IOException，比忽略 mkdirs() 的返回值安全。
            Files.createDirectories(parent.toPath());
        }
        File tmp = new File(parent, file.getName() + ".tmp");
        try (Writer writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            GSON.toJson(root, writer);
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String readFile(File file) throws IOException {
        return Files.readString(file.toPath());
    }

    // ------------------------------------------------------------------ 导入导出

    /**
     * 把指定分页导出到 bookmark_pages 目录，返回导出的文件，失败返回 null。
     */
    public static synchronized File exportPages(List<BookmarkPage> targets, String label) {
        initializeIfNeeded();
        refreshStacks();
        Set<String> used = new HashSet<>();
        for (BookmarkPage page : targets) {
            used.addAll(page.bookmarkKeys());
        }
        JsonObject stacksJson = new JsonObject();
        stacks.forEach((key, value) -> {
            if (used.contains(key)) {
                stacksJson.add(key, value);
            }
        });
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("exported", LocalDateTime.now().format(STAMP));
        root.add("stacks", stacksJson);
        JsonArray savedPages = new JsonArray();
        for (BookmarkPage page : targets) {
            savedPages.add(page.toJson());
        }
        root.add("pages", savedPages);
        String name = "emipagedbookmarks-" + safeName(label) + "-" + LocalDateTime.now().format(STAMP) + ".json";
        File file = new File(exportDir(), name);
        try {
            writeJson(file, root);
            return file;
        } catch (IOException exception) {
            LOGGER.error("Failed to export bookmark pages", exception);
            return null;
        }
    }

    /**
     * 导入一个导出文件，分页一律作为新分页追加，不会覆盖现有数据。
     *
     * @return 导入的分页数量，格式不正确时返回 -1
     */
    public static synchronized int importFrom(File file) {
        initializeIfNeeded();
        JsonObject root;
        try {
            root = GSON.fromJson(readFile(file), JsonObject.class);
        } catch (Exception exception) {
            return -1;
        }
        if (root == null || !root.has("pages") || !root.get("pages").isJsonArray()) {
            return -1;
        }
        JsonArray savedPages = root.getAsJsonArray("pages");
        Map<String, JsonObject> incoming = new HashMap<>();
        JsonObject savedStacks = root.getAsJsonObject("stacks");
        if (savedStacks != null) {
            savedStacks.entrySet().forEach(entry -> {
                if (entry.getValue().isJsonObject()) {
                    incoming.put(entry.getKey(), entry.getValue().getAsJsonObject());
                }
            });
        }
        // 尚未整理过数据时留着一个空的第一页，导入时直接顶掉，避免多出空白分页。
        if (pages.size() == 1 && pages.get(0).bookmarkKeys().isEmpty() && pages.get(0).groups().isEmpty()) {
            pages.clear();
        }
        int firstIndex = pages.size();
        int imported;
        // 整个导入过程挂起落盘：全程只有最后这一次写文件（外加 EMI 一次），
        // 否则 M 个条目就是 M 次全量序列化 + M 次写盘，上千条会卡到像是卡死。
        beginBatch();
        try {
            for (int i = 0; i < savedPages.size(); i++) {
                if (!savedPages.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject json = savedPages.get(i).getAsJsonObject();
                String name = json.has("name") ? json.get("name").getAsString() : "?";
                BookmarkPage page = appendPage(name);
                JsonArray keys = json.getAsJsonArray("bookmarks");
                if (keys != null) {
                    for (JsonElement element : keys) {
                        importKey(page, element.getAsString(), incoming);
                    }
                }
                JsonArray groups = json.getAsJsonArray("groups");
                if (groups != null) {
                    for (JsonElement element : groups) {
                        if (!element.isJsonObject()) {
                            continue;
                        }
                        FavoriteGroup source = FavoriteGroup.fromJson(element.getAsJsonObject());
                        FavoriteGroup copy = new FavoriteGroup(UUID.randomUUID(), source.startIndex(), source.endIndex());
                        copy.folded(source.folded());
                        copy.lineBreak(source.lineBreak());
                        page.groups().add(copy);
                    }
                }
            }
            imported = pages.size() - firstIndex;
            if (imported > 0) {
                currentPage.set(firstIndex);
            }
        } finally {
            endBatch();
        }
        if (imported > 0) {
            refresh();
        }
        return imported;
    }

    private static void importKey(BookmarkPage page, String key, Map<String, JsonObject> incoming) {
        JsonObject entry = incoming.get(key);
        EmiIngredient stack = entry == null || !entry.has("stack") ? null : deserializeStack(entry.get("stack"));
        if (stack != null && !stack.isEmpty()) {
            EmiRecipe recipe = entry.has("recipe") ? resolveRecipe(entry.get("recipe").getAsString()) : null;
            // 先把条目写回 EMI 的收藏表（收藏栏要拿它当实例），再按文件里记录的键值归位到本分页。
            // 批量模式下 EmiFavoritesMixin 的 hook 会跳过逐条 diff，所以这里自己 addKey。
            EmiFavorites.addFavorite(stack, recipe);
        }
        // 解析不到的条目也保留占位，避免分组索引错位。
        page.addKey(key);
    }

    /** 反序列化物品；数据损坏时返回 null，由调用方当作"解析不到"处理。 */
    private static EmiIngredient deserializeStack(JsonElement json) {
        try {
            return EmiIngredientSerializer.getDeserialized(json);
        } catch (Exception exception) {
            return null;
        }
    }

    private static EmiRecipe resolveRecipe(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            ResourceLocation location = ResourceLocation.tryParse(id);
            return location == null ? null : EmiApi.getRecipeManager().getRecipe(location);
        } catch (Exception exception) {
            return null;
        }
    }

    private static String safeName(String label) {
        String name = label == null ? "" : label.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isBlank()) {
            return "export";
        }
        return name.length() > 24 ? name.substring(0, 24) : name;
    }

    // ------------------------------------------------------------------ 内部工具

    private static synchronized void loadIfNeeded() {
        if (loaded) {
            ensurePageExists();
            return;
        }
        loaded = true;
        ensurePageExists();
        load();
        ensurePageExists();
    }

    private static void ensurePageExists() {
        if (pages.isEmpty()) {
            pages.add(new BookmarkPage("1"));
            currentPage.set(0);
        }
    }

    /**
     * 把 EMI 收藏表里还没有被任何分页认领的条目收编到第一页。
     */
    private static void adoptUnassignedFavorites() {
        ensurePageExists();
        Set<String> assigned = new HashSet<>();
        for (BookmarkPage page : pages) {
            assigned.addAll(page.bookmarkKeys());
        }
        BookmarkPage target = pages.get(0);
        boolean changed = false;
        for (EmiFavorite favorite : EmiFavorites.favorites) {
            String key = keyOf(favorite);
            if (!assigned.contains(key)) {
                assigned.add(key);
                changed |= target.addKey(key);
            }
        }
        if (changed) {
            save();
        }
    }

    private static boolean removeKeyFromPage(BookmarkPage page, String key) {
        int index = page.removeKey(key);
        if (index < 0) {
            return false;
        }
        // 调用方都持有 BookmarkPages 的锁，这里用无锁版本修正分组边界，避免锁序倒置。
        GroupManager.adjustOnRemove(page.groups(), index);
        return true;
    }

    private static boolean anyPageHasKey(String key) {
        for (BookmarkPage page : pages) {
            if (page.hasKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean otherPagesContain(String key) {
        int current = currentIndex();
        for (int i = 0; i < pages.size(); i++) {
            if (i != current && pages.get(i).hasKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static Set<EmiFavorite> identitySet(List<EmiFavorite> favorites) {
        Set<EmiFavorite> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(favorites);
        return set;
    }

    private static String uniqueName(String base) {
        String name = base == null || base.isBlank() ? "?" : base.trim();
        Set<String> taken = new HashSet<>();
        for (BookmarkPage page : pages) {
            taken.add(page.name());
        }
        if (!taken.contains(name)) {
            return name;
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = name + " (" + i + ")";
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        return name;
    }

    private static void initializeIfNeeded() {
        if (!loaded || pages.isEmpty()) {
            initialize();
        }
    }

    private static void refresh() {
        if (Minecraft.getInstance().screen != null) {
            EmiScreenManager.forceRecalculate();
        }
    }
}
