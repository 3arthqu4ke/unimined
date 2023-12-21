package xyz.wagyourtail.unimined.api.minecraft.patch.bukkit

import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.minecraft.patch.MinecraftPatcher

interface CraftbukkitPatcher : MinecraftPatcher {

    @get:ApiStatus.Internal
    var loader: String

    fun loader(version: String) {
        this.loader = version
    }

}