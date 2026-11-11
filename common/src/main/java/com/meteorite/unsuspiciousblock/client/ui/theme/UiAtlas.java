package com.meteorite.unsuspiciousblock.client.ui.theme;

import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.meteosuikit.api.render.UiNineSlice;
import com.meteorite.meteosuikit.api.style.UiControlPainter;
import com.meteorite.meteosuikit.api.style.UiControlSkin;
import com.meteorite.meteosuikit.api.style.UiControlStyle;
import com.meteorite.meteosuikit.api.style.UiSkinImages;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.resources.ResourceLocation;

import java.util.EnumMap;
import java.util.Map;

/**
 * 全模组共享 UI 图标与控件皮肤图集注册表，统一从 {@code textures/gui/ui_atlas.png}（256×128 px）取样。
 */
public final class UiAtlas {
    /** 统一 UI 图集资源路径。 */
    public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/ui_atlas.png");

    /** 图集总宽度（像素）。 */
    public static final int ATLAS_WIDTH = 256;

    /** 图集总高度（像素）。 */
    public static final int ATLAS_HEIGHT = 128;

    /** Zone 1 常规小图标单元格边长（像素）。 */
    public static final int ICON_SIZE = 10;

    /** Zone 1（10×10 网格）所有功能图标与行内状态标识的槽位定义。 */
    public enum Icon {
        SEARCH(0, 0),
        CLOSE(1, 0),
        ARROW_UP(2, 0),
        ARROW_DOWN(3, 0),
        SHOW_LOCKED(4, 0),
        HIDE_LOCKED(5, 0),
        SORT_DEFAULT(6, 0),
        SORT_NAME(7, 0),
        SORT_ITEM_COUNT(8, 0),
        SORT_FAVORITE(9, 0),
        SORT_UNLOCK(10, 0),
        SORT_UPDATE_TIME(11, 0),

        BACK(0, 1),
        ENABLED(1, 1),
        CHECK(1, 1),
        DISABLED(2, 1),
        REDSTONE(3, 1),
        GEAR(4, 1),
        HELP(5, 1),
        PENCIL(6, 1),
        CLOCK(7, 1),
        BIOME(8, 1),
        DIMENSION(9, 1),
        TRASH_EMPTY(10, 1),
        TRASH_FULL(11, 1),

        SQUARE(0, 2),
        GROUP(1, 2),
        COPY(2, 2),
        EXPAND(3, 2),
        FIT(4, 2),
        PLUS(5, 2),
        MINUS(6, 2),
        PARAMS(7, 2),
        CALCULATE(8, 2),
        RECOMMEND(9, 2),
        CHEVRON_DOWN(10, 2),
        CHEVRON_UP(11, 2),

        TREE_COLLAPSED(0, 3),
        TREE_EXPANDED(1, 3),
        FAVORITE_BADGE(2, 3),
        CHILD_BADGE(3, 3),
        NOTE_BADGE(4, 3),
        NOTE_BADGE_HOVER(5, 3),
        CHECKBOX_UNCHECKED(6, 3),
        CHECKBOX_CHECKED(7, 3),
        BRANCH_ARROW(8, 3),
        HELP_CREAM(9, 3),
        DARK_TREE_COLLAPSED(10, 3),
        DARK_TREE_EXPANDED(11, 3),

        DARK_TRACKED(0, 4),
        DARK_UNTRACKED(1, 4),
        POTTERY_ENABLED(2, 4),
        POTTERY_DISABLED(3, 4),
        POTTERY_REDSTONE(4, 4);

        private final int column;
        private final int row;

        Icon(int column, int row) {
            this.column = column;
            this.row = row;
        }

        /** @return 在 Zone 1 网格中的列号（从 0 起）。 */
        public int column() {
            return column;
        }

        /** @return 在 Zone 1 网格中的行号（从 0 起）。 */
        public int row() {
            return row;
        }
    }

    /** 书本左侧阶梯标签底板种类。 */
    public enum SideTabKind {
        MANAGEMENT(52),
        HELP(72);

        private final int atlasV;

        SideTabKind(int atlasV) {
            this.atlasV = atlasV;
        }
    }

