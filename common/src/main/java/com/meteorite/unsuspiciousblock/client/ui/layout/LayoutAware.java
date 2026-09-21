package com.meteorite.unsuspiciousblock.client.ui.layout;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground.BookLayout;

/** 可在书本布局变化时重新定位的面板。 */
public interface LayoutAware {
    void applyLayout(BookLayout layout);
}
