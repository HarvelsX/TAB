package me.neznamy.tab.platforms.bukkit;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import me.neznamy.tab.platforms.bukkit.nms.storage.nms.NMSStorage;
import me.neznamy.tab.shared.ProtocolVersion;
import me.neznamy.tab.shared.chat.IChatBaseComponent;
import me.neznamy.tab.shared.platform.TabList;
import me.neznamy.tab.shared.util.ReflectionUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * TabList which support modifying many entries at once
 * for significantly better performance. For 1.7 players,
 * ViaVersion properly splits the packet into multiple, so
 * we don't need to worry about that here.
 * <p>
 * This class does not support server versions of 1.7 and
 * below, because of the massive differences in tab list
 * and packet fields.
 */
@RequiredArgsConstructor
@SuppressWarnings({"unchecked", "rawtypes"})
public class BukkitTabList implements TabList {

    // NMS Fields
    public static Class<?> PacketPlayOutPlayerListHeaderFooterClass;
    public static Constructor<?> newPacketPlayOutPlayerListHeaderFooter;
    public static Field HEADER;
    public static Field FOOTER;

    public static Class<?> PacketPlayOutPlayerInfoClass;
    public static Constructor<?> newPacketPlayOutPlayerInfo;
    public static Field ACTION;
    public static Field PLAYERS;
    public static Class<Enum> EnumPlayerInfoActionClass;
    public static Class<Enum> EnumGamemodeClass;
    public static Class<?> ClientboundPlayerInfoRemovePacket;
    public static Class<?> RemoteChatSession$Data;
    public static Constructor<?> newClientboundPlayerInfoRemovePacket;

    public static Class<?> PlayerInfoDataClass;
    public static Constructor<?> newPlayerInfoData;
    public static Method PlayerInfoData_getProfile;
    public static Field PlayerInfoData_Latency;
    public static Field PlayerInfoData_GameMode;
    public static Field PlayerInfoData_DisplayName;
    public static Field PlayerInfoData_Listed;
    public static Field PlayerInfoData_RemoteChatSession;

    /** Player this TabList belongs to */
    private final BukkitTabPlayer player;

    public static void load(NMSStorage nms) throws NoSuchMethodException {
        if (nms.getMinorVersion() >= 17) {
            newPacketPlayOutPlayerListHeaderFooter = PacketPlayOutPlayerListHeaderFooterClass.getConstructor(nms.IChatBaseComponent, nms.IChatBaseComponent);
        } else {
            newPacketPlayOutPlayerListHeaderFooter = PacketPlayOutPlayerListHeaderFooterClass.getConstructor();
            HEADER = ReflectionUtils.getFields(PacketPlayOutPlayerListHeaderFooterClass, nms.IChatBaseComponent).get(0);
            FOOTER = ReflectionUtils.getFields(PacketPlayOutPlayerListHeaderFooterClass, nms.IChatBaseComponent).get(1);
        }
        if (nms.is1_19_3Plus()) {
            newClientboundPlayerInfoRemovePacket = ClientboundPlayerInfoRemovePacket.getConstructor(List.class);
            newPacketPlayOutPlayerInfo = PacketPlayOutPlayerInfoClass.getConstructor(EnumSet.class, Collection.class);
            ACTION = ReflectionUtils.getOnlyField(PacketPlayOutPlayerInfoClass, EnumSet.class);
        } else {
            newPacketPlayOutPlayerInfo = PacketPlayOutPlayerInfoClass.getConstructor(EnumPlayerInfoActionClass, Array.newInstance(nms.EntityPlayer, 0).getClass());
            ACTION = ReflectionUtils.getOnlyField(PacketPlayOutPlayerInfoClass, EnumPlayerInfoActionClass);
        }
        PLAYERS = ReflectionUtils.getOnlyField(PacketPlayOutPlayerInfoClass, List.class);
        newPlayerInfoData = ReflectionUtils.getOnlyConstructor(PlayerInfoDataClass);
        PlayerInfoData_getProfile = ReflectionUtils.getOnlyMethod(PlayerInfoDataClass, GameProfile.class);
        PlayerInfoData_Latency = ReflectionUtils.getOnlyField(PlayerInfoDataClass, int.class);
        PlayerInfoData_GameMode = ReflectionUtils.getOnlyField(PlayerInfoDataClass, EnumGamemodeClass);
        PlayerInfoData_DisplayName = ReflectionUtils.getOnlyField(PlayerInfoDataClass, nms.IChatBaseComponent);
        if (nms.is1_19_3Plus()) {
            PlayerInfoData_Listed = ReflectionUtils.getOnlyField(PlayerInfoDataClass, boolean.class);
            PlayerInfoData_RemoteChatSession = ReflectionUtils.getOnlyField(PlayerInfoDataClass, RemoteChatSession$Data);
        }
    }

    @Override
    @SneakyThrows
    public void removeEntry(@NotNull UUID entry) {
        if (ClientboundPlayerInfoRemovePacket != null) {
            //1.19.3+
            player.sendPacket(newClientboundPlayerInfoRemovePacket.newInstance(Collections.singletonList(entry)));
        } else {
            //1.19.2-
            player.sendPacket(createPacket(Action.REMOVE_PLAYER, new Entry.Builder(entry).build(), player.getVersion()));
        }
    }

