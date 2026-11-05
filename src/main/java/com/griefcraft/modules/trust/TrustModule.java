/*
 * Copyright 2011 Tyler Blair. All rights reserved.
 */

package com.griefcraft.modules.trust;

import com.griefcraft.lwc.LWC;
import com.griefcraft.model.Protection;
import com.griefcraft.scripting.JavaModule;
import com.griefcraft.scripting.event.LWCAccessEvent;
import com.griefcraft.scripting.event.LWCCommandEvent;
import com.griefcraft.scripting.event.LWCReloadEvent;
import com.griefcraft.util.UUIDRegistry;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TrustModule extends JavaModule {

    private final Map<UUID, Set<UUID>> trusts = new HashMap<UUID, Set<UUID>>();
    private final Set<UUID> loadedOwners = new HashSet<UUID>();
    private File file;

    @Override
    public void load(LWC lwc) {
        file = new File(lwc.getPlugin().getDataFolder(), "trust.yml");
        loadLegacyTrusts();
    }

    @Override
    public void onReload(LWCReloadEvent event) {
        loadLegacyTrusts();
    }

    @Override
    public void onAccessRequest(LWCAccessEvent event) {
        Protection protection = event.getProtection();
        Player player = event.getPlayer();

        if (protection != null && player != null && isTrusted(protection.getOwner(), player.getUniqueId())) {
            event.setAccess(com.griefcraft.model.Permission.Access.ADMIN);
        }
    }

    @Override
    public void onCommand(LWCCommandEvent event) {
        if (event.isCancelled() || !event.hasFlag("trust", "untrust")) {
            return;
        }

        event.setCancelled(true);
        LWC lwc = event.getLWC();
        CommandSender sender = event.getSender();

        if (!(sender instanceof Player)) {
            lwc.sendLocale(sender, "lwc.onlyrealplayers");
            return;
        }

        if (!lwc.hasPlayerPermission(sender, "lwc.trust")) {
            lwc.sendLocale(sender, "protection.accessdenied");
            return;
        }

        String[] args = event.getArgs();
        if (args.length != 1) {
            lwc.sendSimpleUsage(sender, "/lwc " + event.getCommand() + " <player>");
            return;
        }

        final Player player = (Player) sender;
        final String targetName = args[0];
        final boolean trust = event.hasFlag("trust");
        Player onlineTarget = Bukkit.getPlayerExact(targetName);
        UUID target = onlineTarget == null ? parseUuid(targetName) : onlineTarget.getUniqueId();
        if (target == null && !UUIDRegistry.isValidUUID(targetName)) {
            resolveTargetAsync(lwc, sender, player, targetName, trust);
            return;
        }

        applyTrust(lwc, sender, player, target, targetName, trust);
    }

    private void resolveTargetAsync(final LWC lwc, final CommandSender sender,
                                    final Player ownerPlayer, final String targetName,
                                    final boolean trust) {
        lwc.getPlugin().getServer().getScheduler().scheduleAsyncDelayedTask(lwc.getPlugin(), new Runnable() {
            @Override
            public void run() {
                final UUID target = UUIDRegistry.getUUID(targetName);
                lwc.getPlugin().getServer().getScheduler().scheduleSyncDelayedTask(lwc.getPlugin(), new Runnable() {
                    @Override
                    public void run() {
                        if (target == null) {
                            lwc.sendLocale(sender, "lwc.trust.playernotfound", "name", targetName);
                            return;
                        }
                        applyTrust(lwc, sender, ownerPlayer, target, targetName, trust);
                    }
                });
            }
        });
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void applyTrust(final LWC lwc, CommandSender sender, Player player,
                            UUID target, String targetName, boolean trust) {
        if (target == null) {
            lwc.sendLocale(sender, "lwc.trust.playernotfound", "name", targetName);
            return;
        }

        UUID owner = player.getUniqueId();
        loadOwner(owner);
        if (owner.equals(target)) {
            lwc.sendLocale(sender, "lwc.trust.self");
            return;
        }

        Set<UUID> trusted = trusts.get(owner);
        boolean changed;

        if (trust) {
            if (trusted == null) {
                trusted = new HashSet<UUID>();
                trusts.put(owner, trusted);
            }
            changed = trusted.add(target);
        } else {
            changed = trusted != null && trusted.remove(target);
            if (trusted != null && trusted.isEmpty()) {
                trusts.remove(owner);
            }
        }

        if (changed) {
            final UUID databaseOwner = owner;
            final UUID databaseTarget = target;
            lwc.getDatabaseThread().addTask(new Runnable() {
                @Override
                public void run() {
                    if (trust) {
                        lwc.getPhysicalDatabase().addTrust(databaseOwner, databaseTarget);
                    } else {
                        lwc.getPhysicalDatabase().removeTrust(databaseOwner, databaseTarget);
                    }
                }
            });
        }

        lwc.sendLocale(sender, trust ? "lwc.trust.added" : "lwc.trust.removed",
                "name", targetName);
    }

    private boolean isTrusted(String owner, UUID player) {
        if (owner == null || player == null) {
            return false;
        }

        UUID ownerUuid = resolveOwner(owner);
        if (ownerUuid == null) {
            return false;
        }

        loadOwner(ownerUuid);
        Set<UUID> trusted = trusts.get(ownerUuid);
        return trusted != null && trusted.contains(player);
    }

    private void loadOwner(UUID owner) {
        if (loadedOwners.contains(owner)) {
            return;
        }

        Set<UUID> trusted = new HashSet<UUID>();
        LWC.getInstance().getPhysicalDatabase().loadTrusts(owner, trusted);
        trusts.put(owner, trusted);
        loadedOwners.add(owner);
    }

    private UUID resolveOwner(String owner) {
        if (UUIDRegistry.isValidUUID(owner)) {
            return parseUuid(owner);
        }
        Player onlineOwner = Bukkit.getPlayerExact(owner);
        return onlineOwner == null ? UUIDRegistry.getUUID(owner) : onlineOwner.getUniqueId();
    }

    private void loadLegacyTrusts() {
        trusts.clear();
        loadedOwners.clear();
        if (file == null || !file.isFile()) {
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("trusts");
        if (section == null) {
            return;
        }

        for (String ownerText : section.getKeys(false)) {
            try {
                UUID owner = UUID.fromString(ownerText);
                Set<UUID> trusted = new HashSet<UUID>();
                for (String targetText : section.getStringList(ownerText)) {
                    try {
                        trusted.add(UUID.fromString(targetText));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                if (!trusted.isEmpty()) {
                    trusts.put(owner, trusted);
                    loadedOwners.add(owner);
                    for (UUID target : trusted) {
                        final UUID databaseOwner = owner;
                        final UUID databaseTarget = target;
                        LWC.getInstance().getDatabaseThread().addTask(new Runnable() {
                            @Override
                            public void run() {
                                LWC.getInstance().getPhysicalDatabase().addTrust(databaseOwner, databaseTarget);
                            }
                        });
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        File migrated = new File(file.getPath() + ".migrated");
        if (!file.renameTo(migrated)) {
            LWC.getInstance().log("Unable to archive migrated trust.yml");
        }
    }
}
