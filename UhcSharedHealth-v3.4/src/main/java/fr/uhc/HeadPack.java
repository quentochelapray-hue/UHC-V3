package fr.uhc;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.awt.image.BufferedImage;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.URL;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tetes des joueurs affichees devant leur pseudo (chat, Tab, barres...) grace a un pack de textures
 * genere automatiquement a partir des skins, puis envoye aux joueurs a la connexion.
 */
final class HeadPack {

    private static final Key FONT = Key.key("uhc", "heads");
    private static final String[] SOUNDS = {"cap", "run_end", "hurt", "chest"};
    private static final String[] BAR_FILES = {"blue_background", "blue_progress", "yellow_background", "yellow_progress",
            "white_background", "white_progress"};

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("uhc-heads-pack".getBytes());

    private final UhcPlugin plugin;
    private final File headsDir;
    private final File zipFile;
    private final File indexFile;
    private final File soundsDir;
    // Joueurs dont le pack est charge (les sons / flash personnalises ne leur sont envoyes qu'a eux)
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();

    private boolean enabled = false;
    private volatile Map<String, Integer> index = Map.of();
    private volatile byte[] sha1 = null;
    private volatile boolean building = false;
    private HttpServer server = null;
    private int port = 0;

    HeadPack(UhcPlugin plugin) {
        this.plugin = plugin;
        File dir = plugin.getDataFolder();
        this.headsDir = new File(dir, "heads");
        this.zipFile = new File(dir, "pack/UhcHeads.zip");
        this.indexFile = new File(dir, "pack/heads-index.yml");
        this.soundsDir = new File(dir, "sounds");
    }

    // ---------- Demarrage / arret ----------

    void enable() {
        enabled = plugin.getConfig().getBoolean("heads.enabled", true);
        if (!enabled) return;

        headsDir.mkdirs();
        soundsDir.mkdirs();
        loadIndex();
        rebuildLocal(); // le pack suit toujours la version du plugin (sons, barres...) sans reseau

        port = plugin.getConfig().getInt("heads.http-port", 8765);
        String fixedUrl = plugin.getConfig().getString("heads.url", "");
        if (port > 0 && (fixedUrl == null || fixedUrl.isBlank())) {
            try {
                server = PackBuilder.serve(port, zipFile);
                plugin.getLogger().info("Serveur du pack de tetes : port " + port + " (ouvre-le sur ta box).");
            } catch (Throwable t) {
                server = null;
                plugin.getLogger().warning("Impossible de lancer le serveur du pack (port " + port + ") : " + t);
            }
        }
    }

    void disable() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void loadIndex() {
        try {
            if (!zipFile.isFile() || !indexFile.isFile()) return;
            YamlConfiguration c = YamlConfiguration.loadConfiguration(indexFile);
            ConfigurationSection s = c.getConfigurationSection("heads");
            if (s == null) return;
            Map<String, Integer> idx = new LinkedHashMap<>();
            for (String key : s.getKeys(false)) {
                idx.put(key, s.getInt(key));
            }
            index = idx;
            sha1 = PackBuilder.sha1(zipFile);
        } catch (Exception ex) {
            plugin.getLogger().warning("Index des tetes illisible : " + ex);
        }
    }

    private void saveIndex(Map<String, Integer> idx) throws java.io.IOException {
        YamlConfiguration c = new YamlConfiguration();
        for (Map.Entry<String, Integer> e : idx.entrySet()) {
            c.set("heads." + e.getKey(), e.getValue());
        }
        c.save(indexFile);
    }

    // ---------- Contenu supplementaire du pack ----------