    @Override
    public void updateDisplayName(@NotNull UUID entry, @Nullable IChatBaseComponent displayName) {
        player.sendPacket(createPacket(Action.UPDATE_DISPLAY_NAME,
                new Entry.Builder(entry).displayName(displayName).build(), player.getVersion())
        );
    }

    @Override
    public void updateLatency(@NotNull UUID entry, int latency) {
        player.sendPacket(createPacket(Action.UPDATE_LATENCY,
                new Entry.Builder(entry).latency(latency).build(), player.getVersion())
        );
    }

    @Override
    public void updateGameMode(@NotNull UUID entry, int gameMode) {
        player.sendPacket(createPacket(Action.UPDATE_GAME_MODE,
                new Entry.Builder(entry).gameMode(gameMode).build(), player.getVersion())
        );
    }

    @Override
    public void addEntry(@NotNull Entry entry) {
        player.sendPacket(createPacket(Action.ADD_PLAYER, entry, player.getVersion()));
    }

    @Override
    @SneakyThrows
    public void setPlayerListHeaderFooter(@NotNull IChatBaseComponent header, @NotNull IChatBaseComponent footer) {
        if (PacketPlayOutPlayerListHeaderFooterClass == null) return;
        NMSStorage nms = NMSStorage.getInstance();
        Object packet;
        if (nms.getMinorVersion() >= 17) {
            packet = newPacketPlayOutPlayerListHeaderFooter.newInstance(
                    nms.toNMSComponent(header, player.getVersion()), nms.toNMSComponent(footer, player.getVersion()));
        } else {
            packet = newPacketPlayOutPlayerListHeaderFooter.newInstance();
            HEADER.set(packet, nms.toNMSComponent(header, player.getVersion()));
            FOOTER.set(packet, nms.toNMSComponent(footer, player.getVersion()));
        }
        player.sendPacket(packet);
    }

    @SneakyThrows
    private Object createPacket(TabList.Action action, TabList.Entry entry, ProtocolVersion clientVersion) {
        NMSStorage nms = NMSStorage.getInstance();
        if (nms.getMinorVersion() < 8) return null;
        Object packet;
        List<Object> players = new ArrayList<>();
        if (NMSStorage.getInstance().is1_19_3Plus()) {
            EnumSet<?> actions;
            if (action == TabList.Action.ADD_PLAYER) {
                actions = EnumSet.allOf(EnumPlayerInfoActionClass);
            } else {
                actions = EnumSet.of(Enum.valueOf(EnumPlayerInfoActionClass, action.name()));
            }
            packet = newPacketPlayOutPlayerInfo.newInstance(actions, Collections.emptyList());
            GameProfile profile = new GameProfile(entry.getUniqueId(), entry.getName());
            if (entry.getSkin() != null) profile.getProperties().put(TabList.TEXTURES_PROPERTY,
                    new Property(TabList.TEXTURES_PROPERTY, entry.getSkin().getValue(), entry.getSkin().getSignature()));
            players.add(newPlayerInfoData.newInstance(
                    entry.getUniqueId(),
                    profile,
                    true,
                    entry.getLatency(),
                    int2GameMode(entry.getGameMode()),
                    entry.getDisplayName() == null ? null : nms.toNMSComponent(entry.getDisplayName(), clientVersion),
                    null
            ));
        } else {
            packet = newPacketPlayOutPlayerInfo.newInstance(Enum.valueOf(EnumPlayerInfoActionClass, action.name()),
                    Array.newInstance(NMSStorage.getInstance().EntityPlayer, 0));
            GameProfile profile = new GameProfile(entry.getUniqueId(), entry.getName());
            if (entry.getSkin() != null) profile.getProperties().put(TabList.TEXTURES_PROPERTY,
                    new Property(TabList.TEXTURES_PROPERTY, entry.getSkin().getValue(), entry.getSkin().getSignature()));
            List<Object> parameters = new ArrayList<>();
            if (newPlayerInfoData.getParameterTypes()[0] == PacketPlayOutPlayerInfoClass) {
                parameters.add(packet);
            }
            parameters.add(profile);
            parameters.add(entry.getLatency());
            parameters.add(int2GameMode(entry.getGameMode()));
            parameters.add(entry.getDisplayName() == null ? null : nms.toNMSComponent(entry.getDisplayName(), clientVersion));
            if (nms.getMinorVersion() >= 19) parameters.add(null);
            players.add(newPlayerInfoData.newInstance(parameters.toArray()));
        }
        PLAYERS.set(packet, players);
        return packet;
    }

    private Object int2GameMode(int gameMode) {
        switch (gameMode) {
            case 1: return Enum.valueOf(EnumGamemodeClass, "CREATIVE");
            case 2: return Enum.valueOf(EnumGamemodeClass, "ADVENTURE");
            case 3: return Enum.valueOf(EnumGamemodeClass, "SPECTATOR");
            default: return Enum.valueOf(EnumGamemodeClass, "SURVIVAL");
        }
    }
}
