# Journal Icon Atlas

`common/src/main/resources/assets/unsuspiciousblock/textures/gui/journal_icon_atlas.png`

The atlas is a transparent `176x16` PNG. Each icon occupies one `16x16` cell and is drawn at native pixel size. The atlas is sampled without scaling by `IconButton`.

| Cell | Icon enum | Usage |
| ---: | --- | --- |
| 0 | `SEARCH` | Open search |
| 1 | `CLOSE` | Close search |
| 2 | `SORT_DEFAULT` | Default sort |
| 3 | `SORT_NAME` | Sort by name |
| 4 | `SORT_UNLOCK` | Sort by unlock status |
| 5 | `SORT_ITEM_COUNT` | Sort by item count |
| 6 | `SORT_FAVORITE` | Favorites first |
| 7 | `ARROW_UP` | Ascending |
| 8 | `ARROW_DOWN` | Descending |
| 9 | `SHOW_LOCKED` | Locked entries shown |
| 10 | `HIDE_LOCKED` | Locked entries hidden |

Run `python scripts/generate_journal_icon_atlas.py` after changing the deterministic source shapes. Photoshop edits can be made directly in the PNG while preserving the `16x16` cell boundaries.
