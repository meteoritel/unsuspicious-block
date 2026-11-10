package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.support.CatalogSorter;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalSearchQuery;

/** 目录投影所需的一组查询输入。 */
record CatalogQueryState(String searchText, CatalogSorter.SortOrder sortOrder,
                         boolean sortDescending, boolean hideLocked) {
    JournalSearchQuery search() {
        return JournalSearchQuery.parse(searchText);
    }
}
