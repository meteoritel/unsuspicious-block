package com.meteorite.unsuspiciousblock.core.loottable.analysis;

import org.jetbrains.annotations.Nullable;

/** 函数登记项：描述为必需能力，预览为独立可选能力。 */
public record LootFunctionHandler(LootFunctionDescription description, @Nullable LootFunctionPreview preview) {}
