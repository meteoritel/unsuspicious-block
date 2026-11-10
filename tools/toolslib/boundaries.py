"""已确认的客户端所有者与宿主 UI 边界。"""
from .java_references import Reference, Source, TypeIndex

PROJECT = 'com.meteorite.unsuspiciousblock.'
KIT = 'com.meteorite.meteosuikit.'
PAYLOADS = PROJECT + 'network.ModPayloads'
CLIENT_FILES = frozenset({
    'common/src/main/java/com/meteorite/unsuspiciousblock/plugin/jei/JeiPlugin.java',
    'common/src/main/java/com/meteorite/unsuspiciousblock/plugin/jei/JournalJeiGuiHandler.java',
    'common/src/main/java/com/meteorite/unsuspiciousblock/plugin/jei/PotteryWheelJeiCategory.java',
    'fabric/src/main/java/com/meteorite/unsuspiciousblock/UnsuspiciousBlockFabricClient.java',
    'neoforge/src/main/java/com/meteorite/unsuspiciousblock/UnsuspiciousBlockNeoForgeClient.java',
    'neoforge/src/main/java/com/meteorite/unsuspiciousblock/UnsuspiciousBlockNeoForgeClientMod.java',
})
MODMENU = 'fabric/src/main/java/com/meteorite/unsuspiciousblock/modmenu/'
NATIVE_WIDGETS = frozenset(
    'net.minecraft.client.gui.components.' + name for name in (
        'AbstractButton', 'AbstractWidget', 'Button', 'EditBox', 'MultiLineEditBox',
        'MultiLineTextWidget', 'Checkbox', 'CycleButton',
    )
) | frozenset('net.minecraft.client.gui.screens.' + name for name in ('ConfirmScreen', 'ConfirmLinkScreen'))


def is_client_source(source: Source) -> bool:
    return '/client/' in source.relative or source.relative in CLIENT_FILES or source.relative.startswith(MODMENU)


def is_client_owner(source: Source, reference: Reference) -> bool:
    return is_client_source(source) or reference.owner in (PAYLOADS + '.ClientHandlers', PAYLOADS + '.Client') or reference.owner.startswith(PAYLOADS + '.Client.')


def is_client_target(target: str, index: TypeIndex) -> bool:
    if target.startswith(('net.minecraft.client.', PROJECT + 'client.', KIT, PAYLOADS + '.Client.')):
        return True
    if target == PAYLOADS + '.Client':
        return True
    return any(is_client_source(source) for source in index.types.get(index.owner_name(target), []))