    private static final Map<Icon, UiIcon> ICONS = buildIconMap();
    private static final UiIcon[] STATUS_BADGES = {
            sprite(128, 0, 12, 12),
            sprite(140, 0, 12, 12),
            sprite(152, 0, 12, 12),
            sprite(164, 0, 12, 12)
    };

    /** 未知/未解锁物品与路径槽位的 16×16 占位图标。 */
    public static final UiIcon UNKNOWN_ITEM = sprite(180, 0, 16, 16);

    /** 猫之恩惠 HUD 专用可染色中性猫爪图标（16×16）。 */
    public static final UiIcon CAT_FAVOR_HUD = sprite(196, 0, 16, 16);

    /** 欢迎页 GitHub 外链 16×16 像素图标。 */
    public static final UiIcon EXTERNAL_GITHUB = sprite(128, 18, 16, 16);

    /** 欢迎页 CurseForge 外链 16×16 像素图标。 */
    public static final UiIcon EXTERNAL_CURSEFORGE = sprite(144, 18, 16, 16);

    /** 欢迎页 Modrinth 外链 16×16 像素图标。 */
    public static final UiIcon EXTERNAL_MODRINTH = sprite(160, 18, 16, 16);

    /** 场景条件框与浮层的统一 9-slice 羊皮纸底板。 */
    public static final UiNineSlice SCENARIO_PANEL = new UiNineSlice(
            TEXTURE, 164, 52, 12, 3, ATLAS_WIDTH, ATLAS_HEIGHT);

    private static final UiSkinImages.Image PARCHMENT_NORMAL = nineSliceImage(104, 52);
    private static final UiSkinImages.Image PARCHMENT_HOVER = nineSliceImage(116, 52);
    private static final UiSkinImages.Image PARCHMENT_PRESSED = nineSliceImage(128, 52);
    private static final UiSkinImages.Image PARCHMENT_SELECTED = nineSliceImage(140, 52);
    private static final UiSkinImages.Image PARCHMENT_DISABLED = nineSliceImage(152, 52);

    private static final UiSkinImages.Image ACCENT_NORMAL = nineSliceImage(104, 64);
    private static final UiSkinImages.Image ACCENT_HOVER = nineSliceImage(116, 64);
    private static final UiSkinImages.Image ACCENT_PRESSED = nineSliceImage(128, 64);
    private static final UiSkinImages.Image ACCENT_DISABLED = nineSliceImage(140, 64);

    private static final UiSkinImages.Image QUIET_NORMAL = nineSliceImage(104, 76);

    private static final UiControlStyle READOUT_STYLE = new UiControlStyle(
            0, 0, 0, 0, 0,
            UiControlStyle.PARCHMENT.focusOutline(),
            UiControlStyle.PARCHMENT.scrollbarTrack(),
            UiControlStyle.PARCHMENT.scrollbarThumb());

    private static final UiControlSkin BASE_BODY_SKIN = new UiControlSkin(
            UiControlStyle.PARCHMENT,
            UiControlPainter.FILL,
            UiTextScheme.PARCHMENT.body(),
            UiTextScheme.PARCHMENT.hint());

    private static final UiControlSkin BASE_TITLE_SKIN = new UiControlSkin(
            UiControlStyle.PARCHMENT,
            UiControlPainter.FILL,
            UiTextScheme.PARCHMENT.title(),
            UiTextScheme.PARCHMENT.hint());

    /** 常规羊皮纸按钮 9-slice 皮肤（用于工具栏、目录、返回及一般浮层按钮）。 */
    public static final UiControlSkin PARCHMENT_BUTTON_SKIN = new UiSkinImages(
            PARCHMENT_NORMAL, PARCHMENT_HOVER, PARCHMENT_PRESSED,
            PARCHMENT_SELECTED, PARCHMENT_DISABLED, PARCHMENT_HOVER
    ).control(BASE_BODY_SKIN);

    /** 铜金强调按钮 9-slice 皮肤（用于开始模拟、保存并计算等主路径操作）。 */
    public static final UiControlSkin ACCENT_BUTTON_SKIN = new UiSkinImages(
            ACCENT_NORMAL, ACCENT_HOVER, ACCENT_PRESSED,
            ACCENT_HOVER, ACCENT_DISABLED, ACCENT_HOVER
    ).control(BASE_TITLE_SKIN);

