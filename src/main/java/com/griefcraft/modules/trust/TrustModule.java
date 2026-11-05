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
    private File file;

    @Override
    public void load(LWC lwc) {
        file = new File(lwc.getPlugin().getDataFolder(), "trust.yml");
        loadTrusts();
    }

    @Override
    public void onReload(LWCReloadEvent event) {
        loadTrusts();
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

        Player player = (Player) sender;
        UUID target = UUIDRegistry.getUUID(args[0]);
        if (target == null) {
            lwc.sendLocale(sender, "lwc.trust.playernotfound", "name", args[0]);
            return;
        }

        UUID owner = player.getUniqueId();
        if (owner.equals(target)) {
            lwc.sendLocale(sender, "lwc.trust.self");
            return;
        }

        boolean trust = event.hasFlag("trust");
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
            if (trust) {
                lwc.getPhysicalDatabase().addTrust(owner, target);
            } else {
                lwc.getPhysicalDatabase().removeTrust(owner, target);
            }
        }

        lwc.sendLocale(sender, trust ? "lwc.trust.added" : "lwc.trust.removed",
                "name", UUIDRegistry.formatPlayerName(target.toString(), false));
    }

    private boolean isTrusted(String owner, UUID player) {
        if (owner == null || player == null) {
            return false;
        }

        UUID ownerUuid = UUIDRegistry.getUUID(owner);
        if (ownerUuid == null) {
            try {
                ownerUuid = UUID.fromString(owner);
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }

        Set<UUID> trusted = trusts.get(ownerUuid);
        return trusted != null && trusted.contains(player);
    }

    private void loadTrusts() {
        trusts.clear();
        int databaseTrusts = LWC.getInstance().getPhysicalDatabase().loadTrusts(trusts);
        if (databaseTrusts > 0 || file == null || !file.isFile()) {
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
                    for (UUID target : trusted) {
                        LWC.getInstance().getPhysicalDatabase().addTrust(owner, target);
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
