package com.extendedae_plus.network.crafting;

import com.extendedae_plus.integration.jei.JeiRuntimeProxy;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C: 将缺失的合成材料发送到客户端，由客户端添加到 JEI 书签。
 * 在上传新样板后，若合成模拟发现材料不足时发送。
 */
public class AddJeiBookmarksS2CPacket {

    private final List<ItemStack> missingItems;

    public AddJeiBookmarksS2CPacket(List<ItemStack> missingItems) {
        this.missingItems = missingItems;
    }

    public static void encode(AddJeiBookmarksS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.missingItems.size());
        for (ItemStack stack : msg.missingItems) {
            buf.writeItem(stack);
        }
    }

    public static AddJeiBookmarksS2CPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<ItemStack> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            items.add(buf.readItem());
        }
        return new AddJeiBookmarksS2CPacket(items);
    }

    public static void handle(AddJeiBookmarksS2CPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        var ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> handleClient(msg));
        ctx.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(AddJeiBookmarksS2CPacket msg) {
        if (!ModList.get().isLoaded("jei")) return;
        for (ItemStack stack : msg.missingItems) {
            if (!stack.isEmpty()) {
                JeiRuntimeProxy.addBookmark(stack);
            }
        }
    }
}
