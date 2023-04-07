package me.neznamy.tab.platforms.bukkit;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.features.types.TabFeature;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.chat.IChatBaseComponent;
import me.neznamy.tab.platforms.bukkit.nms.storage.nms.NMSStorage;
import me.neznamy.tab.platforms.bukkit.nms.storage.packet.PacketPlayOutPlayerInfoStorage;
import me.neznamy.tab.platforms.bukkit.nms.storage.packet.PacketPlayOutPlayerInfoStorage.PlayerInfoDataStorage;
import me.neznamy.tab.platforms.bukkit.nms.storage.packet.PacketPlayOutScoreboardDisplayObjectiveStorage;
import me.neznamy.tab.platforms.bukkit.nms.storage.packet.PacketPlayOutScoreboardObjectiveStorage;
import me.neznamy.tab.platforms.bukkit.nms.storage.packet.PacketPlayOutScoreboardTeamStorage;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.features.injection.NettyPipelineInjector;
import me.neznamy.tab.shared.features.sorting.Sorting;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Pipeline injection for bukkit
 */
public class BukkitPipelineInjector extends NettyPipelineInjector {

    /**
     * Constructs new instance
     */
    public BukkitPipelineInjector() {
        super("packet_handler");
    }

    @Override
    protected Channel getChannel(TabPlayer player) {
        BukkitTabPlayer bukkit = (BukkitTabPlayer) player;
        NMSStorage nms = NMSStorage.getInstance();
        try {
            if (nms.CHANNEL != null) return (Channel) nms.CHANNEL.get(nms.NETWORK_MANAGER.get(bukkit.getPlayerConnection()));
        } catch (final IllegalAccessException exception) {
            TAB.getInstance().getErrorManager().printError("Failed to get channel of " + bukkit.getName(), exception);
        }
        return null;
    }

    @Override
    public void onDisplayObjective(TabPlayer player, Object packet) throws IllegalAccessException {
        TAB.getInstance().getFeatureManager().onDisplayObjective(player,
                PacketPlayOutScoreboardDisplayObjectiveStorage.POSITION.getInt(packet),
                (String) PacketPlayOutScoreboardDisplayObjectiveStorage.OBJECTIVE_NAME.get(packet));
    }

    @Override
    public void onObjective(TabPlayer player, Object packet) throws IllegalAccessException {
        TAB.getInstance().getFeatureManager().onObjective(player,
                PacketPlayOutScoreboardObjectiveStorage.METHOD.getInt(packet),
                (String) PacketPlayOutScoreboardObjectiveStorage.OBJECTIVE_NAME.get(packet));
    }

    @Override
    public boolean isDisplayObjective(Object packet) {
        return PacketPlayOutScoreboardDisplayObjectiveStorage.CLASS.isInstance(packet);
    }

    @Override
    public boolean isObjective(Object packet) {
        return PacketPlayOutScoreboardObjectiveStorage.CLASS.isInstance(packet);
    }

    @Override
    public boolean isTeam(Object packet) {
        return PacketPlayOutScoreboardTeamStorage.CLASS.isInstance(packet);
    }

    @Override
    public boolean isPlayerInfo(Object packet) {
        return PacketPlayOutPlayerInfoStorage.CLASS.isInstance(packet);
    }

    @Override
    public void onPlayerInfo(TabPlayer receiver, Object packet) throws ReflectiveOperationException {
        NMSStorage nms = NMSStorage.getInstance();
        List<String> actions;
        if (PacketPlayOutPlayerInfoStorage.ClientboundPlayerInfoRemovePacket != null) {
            //1.19.3+
            actions = ((EnumSet<?>)PacketPlayOutPlayerInfoStorage.ACTION.get(packet)).stream().map(Enum::name).collect(Collectors.toList());
        } else {
            //1.19.2-
            actions = Collections.singletonList(PacketPlayOutPlayerInfoStorage.ACTION.get(packet).toString());
        }
        List<Object> updatedList = new ArrayList<>();
        for (Object nmsData : (List<?>) PacketPlayOutPlayerInfoStorage.PLAYERS.get(packet)) {
            GameProfile profile = (GameProfile) PlayerInfoDataStorage.PlayerInfoData_getProfile.invoke(nmsData);
            Object displayName = null;
            if (actions.contains("UPDATE_DISPLAY_NAME") || actions.contains("ADD_PLAYER")) {
                displayName = PlayerInfoDataStorage.PlayerInfoData_DisplayName.get(nmsData);
                IChatBaseComponent newDisplayName = TAB.getInstance().getFeatureManager().onDisplayNameChange(receiver, profile.getId());
                if (newDisplayName != null) displayName = nms.toNMSComponent(newDisplayName, receiver.getVersion());
                if (!nms.is1_19_3Plus()) PlayerInfoDataStorage.PlayerInfoData_DisplayName.set(nmsData, displayName);
            }
            if (actions.contains("ADD_PLAYER")) {
                TAB.getInstance().getFeatureManager().onEntryAdd(receiver, profile.getId(), profile.getName());
            }
            if (nms.is1_19_3Plus()) {
                // 1.19.3 is using records, which do not allow changing final fields, need to rewrite the list entirely
                updatedList.add(PlayerInfoDataStorage.newPlayerInfoData.newInstance(
                        profile.getId(),
                        profile,
                        PlayerInfoDataStorage.PlayerInfoData_Listed.getBoolean(nmsData),
                        PlayerInfoDataStorage.PlayerInfoData_Latency.getInt(nmsData),
                        PlayerInfoDataStorage.PlayerInfoData_GameMode.get(nmsData),
                        displayName,
                        PlayerInfoDataStorage.PlayerInfoData_RemoteChatSession.get(nmsData)));
            }
        }
        if (nms.is1_19_3Plus()) {
            PacketPlayOutPlayerInfoStorage.PLAYERS.set(packet, updatedList);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void modifyPlayers(Object packetPlayOutScoreboardTeam) throws ReflectiveOperationException {
        int action = PacketPlayOutScoreboardTeamStorage.ACTION.getInt(packetPlayOutScoreboardTeam);
        if (action == 1 || action == 2 || action == 4) return;
        Collection<String> players = (Collection<String>) PacketPlayOutScoreboardTeamStorage.PLAYERS.get(packetPlayOutScoreboardTeam);
        String teamName = (String) PacketPlayOutScoreboardTeamStorage.NAME.get(packetPlayOutScoreboardTeam);
        if (players == null) return;
        //creating a new list to prevent NoSuchFieldException in minecraft packet encoder when a player is removed
        Collection<String> newList = new ArrayList<>();
        for (String entry : players) {
            TabPlayer p = getPlayer(entry);
            if (p == null) {
                newList.add(entry);
                continue;
            }
            Sorting sorting = TAB.getInstance().getFeatureManager().getFeature(TabConstants.Feature.SORTING);
            if (!((TabFeature)TAB.getInstance().getTeamManager()).isDisabledPlayer(p) &&
                    !TAB.getInstance().getTeamManager().hasTeamHandlingPaused(p) && !teamName.equals(sorting.getShortTeamName(p))) {
                logTeamOverride(teamName, p.getName(), sorting.getShortTeamName(p));
            } else {
                newList.add(entry);
            }
        }
        PacketPlayOutScoreboardTeamStorage.PLAYERS.set(packetPlayOutScoreboardTeam, newList);
    }
}