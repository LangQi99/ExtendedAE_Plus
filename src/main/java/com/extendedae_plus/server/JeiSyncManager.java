package com.extendedae_plus.server;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.security.IActionHost;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.items.tools.powered.WirelessTerminalItem;
import com.extendedae_plus.init.ModNetwork;
import com.extendedae_plus.network.jei.SyncNetworkInventoryS2CPacket;
import com.extendedae_plus.util.wireless.WirelessTerminalLocator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.*;

public class JeiSyncManager {

    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int MAX_ENTRIES_PER_PACKET = 8192;

    private static final Map<UUID, PlayerSyncState> playerStates = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer sp)) return;

        UUID playerId = sp.getUUID();
        PlayerSyncState state = playerStates.computeIfAbsent(playerId, k -> new PlayerSyncState());

        state.tickCounter++;
        if (state.tickCounter < SYNC_INTERVAL_TICKS) return;
        state.tickCounter = 0;

        var located = WirelessTerminalLocator.find(sp);
        if (located == null || located.isEmpty()) {
            if (state.wasConnected) {
                sendClear(sp);
                state.reset();
            }
            return;
        }

        IGrid grid = getGridFromTerminal(located, sp);
        if (grid == null) {
            if (state.wasConnected) {
                sendClear(sp);
                state.reset();
            }
            return;
        }

        MEStorage storage = grid.getStorageService().getInventory();
        ICraftingService craftingService = grid.getCraftingService();

        KeyCounter currentStacks = storage.getAvailableStacks();
        Set<AEKey> currentCraftables = craftingService.getCraftables(key -> true);

        boolean fullUpdate = !state.wasConnected;
        List<SyncNetworkInventoryS2CPacket.Entry> entries = new ArrayList<>();

        if (fullUpdate) {
            state.serialMap.clear();
            state.previousAmounts.clear();
            state.previousCraftables.clear();
            state.nextSerial = 1;

            for (var entry : currentStacks) {
                AEKey key = entry.getKey();
                long amount = entry.getLongValue();
                boolean craftable = currentCraftables.contains(key);
                long serial = state.nextSerial++;
                state.serialMap.put(key, serial);
                state.previousAmounts.put(key, amount);
                if (craftable) state.previousCraftables.add(key);
                entries.add(new SyncNetworkInventoryS2CPacket.Entry(serial, key, amount, craftable));
            }
            // craftable-only items (not in storage)
            for (AEKey key : currentCraftables) {
                if (!state.serialMap.containsKey(key)) {
                    long serial = state.nextSerial++;
                    state.serialMap.put(key, serial);
                    state.previousCraftables.add(key);
                    entries.add(new SyncNetworkInventoryS2CPacket.Entry(serial, key, 0, true));
                }
            }
        } else {
            // Incremental diff
            Set<AEKey> seen = new HashSet<>();

            for (var entry : currentStacks) {
                AEKey key = entry.getKey();
                long amount = entry.getLongValue();
                boolean craftable = currentCraftables.contains(key);
                seen.add(key);

                Long serial = state.serialMap.get(key);
                Long prevAmount = state.previousAmounts.get(key);
                boolean prevCraftable = state.previousCraftables.contains(key);

                if (serial == null) {
                    // new item
                    serial = state.nextSerial++;
                    state.serialMap.put(key, serial);
                    state.previousAmounts.put(key, amount);
                    if (craftable) state.previousCraftables.add(key);
                    entries.add(new SyncNetworkInventoryS2CPacket.Entry(serial, key, amount, craftable));
                } else if (prevAmount == null || prevAmount != amount || prevCraftable != craftable) {
                    // changed amount or craftable status
                    state.previousAmounts.put(key, amount);
                    if (craftable) state.previousCraftables.add(key);
                    else state.previousCraftables.remove(key);
                    entries.add(new SyncNetworkInventoryS2CPacket.Entry(serial, null, amount, craftable));
                }
            }

            // craftable-only items not in storage
            for (AEKey key : currentCraftables) {
                if (!seen.contains(key)) {
                    seen.add(key);
                    Long serial = state.serialMap.get(key);
                    boolean prevCraftable = state.previousCraftables.contains(key);
                    if (serial == null) {
                        serial = state.nextSerial++;
                        state.serialMap.put(key, serial);
                        state.previousCraftables.add(key);
                        entries.add(new SyncNetworkInventoryS2CPacket.Entry(serial, key, 0, true));
                    } else if (!prevCraftable) {
                        state.previousCraftables.add(key);
                        entries.add(new SyncNetworkInventoryS2CPacket.Entry(serial, null, 0, true));
                    }
                }
            }

            // removed items
            Iterator<Map.Entry<AEKey, Long>> it = state.serialMap.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<AEKey, Long> e = it.next();
                AEKey key = e.getKey();
                if (!seen.contains(key)) {
                    long serial = e.getValue();
                    entries.add(new SyncNetworkInventoryS2CPacket.Entry(serial, null, 0, false));
                    it.remove();
                    state.previousAmounts.remove(key);
                    state.previousCraftables.remove(key);
                }
            }
        }

        if (!entries.isEmpty()) {
            // Send in chunks if too large
            for (int i = 0; i < entries.size(); i += MAX_ENTRIES_PER_PACKET) {
                int end = Math.min(i + MAX_ENTRIES_PER_PACKET, entries.size());
                List<SyncNetworkInventoryS2CPacket.Entry> chunk = entries.subList(i, end);
                boolean isFirst = (i == 0);
                var packet = new SyncNetworkInventoryS2CPacket(fullUpdate && isFirst, chunk);
                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), packet);
            }
        }

        state.wasConnected = true;
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        playerStates.remove(event.getEntity().getUUID());
    }

    private static void sendClear(ServerPlayer sp) {
        var packet = new SyncNetworkInventoryS2CPacket(true, Collections.emptyList());
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), packet);
    }

    private static IGrid getGridFromTerminal(WirelessTerminalLocator.LocatedTerminal located, ServerPlayer player) {
        var stack = located.stack;
        if (stack.isEmpty()) return null;

        if (stack.getItem() instanceof WirelessTerminalItem wt) {
            try {
                var grid = wt.getLinkedGrid(stack, player.level(), player);
                return grid != null ? grid : null;
            } catch (Exception e) {
                return null;
            }
        }

        // Try ae2wtlib/ExtendedAE wireless terminals
        try {
            if (stack.getItem() instanceof IActionHost actionHost) {
                var node = actionHost.getActionableNode();
                return node != null ? node.getGrid() : null;
            }
        } catch (Exception ignored) {}

        return null;
    }

    private static class PlayerSyncState {
        int tickCounter = 0;
        boolean wasConnected = false;
        long nextSerial = 1;
        final Map<AEKey, Long> serialMap = new HashMap<>();
        final Map<AEKey, Long> previousAmounts = new HashMap<>();
        final Set<AEKey> previousCraftables = new HashSet<>();

        void reset() {
            wasConnected = false;
            serialMap.clear();
            previousAmounts.clear();
            previousCraftables.clear();
            nextSerial = 1;
        }
    }
}
