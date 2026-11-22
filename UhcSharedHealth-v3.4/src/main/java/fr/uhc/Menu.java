package fr.uhc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * v3.7 : menu interactif (un coffre a onglets, un peu comme l'ecran des succes).
 * Ouverture : Shift + F (la touche "echanger les mains" pendant qu'on est accroupi) ou /uhcmenu.
 * Onglets : Commandes, Succes, Reperes, Equipe, et Admin (reserve aux op : permission uhc.reset).
 * Le serveur ne peut pas lire une touche comme P : il n'y a pas de mod cote joueur.
 */
final class Menu implements Listener {

    static final int PAGE_CMDS = 0;
    static final int PAGE_BADGES = 1;
    static final int PAGE_MARKS = 2;
    static final int PAGE_TEAM = 3;
    static final int PAGE_ADMIN = 4;
    static final int PAGE_SETTINGS = 5;

    private interface Action {
        void run(Player p, ClickType click);
    }

    // reglage modifiable en jeu : cle de config, nom, icone, min, max, pas, vrai = interrupteur
    private record Setting(String key, String label, Material icon, double min, double max, double step, boolean bool) {
    }

    private static final List<Setting> SETTINGS = List.of(
            new Setting("teacher.enabled", "Professeur activé", Material.OAK_SIGN, 0, 1, 1, true),
            new Setting("teacher.voice-volume", "Volume de la voix", Material.NOTE_BLOCK, 0.0, 1.0, 0.1, false),
            new Setting("teacher.ambient", "Remarques d'ambiance", Material.CLOCK, 0, 1, 1, true),
            new Setting("teacher.ambient-min-seconds", "Ambiance : délai min (s)", Material.CLOCK, 30, 900, 30, false),
            new Setting("teacher.ambient-max-seconds", "Ambiance : délai max (s)", Material.CLOCK, 60, 1800, 30, false),
            new Setting("teacher.chars-per-second", "Vitesse d'écriture", Material.FEATHER, 10, 60, 2, false),
            new Setting("messages.broadcast-points", "Points annoncés à tous", Material.PAPER, 0, 1, 1, true),
            new Setting("messages.joke-interval-seconds", "Blague d'ambiance (s, 0 = off)", Material.BELL, 0, 900, 60, false),
            new Setting("penalties.friendly-fire-points", "Malus coup à un coéquipier", Material.IRON_SWORD, 0, 10, 0.5, false),
            new Setting("penalties.rate.stupid", "Sévérité : erreurs bêtes", Material.LAVA_BUCKET, 0, 3, 0.1, false),
            new Setting("penalties.rate.normal", "Sévérité : erreurs normales", Material.ANVIL, 0, 3, 0.1, false),
            new Setting("penalties.rate.unlucky", "Sévérité : pas de chance", Material.ARROW, 0, 3, 0.05, false),
            new Setting("team.critical-hearts", "Alerte vie critique (cœurs)", Material.APPLE, 1, 8, 0.5, false),
            new Setting("team.dispersion-enabled", "Alerte de dispersion", Material.LEAD, 0, 1, 1, true),
            new Setting("team.dispersion-distance", "Dispersion : distance (blocs)", Material.SPYGLASS, 50, 400, 10, false),
            new Setting("team.end-counter", "Compteur de l'End", Material.END_PORTAL_FRAME, 0, 1, 1, true)
    );

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        int page = PAGE_CMDS;
        int confirmSlot = -1;
        long confirmAt = 0L;
        final Map<Integer, Action> actions = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final JavaPlugin plugin;
    private final Team team;
    private final Extras extras;
    private final Supplier<String> voiceGet;      // voix du professeur actuelle (villager, blip, talk, off)
    private final Consumer<String> voiceSet;      // change la voix par defaut du serveur et la fait essayer
    private final BiConsumer<Player, String> personalVoiceSet;   // change la voix d'un joueur ("" = defaut) et la lui fait essayer

    Menu(JavaPlugin plugin, Team team, Extras extras, Supplier<String> voiceGet, Consumer<String> voiceSet,
         BiConsumer<Player, String> personalVoiceSet) {
        this.plugin = plugin;
        this.team = team;
        this.extras = extras;
        this.voiceGet = voiceGet;
        this.voiceSet = voiceSet;
        this.personalVoiceSet = personalVoiceSet;
    }

    void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Les pages "Reperes" et "Equipe" se mettent a jour toutes les secondes quand elles sont ouvertes
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder h
                        && (h.page == PAGE_MARKS || h.page == PAGE_TEAM)) {
                    render(p, h);
                }
            }
        }, 20L, 20L);
    }

    // ---------------------------------------------------------------- ouverture

    void open(Player p, int page) {
        Holder h = new Holder();
        h.page = page;
        h.inv = Bukkit.createInventory(h, 54, Component.text("Menu UHC", NamedTextColor.DARK_GRAY));
        render(p, h);
        p.openInventory(h.inv);
    }

    // ---------------------------------------------------------------- evenements

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!p.isSneaking()) return;     // F tout seul : echange normal des mains (totem, bouclier...)
        e.setCancelled(true);
        open(p, PAGE_CMDS);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        Action a = h.actions.get(e.getRawSlot());
        if (a != null) a.run(p, e.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) {
                p.sendMessage(Component.text("Menu UHC : accroupi + F (ou /uhcmenu).", NamedTextColor.GRAY));
            }
        }, 100L);
    }

    // ---------------------------------------------------------------- outils d'affichage

    private ItemStack item(Material mat, String name, NamedTextColor color, boolean glint, int amount, String... lore) {
        ItemStack it = new ItemStack(mat, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) {
                l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(l);
        }
        if (glint) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack pane(Material mat) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(" "));
        it.setItemMeta(meta);
        return it;
    }

    private void put(Holder h, int slot, ItemStack it, Action a) {
        h.inv.setItem(slot, it);
        if (a != null) h.actions.put(slot, a);
    }

    // lance une commande apres avoir ferme le menu
    private void cmd(Player p, String command) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            p.closeInventory();
            p.performCommand(command);
        });
    }

    private void tab(Holder h, int slot, int page, Material mat, String name, String desc) {
        boolean sel = h.page == page;
        put(h, slot, item(mat, name, sel ? NamedTextColor.GREEN : NamedTextColor.WHITE, sel, 1, desc),
                (pl, c) -> {
                    h.page = page;
                    h.confirmSlot = -1;
                    render(pl, h);
                });
        h.inv.setItem(slot + 9, pane(sel ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE));
    }

    // ---------------------------------------------------------------- rendu

    private void render(Player p, Holder h) {
        Inventory inv = h.inv;
        inv.clear();
        h.actions.clear();
        boolean op = p.hasPermission("uhc.reset");
        if ((h.page == PAGE_ADMIN || h.page == PAGE_SETTINGS) && !op) h.page = PAGE_CMDS;

        for (int i = 9; i < 18; i++) {
            inv.setItem(i, pane(Material.GRAY_STAINED_GLASS_PANE));
        }
        tab(h, 0, PAGE_CMDS, Material.BOOK, "Commandes", "Les commandes pour tous les joueurs");
        tab(h, 1, PAGE_BADGES, Material.NETHER_STAR, "Succès", "Tes succès débloqués");
        tab(h, 2, PAGE_MARKS, Material.COMPASS, "Repères", "Lieux partagés de l'équipe");
        tab(h, 3, PAGE_TEAM, Material.CHEST, "Équipe", "Stock et vie de l'équipe");
        if (op) {
            tab(h, 7, PAGE_SETTINGS, Material.COMPARATOR, "Réglages", "Réglages du serveur (opérateurs)");
            tab(h, 8, PAGE_ADMIN, Material.COMMAND_BLOCK, "Admin", "Réservé aux opérateurs");
        }
        put(h, 49, item(Material.BARRIER, "Fermer", NamedTextColor.RED, false, 1), (pl, c) -> pl.closeInventory());

        switch (h.page) {
            case PAGE_BADGES -> renderBadges(p, h);
            case PAGE_MARKS -> renderMarks(p, h);
            case PAGE_TEAM -> renderTeam(h);
            case PAGE_ADMIN -> renderAdmin(p, h);
            case PAGE_SETTINGS -> renderSettings(h);
            default -> renderCommands(p, h);
        }
    }

    private void renderCommands(Player p, Holder h) {
        UUID id = p.getUniqueId();
        put(h, 19, item(Material.PAPER, "Stats de la run", NamedTextColor.GOLD, false, 1, "/uhcstats"),
                (pl, c) -> cmd(pl, "uhcstats"));
        put(h, 20, item(Material.WRITABLE_BOOK, "Notes", NamedTextColor.GOLD, false, 1, "/uhcnotes"),
                (pl, c) -> cmd(pl, "uhcnotes"));
        put(h, 21, item(Material.GOLD_INGOT, "Classement des runs", NamedTextColor.GOLD, false, 1, "/uhcruns"),
                (pl, c) -> cmd(pl, "uhcruns"));
        put(h, 22, item(Material.MAP, "Stats depuis le début", NamedTextColor.GOLD, false, 1, "/uhcglobal"),
                (pl, c) -> cmd(pl, "uhcglobal"));
        put(h, 23, item(Material.CHEST, "Stock d'équipe", NamedTextColor.GOLD, false, 1, "/uhcstock"),
                (pl, c) -> cmd(pl, "uhcstock"));
        put(h, 24, item(Material.ENDER_EYE, "Partager ma position", NamedTextColor.GOLD, false, 1, "/uhcpos"),
                (pl, c) -> cmd(pl, "uhcpos"));
        put(h, 25, item(Material.END_PORTAL_FRAME, "Préparation de l'End", NamedTextColor.GOLD, false, 1,
                        "Yeux d'Ender, lits, blocs, nourriture...", "/uhcend"),
                (pl, c) -> cmd(pl, "uhcend"));

        boolean off = extras.profOff(id);
        put(h, 28, item(Material.OAK_SIGN, "Bulles du professeur : " + (off ? "COUPÉES" : "activées"),
                        off ? NamedTextColor.RED : NamedTextColor.GREEN, false, 1,
                        "Clique pour " + (off ? "les remettre" : "les couper"), "(coupe aussi la voix)", "/uhcprof off|on"),
                (pl, c) -> {
                    extras.toggleProf(pl.getUniqueId(), pl.getName());
                    render(pl, h);
                });
        boolean muted = off || extras.voiceMuted(id);
        put(h, 29, item(Material.NOTE_BLOCK, "Voix du professeur : " + (muted ? "COUPÉE" : "activée"),
                        muted ? NamedTextColor.RED : NamedTextColor.GREEN, false, 1,
                        "Clique pour " + (extras.voiceMuted(id) ? "la remettre" : "la couper"), "/uhcprof voix off|on"),
                (pl, c) -> {
                    extras.toggleVoice(pl.getUniqueId(), pl.getName());
                    render(pl, h);
                });
        String own = extras.voiceOf(id);
        String ownLabel = own.isEmpty() ? "par défaut (" + Voices.get(voiceGet.get()).label() + ")" : Voices.get(own).label();
        put(h, 30, item(Material.JUKEBOX, "Ma voix : " + ownLabel, NamedTextColor.AQUA, false, 1,
                        "Clic gauche : voix suivante (avec essai)", "Clic droit : voix précédente",
                        "Pour toi seul : les autres gardent la leur", "/uhcprof voix <nom>"),
                (pl, c) -> {
                    List<String> order = new ArrayList<>();
                    order.add("");
                    for (Voices.Voice v : Voices.selectable(false)) {
                        order.add(v.id());
                    }
                    int idx = Math.max(0, order.indexOf(extras.voiceOf(pl.getUniqueId())));
                    idx = (idx + (c.isRightClick() ? order.size() - 1 : 1)) % order.size();
                    personalVoiceSet.accept(pl, order.get(idx));
                    render(pl, h);
                });
        String nick = extras.nick(id);
        put(h, 31, item(Material.NAME_TAG, nick.isEmpty() ? "Pas encore de surnom" : "Ton surnom : « " + nick + " »",
                NamedTextColor.LIGHT_PURPLE, false, 1, "Fais 3 fois la même erreur pour en gagner un."), null);
    }

    private void renderBadges(Player p, Holder h) {
        int[] slots = {19, 20, 21, 22, 23, 28, 29, 30, 31, 32};
        List<String[]> states = extras.badgeStates(p.getUniqueId());
        int ok = 0;
        for (int i = 0; i < states.size() && i < slots.length; i++) {
            String[] b = states.get(i);
            if (b[2].equals("1")) {
                ok++;
                put(h, slots[i], item(Material.EXPERIENCE_BOTTLE, b[0], NamedTextColor.YELLOW, true, 1, b[1],
                        "Débloqué !"), null);
            } else {
                put(h, slots[i], item(Material.GRAY_DYE, b[0], NamedTextColor.DARK_GRAY, false, 1, b[1],
                        "Pas encore débloqué"), null);
            }
        }
        put(h, 40, item(Material.GOLD_NUGGET, "Succès : " + ok + "/" + states.size(), NamedTextColor.GOLD, false, 1), null);
    }

    private void renderMarks(Player p, Holder h) {
        List<Team.Mark> ms = team.marks();
        for (int i = 0; i < ms.size() && i < Team.MAX_MARKS; i++) {
            final Team.Mark m = ms.get(i);
            put(h, 18 + i, item(Material.COMPASS, m.name, NamedTextColor.AQUA, false, 1,
                            team.describe(p, m), "Posé par " + m.by,
                            "Clic : direction dans la barre d'action", "Shift + clic : supprimer"),
                    (pl, c) -> {
                        if (c.isShiftClick()) {
                            team.removeMark(m.name);
                            render(pl, h);
                        } else {
                            pl.sendActionBar(Component.text(m.name + " — " + team.describe(pl, m), NamedTextColor.AQUA));
                        }
                    });
        }
        if (ms.isEmpty()) {
            put(h, 22, item(Material.PAPER, "Aucun repère", NamedTextColor.GRAY, false, 1,
                    "Pose-en un avec les boutons ci-dessous", "ou /uhcmark <nom>"), null);
        }
        String[] quick = {"base", "portail", "forteresse", "village", "mine"};
        for (int i = 0; i < quick.length; i++) {
            final String name = quick[i];
            put(h, 38 + i, item(Material.LIME_DYE, "Poser « " + name + " » ici", NamedTextColor.GREEN, false, 1,
                    "Repère à ta position actuelle"), (pl, c) -> {
                team.addMark(pl, name);
                render(pl, h);
            });
        }
    }

    private void renderTeam(Holder h) {
        List<Team.Stat> st = team.stock();
        for (int i = 0; i < st.size() && i < 18; i++) {
            Team.Stat s = st.get(i);
            put(h, 18 + i, item(s.icon, s.label + " : " + s.n, s.n > 0 ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY,
                    false, s.n), null);
        }
        double hearts = team.teamHearts();
        if (hearts >= 0.0) {
            put(h, 40, item(Material.GOLDEN_APPLE, "Vie d'équipe : " + (Math.round(hearts * 10.0) / 10.0) + " ❤",
                    hearts <= 3.0 ? NamedTextColor.RED : NamedTextColor.GREEN, false, 1,
                    "Mise à jour chaque seconde"), null);
        }
    }

    private void renderAdmin(Player p, Holder h) {
        admin(h, 20, Material.TNT, "Nouvelle map (reset)", cmdAction("uhcreset"), true,
                "Termine la run et relance le serveur", "avec une nouvelle map");
        admin(h, 22, Material.ENDER_CHEST, "Recréer le pack de têtes", cmdAction("uhcpack"), false,
                "/uhcpack");
        admin(h, 24, Material.WRITABLE_BOOK, "Notes de tous à 20/20", cmdAction("uhcnotes reset"), true,
                "/uhcnotes reset");
        admin(h, 29, Material.OAK_SIGN, "Test : professeur", cmdAction("uhctest prof"), false, "/uhctest prof");
        admin(h, 30, Material.GOLDEN_APPLE, "Test : fayot", cmdAction("uhctest fayot"), false, "/uhctest fayot");
        admin(h, 31, Material.ROTTEN_FLESH, "Test : cancre", cmdAction("uhctest cancre"), false, "/uhctest cancre");
        admin(h, 32, Material.BARRIER, "Arrêter les tests", cmdAction("uhctest stop"), false, "/uhctest stop");
    }

    private Action cmdAction(String command) {
        return (pl, c) -> cmd(pl, command);
    }

    // bouton reserve aux op ; confirm = il faut cliquer deux fois (dans les 5 secondes)
    private void admin(Holder h, int slot, Material mat, String name, Action act, boolean confirm, String... lore) {
        boolean pending = confirm && h.confirmSlot == slot;
        ItemStack it = pending
                ? item(mat, "⚠ Clique encore pour confirmer", NamedTextColor.RED, true, 1, name)
                : item(mat, name, NamedTextColor.GOLD, false, 1, lore);
        put(h, slot, it, (pl, c) -> {
            if (!pl.hasPermission("uhc.reset")) return;
            if (!confirm) {
                act.run(pl, c);
                return;
            }
            long now = System.currentTimeMillis();
            if (h.confirmSlot == slot && now - h.confirmAt < 5000L) {
                h.confirmSlot = -1;
                act.run(pl, c);
            } else {
                h.confirmSlot = slot;
                h.confirmAt = now;
                render(pl, h);
            }
        });
    }

    // ---------------------------------------------------------------- reglages (op)

    private String valueText(Setting s) {
        FileConfiguration cfg = plugin.getConfig();
        if (s.bool()) return cfg.getBoolean(s.key()) ? "ACTIVÉ" : "DÉSACTIVÉ";
        double v = cfg.getDouble(s.key());
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.valueOf(Math.round(v * 100.0) / 100.0).replace('.', ',');
    }

    private String defaultText(Setting s) {
        Configuration defs = plugin.getConfig().getDefaults();
        Object d = defs == null ? null : defs.get(s.key());
        if (d == null) return "?";
        if (s.bool()) return Boolean.TRUE.equals(d) ? "ACTIVÉ" : "DÉSACTIVÉ";
        if (d instanceof Number n) {
            double v = n.doubleValue();
            if (v == Math.floor(v)) return String.valueOf((long) v);
            return String.valueOf(v).replace('.', ',');
        }
        return String.valueOf(d);
    }

    private void changeSetting(Setting s, ClickType c) {
        FileConfiguration cfg = plugin.getConfig();
        if (s.bool()) {
            cfg.set(s.key(), !cfg.getBoolean(s.key()));
        } else {
            boolean isInt = cfg.get(s.key()) instanceof Integer;
            double step = s.step() * (c.isShiftClick() ? 5.0 : 1.0) * (c.isRightClick() ? -1.0 : 1.0);
            double v = Math.max(s.min(), Math.min(s.max(), cfg.getDouble(s.key()) + step));
            v = Math.round(v * 100.0) / 100.0;
            if (isInt) cfg.set(s.key(), (int) Math.round(v));
            else cfg.set(s.key(), v);
        }
        plugin.saveConfig();
    }

    private void resetSettings() {
        FileConfiguration cfg = plugin.getConfig();
        Configuration defs = cfg.getDefaults();
        if (defs == null) return;
        for (Setting s : SETTINGS) {
            if (defs.get(s.key()) != null) cfg.set(s.key(), defs.get(s.key()));
        }
        plugin.saveConfig();
    }

    private void renderSettings(Holder h) {
        for (int i = 0; i < SETTINGS.size() && i < 18; i++) {
            final Setting s = SETTINGS.get(i);
            boolean on = !s.bool() || plugin.getConfig().getBoolean(s.key());
            String[] lore = s.bool()
                    ? new String[]{"Clic : activer / désactiver", "Par défaut : " + defaultText(s)}
                    : new String[]{"Clic gauche : +" + s.step() + "  ·  clic droit : -" + s.step(),
                    "Shift : x5", "Par défaut : " + defaultText(s)};
            put(h, 18 + i, item(s.icon(), s.label() + " : " + valueText(s),
                            on ? NamedTextColor.GREEN : NamedTextColor.RED, false, 1, lore),
                    (pl, c) -> {
                        if (!pl.hasPermission("uhc.reset")) return;
                        changeSetting(s, c);
                        render(pl, h);
                    });
        }
        put(h, 34, item(Material.JUKEBOX, "Voix par défaut : " + Voices.get(voiceGet.get()).label(), NamedTextColor.GOLD,
                        false, 1, "Clic gauche : suivante, clic droit : précédente",
                        "Pour ceux qui n'ont pas choisi leur voix", "(chacun choisit la sienne dans l'onglet Commandes)"),
                (pl, c) -> {
                    if (!pl.hasPermission("uhc.reset")) return;
                    List<Voices.Voice> all = Voices.selectable(true);
                    int n = all.size();
                    int idx = 0;
                    for (int i = 0; i < n; i++) {
                        if (all.get(i).id().equals(Voices.get(voiceGet.get()).id())) idx = i;
                    }
                    idx = (idx + (c.isRightClick() ? n - 1 : 1)) % n;
                    voiceSet.accept(all.get(idx).id());
                    render(pl, h);
                });
        put(h, 40, item(Material.PAPER, "Appliqué tout de suite", NamedTextColor.GRAY, false, 1,
                "Sauvegardé dans config.yml", "Pas besoin de redémarrer"), null);
        admin(h, 41, Material.TNT, "Remettre ces réglages par défaut", (pl, c) -> {
            resetSettings();
            render(pl, h);
        }, true, "Seulement les réglages de cette page", "(pas l'interface ni les objectifs)");
    }
}
