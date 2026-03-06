package com.extendedae_plus.mixin.extendedae.accessor;

import appeng.client.gui.me.patternaccess.PatternContainerRecord;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@OnlyIn(Dist.CLIENT)
@Mixin(targets = "com.glodblock.github.extendedae.client.gui.GuiExPatternTerminal$SlotsRow", remap = false)
public interface GuiExPatternTerminalSlotsRowAccessor {
    @Invoker("container")
    PatternContainerRecord eap$container();

    @Invoker("offset")
    int eap$offset();

    @Invoker("slots")
    int eap$slots();
}
