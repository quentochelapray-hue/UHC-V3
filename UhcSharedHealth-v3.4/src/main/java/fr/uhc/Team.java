package fr.uhc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * v3.7 : outils d'equipe : stock d'equipe (/uhcstock), repères partages (/uhcmark, /uhcmarks),
 * position partagee (/uhcpos) et alerte de vie partagee critique.
 * Les reperes ne sont pas conserves : chaque run a une nouvelle map.
 */
final class Team {

    static final class Mark {
        String name;
        String world;
        double x;
        double y;
        double z;
        String by;
    }

    static final class Stat {
        final String label;
        final Material icon;
        int n = 0;

        Stat(String label, Material icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    static final int MAX_MARKS = 18;

    private final JavaPlugin plugin;
    private final Extras.Say say;
    private final BooleanSupplier busy;
    private final Supplier<String> nextGoalKey;   // cle du prochain objectif (ex : "stronghold") ou null

    // dispersion
    private final Map<UUID, Integer> apart = new HashMap<>();
    private final Map<UUID, Long> apartAlert = new HashMap<>();

    // l'End
    private int lastCrystals = -1;
    private long eyeNext = 0L;

    private final Map<String, Mark> marks = new LinkedHashMap<>();

    private boolean critical = false;      // alerte "vie critique" deja donnee
    private boolean lastHeart = false;     // alerte "dernier coeur" deja donnee
    private long lastAlert = 0L;

    Team(JavaPlugin plugin, Extras.Say say, BooleanSupplier busy, Supplier<String> nextGoalKey) {
        this.plugin = plugin;
        this.say = say;
        this.busy = busy;
        this.nextGoalKey = nextGoalKey;
    }

    void enable() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 20L, 20L);
    }

    private void tickAll() {
        tickHealth();
        tickDispersion();
        tickEnd();
    }

    // ---------------------------------------------------------------- outils

    private static String num(double v) {
        double r = Math.round(v * 10.0) / 10.0;
        if (r == Math.floor(r)) return String.valueOf((long) r);
        return String.valueOf(r).replace('.', ',');
    }

    private static String dimension(org.bukkit.World w) {
        if (w == null) return "?";
        return switch (w.getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> "Overworld";
        };
    }

    // ---------------------------------------------------------------- stock d'equipe

    private static final Set<Material> FOODS = Set.of(
            Material.COOKED_BEEF, Material.COOKED_PORKCHOP, Material.COOKED_MUTTON, Material.COOKED_CHICKEN,
            Material.COOKED_RABBIT, Material.COOKED_COD, Material.COOKED_SALMON, Material.BREAD,
            Material.BAKED_POTATO, Material.GOLDEN_CARROT, Material.APPLE, Material.PUMPKIN_PIE);

