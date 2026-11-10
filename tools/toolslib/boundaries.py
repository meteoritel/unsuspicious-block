"""已确认的客户端所有者与宿主 UI 边界。"""
from .java_references import Reference, Source, TypeIndex

PROJECT = 'com.meteorite.unsuspiciousblock.'
KIT = 'com.meteorite.meteosuikit.'
CLIENT_PREFIXES = (
    'net.minecraft.client.', PROJECT + 'client.', KIT,
    'com.mojang.blaze3d.', 'org.lwjgl.',
    'net.fabricmc.fabric.api.client.', 'net.fabricmc.api.ClientModInitializer',
    'net.neoforged.neoforge.client.',
)
NATIVE_WIDGETS = frozenset(
    'net.minecraft.client.gui.components.' + name for name in (
        'AbstractButton', 'AbstractWidget', 'Button', 'EditBox', 'MultiLineEditBox',
        'MultiLineTextWidget', 'Checkbox', 'CycleButton',
    )
) | frozenset('net.minecraft.client.gui.screens.' + name for name in ('ConfirmScreen', 'ConfirmLinkScreen'))


def is_client_source(source: Source) -> bool:
    return source.package == PROJECT + 'client' or source.package.startswith(PROJECT + 'client.')


def is_client_owner(source: Source, reference: Reference) -> bool:
    return is_client_source(source)


def is_client_target(target: str, index: TypeIndex) -> bool:
    if target.startswith(CLIENT_PREFIXES):
        return True
    return any(is_client_source(source) for source in index.types.get(index.owner_name(target), []))
