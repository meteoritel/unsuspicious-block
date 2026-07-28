package com.meteorite.unsuspiciousblock.entity.ai.spiritcat;

import com.meteorite.unsuspiciousblock.entity.MerchantCat;
import com.meteorite.unsuspiciousblock.entity.MessengerCat;
import com.meteorite.unsuspiciousblock.entity.SpiritCat;
import com.meteorite.unsuspiciousblock.entity.SwordsmanCat;

/**
 * 三种猫国灵体职业标识，供调试绑定、筛选和命令解析共用。
 */
public enum SpiritCatRole {
    MESSENGER("messenger"),
    SWORDSMAN("swordsman"),
    MERCHANT("merchant");

    private final String commandName;

    SpiritCatRole(String commandName) {
        this.commandName = commandName;
    }

    public String commandName() {
        return this.commandName;
    }

    public boolean matches(SpiritCat cat) {
        return switch (this) {
            case MESSENGER -> cat instanceof MessengerCat;
            case SWORDSMAN -> cat instanceof SwordsmanCat;
            case MERCHANT -> cat instanceof MerchantCat;
        };
    }

    public static SpiritCatRole parse(String name) {
        for (SpiritCatRole role : values()) {
            if (role.commandName.equalsIgnoreCase(name)) {
                return role;
            }
        }
        return null;
    }
}