    private byte[] resource(String path) {
        try (InputStream in = plugin.getResource(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, byte[]> extras() {
        Map<String, byte[]> extra = new LinkedHashMap<>();
        FileConfiguration cfg = plugin.getConfig();

        if (cfg.getBoolean("pack.sounds", true)) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (String name : SOUNDS) {
                // Un fichier plugins/UhcSharedHealth/sounds/<nom>.ogg remplace le son par defaut
                byte[] data = null;
                File custom = new File(soundsDir, name + ".ogg");
                try {
                    if (custom.isFile()) data = Files.readAllBytes(custom.toPath());
                } catch (Exception ex) {
                    plugin.getLogger().warning("Son illisible : " + custom.getName());
                }
                if (data == null) data = resource("pack/sounds/" + name + ".ogg");
                if (data == null) continue;
                extra.put("assets/uhc/sounds/" + name + ".ogg", data);
                if (!first) json.append(',');
                first = false;
                json.append("\"").append(name).append("\":{\"sounds\":[\"uhc:").append(name).append("\"]}");
            }
            json.append('}');
            extra.put("assets/uhc/sounds.json", json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        if (cfg.getBoolean("pack.bar-colors", true)) {
            for (String f : BAR_FILES) {
                byte[] data = resource("pack/bar/" + f + ".png");
                if (data != null) extra.put("assets/minecraft/textures/gui/sprites/boss_bar/" + f + ".png", data);
            }
        }

        if (cfg.getBoolean("pack.ui", true)) {
            for (Ui.Glyph g : Ui.glyphs()) {
                if (g.file().contains(":")) continue; // texture du jeu : rien a copier
                byte[] data = resource("pack/ui/" + g.file() + ".png");
                if (data != null) extra.put("assets/uhc/textures/font/ui/" + g.file() + ".png", data);
            }
            extra.put("assets/uhc/font/ui.json", Ui.fontJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        if (cfg.getBoolean("pack.damage-flash", false)) {
            byte[] data = resource("pack/flash.png");
            if (data != null) {
                extra.put("assets/uhc/textures/font/flash.png", data);
                extra.put("assets/uhc/font/flash.json", ("{\"providers\":[{\"type\":\"bitmap\","
                        + "\"file\":\"uhc:font/flash.png\",\"ascent\":300,\"height\":600,"
                        + "\"chars\":[\"\\uF000\"]}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return extra;
    }

    /** Recree le zip avec les tetes deja enregistrees (sans telechargement). */
    private void rebuildLocal() {
        try {
            Map<String, Integer> idx = PackBuilder.buildPack(headsDir, zipFile, extras());
            byte[] hash = PackBuilder.sha1(zipFile);
            saveIndex(idx);
            index = idx;
            sha1 = hash;
        } catch (Exception ex) {
            plugin.getLogger().warning("Creation du pack impossible : " + ex);
        }
    }

    // ---------- Sons et flash personnalises (seulement pour ceux qui ont le pack) ----------

    void onStatus(Player p, PlayerResourcePackStatusEvent.Status status, UUID id) {
        if (id != null && !PACK_ID.equals(id)) return;
        switch (status) {
            case SUCCESSFULLY_LOADED -> loaded.add(p.getUniqueId());
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> loaded.remove(p.getUniqueId());
            default -> { }
        }
    }

    void onQuit(Player p) {
        loaded.remove(p.getUniqueId());
    }

    boolean hasPack(Player p) {
        return enabled && loaded.contains(p.getUniqueId());
    }

    /** Interface a icones : seulement pour ceux qui ont le pack (sinon on garde l'affichage texte). */
    boolean hasUi(Player p) {
        return hasPack(p) && plugin.getConfig().getBoolean("pack.ui", true);
    }

    /** Son du pack s'il est charge, sinon le son Minecraft de remplacement. */
    void play(Player p, String name, org.bukkit.Sound fallback, float volume, float pitch) {
        if (hasPack(p) && plugin.getConfig().getBoolean("pack.sounds", true)) {
            p.playSound(Sound.sound(Key.key("uhc", name), Sound.Source.MASTER, volume, pitch));
        } else {
            p.playSound(p.getLocation(), fallback, volume, pitch);
        }
    }

    /** Eclair rouge a l'ecran (image plein ecran affichee via un titre). */
    void flash(Player p) {
        if (!hasPack(p) || !plugin.getConfig().getBoolean("pack.damage-flash", false)) return;
        Component glyph = Component.text("\uF000", NamedTextColor.WHITE).font(Key.key("uhc", "flash"));
        p.showTitle(Title.title(glyph, Component.empty(), Title.Times.times(
                java.time.Duration.ZERO, java.time.Duration.ofMillis(150), java.time.Duration.ofMillis(450))));
    }

    // ---------- Affichage ----------

    /** Tete du joueur suivie d'une espace (vide si le joueur n'est pas dans le pack). */
    Component head(String name) {
        Map<String, Integer> idx = index;
        Integer i = enabled && sha1 != null && name != null ? idx.get(name.toLowerCase(Locale.ROOT)) : null;
        if (i == null) return Component.empty();
        String glyph = String.valueOf((char) (PackBuilder.FIRST_GLYPH + i));
        return Component.empty()
                .append(Component.text(glyph, NamedTextColor.WHITE).font(FONT))
                .append(Component.text(" "));
    }

    // ---------- Envoi du pack aux joueurs ----------

    private String urlFor(Player p) {
        String fixed = plugin.getConfig().getString("heads.url", "");
        if (fixed != null && !fixed.isBlank()) return fixed;
        if (server == null) return null;
        InetSocketAddress vh = p.getVirtualHost();
        String host = vh != null ? vh.getHostString() : null;
        if (host == null || host.isBlank()) return null;
        if (host.contains(":")) host = "[" + host + "]";
        return "http://" + host + ":" + port + PackBuilder.ZIP_PATH;
    }

    void sendTo(Player p) {
        byte[] hash = sha1;
        if (!enabled || hash == null || !p.isOnline()) return;
        String url = urlFor(p);
        if (url == null) return;
        p.setResourcePack(PACK_ID, url, hash, Component.text("Têtes des joueurs (UHC)"),
                plugin.getConfig().getBoolean("heads.required", false));
    }

    /** A la connexion : envoie le pack, ou cree la tete du nouveau joueur si elle manque. */
    void onJoin(Player p) {
        if (!enabled) return;
        boolean known = sha1 != null && index.containsKey(p.getName().toLowerCase(Locale.ROOT));
        if (known) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> sendTo(p), 20L);
        } else {
            rebuild(List.of(p), null);
        }
    }

    // ---------- (Re)creation du pack ----------

    private void tell(CommandSender who, String message) {
        plugin.getLogger().info(message);
        if (who != null) {
            who.sendMessage(Component.text(message, NamedTextColor.GRAY));
        }
    }

    /**
     * Telecharge le skin des joueurs donnes (les autres tetes deja enregistrees sont gardees),
     * recree le pack puis l'envoie a tous les joueurs connectes.
     */
    void rebuild(Collection<Player> players, CommandSender feedback) {
        if (!enabled) {
            tell(feedback, "Les têtes sont désactivées (heads.enabled dans config.yml).");
            return;
        }
        if (building) {
            tell(feedback, "Une création du pack est déjà en cours.");
            return;
        }
        building = true;

        // Les profils se lisent sur le thread principal
        final Map<String, URL> skins = new LinkedHashMap<>();
        final List<String> missing = new ArrayList<>();
        for (Player p : players) {
            try {
                URL url = p.getPlayerProfile().getTextures().getSkin();
                if (url != null) {
                    skins.put(p.getName().toLowerCase(Locale.ROOT), url);
                } else {
                    missing.add(p.getName() + " (pas de skin : serveur en mode non-premium ?)");
                }
            } catch (Exception ex) {
                missing.add(p.getName() + " (" + ex.getMessage() + ")");
            }
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final List<String> failed = new ArrayList<>(missing);
            int saved = 0;
            headsDir.mkdirs();
            for (Map.Entry<String, URL> e : skins.entrySet()) {
                try {
                    BufferedImage face = PackBuilder.face(PackBuilder.readSkin(e.getValue()));
                    Files.write(new File(headsDir, e.getKey() + ".png").toPath(), PackBuilder.png(face));
                    saved++;
                } catch (Exception ex) {
                    failed.add(e.getKey() + " (" + ex.getMessage() + ")");
                }
            }

            if (saved > 0) {
                try {
                    Map<String, Integer> idx = PackBuilder.buildPack(headsDir, zipFile, extras());
                    byte[] hash = PackBuilder.sha1(zipFile);
                    saveIndex(idx);
                    index = idx;
                    sha1 = hash;
                } catch (Exception ex) {
                    failed.add("création du zip (" + ex + ")");
                }
            }

            final int count = saved;
            Bukkit.getScheduler().runTask(plugin, () -> {
                building = false;
                tell(feedback, "Pack des têtes : " + count + " tête(s) mise(s) à jour, " + index.size()
                        + " au total." + (failed.isEmpty() ? "" : " Échecs : " + String.join(", ", failed)));
                for (Player o : Bukkit.getOnlinePlayers()) {
                    sendTo(o);
                }
            });
        });
    }
}