    /** Compte ce que l'equipe (tous les joueurs connectes) a dans ses inventaires. */
    private Map<Material, Integer> counts() {
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (ItemStack it : p.getInventory().getContents()) {
                if (it == null || it.getType() == Material.AIR) continue;
                counts.merge(it.getType(), it.getAmount(), Integer::sum);
            }
        }
        return counts;
    }

    List<Stat> stock() {
        Map<Material, Integer> counts = counts();
        List<Stat> out = new ArrayList<>();
        out.add(stat("Lingots de fer", Material.IRON_INGOT, counts, Material.IRON_INGOT));
        out.add(stat("Fer brut", Material.RAW_IRON, counts, Material.RAW_IRON));
        out.add(stat("Diamants", Material.DIAMOND, counts, Material.DIAMOND));
        out.add(stat("Obsidienne", Material.OBSIDIAN, counts, Material.OBSIDIAN));
        out.add(stat("Perles d'Ender", Material.ENDER_PEARL, counts, Material.ENDER_PEARL));
        out.add(stat("Yeux d'Ender", Material.ENDER_EYE, counts, Material.ENDER_EYE));
        out.add(stat("Blaze rods", Material.BLAZE_ROD, counts, Material.BLAZE_ROD));
        out.add(stat("Poudre de blaze", Material.BLAZE_POWDER, counts, Material.BLAZE_POWDER));
        out.add(stat("Pommes d'or", Material.GOLDEN_APPLE, counts, Material.GOLDEN_APPLE,
                Material.ENCHANTED_GOLDEN_APPLE));

        Stat food = new Stat("Nourriture", Material.COOKED_BEEF);
        Stat beds = new Stat("Lits", Material.RED_BED);
        for (Map.Entry<Material, Integer> en : counts.entrySet()) {
            if (FOODS.contains(en.getKey())) food.n += en.getValue();
            if (Tag.BEDS.isTagged(en.getKey())) beds.n += en.getValue();
        }
        out.add(food);
        out.add(beds);
        out.add(stat("Seaux d'eau", Material.WATER_BUCKET, counts, Material.WATER_BUCKET));
        out.add(stat("Seaux de lave", Material.LAVA_BUCKET, counts, Material.LAVA_BUCKET));
        out.add(stat("Briquets", Material.FLINT_AND_STEEL, counts, Material.FLINT_AND_STEEL));
        out.add(stat("Flèches", Material.ARROW, counts, Material.ARROW));
        out.add(stat("Totems", Material.TOTEM_OF_UNDYING, counts, Material.TOTEM_OF_UNDYING));
        return out;
    }

    private Stat stat(String label, Material icon, Map<Material, Integer> counts, Material... mats) {
        Stat s = new Stat(label, icon);
        for (Material m : mats) {
            s.n += counts.getOrDefault(m, 0);
        }
        return s;
    }

    private int stockOf(List<Stat> stats, String label) {
        for (Stat s : stats) {
            if (s.label.equals(label)) return s.n;
        }
        return 0;
    }

    /** Vie partagee actuelle de l'equipe en coeurs (la plus basse des joueurs connectes), ou -1 si personne. */
    double teamHearts() {
        double min = -1.0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            double h = p.getHealth() / 2.0;
            if (min < 0.0 || h < min) min = h;
        }
        return min;
    }

    // ---------------------------------------------------------------- alerte vie critique

    private void tickHealth() {
        if (busy.getAsBoolean()) return;
        double hearts = teamHearts();
        if (hearts < 0.0) return;

        double crit = plugin.getConfig().getDouble("team.critical-hearts", 3.0);
        if (hearts >= crit + 2.0) critical = false;
        if (hearts >= 2.5) lastHeart = false;

        long now = System.currentTimeMillis();
        if (now - lastAlert < 30_000L) return;

        if (hearts <= 1.0 && !lastHeart) {
            lastHeart = true;
            critical = true;
            lastAlert = now;
            alert("DERNIER CŒUR : " + num(hearts) + " ❤. Plus aucune erreur possible. " + supplies());
        } else if (hearts <= crit && !critical) {
            critical = true;
            lastAlert = now;
            alert("Vie d'équipe critique : " + num(hearts) + " ❤. " + supplies());
        }
    }

    private String supplies() {
        List<Stat> st = stock();
        int golden = stockOf(st, "Pommes d'or");
        int food = stockOf(st, "Nourriture");
        String a = golden > 0 ? "Pommes d'or : " + golden + "." : "Aucune pomme d'or.";
        String b = food > 0 ? " Nourriture : " + food + "." : " Plus aucune nourriture !";
        return a + b + " Reculez et soignez-vous.";
    }

    private void alert(String text) {
        Component c = Component.text("⚠ " + text, NamedTextColor.RED);
        Bukkit.getServer().sendMessage(c);
        Bukkit.getServer().sendActionBar(c);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.6f);
        }
        say.say(text, true);
    }

    // ---------------------------------------------------------------- alerte de dispersion

    private void tickDispersion() {
        if (busy.getAsBoolean() || !plugin.getConfig().getBoolean("team.dispersion-enabled", true)) return;
        List<Player> ps = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SPECTATOR) ps.add(p);
        }
        if (ps.size() < 2) {
            apart.clear();
            return;
        }
        double limit = plugin.getConfig().getDouble("team.dispersion-distance", 150.0);
        int needSame = Math.max(5, plugin.getConfig().getInt("team.dispersion-seconds", 20));
        int needDim = Math.max(needSame, 45);
        long cooldown = Math.max(30, plugin.getConfig().getInt("team.dispersion-cooldown-seconds", 180)) * 1000L;
        long now = System.currentTimeMillis();

        for (Player p : ps) {
            UUID id = p.getUniqueId();
            boolean sameWorld = false;
            double nearest = Double.MAX_VALUE;
            for (Player o : ps) {
                if (o == p || !o.getWorld().equals(p.getWorld())) continue;
                sameWorld = true;
                nearest = Math.min(nearest, o.getLocation().distance(p.getLocation()));
            }
            boolean far = !sameWorld || nearest > limit;
            if (!far) {
                apart.remove(id);
                continue;
            }
            int t = apart.merge(id, 1, Integer::sum);
            if (t < (sameWorld ? needSame : needDim)) continue;
            Long last = apartAlert.get(id);
            if (last != null && now - last < cooldown) continue;
            apartAlert.put(id, now);
            String text = sameWorld
                    ? p.getName() + " est à " + Math.round(nearest) + " blocs de l'équipe. La vie est partagée : restez groupés."
                    : p.getName() + " est seul dans " + dimension(p.getWorld()) + ". La vie est partagée : ne vous séparez pas.";
            Bukkit.getServer().sendMessage(Component.text("⚠ " + text, NamedTextColor.YELLOW));
            say.say(text, false);
        }
    }

    // ---------------------------------------------------------------- l'End : yeux d'Ender, preparation, cristaux

    private static final int EYES_NEEDED = 12;
    private static final int CRYSTALS_TOTAL = 10;

    /** Yeux d'Ender : en main, fabricables (perles + poudre de blaze), total. */
    private int[] eyeCounts() {
        Map<Material, Integer> c = counts();
        int eyes = c.getOrDefault(Material.ENDER_EYE, 0);
        int pearls = c.getOrDefault(Material.ENDER_PEARL, 0);
        int powder = c.getOrDefault(Material.BLAZE_POWDER, 0);
        return new int[]{eyes, Math.min(pearls, powder), pearls, powder};
    }

    private String eyesLine() {
        int[] e = eyeCounts();
        int total = e[0] + e[1];
        String base = "Pour trouver le stronghold, il faut " + EYES_NEEDED + " yeux d'Ender. Vous en avez " + e[0]
                + " et pouvez en fabriquer " + e[1] + " de plus (perles " + e[2] + ", poudre de blaze " + e[3] + "). ";
        if (total >= EYES_NEEDED) return base + "C'est suffisant.";
        return base + "Il vous en manque " + (EYES_NEEDED - total) + ".";
    }

    /** Liste de preparation pour l'End : {libelle, "x/y", "1" si ok}. */
    List<String[]> endChecklist() {
        Map<Material, Integer> c = counts();
        int[] e = eyeCounts();
        int beds = 0;
        for (Map.Entry<Material, Integer> en : c.entrySet()) {
            if (Tag.BEDS.isTagged(en.getKey())) beds += en.getValue();
        }
        int blocks = c.getOrDefault(Material.COBBLESTONE, 0) + c.getOrDefault(Material.COBBLED_DEEPSLATE, 0)
                + c.getOrDefault(Material.NETHERRACK, 0) + c.getOrDefault(Material.STONE, 0);
        int food = 0;
        for (Map.Entry<Material, Integer> en : c.entrySet()) {
            if (FOODS.contains(en.getKey())) food += en.getValue();
        }
        int golden = c.getOrDefault(Material.GOLDEN_APPLE, 0) + c.getOrDefault(Material.ENCHANTED_GOLDEN_APPLE, 0);
        List<String[]> out = new ArrayList<>();
        out.add(row("Yeux d'Ender (en main + fabricables)", e[0] + e[1], EYES_NEEDED));
        out.add(row("Lits (pour blesser le dragon)", beds, 4));
        out.add(row("Blocs de construction", blocks, 64));
        out.add(row("Nourriture", food, 16));
        out.add(row("Pommes d'or", golden, 1));
        out.add(row("Seau d'eau", c.getOrDefault(Material.WATER_BUCKET, 0), 1));
        out.add(row("Arc", c.getOrDefault(Material.BOW, 0), 1));
        out.add(row("Flèches", c.getOrDefault(Material.ARROW, 0), 32));
        return out;
    }

    private String[] row(String label, int have, int need) {
        return new String[]{label, have + "/" + need, have >= need ? "1" : "0"};
    }

    /** Cristaux d'Ender restants dans l'End (-1 si personne n'y est encore passe). */
    int crystalsLeft() {
        return lastCrystals;
    }

    private void tickEnd() {
        if (busy.getAsBoolean() || !plugin.getConfig().getBoolean("team.end-counter", true)) return;
        long now = System.currentTimeMillis();

        // Rappel des yeux d'Ender tant que le stronghold n'est pas trouve
        String key = nextGoalKey.get();
        if ("stronghold".equals(key) && !Bukkit.getOnlinePlayers().isEmpty()) {
            if (eyeNext == 0L) {
                eyeNext = now + 60_000L;
            } else if (now >= eyeNext) {
                eyeNext = now + 240_000L;
                say.say(eyesLine(), false);
            }
        }

        // Cristaux du dragon : compte dans l'End quand quelqu'un y est
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() != org.bukkit.World.Environment.THE_END || w.getPlayers().isEmpty()) continue;
            Collection<EnderCrystal> cs = w.getEntitiesByClass(EnderCrystal.class);
            int left = Math.min(CRYSTALS_TOTAL, cs.size());
            if (lastCrystals < 0) {
                lastCrystals = left;
                if (left > 0) {
                    Bukkit.getServer().sendActionBar(Component.text("Cristaux d'Ender : " + left + "/" + CRYSTALS_TOTAL
                            + " à détruire", NamedTextColor.LIGHT_PURPLE));
                }
            } else if (left < lastCrystals) {
                lastCrystals = left;
                Component c = Component.text("Cristaux d'Ender restants : " + left + "/" + CRYSTALS_TOTAL,
                        NamedTextColor.LIGHT_PURPLE);
                Bukkit.getServer().sendActionBar(c);
                Bukkit.getServer().sendMessage(c);
                if (left == 0) say.say("Plus aucun cristal. Le dragon ne se soigne plus : c'est le moment de frapper !", true);
            }
            break;
        }
    }

    // ---------------------------------------------------------------- reperes

    List<Mark> marks() {
        return new ArrayList<>(marks.values());
    }

    boolean addMark(Player p, String rawName) {
        String name = rawName.trim();
        if (name.length() > 20) name = name.substring(0, 20);
        if (name.isEmpty()) return false;
        String key = name.toLowerCase(Locale.ROOT);
        if (!marks.containsKey(key) && marks.size() >= MAX_MARKS) return false;
        Mark m = new Mark();
        m.name = name;
        Location l = p.getLocation();
        m.world = l.getWorld().getName();
        m.x = l.getX();
        m.y = l.getY();
        m.z = l.getZ();
        m.by = p.getName();
        marks.put(key, m);
        Bukkit.getServer().sendMessage(Component.text("📍 ", NamedTextColor.AQUA)
                .append(Component.text(p.getName() + " pose le repère « " + name + " » : "
                        + Math.round(m.x) + " " + Math.round(m.y) + " " + Math.round(m.z)
                        + " (" + dimension(l.getWorld()) + ")", NamedTextColor.AQUA)));
        return true;
    }

    boolean removeMark(String rawName) {
        return marks.remove(rawName.trim().toLowerCase(Locale.ROOT)) != null;
    }

    /** "124 m ↗" (ou "autre dimension"). */
    String describe(Player p, Mark m) {
        Location l = p.getLocation();
        String dim = "";
        org.bukkit.World w = Bukkit.getWorld(m.world);
        if (w != null) dim = dimension(w);
        if (!l.getWorld().getName().equals(m.world)) {
            return "autre dimension (" + dim + ") " + Math.round(m.x) + " " + Math.round(m.y) + " " + Math.round(m.z);
        }
        double dx = m.x - l.getX();
        double dz = m.z - l.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = targetYaw - l.getYaw();
        while (rel > 180.0) rel -= 360.0;
        while (rel < -180.0) rel += 360.0;
        String arrow;
        double a = Math.abs(rel);
        if (a <= 22.5) arrow = "↑";
        else if (a >= 157.5) arrow = "↓";
        else if (a < 67.5) arrow = rel > 0 ? "↗" : "↖";
        else if (a <= 112.5) arrow = rel > 0 ? "→" : "←";
        else arrow = rel > 0 ? "↘" : "↙";
        double dy = m.y - l.getY();
        String height = Math.abs(dy) >= 5.0 ? (dy > 0 ? "  ▲" : "  ▼") + Math.round(Math.abs(dy)) : "";
        return Math.round(dist) + " m " + arrow + height;
    }

    // ---------------------------------------------------------------- commandes

    boolean command(CommandSender sender, String name, String[] args) {
        switch (name) {
            case "uhcstock" -> showStock(sender);
            case "uhcpos" -> sharePosition(sender);
            case "uhcmarks" -> listMarks(sender);
            case "uhcmark" -> markCommand(sender, args);
            case "uhcend" -> showEnd(sender);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void showStock(CommandSender sender) {
        List<Stat> st = stock();
        sender.sendMessage(Component.text("──── Stock d'équipe ────", NamedTextColor.GOLD));
        StringBuilder sb = new StringBuilder();
        for (Stat s : st) {
            if (s.n <= 0) continue;
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(s.label).append(" ").append(s.n);
        }
        sender.sendMessage(Component.text(sb.length() == 0 ? "Les inventaires sont vides." : sb.toString(),
                NamedTextColor.YELLOW));
        double h = teamHearts();
        if (h >= 0.0) {
            sender.sendMessage(Component.text("Vie d'équipe : " + num(h) + " ❤", NamedTextColor.RED));
        }
    }

    private void showEnd(CommandSender sender) {
        sender.sendMessage(Component.text("──── Préparation de l'End ────", NamedTextColor.GOLD));
        for (String[] r : endChecklist()) {
            boolean ok = r[2].equals("1");
            sender.sendMessage(Component.text((ok ? "✔ " : "✘ ") + r[0] + " : " + r[1],
                    ok ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        int[] e = eyeCounts();
        sender.sendMessage(Component.text("Yeux en main " + e[0] + " · perles " + e[2] + " · poudre de blaze " + e[3],
                NamedTextColor.GRAY));
        if (lastCrystals >= 0) {
            sender.sendMessage(Component.text("Cristaux d'Ender restants : " + lastCrystals + "/" + CRYSTALS_TOTAL,
                    NamedTextColor.LIGHT_PURPLE));
        }
    }

    private void sharePosition(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Commande réservée aux joueurs.");
            return;
        }
        Location l = p.getLocation();
        Bukkit.getServer().sendMessage(Component.text("📍 " + p.getName() + " est en "
                + Math.round(l.getX()) + " " + Math.round(l.getY()) + " " + Math.round(l.getZ())
                + " (" + dimension(l.getWorld()) + ")", NamedTextColor.AQUA));
    }

    private void listMarks(CommandSender sender) {
        if (marks.isEmpty()) {
            sender.sendMessage(Component.text("Aucun repère. Pose-en un avec /uhcmark <nom> (ou via le menu : Shift+F).",
                    NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text("──── Repères ────", NamedTextColor.GOLD));
        for (Mark m : marks.values()) {
            String where = sender instanceof Player p ? describe(p, m)
                    : Math.round(m.x) + " " + Math.round(m.y) + " " + Math.round(m.z);
            sender.sendMessage(Component.text(m.name + " — " + where + "  (posé par " + m.by + ")",
                    NamedTextColor.AQUA));
        }
    }

    private void markCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            listMarks(sender);
            return;
        }
        if (args[0].equalsIgnoreCase("del") || args[0].equalsIgnoreCase("suppr")) {
            if (args.length < 2) {
                sender.sendMessage(Component.text("Utilisation : /uhcmark del <nom>", NamedTextColor.RED));
                return;
            }
            String n = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            sender.sendMessage(Component.text(removeMark(n) ? "Repère supprimé." : "Repère introuvable.",
                    NamedTextColor.YELLOW));
            return;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Commande réservée aux joueurs.");
            return;
        }
        if (!addMark(p, String.join(" ", args))) {
            sender.sendMessage(Component.text("Impossible : nom vide ou plus de " + MAX_MARKS + " repères.",
                    NamedTextColor.RED));
        }
    }
}