    /** 轻量内嵌按钮 9-slice 皮肤（用于页内下拉标题与复制坐标按钮）。 */
    public static final UiControlSkin QUIET_BUTTON_SKIN = new UiSkinImages(
            QUIET_NORMAL, PARCHMENT_HOVER, PARCHMENT_PRESSED,
            PARCHMENT_SELECTED, PARCHMENT_DISABLED, PARCHMENT_HOVER
    ).control(BASE_BODY_SKIN);

    /** 禁用态羊皮纸按钮 9-slice 皮肤。 */
    public static final UiControlSkin PARCHMENT_DISABLED_SKIN = new UiSkinImages(
            PARCHMENT_DISABLED
    ).control(BASE_BODY_SKIN);

    /** 透明只读标签皮肤（用于场景页只读数值与小节标题）。 */
    public static final UiControlSkin READOUT_SKIN = new UiControlSkin(
            READOUT_STYLE, UiControlPainter.FILL,
            UiTextScheme.PARCHMENT.body(), UiTextScheme.PARCHMENT.hint());

    private static final UiIcon[] PAGE_PREV_ARROWS = {
            sprite(0, 52, 23, 13),
            sprite(0, 65, 23, 13),
            sprite(0, 78, 23, 13)
    };
    private static final UiIcon[] PAGE_NEXT_ARROWS = {
            sprite(23, 52, 23, 13),
            sprite(23, 65, 23, 13),
            sprite(23, 78, 23, 13)
    };
    private static final UiIcon[][] SIDE_TAB_PLATES = {
            {sprite(50, SideTabKind.MANAGEMENT.atlasV, 24, 20), sprite(74, SideTabKind.MANAGEMENT.atlasV, 24, 20)},
            {sprite(50, SideTabKind.HELP.atlasV, 24, 20), sprite(74, SideTabKind.HELP.atlasV, 24, 20)}
    };

    private UiAtlas() {
    }

    /** 获取指定 10×10 图集图标。 */
    public static UiIcon icon(Icon icon) {
        return ICONS.get(icon);
    }

    /** 根据场景计算状态返回对应 12×12 状态角标。 */
    public static UiIcon statusBadge(String status) {
        int slot = switch (status) {
            case "pending", "querying", "retrieving" -> 1;
            case "cached", "available" -> 2;
            case "failed", "query_failed" -> 3;
            default -> 0;
        };
        return STATUS_BADGES[slot];
    }

    /** 获取右页底部 23×13 翻页箭头精灵。 */
    public static UiIcon pageArrow(boolean forward, boolean disabled, boolean highlighted) {
        int index = disabled ? 2 : highlighted ? 1 : 0;
        return forward ? PAGE_NEXT_ARROWS[index] : PAGE_PREV_ARROWS[index];
    }

    /** 获取书本左侧 24×20 阶梯标签底板精灵。 */
    public static UiIcon sideTabPlate(SideTabKind kind, boolean highlighted) {
        return SIDE_TAB_PLATES[kind.ordinal()][highlighted ? 1 : 0];
    }

    private static Map<Icon, UiIcon> buildIconMap() {
        EnumMap<Icon, UiIcon> map = new EnumMap<>(Icon.class);
        for (Icon icon : Icon.values()) {
            map.put(icon, sprite(icon.column * ICON_SIZE, icon.row * ICON_SIZE, ICON_SIZE, ICON_SIZE));
        }
        return Map.copyOf(map);
    }

    private static UiIcon sprite(int u, int v, int width, int height) {
        return new UiIcon.Sprite(TEXTURE, u, v, width, height, ATLAS_WIDTH, ATLAS_HEIGHT);
    }

    private static UiSkinImages.Image nineSliceImage(int u, int v) {
        return UiSkinImages.Image.nineSlice(new UiNineSlice(TEXTURE, u, v, 12, 2, ATLAS_WIDTH, ATLAS_HEIGHT));
    }
}
