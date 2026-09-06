package com.lai.emipagedbookmarks.client;

import java.util.concurrent.CopyOnWriteArrayList;

public final class BookmarkPage {
    private final CopyOnWriteArrayList<String> bookmarkKeys = new CopyOnWriteArrayList<>();
    private volatile String name;

    public BookmarkPage(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public void rename(String name) {
        this.name = name.isBlank() ? "?" : name.trim();
    }

    public CopyOnWriteArrayList<String> bookmarkKeys() {
        return bookmarkKeys;
    }
}
