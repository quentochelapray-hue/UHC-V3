package fr.uhc;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ultra hardcore en equipe (v3.0).
 *
 * - Pas de regeneration naturelle (via la regle de jeu : la faim n'est pas affectee), vie partagee,
 *   vision nocturne infinie, bruit de degats pour tout le monde.
 * - Notes : +0,2 par progres (0,5 si premier), +1 cœurs regeneres, gros caps (pierre -> dragon),
 *   mauvais points selon la gravite de l'erreur, -3 pour celui qui frappe un coequipier.
 * - Barre en haut : duree de la run + prochain objectif + note. Radar vers les coequipiers.
 * - Ecran de fin a la mort, puis nouvelle map (start.bat).
 * - Au debut de la run suivante : roulette pour designer le FAYOT (kit) et le CANCRE (malus 5 min).
 */
public class UhcPlugin extends JavaPlugin implements Listener {

    private static final long FINALE_TICKS = 460L; // 23 secondes

    // Gros caps dans l'ordre : id (cle de config), advancement Minecraft, libelle, points par defaut
    private static final String[][] CAPS = {
            {"stone", "story/mine_stone", "Obtenir de la pierre", "1.0"},
            {"iron", "story/smelt_iron", "Fondre du fer", "1.0"},
            {"iron_tools", "story/iron_tools", "Pioche en fer", "1.0"},
            {"diamond", "story/mine_diamond", "Trouver des diamants", "2.0"},
            {"nether", "story/enter_the_nether", "Entrer dans le Nether", "2.0"},
            {"fortress", "nether/find_fortress", "Trouver une forteresse", "2.0"},
            {"blaze_rod", "nether/obtain_blaze_rod", "Obtenir une blaze rod", "2.0"},
            {"stronghold", "story/follow_ender_eye", "Trouver le stronghold", "3.0"},
            {"end", "story/enter_the_end", "Entrer dans l'End", "3.0"},
            {"dragon", "end/kill_dragon", "Tuer l'Ender Dragon", "5.0"},
    };

    private boolean resetting = false;
    private int survivedSeconds = 0;
    private int deaths = 0;
    private int bestSeconds = 0;

    // ----- Historique des runs : classees par score (objectifs ET rapidite) -----
    private static final class Run {
        int number;
        int seconds;                                   // duree de survie
        int caps;                                      // objectifs atteints
        int lastCapAt;                                 // instant du dernier objectif
        double score;                                  // score : objectifs x rapidite (voir runScore)
        final Map<String, Integer> splits = new LinkedHashMap<>(); // objectif -> temps
    }

    private final List<Run> runs = new ArrayList<>();
    private final Map<String, Integer> capTimes = new HashMap<>();   // objectifs de CETTE run -> temps
    private final Map<String, Integer> bestSplits = new HashMap<>(); // meilleur temps jamais fait par objectif

    private void markCap(String key) {
        if (teamCaps.add(key)) {
            capTimes.put(key, survivedSeconds);
            lastCapSeconds = survivedSeconds;
        }
    }

    // meilleure run d'abord : meilleur score, puis plus d'objectifs, puis dernier objectif plus tot, puis survie
    private void sortRuns() {
        runs.sort((a, b) -> {
            if (Math.abs(a.score - b.score) > 0.001) return Double.compare(b.score, a.score);
            if (a.caps != b.caps) return b.caps - a.caps;
            if (a.caps > 0 && a.lastCapAt != b.lastCapAt) return a.lastCapAt - b.lastCapAt;
            return b.seconds - a.seconds;
        });
    }

    private String runText(Run r) {
        return note(r.score) + " pts (" + r.caps + "/" + CAPS.length + " objectifs, " + format(r.seconds) + ")";
    }


    private int lastCapSeconds = 0;
    // Ancien format (v3.2.1) : une seule meilleure run, reprise dans l'historique au chargement
    private double legacyBestScore = 0.0;
    private int legacyBestObjectives = 0;
    private int legacyBestSeconds = 0;

    // Stats de la run
    private final Map<UUID, Double> heartsLost = new HashMap<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, Double> scores = new HashMap<>();
    private final Map<String, UUID> advancementFirst = new HashMap<>();
    private final Map<String, Long> penaltyTimes = new HashMap<>();
    private final Map<UUID, Long> lastRegenBonus = new HashMap<>();
    private final Map<UUID, Long> lastNoteTime = new HashMap<>();
    private final Map<UUID, String> lastNoteReason = new HashMap<>();
    private final Map<String, Long> noteThrottle = new HashMap<>();
    private final Map<UUID, Double> runBonus = new HashMap<>();
    private final java.util.Set<UUID> runPlayers = new java.util.HashSet<>();
    private final Map<String, Incident> incidents = new HashMap<>();
    private final Map<UUID, Integer> capCounts = new HashMap<>();
    private final Map<UUID, BukkitTask> malusTasks = new HashMap<>();

    // Degats d'une meme cause rapproches dans le temps : ils s'additionnent (sanction progressive)
    private static final class Incident {
        double hearts = 0.0;
        double applied = 0.0;
        double announced = 0.0;
        long last = 0L;
        String detail = ""; // type du monstre a l'origine des degats
        BukkitTask task = null;
    }

    // Affichages
    private final Map<UUID, BossBar> radars = new HashMap<>();
    private final Map<UUID, BossBar> hudBars = new HashMap<>();
    private final Map<UUID, Boolean> hudUi = new HashMap<>();   // true = barre a icones
    private final Map<UUID, List<BossBar>> bubbleBars = new HashMap<>();

    // Stats globales (depuis le debut, conservees entre les runs dans stats.yml)
    private int totalSeconds = 0;
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Double> totalHeartsLost = new HashMap<>();
    private final Map<UUID, Integer> totalKills = new HashMap<>();
    private final Map<UUID, Integer> totalDeaths = new HashMap<>();
    private final Map<UUID, Integer> totalPlaySeconds = new HashMap<>();
    private final Map<UUID, Double> totalPointsGained = new HashMap<>();
    private final Map<UUID, Double> totalPointsLost = new HashMap<>();
    private final Map<String, Integer> causes = new HashMap<>();

    // Notes de la derniere run + animation du fayot / cancre au debut de la run suivante
    private final Map<UUID, Double> lastRunScores = new HashMap<>();
    private boolean introApplied = true;
    private boolean introStarted = false;
    private int introWait = 0;

    private File flagFile;
    private File statsFile;
    private NamespacedKey miniMapKey;
    private HeadPack heads;
    private final java.util.Set<String> teamCaps = new java.util.HashSet<>(); // objectifs faits par l'equipe
    private final Map<String, Integer> advancementCount = new HashMap<>();    // combien de joueurs l'ont reussi
    private boolean chestFirstTaken = false;
    private int quipTimer = 180;

    // Le professeur : personnage qui commente la run avec des bulles (barre violette en haut de l'ecran)
    private final java.util.ArrayDeque<String> teacherQueue = new java.util.ArrayDeque<>();
    private BossBar teacherBar;
    private String lastBubbleText = "";
    private int teacherLeft = 0;   // ticks restants pour la bulle affichee
    private int teacherTotal = 1;
    private boolean packAutoDone = false;
    private long introUntil = 0L;

    // ---------- Demarrage ----------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();
        Ui.setIconAdvances(getConfig().getIntegerList("pack.icon-advances"));
        flagFile = new File(Bukkit.getWorldContainer(), "reset.flag");
        statsFile = new File(getDataFolder(), "stats.yml");
        miniMapKey = new NamespacedKey(this, "minimap"); // anciennes minimaps (retirees a la connexion)

        // Un reset.flag oublie (serveur lance sans start.bat apres une mort) :
        // on le supprime pour ne pas effacer par erreur une partie en cours.
        if (flagFile.exists()) {
            flagFile.delete();
            getLogger().warning("Un ancien reset.flag a ete supprime. "
                    + "Lance toujours le serveur avec start.bat pour que la nouvelle map soit generee.");
        }

        loadStats();
        heads = new HeadPack(this);
        heads.enable();
        Bukkit.getPluginManager().registerEvents(this, this);
        applyWorldRules();

        Bukkit.getScheduler().runTaskTimer(this, this::everySecond, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::updateRadar, 20L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, this::tickTeacher, 5L, 5L);
    }

    @Override
    public void onDisable() {
        saveStats();
        if (heads != null) heads.disable();
    }

    // Pas de regeneration naturelle via la regle de jeu : contrairement a l'annulation de l'evenement,
    // la faim n'est alors pas consommee a cause des cœurs manquants.
    private void applyWorldRules() {
        for (World w : Bukkit.getWorlds()) {
            w.setGameRule(GameRule.NATURAL_REGENERATION, false);
        }
    }

    private void ensureNightVision(Player p) {
        if (!p.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                    PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }
    }

    private void everySecond() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (Player p : online) {
            ensureNightVision(p);
        }

        if (!resetting && !online.isEmpty()) {
            survivedSeconds++;
            totalSeconds++;
            for (Player p : online) {
                names.put(p.getUniqueId(), p.getName());
                runPlayers.add(p.getUniqueId());
                totalPlaySeconds.merge(p.getUniqueId(), 1, Integer::sum);
            }
            if (totalSeconds % 60 == 0) {
                saveStats(); // sauvegarde reguliere (en cas de plantage)
            }
            checkIntro();
            tickQuips();
            autoPack();
        }

        updateHud();
        updateTab();
    }

    // ---------- Blagues selon l'avancee de l'aventure ----------

    // Temps "par" (en secondes) de chaque objectif, dans l'ordre de CAPS (reglable dans config.yml)
    private double parSeconds(int index) {
        double[] defaults = {4, 10, 14, 30, 35, 45, 55, 70, 80, 95};
        List<Double> list = getConfig().getDoubleList("best-run.par-minutes");
        double minutes = index < list.size() ? list.get(index) : defaults[Math.min(index, defaults.length - 1)];
        return Math.max(30.0, minutes * 60.0);
    }

    // Score de run : chaque objectif fait par l'equipe vaut ses points x 10 x un coefficient de rapidite.
    // Coefficient = 1,5 si fait tout de suite, 1,0 au temps "par", 0,5 (minimum) s'il est fait 2 fois plus tard.
    private double runScore() {
        double total = 0.0;
        for (int i = 0; i < CAPS.length; i++) {
            Integer t = capTimes.get(CAPS[i][0]);
            if (t == null) continue;
            double multiplier = Math.max(0.5, Math.min(1.5, 1.5 - 0.5 * t / parSeconds(i)));
            total += capPoints(i) * 10.0 * multiplier;
        }
        return total;
    }

    private int stage() {
        int n = teamCaps.size();
        if (n == 0) return 0;
        if (n <= 2) return 1;
        if (n <= 4) return 2;
        if (n <= 7) return 3;
        return 4;
    }

    private void tickQuips() {
        if (!getConfig().getBoolean("teacher.ambient", true) || survivedSeconds < 60) return;
        if (--quipTimer > 0) return;

        // Delai ALEATOIRE entre deux remarques du professeur
        int min = Math.max(30, getConfig().getInt("teacher.ambient-min-seconds", 150));
        int max = Math.max(min + 1, getConfig().getInt("teacher.ambient-max-seconds", 480));
        quipTimer = ThreadLocalRandom.current().nextInt(min, max + 1);

        // La situation de la run : sert a choisir une remarque qui colle au moment
        Jokes.Ctx ctx = new Jokes.Ctx();
        ctx.stage = stage();
        ctx.minutes = survivedSeconds / 60;
        ctx.caps = teamCaps.size();
        ctx.totalCaps = CAPS.length;
        ctx.sinceCapMinutes = (survivedSeconds - lastCapSeconds) / 60;
        for (int i = 0; i < CAPS.length; i++) {
            if (!teamCaps.contains(CAPS[i][0])) {
                ctx.nextObjective = CAPS[i][2];
                break;
            }
        }
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        ctx.alone = online.size() == 1;
        double bestScore = -1.0;
        double worstScore = Double.MAX_VALUE;
        for (Player o : online) {
            UUID id = o.getUniqueId();
            double lost = heartsLost.getOrDefault(id, 0.0);
            if (lost > ctx.clumsyHearts) {
                ctx.clumsyHearts = lost;
                ctx.clumsy = o.getName();
            }
            double hp = o.getHealth() / 2.0;
            if (hp <= 3.0 && (ctx.lowHealth == null || hp < ctx.lowHearts)) {
                ctx.lowHealth = o.getName();
                ctx.lowHearts = hp;
            }
            double sc = score(id);
            if (sc > bestScore) {
                bestScore = sc;
                ctx.best = o.getName();
                ctx.bestScore = sc;
            }
            if (sc < worstScore) {
                worstScore = sc;
                ctx.worst = o.getName();
                ctx.worstScore = sc;
            }
        }
        teacherSay(Jokes.ambient(ctx), false);
    }

    // Lance d'office /uhcpack des que quelqu'un est connecte (une fois par demarrage du serveur)
    private void autoPack() {
        if (packAutoDone || survivedSeconds < 8) return;
        packAutoDone = true;
        heads.rebuild(new ArrayList<>(Bukkit.getOnlinePlayers()), Bukkit.getConsoleSender());
    }

    // ---------- Stats ----------

    private void loadStats() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(statsFile);
        deaths = c.getInt("deaths", 0);
        bestSeconds = c.getInt("best-seconds", 0);
        legacyBestScore = c.getDouble("best-run.score", 0.0);
        legacyBestObjectives = c.getInt("best-run.objectives", 0);
        legacyBestSeconds = c.getInt("best-run.seconds", 0);

        // Memoire des phrases deja entendues : on evite les repetitions d'une run a l'autre
        ConfigurationSection jokesSeen = c.getConfigurationSection("jokes-seen");
        if (jokesSeen != null) {
            Map<String, List<Integer>> memory = new HashMap<>();
            for (String key : jokesSeen.getKeys(false)) {
                memory.put(key, jokesSeen.getIntegerList(key));
            }
            Jokes.importMemory(memory);
        }
        totalSeconds = c.getInt("total-seconds", 0);

        runs.clear();
        ConfigurationSection rs = c.getConfigurationSection("runs");
        if (rs != null) {
            for (String key : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(key);
                if (r == null) continue;
                Run run = new Run();
                run.number = r.getInt("number", 0);
                run.seconds = r.getInt("seconds", 0);
                run.caps = r.getInt("caps", 0);
                run.lastCapAt = r.getInt("last-cap-at", 0);
                run.score = r.getDouble("score", 0.0);
                ConfigurationSection sp = r.getConfigurationSection("splits");
                if (sp != null) {
                    for (String k : sp.getKeys(false)) run.splits.put(k, sp.getInt(k));
                }
                runs.add(run);
            }
        }
        if (runs.isEmpty() && legacyBestScore > 0) {
            Run old = new Run();
            old.score = legacyBestScore;
            old.caps = legacyBestObjectives;
            old.seconds = legacyBestSeconds;
            runs.add(old);
        }
        bestSplits.clear();
        for (Run r : runs) {
            for (Map.Entry<String, Integer> e : r.splits.entrySet()) {
                bestSplits.merge(e.getKey(), e.getValue(), Math::min);
            }
        }
        sortRuns();

        ConfigurationSection players = c.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                UUID id;
                try {
                    id = UUID.fromString(key);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                ConfigurationSection s = players.getConfigurationSection(key);
                if (s == null) continue;
                names.put(id, s.getString("name", "?"));
                totalHeartsLost.put(id, s.getDouble("hearts-lost", 0.0));
                totalKills.put(id, s.getInt("kills", 0));
                totalDeaths.put(id, s.getInt("deaths", 0));
                totalPlaySeconds.put(id, s.getInt("play-seconds", 0));
                totalPointsGained.put(id, s.getDouble("points-gained", 0.0));
                totalPointsLost.put(id, s.getDouble("points-lost", 0.0));
            }
        }

        ConfigurationSection causeSection = c.getConfigurationSection("causes");
        if (causeSection != null) {
            for (String key : causeSection.getKeys(false)) {
                causes.put(key, causeSection.getInt(key, 0));
            }
        }

        ConfigurationSection saved = c.getConfigurationSection("scores");
        if (saved != null) {
            for (String key : saved.getKeys(false)) {
                try {
                    scores.put(UUID.fromString(key), saved.getDouble(key, maxScore()));
                } catch (IllegalArgumentException ignored) {
                    // cle invalide : on l'ignore
                }
            }
        }

        introApplied = c.getBoolean("last-run.applied", true);
        ConfigurationSection last = c.getConfigurationSection("last-run.scores");
        if (last != null) {
            for (String key : last.getKeys(false)) {
                try {
                    lastRunScores.put(UUID.fromString(key), last.getDouble(key, 0.0));
                } catch (IllegalArgumentException ignored) {
                    // cle invalide : on l'ignore
                }
            }
        }
    }

    private void saveStats() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("deaths", deaths);
        c.set("best-seconds", bestSeconds);
        for (Map.Entry<String, List<Integer>> entry : Jokes.exportMemory().entrySet()) {
            c.set("jokes-seen." + entry.getKey(), entry.getValue());
        }
        c.set("total-seconds", totalSeconds);

        int idx = 0;
        for (Run r : runs) {
            String base = "runs.r" + (idx++);
            c.set(base + ".number", r.number);
            c.set(base + ".seconds", r.seconds);
            c.set(base + ".caps", r.caps);
            c.set(base + ".last-cap-at", r.lastCapAt);
            c.set(base + ".score", r.score);
            for (Map.Entry<String, Integer> e : r.splits.entrySet()) {
                c.set(base + ".splits." + e.getKey(), e.getValue());
            }
        }

        for (UUID id : names.keySet()) {
            String base = "players." + id;
            c.set(base + ".name", names.get(id));
            c.set(base + ".hearts-lost", totalHeartsLost.getOrDefault(id, 0.0));
            c.set(base + ".kills", totalKills.getOrDefault(id, 0));
            c.set(base + ".deaths", totalDeaths.getOrDefault(id, 0));
            c.set(base + ".play-seconds", totalPlaySeconds.getOrDefault(id, 0));
            c.set(base + ".points-gained", totalPointsGained.getOrDefault(id, 0.0));
            c.set(base + ".points-lost", totalPointsLost.getOrDefault(id, 0.0));
        }
        for (Map.Entry<String, Integer> entry : causes.entrySet()) {
            c.set("causes." + entry.getKey(), entry.getValue());
        }

        for (Map.Entry<UUID, Double> entry : scores.entrySet()) {
            c.set("scores." + entry.getKey(), entry.getValue());
        }
        c.set("last-run.applied", introApplied);
        for (Map.Entry<UUID, Double> entry : lastRunScores.entrySet()) {
            c.set("last-run.scores." + entry.getKey(), entry.getValue());
        }

        try {
            c.save(statsFile);
        } catch (IOException e) {
            getLogger().warning("Impossible d'enregistrer stats.yml : " + e.getMessage());
        }
    }

    // ---------- Petits outils ----------

    private double cfg(String path, double def) {
        return getConfig().getDouble(path, def);
    }

    // Tous les temps s'affichent en heures:minutes:secondes (ex : 01:05:09)
    private String format(int seconds) {
        return formatClock(seconds);
    }

    private String formatClock(int seconds) {
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s);
    }

    // Chrono de la barre : h:mm:ss (ex. 0:03:18)
    private String hudClock(int seconds) {
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private String formatLong(int seconds) {
        return String.format(Locale.ROOT, "%d h %02d min %02d s", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private String hearts(double value) {
        return String.format(Locale.ROOT, "%.1f", value).replace('.', ',');
    }

    private String note(double v) {
        return Math.abs(v - Math.round(v)) < 0.05 ? String.valueOf(Math.round(v)) : hearts(v);
    }

    // Pseudo precede de la tete du joueur (si elle existe dans le pack)
    private Component nameTag(String name, NamedTextColor color) {
        return Component.empty().append(heads.head(name)).append(Component.text(name, color));
    }

    private String nameOf(UUID id) {
        return names.getOrDefault(id, "?");
    }

    private Component statsLine() {
        String text = "Tentative n°" + (deaths + 1);
        if (!runs.isEmpty()) {
            text += " | Meilleure run : " + runText(runs.get(0));
        }
        return Component.text(text, NamedTextColor.GOLD);
    }

    private String dimension(World w) {
        return switch (w.getEnvironment()) {
            case NORMAL -> "Overworld";
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> "autre";
        };
    }

    private String globalSummary() {
        return "Runs perdues : " + deaths + "   |   Temps de jeu total : " + formatLong(totalSeconds)
                + (!runs.isEmpty() ? "   |   Meilleure run : " + runText(runs.get(0)) : "");
    }

    private List<UUID> sortedIds() {
        List<UUID> ids = new ArrayList<>(names.keySet());
        ids.sort((a, b) -> nameOf(a).compareToIgnoreCase(nameOf(b)));
        return ids;
    }

    private Component globalLine(UUID id) {
        return nameTag(nameOf(id), NamedTextColor.YELLOW)
                .append(Component.text("   perdus " + hearts(totalHeartsLost.getOrDefault(id, 0.0)) + " ❤",
                        NamedTextColor.RED))
                .append(Component.text("   kills " + totalKills.getOrDefault(id, 0), NamedTextColor.GRAY))
                .append(Component.text("   fins de run " + totalDeaths.getOrDefault(id, 0), NamedTextColor.GRAY))
                .append(Component.text("   temps " + formatLong(totalPlaySeconds.getOrDefault(id, 0)),
                        NamedTextColor.AQUA))
                .append(Component.text("   bons +" + hearts(totalPointsGained.getOrDefault(id, 0.0))
                        + " / mauvais -" + hearts(totalPointsLost.getOrDefault(id, 0.0)), NamedTextColor.GREEN));
    }

    // ---------- Notes : points ----------

    private double maxScore() {
        return Math.max(1.0, cfg("max-score", 20.0));
    }

    private double score(UUID id) {
        return scores.getOrDefault(id, maxScore());
    }

    private NamedTextColor noteColor(double v) {
        double ratio = v / maxScore();
        if (ratio >= 0.75) return NamedTextColor.GREEN;
        if (ratio >= 0.5) return NamedTextColor.YELLOW;
        return NamedTextColor.RED;
    }

    private Component noteComponent(UUID id) {
        double s = score(id);
        return Component.text(note(s), noteColor(s));
    }

    private String mention(double v) {
        double on20 = v / maxScore() * 20.0;
        if (on20 >= 18) return "Félicitations";
        if (on20 >= 16) return "Très bien";
        if (on20 >= 14) return "Bien";
        if (on20 >= 12) return "Assez bien";
        if (on20 >= 10) return "Passable";
        if (on20 >= 8) return "Doit mieux faire";
        return "Redoublement";
    }

    private void addPoints(Player p, double delta, String reason) {
        addPoints(p, delta, reason, reason, null, true);
    }

    // throttleKey identique = un seul message toutes les 3 s (les points, eux, sont toujours comptes)
    private void addPoints(Player p, double delta, String reason, String throttleKey) {
        addPoints(p, delta, reason, throttleKey, null, true);
    }

    private static String pointsText(double signed) {
        double abs = Math.abs(signed);
        String number = Math.abs(abs - Math.round(abs)) < 0.05
                ? String.valueOf(Math.round(abs))
                : String.format(Locale.ROOT, "%.1f", abs).replace('.', ',');
        return (signed >= 0 ? "+" : "-") + number + (abs >= 2 ? " points" : " point");
    }

    // Ligne "evann71 -3 points : raison « blague »" (envoyee a tout le monde, ou au joueur seul)
    private void sendPointsLine(Player p, double signed, String reason, String quip) {
        announcePoints(p, signed, reason, quip, -1);
    }

    // Perte : barre d'action vue par TOUT LE MONDE, juste au-dessus de la vie : pseudo, coeurs perdus, points perdus.
    // Gain : barre d'action du joueur. La remarque du professeur (bulle) accompagne les gros evenements.
    private void announcePoints(Player p, double signed, String reason, String quip, double heartsLost) {
        if (signed > 0) {
            p.sendActionBar(Component.text("✔ " + pointsText(signed) + " ", NamedTextColor.GREEN)
                    .append(Component.text(reason, NamedTextColor.GRAY))
                    .append(Component.text("  (" + note(score(p.getUniqueId())) + ")", NamedTextColor.DARK_GRAY)));
        } else {
            Component bar = nameTag(p.getName(), NamedTextColor.WHITE);
            if (heartsLost > 0) {
                bar = bar.append(Component.text("  -" + hearts(heartsLost) + " ❤", NamedTextColor.RED));
            }
            bar = bar.append(Component.text("  " + pointsText(signed), NamedTextColor.GOLD))
                    .append(Component.text("  " + reason, NamedTextColor.GRAY));
            final Component shown = bar;
            Bukkit.getServer().sendActionBar(shown);
            Bukkit.getScheduler().runTaskLater(this, () -> Bukkit.getServer().sendActionBar(shown), 40L);
        }
        if (quip != null && !quip.isEmpty()) {
            teacherSay(quip, Math.abs(signed) >= 1.0);
        }
    }

    private void addPoints(Player p, double delta, String reason, String throttleKey, String quip,
                           boolean announce) {
        if (resetting || Math.abs(delta) < 0.001) return;

        UUID id = p.getUniqueId();
        double before = score(id);
        double after = Math.max(0.0, Math.min(maxScore(), before + delta)); // jamais > max, jamais < 0
        double applied = after - before;

        scores.put(id, after);
        names.put(id, p.getName());
        if (delta > 0) {
            runBonus.merge(id, delta, Double::sum);
        }
        if (applied > 0) {
            totalPointsGained.merge(id, applied, Double::sum);
        } else if (applied < 0) {
            totalPointsLost.merge(id, -applied, Double::sum);
        }
        if (Math.abs(applied) < 0.001 || !announce) return;

        long now = System.currentTimeMillis();
        String key = id + ":" + throttleKey;
        Long last = noteThrottle.get(key);
        noteThrottle.put(key, now);
        if (last != null && now - last < 1500) return;

        sendPointsLine(p, applied, reason, quip);
    }

    // Sanction progressive : une seule annonce a la fin de l'incident (total des points perdus)
    private void scheduleIncidentAnnounce(Player victim, Incident inc, String key, String tier) {
        if (inc.task != null) {
            inc.task.cancel();
        }
        final UUID id = victim.getUniqueId();
        long ticks = Math.max(10L, (long) (cfg("penalties.incident-window-seconds", 2.5) * 20));
        inc.task = Bukkit.getScheduler().runTaskLater(this, () -> {
            inc.task = null;
            if (resetting) return;
            Player p = Bukkit.getPlayer(id);
            if (p == null) return;

            // Les degats se sont arretes : on calcule le total UNE seule fois
            double total = tierRate(tier) * Math.pow(inc.hearts, tierExponent(tier));
            double lost = total - inc.applied;
            if (lost < 0.05) return;
            inc.applied = total;

            addPoints(p, -lost, tierLabel(tier), "dmg:" + key, null, false);
            announcePoints(p, -lost, tierLabel(tier) + " : " + causeLabel(key),
                    Jokes.damage(p.getName(), key, inc.detail, inc.hearts, lost, stage()), inc.hearts);
        }, ticks);
    }

    // Classement : la note, puis les bons points gagnes, puis les coeurs perdus (pour departager)
    private double rankKey(UUID id) {
        return score(id); // classement = la note seule : en cas d'egalite, meme recompense / meme sanction
    }

    private void celebrateCap(Player p, String label) {
        Bukkit.getServer().sendActionBar(Component.text("★ ", NamedTextColor.GREEN)
                .append(nameTag(p.getName(), NamedTextColor.GREEN))
                .append(Component.text(" a passé le cap : " + label, NamedTextColor.GREEN)));
        for (Player o : Bukkit.getOnlinePlayers()) {
            heads.play(o, "cap", Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        }
    }

    private double capPoints(int index) {
        return cfg("caps." + CAPS[index][0], Double.parseDouble(CAPS[index][3]));
    }

    private boolean capDone(Player p, String key) {
        Advancement adv = Bukkit.getAdvancement(NamespacedKey.minecraft(key));
        if (adv == null) return true; // advancement inconnu : on ne bloque pas l'objectif
        return p.getAdvancementProgress(adv).isDone();
    }

    // ---------- Progres, caps, cœurs regeneres : bons points ----------

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        String key = e.getAdvancement().getKey().getKey();
        if (key.startsWith("recipes/") || key.endsWith("/root")) return;

        Player p = e.getPlayer();
        String label = key.substring(key.lastIndexOf('/') + 1).replace('_', ' ');

        int capIndex = -1;
        for (int i = 0; i < CAPS.length; i++) {
            if (CAPS[i][1].equals(key)) capIndex = i;
        }
        boolean collective = getConfig().getStringList("collective-advancements").contains(key);
        int rank = advancementCount.merge(key, 1, Integer::sum); // 1 = premier de l'equipe, 2 = deuxieme...
        if (rank == 1) {
            advancementFirst.putIfAbsent(key, p.getUniqueId());
        }

        if (capIndex >= 0 && rank == 1) {
            markCap(CAPS[capIndex][0]); // objectif fait pour toute l'equipe
        }

        // --- Defi COLLECTIF (lit, Nether...) : des qu'un joueur le fait, tout le monde gagne les points ---
        if (collective) {
            if (rank == 1) {
                double pts = capIndex >= 0 ? capPoints(capIndex) : cfg("bonus.collective", 0.5);
                String name = capIndex >= 0 ? CAPS[capIndex][2] : label;
                for (Player o : Bukkit.getOnlinePlayers()) {
                    addPoints(o, pts, "défi collectif : " + name, "collective:" + key, null, false);
                    heads.play(o, "cap", Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
                }
                Bukkit.getServer().sendActionBar(Component.text("★ ", NamedTextColor.GREEN)
                        .append(nameTag(p.getName(), NamedTextColor.GREEN))
                        .append(Component.text(" a réussi un défi collectif : " + name + " → tout le monde gagne "
                                + pointsText(pts), NamedTextColor.GREEN)));
                if (capIndex >= 0) {
                    teacherSay(Jokes.cap(capIndex, p.getName()), true);
                }
            }
            return;
        }

        // --- Defi INDIVIDUEL : le premier gagne le plus, les suivants beaucoup moins ---
        if (capIndex >= 0) {
            if (rank == 1) {
                addPoints(p, cfg("bonus.advancement-first", 0.5), "premier à le faire : " + label);
                addPoints(p, capPoints(capIndex), "cap passé : " + CAPS[capIndex][2]);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
                celebrateCap(p, CAPS[capIndex][2]);
                teacherSay(Jokes.cap(capIndex, p.getName()), true);
            } else {
                addPoints(p, cfg("bonus.cap-later", 0.3), "cap déjà passé par l'équipe : " + CAPS[capIndex][2]);
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f);
            }
            return;
        }

        if (rank == 1) {
            addPoints(p, cfg("bonus.advancement-first", 0.5), "premier à le faire : " + label);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        } else if (rank == 2) {
            addPoints(p, cfg("bonus.advancement-second", 0.3), "deuxième à le faire : " + label);
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.1f);
        } else {
            addPoints(p, cfg("bonus.advancement", 0.2), "progrès : " + label);
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onRegain(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        RegainReason reason = e.getRegainReason();
        if (reason == RegainReason.SATIATED || reason == RegainReason.REGEN) {
            e.setCancelled(true); // securite : la regle de jeu s'en charge deja
            return;
        }

        // Reussir a regenerer des cœurs (pomme d'or, potion...) : bon point
        if (e.getAmount() > 0 && p.getHealth() < p.getMaxHealth() - 0.01) {
            long cooldown = (long) (cfg("bonus.regen-cooldown-seconds", 30.0) * 1000);
            long now = System.currentTimeMillis();
            if (now - lastRegenBonus.getOrDefault(p.getUniqueId(), 0L) >= cooldown) {
                lastRegenBonus.put(p.getUniqueId(), now);
                addPoints(p, cfg("bonus.regen", 1.0), "cœurs régénérés", "regen", Jokes.regen(p.getName()), true);
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
            }
        }
        // Soin partage
        Bukkit.getScheduler().runTask(this, () -> syncFrom(p));
    }

    // ---------- Coup mortel : on l'intercepte pour jouer l'ecran de fin ----------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethalDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        // Pendant l'ecran de fin, personne ne peut plus mourir ni prendre de degats
        if (resetting) {
            e.setCancelled(true);
            return;
        }
        if (p.getHealth() - e.getFinalDamage() > 0) return;
        if (holdsTotem(p)) return; // le totem d'immortalite fait son travail normalement

        e.setCancelled(true);
        startReset(p, joke(p, e), causeName(p, e));
    }

    private boolean holdsTotem(Player p) {
        return p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }

    // ---------- Degats : bruit, barre, mauvais points, vie partagee ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        double damage = e.getFinalDamage();
        if (damage > 0 && !resetting) {
            heartsLost.merge(p.getUniqueId(), damage / 2.0, Double::sum);
            totalHeartsLost.merge(p.getUniqueId(), damage / 2.0, Double::sum);
            names.put(p.getUniqueId(), p.getName());
            playHurtSound(p);
            applyDamagePenalty(p, e, damage);
        }
        // La vie est mise a jour apres l'evenement : on synchronise au tick suivant
        Bukkit.getScheduler().runTask(this, () -> syncFrom(p));
    }

    // Petit bruit de degats pour tous les autres joueurs (le blesse entend deja le sien)
    private void playHurtSound(Player victim) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.equals(victim)) {
                heads.play(o, "hurt", Sound.ENTITY_PLAYER_HURT, 0.7f, 1.0f);
            }
        }
    }

    private void showDamageBar(Player p, double damage) {
        Component bar = Component.text("-" + hearts(damage / 2.0) + " ❤ ", NamedTextColor.RED)
                .append(nameTag(p.getName(), NamedTextColor.WHITE));
        Bukkit.getServer().sendActionBar(bar);
    }

    private boolean penaltyReady(UUID id, String key) {
        long cooldown = (long) (cfg("penalties.cooldown-seconds", 3.0) * 1000);
        long now = System.currentTimeMillis();
        String k = id + ":" + key;
        Long last = penaltyTimes.get(k);
        if (last != null && now - last < cooldown) return false;
        penaltyTimes.put(k, now);
        return true;
    }

    private String tierOf(String key) {
        if (getConfig().getStringList("penalties.stupid").contains(key)) return "stupid";
        if (getConfig().getStringList("penalties.normal").contains(key)) return "normal";
        return "unlucky";
    }

    private double tierRate(String tier) {
        return switch (tier) {
            case "stupid" -> cfg("penalties.rate.stupid", 1.0);
            case "normal" -> cfg("penalties.rate.normal", 0.6);
            default -> cfg("penalties.rate.unlucky", 0.25);
        };
    }

    private double tierExponent(String tier) {
        return switch (tier) {
            case "stupid" -> cfg("penalties.exponent.stupid", 1.4);
            case "normal" -> cfg("penalties.exponent.normal", 1.3);
            default -> cfg("penalties.exponent.unlucky", 1.0);
        };
    }

    private String tierLabel(String tier) {
        return switch (tier) {
            case "stupid" -> "erreur bête";
            case "normal" -> "erreur normale";
            default -> "pas de chance";
        };
    }

    private void applyDamagePenalty(Player victim, EntityDamageEvent ev, double damage) {
        // Frapper un coequipier : gros malus pour celui qui frappe, rien pour celui qui recoit
        Entity killer = killerOf(ev);
        if (killer instanceof Player attacker && !attacker.equals(victim)) {
            if (penaltyReady(attacker.getUniqueId(), "FRIENDLY")) {
                addPoints(attacker, -cfg("penalties.friendly-fire-points", 3.0),
                        "coup porté à " + victim.getName(), "FRIENDLY",
                        Jokes.friendly(attacker.getName(), victim.getName()), true);
            }
            return;
        }

        String key = ev.getCause().name();
        if (key.equals("FIRE_TICK") || key.equals("CAMPFIRE")) key = "FIRE";

        // Sanction progressive : les degats de meme cause rapproches forment un "incident".
        // Les points sont calcules et annonces UNE SEULE FOIS, quand les degats s'arretent.
        String tier = tierOf(key);
        long window = (long) (cfg("penalties.incident-window-seconds", 2.5) * 1000);
        long now = System.currentTimeMillis();
        Incident incident = incidents.computeIfAbsent(victim.getUniqueId() + ":" + key, x -> new Incident());
        if (now - incident.last > window) {
            incident.hearts = 0.0;
            incident.applied = 0.0;
            incident.announced = 0.0;
            incident.detail = "";
        }
        incident.hearts += damage / 2.0;
        incident.last = now;
        String extra = detailOf(victim, ev);
        if (!extra.isEmpty()) {
            incident.detail = extra;
        }

        scheduleIncidentAnnounce(victim, incident, key, tier);
    }

    // Detail utile aux blagues : monstre, bloc qui blesse (cactus, baies...) ou bloc dans lequel on etouffe
    private String detailOf(Player victim, EntityDamageEvent ev) {
        Entity killer = killerOf(ev);
        if (killer != null && !killer.equals(victim)) {
            return killer.getType().name();
        }
        if (ev instanceof EntityDamageByBlockEvent byBlock && byBlock.getDamager() != null) {
            return "BLOCK:" + byBlock.getDamager().getType().name();
        }
        if (ev.getCause().name().equals("SUFFOCATION")) {
            return "HEAD:" + victim.getEyeLocation().getBlock().getType().name();
        }
        return "";
    }

    // ---------- Le professeur : bulles de dialogue en haut de l'ecran ----------

    private String teacherName() {
        return getConfig().getString("teacher.name", "ProfB");
    }

    // important = false : la remarque est ignoree si le professeur est deja en train de parler
    private void teacherSay(String text, boolean important) {
        if (text == null || text.isBlank()) return;
        if (!getConfig().getBoolean("teacher.enabled", true)) return;
        if (!important && (teacherLeft > 0 || !teacherQueue.isEmpty())) return;
        for (String part : splitBubble(text, Math.max(30, getConfig().getInt("teacher.max-chars", 66)))) {
            if (teacherQueue.size() < 8) {
                teacherQueue.add(part);
            }
        }
    }

    private List<String> splitBubble(String text, int max) {
        List<String> parts = new ArrayList<>();
        String rest = text.trim();
        while (rest.length() > max) {
            int cut = rest.lastIndexOf(' ', max);
            if (cut < max / 2) cut = max;
            parts.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) {
            parts.add(rest);
        }
        return parts;
    }

    // Toutes les 5 ticks : affiche la bulle suivante, fait baisser la jauge, puis retire la barre
    private void tickTeacher() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();

        if (teacherLeft > 0) {
            teacherLeft -= 5;
            if (teacherLeft <= 0) {
                teacherLeft = 0;
                if (teacherQueue.isEmpty()) {
                    for (Player p : online) {
                        if (teacherBar != null) p.hideBossBar(teacherBar);
                        hideBubble(p);
                    }
                }
            } else if (teacherBar != null) {
                animateTeacher(online);
                teacherBar.progress(Math.max(0f, Math.min(1f, teacherLeft / (float) teacherTotal)));
                for (Player p : online) {
                    if (heads.hasUi(p)) {
                        if (!bubbleBars.containsKey(p.getUniqueId())) showBubble(p, lastBubbleText);
                    } else if (teacherBar != null) {
                        p.showBossBar(teacherBar); // pour ceux qui se connectent pendant la bulle
                    }
                }
            }
        }

        if (teacherLeft <= 0 && !teacherQueue.isEmpty() && !online.isEmpty()) {
            String text = teacherQueue.poll();
            Component bubble = Component.text(teacherName() + " : ", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .append(Component.text(text, NamedTextColor.WHITE));
            teacherTotal = 40 + (int) (text.length() * 1.5);
            teacherLeft = teacherTotal;
            if (teacherBar == null) {
                teacherBar = BossBar.bossBar(bubble, 1.0f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
            } else {
                teacherBar.name(bubble);
                teacherBar.progress(1.0f);
            }
            lastBubbleText = text;
            for (Player p : online) {
                hideBubble(p);
                if (heads.hasUi(p)) {
                    p.hideBossBar(teacherBar);
                    showBubble(p, text);
                } else {
                    p.showBossBar(teacherBar);
                }
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.5f, 1.3f);
            }
        }
    }

    // Petite animation : le prof "parle" (bouche qui s'ouvre, tete qui rebondit) pendant ~3/4 de la bulle
    private void animateTeacher(Collection<? extends Player> online) {
        int elapsed = teacherTotal - teacherLeft;
        int frame = elapsed < teacherTotal * 3 / 4 ? 1 + (elapsed / 5) % 2 : 0;
        for (Player p : online) {
            List<BossBar> rows = bubbleBars.get(p.getUniqueId());
            if (rows == null || rows.size() < 2) continue;
            List<Component> comps = Ui.bubble(teacherName(), lastBubbleText, 36, frame);
            for (int k = 0; k < comps.size() && k + 1 < rows.size(); k++) {
                rows.get(k + 1).name(comps.get(k));
            }
        }
    }

    // Bulle a portrait : une barre de boss (invisible) par rangee, dessinee avec la police du pack
    private void showBubble(Player p, String text) {
        List<BossBar> rows = new ArrayList<>();
        // Rangee vide : laisse la place sous la barre d'objectifs (le panneau de la bulle depasse un peu au-dessus de sa rangee)
        BossBar spacer = BossBar.bossBar(Component.text(" "), 1.0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        rows.add(spacer);
        p.showBossBar(spacer);
        for (Component c : Ui.bubble(teacherName(), text, 36, 0)) {
            BossBar b = BossBar.bossBar(c, 1.0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
            rows.add(b);
            p.showBossBar(b);
        }
        bubbleBars.put(p.getUniqueId(), rows);
    }

    private String causeLabel(String key) {
        return switch (key) {
            case "FALL" -> "chute";
            case "LAVA" -> "bain de lave";
            case "FIRE" -> "brûlure";
            case "DROWNING" -> "noyade";
            case "STARVATION" -> "faim";
            case "SUFFOCATION" -> "étouffement";
            case "CONTACT" -> "cactus";
            case "HOT_FLOOR" -> "sol brûlant";
            case "FLY_INTO_WALL" -> "mur en élytre";
            case "FALLING_BLOCK" -> "bloc sur la tête";
            case "LIGHTNING" -> "foudre";
            case "BLOCK_EXPLOSION", "ENTITY_EXPLOSION" -> "explosion";
            case "FREEZE" -> "gelure";
            case "VOID" -> "vide";
            case "ENTITY_ATTACK", "ENTITY_SWEEP_ATTACK" -> "coup de monstre";
            case "PROJECTILE" -> "projectile";
            default -> key.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) return;
        Player killer = e.getEntity().getKiller();
        if (killer != null) {
            kills.merge(killer.getUniqueId(), 1, Integer::sum);
            totalKills.merge(killer.getUniqueId(), 1, Integer::sum);
            names.put(killer.getUniqueId(), killer.getName());
        }
    }

    @SuppressWarnings("deprecation")
    private void syncFrom(Player source) {
        if (resetting || !source.isOnline() || source.isDead()) return;

        double health = source.getHealth();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(source) || other.isDead()) continue;
            if (other.getGameMode() == GameMode.SPECTATOR) continue;
            other.setHealth(Math.max(0.1, Math.min(health, other.getMaxHealth())));
        }
    }

    // ---------- Chat : tete devant le pseudo ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent e) {
        final String sender = e.getPlayer().getName();
        e.renderer(ChatRenderer.viewerUnaware((source, displayName, message) ->
                Component.empty()
                        .append(heads.head(sender))
                        .append(Component.text("<", NamedTextColor.WHITE))
                        .append(displayName)
                        .append(Component.text("> ", NamedTextColor.WHITE))
                        .append(message)));
    }

    // ---------- Premier coffre ouvert ----------

    @EventHandler(ignoreCancelled = true)
    public void onChestOpen(InventoryOpenEvent e) {
        if (resetting || chestFirstTaken) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        InventoryHolder holder = e.getInventory().getHolder();
        if (!(holder instanceof Chest) && !(holder instanceof DoubleChest)) return;

        chestFirstTaken = true;
        addPoints(p, cfg("bonus.chest-first", 0.5), "premier à ouvrir un coffre", "chest", Jokes.chest(p.getName()), true);
        for (Player o : Bukkit.getOnlinePlayers()) {
            heads.play(o, "chest", Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
        }
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent e) {
        heads.onStatus(e.getPlayer(), e.getStatus(), e.getID());
    }

    // ---------- Connexion / deconnexion ----------

    @EventHandler
    @SuppressWarnings("deprecation")
    public void onJoin(PlayerJoinEvent e) {
        Player joined = e.getPlayer();
        names.put(joined.getUniqueId(), joined.getName());
        joined.sendMessage(statsLine());

        applyWorldRules();
        ensureNightVision(joined);
        heads.onJoin(joined);

        if (!resetting) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(joined) && !other.isDead()) {
                    joined.setHealth(Math.max(0.1, Math.min(other.getHealth(), joined.getMaxHealth())));
                    break;
                }
            }
        }

        // La minimap n'existe plus : on retire les anciennes cartes
        Bukkit.getScheduler().runTaskLater(this, () -> removeOldMiniMaps(joined), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        heads.onQuit(e.getPlayer());
        saveStats();
        hideBars(e.getPlayer());
    }

    private boolean isOldMiniMap(ItemStack item) {
        return item != null
                && item.getType() == Material.FILLED_MAP
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(miniMapKey, PersistentDataType.BYTE);
    }

    private void removeOldMiniMaps(Player p) {
        if (!p.isOnline()) return;
        PlayerInventory inv = p.getInventory();
        if (isOldMiniMap(inv.getItemInOffHand())) {
            inv.setItemInOffHand(null);
        }
        for (int i = 0; i < inv.getSize(); i++) {
            if (isOldMiniMap(inv.getItem(i))) {
                inv.setItem(i, null);
            }
        }
    }

    // ---------- Affichages : radar, barre de run, Tab ----------

    private void hideRadar(Player p) {
        BossBar bar = radars.remove(p.getUniqueId());
        if (bar != null) {
            p.hideBossBar(bar);
        }
    }

    private void hideBars(Player p) {
        hideRadar(p);
        BossBar hud = hudBars.remove(p.getUniqueId());
        hudUi.remove(p.getUniqueId());
        if (hud != null) {
            p.hideBossBar(hud);
        }
        hideBubble(p);
    }

    private void hideBubble(Player p) {
        List<BossBar> rows = bubbleBars.remove(p.getUniqueId());
        if (rows != null) {
            for (BossBar b : rows) {
                p.hideBossBar(b);
            }
        }
    }

    private double horizontalDistance(Player a, Player b) {
        if (!a.getWorld().equals(b.getWorld())) return Double.MAX_VALUE;
        double dx = b.getLocation().getX() - a.getLocation().getX();
        double dz = b.getLocation().getZ() - a.getLocation().getZ();
        return Math.hypot(dx, dz);
    }

    private String arrow(Player from, Player to) {
        double dx = to.getLocation().getX() - from.getLocation().getX();
        double dz = to.getLocation().getZ() - from.getLocation().getZ();
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = ((target - from.getLocation().getYaw() + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        int index = (int) Math.round(relative / 45.0);
        return switch (index) {
            case 0 -> "↑";
            case 1 -> "↗";
            case 2 -> "→";
            case 3 -> "↘";
            case -1 -> "↖";
            case -2 -> "←";
            case -3 -> "↙";
            default -> "↓";
        };
    }

    // Radar : fleche + distance vers chaque coequipier
    private void updateRadar() {
        if (resetting) return;

        for (Player p : Bukkit.getOnlinePlayers()) {
            List<Player> others = new ArrayList<>();
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!o.equals(p)) others.add(o);
            }
            if (others.isEmpty()) {
                hideRadar(p);
                continue;
            }
            others.sort((a, b) -> Double.compare(horizontalDistance(p, a), horizontalDistance(p, b)));

            Component text = Component.empty();
            boolean first = true;
            for (Player o : others) {
                if (!first) text = text.append(Component.text("   |   ", NamedTextColor.DARK_GRAY));
                first = false;

                if (!o.getWorld().equals(p.getWorld())) {
                    text = text.append(nameTag(o.getName(), NamedTextColor.AQUA))
                            .append(Component.text(" (" + dimension(o.getWorld()) + ")", NamedTextColor.AQUA));
                } else {
                    long distance = Math.round(horizontalDistance(p, o));
                    text = text.append(Component.text(arrow(p, o) + " ", NamedTextColor.YELLOW))
                            .append(nameTag(o.getName(), NamedTextColor.AQUA))
                            .append(Component.text("  " + distance + " m", NamedTextColor.GRAY));
                }
            }

            BossBar bar = radars.get(p.getUniqueId());
            if (bar == null) {
                bar = BossBar.bossBar(text, 1.0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
                radars.put(p.getUniqueId(), bar);
                p.showBossBar(bar);
            } else {
                bar.name(text);
            }
        }
    }

    // Barre de run : duree, prochain objectif, note (avancement des objectifs en progression)
    private void updateHud() {
        if (resetting) return;

        // Objectifs d'equipe : un objectif est fait pour tout le monde des qu'un joueur l'a reussi
        for (int i = 0; i < CAPS.length; i++) {
            if (teamCaps.contains(CAPS[i][0])) continue;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (capDone(p, CAPS[i][1])) {
                    markCap(CAPS[i][0]);
                    break;
                }
            }
        }
        int done = teamCaps.size();
        int next = -1;
        for (int i = 0; i < CAPS.length; i++) {
            if (!teamCaps.contains(CAPS[i][0])) {
                next = i;
                break;
            }
        }
        String objective = next < 0
                ? "tous les objectifs sont faits !"
                : CAPS[next][2] + " (+" + note(capPoints(next)) + ")";
        float progress = Math.max(0f, Math.min(1f, done / (float) CAPS.length));

        for (Player p : Bukkit.getOnlinePlayers()) {
            capCounts.put(p.getUniqueId(), done);

            Component text = Component.text("Run " + formatClock(survivedSeconds), NamedTextColor.WHITE)
                    .append(Component.text("  ·  Objectif : ", NamedTextColor.GRAY))
                    .append(Component.text(objective, NamedTextColor.YELLOW))
                    .append(Component.text("  ·  Note ", NamedTextColor.GRAY))
                    .append(noteComponent(p.getUniqueId()));

            boolean ui = heads.hasUi(p);
            if (ui) {
                boolean[] flags = new boolean[CAPS.length];
                for (int i = 0; i < CAPS.length; i++) {
                    flags[i] = teamCaps.contains(CAPS[i][0]);
                }
                double sc = score(p.getUniqueId());
                text = Ui.objectives(flags, next, hudClock(survivedSeconds), deaths + 1, note(sc), noteColor(sc));
            }
            BossBar bar = hudBars.get(p.getUniqueId());
            if (bar != null && hudUi.getOrDefault(p.getUniqueId(), false) != ui) {
                p.hideBossBar(bar); // le joueur vient de charger (ou de perdre) le pack : on refait la barre
                hudBars.remove(p.getUniqueId());
                bar = null;
            }
            if (bar == null) {
                bar = ui
                        ? BossBar.bossBar(text, 1.0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS)
                        : BossBar.bossBar(text, progress, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
                hudBars.put(p.getUniqueId(), bar);
                hudUi.put(p.getUniqueId(), ui);
                p.showBossBar(bar);
            } else {
                bar.name(text);
                if (!ui) bar.progress(progress);
            }
        }
    }

    private Component playerLine(Player p) {
        UUID id = p.getUniqueId();
        Location l = p.getLocation();
        return nameTag(p.getName(), NamedTextColor.YELLOW)
                .append(Component.text("  ❤" + hearts(p.getHealth() / 2.0), NamedTextColor.RED))
                .append(Component.text("  note ", NamedTextColor.GRAY))
                .append(noteComponent(id))
                .append(Component.text("  perdus " + hearts(heartsLost.getOrDefault(id, 0.0))
                        + "  kills " + kills.getOrDefault(id, 0)
                        + "  objectifs " + capCounts.getOrDefault(id, 0) + "/" + CAPS.length,
                        NamedTextColor.GRAY))
                .append(Component.text("  " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(),
                        NamedTextColor.AQUA));
    }

    // Touche Tab : details de tous les joueurs (hors Tab, on ne voit que sa propre note)
    private void updateTab() {
        if (resetting) return;

        Component header = Component.text("UHC  ·  tentative n°" + (deaths + 1) + "  ·  "
                + formatClock(survivedSeconds)
                + "  ·  run " + note(runScore()) + " pts"
                + (!runs.isEmpty() ? "  ·  record " + note(runs.get(0).score) : ""), NamedTextColor.GOLD);

        Component footer = Component.empty();
        boolean first = true;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!first) footer = footer.append(Component.newline());
            first = false;
            footer = footer.append(playerLine(p));
        }

        footer = footer.append(Component.newline())
                .append(Component.text("DEPUIS LE DÉBUT : " + globalSummary(), NamedTextColor.GOLD));
        for (UUID gid : sortedIds()) {
            footer = footer.append(Component.newline()).append(globalLine(gid));
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendPlayerListHeaderAndFooter(header, footer);
            p.playerListName(Component.text(p.getName(), NamedTextColor.WHITE)
                    .append(Component.text("  ❤" + hearts(p.getHealth() / 2.0), NamedTextColor.RED))
                    .append(Component.text("  "))
                    .append(noteComponent(p.getUniqueId())));
        }
    }

    // ---------- Notes finales ----------

    private List<UUID> gradedIds() {
        java.util.Set<UUID> ids = new java.util.HashSet<>(runPlayers);
        for (Player p : Bukkit.getOnlinePlayers()) {
            ids.add(p.getUniqueId());
        }
        List<UUID> list = new ArrayList<>(ids);
        list.sort((a, b) -> Double.compare(rankKey(b), rankKey(a)));
        return list;
    }

    // Fin de run : on ne revele PAS le fayot et le cancre (ils sont designes au debut de la prochaine run)
    private String gradeSummary() {
        if (gradedIds().isEmpty()) return "Personne n'était là !";
        return "Fayot et cancre : verdict au début de la prochaine run";
    }

    // Notes sur une seule ligne (pour une bulle du professeur), par ordre alphabetique
    private String gradeOneLine() {
        List<UUID> order = gradedIds();
        order.sort((a, b) -> nameOf(a).compareToIgnoreCase(nameOf(b)));
        StringBuilder sb = new StringBuilder("Notes de la classe : ");
        for (int i = 0; i < order.size(); i++) {
            if (i > 0) sb.append("  ·  ");
            sb.append(nameOf(order.get(i))).append(" ").append(note(score(order.get(i))));
        }
        return sb.toString();
    }

    private List<Component> gradeLines() {
        return gradeLines(true);
    }

    // ranked = false : ordre alphabetique (pas de classement revele en fin de run)
    private List<Component> gradeLines(boolean ranked) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("=== Notes /" + note(maxScore()) + " ===", NamedTextColor.GOLD));
        List<UUID> order = gradedIds();
        if (!ranked) {
            order.sort((a, b) -> nameOf(a).compareToIgnoreCase(nameOf(b)));
        }
        for (UUID id : order) {
            double s = score(id);
            lines.add(nameTag(nameOf(id), NamedTextColor.YELLOW)
                    .append(Component.text(" : ", NamedTextColor.YELLOW))
                    .append(noteComponent(id))
                    .append(Component.text(" - " + mention(s), NamedTextColor.GRAY)));
        }
        return lines;
    }

    // ---------- Mort "normale" (filet de securite, ex: /kill) ----------

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        startReset(p, joke(p, p.getLastDamageCause()), causeName(p, p.getLastDamageCause()));
    }

    // ---------- Ecran de fin + nouvelle run ----------

    private void startReset(Player dead, String joke, String cause) {
        if (resetting) return;
        resetting = true;

        for (Player o : Bukkit.getOnlinePlayers()) {
            heads.play(o, "run_end", Sound.ENTITY_WITHER_DEATH, 0.6f, 0.8f);
        }

        int time = survivedSeconds;
        deaths++;
        if (time > bestSeconds) bestSeconds = time;
        Run run = new Run();
        run.number = deaths;
        run.seconds = time;
        run.caps = teamCaps.size();
        run.score = runScore();
        run.splits.putAll(capTimes);
        for (int t : capTimes.values()) run.lastCapAt = Math.max(run.lastCapAt, t);
        List<String> splitRecords = new ArrayList<>();
        for (int i = 0; i < CAPS.length; i++) {
            Integer t = capTimes.get(CAPS[i][0]);
            if (t == null) continue;
            Integer old = bestSplits.get(CAPS[i][0]);
            if (old == null || t < old) {
                bestSplits.put(CAPS[i][0], t);
                if (old != null) splitRecords.add(CAPS[i][2] + " en " + format(t) + " (avant : " + format(old) + ")");
            }
        }
        Run previousBest = runs.isEmpty() ? null : runs.get(0);
        if (time >= 30 || run.caps > 0) runs.add(run); // une run de quelques secondes ne compte pas
        sortRuns();
        while (runs.size() > 200) runs.remove(runs.size() - 1);
        boolean record = !runs.isEmpty() && runs.get(0) == run && (previousBest != null || run.caps > 0);
        int rank = Math.max(1, runs.indexOf(run) + 1);
        if (dead != null) {
            names.put(dead.getUniqueId(), dead.getName());
            totalDeaths.merge(dead.getUniqueId(), 1, Integer::sum);
        }
        if (cause != null) {
            causes.merge(cause, 1, Integer::sum);
        }

        // On garde les notes : au debut de la run suivante, roulette du fayot et du cancre
        lastRunScores.clear();
        if (dead != null) {
            for (UUID id : gradedIds()) {
                lastRunScores.put(id, rankKey(id));
            }
            introApplied = false;
        } else {
            introApplied = true; // reset manuel : pas d'animation
        }
        saveStats();

        try {
            flagFile.createNewFile();
        } catch (IOException ex) {
            getLogger().severe("Impossible de creer reset.flag : " + ex.getMessage());
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            hideBars(p);
        }

        // Ecran noir pour tout le monde
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * 24, 0, false, false, false));
        }

        Title.Times times = Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(500));

        String statsText = "Run n°" + run.number + " : " + runText(run)
                + (record ? " - NOUVELLE MEILLEURE RUN !" : !runs.contains(run) ? "" : " | classement : " + rank + "/" + runs.size()
                + (runs.isEmpty() ? "" : " | meilleure : " + runText(runs.get(0))));

        if (dead != null) {
            String name = dead.getName();

            teacherSay("Bravo " + name + " pour avoir ruiné les espoirs de cette run.", true);
            teacherSay(joke, true);
            teacherSay(statsText, true);
            teacherSay(Jokes.runVerdict(record, run.caps, CAPS.length, run.score,
                    runs.isEmpty() ? run.score : runs.get(0).score), true);
            for (String sr : splitRecords) {
                teacherSay("Record battu : " + sr + ".", true);
            }

            // 1) "Bravo <nom>"
            Bukkit.getServer().showTitle(Title.title(
                    Component.text("Bravo ", NamedTextColor.RED).append(nameTag(name, NamedTextColor.RED)),
                    Component.text("pour avoir ruiné les espoirs de cette run", NamedTextColor.GOLD),
                    times));

            // 2) la blague sur la facon de mourir
            Bukkit.getScheduler().runTaskLater(this, () ->
                    Bukkit.getServer().showTitle(Title.title(
                            Component.empty(),
                            Component.text(joke, NamedTextColor.WHITE),
                            Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofMillis(500)))),
                    100L);
        } else {
            teacherSay("Reset manuel de la partie.", true);
            teacherSay(statsText, true);
            for (String sr : splitRecords) {
                teacherSay("Record battu : " + sr + ".", true);
            }
            Bukkit.getServer().showTitle(Title.title(
                    Component.text("RESET", NamedTextColor.RED),
                    Component.text(statsText, NamedTextColor.GOLD),
                    times));
        }

        // 3) notes finales
        final boolean announceIntro = dead != null;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Bukkit.getServer().showTitle(Title.title(
                    Component.text("NOTES FINALES", NamedTextColor.GOLD),
                    Component.text(gradeSummary(), NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(500))));
            teacherSay(gradeOneLine(), true);
            if (announceIntro) {
                teacherSay("Le fayot et le cancre seront désignés au début de la prochaine run. Dormez bien.", true);
            }
        }, 260L);

        // 4) nouvelle run
        Bukkit.getScheduler().runTaskLater(this, () ->
                Bukkit.getServer().showTitle(Title.title(
                        Component.text("NOUVELLE RUN", NamedTextColor.GREEN),
                        Component.text("Reconnecte-toi dans une trentaine de secondes", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(300)))),
                400L);

        Bukkit.getScheduler().runTaskLater(this, this::finishReset, FINALE_TICKS);
    }

    private void finishReset() {
        Component msg = Component.text(
                "Nouvelle map en preparation ! Reconnecte-toi dans une trentaine de secondes.",
                NamedTextColor.GOLD);
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            p.kick(msg);
        }
        Bukkit.shutdown();
    }

    // ---------- Debut de run : roulette du FAYOT (kit) et du CANCRE (malus) ----------

    private void checkIntro() {
        if (introStarted || introApplied) return;
        if (lastRunScores.size() < 2) {
            introApplied = true; // rien a comparer
            return;
        }

        introWait++;
        boolean allHere = true;
        for (UUID id : lastRunScores.keySet()) {
            if (Bukkit.getPlayer(id) == null) {
                allHere = false;
                break;
            }
        }
        // On attend les joueurs de la derniere run (30 s maximum)
        if (allHere || introWait >= 30) {
            startIntro();
        }
    }

    private void startIntro() {
        introStarted = true;
        introApplied = true;
        introUntil = System.currentTimeMillis() + 60_000L;
        saveStats();

        List<UUID> present = new ArrayList<>();
        for (UUID id : lastRunScores.keySet()) {
            if (Bukkit.getPlayer(id) != null) present.add(id);
        }
        if (present.size() < 2) return;

        double best = Double.NEGATIVE_INFINITY;
        double worst = Double.POSITIVE_INFINITY;
        for (UUID id : present) {
            best = Math.max(best, lastRunScores.get(id));
            worst = Math.min(worst, lastRunScores.get(id));
        }
        if (best - worst < 0.05) {
            teacherSay("Égalité parfaite à la dernière run : ni fayot, ni cancre. Une classe sans relief.", true);
            return;
        }

        // En cas d'egalite, TOUS les joueurs a egalite ont la meme recompense ou la meme sanction
        final List<UUID> fayots = new ArrayList<>();
        final List<UUID> cancres = new ArrayList<>();
        List<String> pool = new ArrayList<>();
        for (UUID id : present) {
            double s = lastRunScores.get(id);
            if (s >= best - 0.05) fayots.add(id);
            if (s <= worst + 0.05) cancres.add(id);
            pool.add(nameOf(id));
        }
        Collections.shuffle(pool);

        teacherSay(Jokes.introLine(), true);
        Bukkit.getScheduler().runTaskLater(this, () ->
                roulette(pool, fayots, true, 0, 14, () ->
                        roulette(pool, cancres, false, 0, 14, null)), 80L);
    }

    private void roulette(List<String> pool, List<UUID> targets, boolean fayot, int frame, int total,
                          Runnable after) {
        if (frame < total) {
            String shown = pool.get(frame % pool.size());
            boolean plural = targets.size() > 1;
            Bukkit.getServer().showTitle(Title.title(
                    Component.text(shown, fayot ? NamedTextColor.GREEN : NamedTextColor.RED),
                    Component.text(fayot
                                    ? (plural ? "les fayots de la dernière run sont..." : "le fayot de la dernière run est...")
                                    : (plural ? "les cancres de la dernière run sont..." : "le cancre de la dernière run est..."),
                            NamedTextColor.GRAY),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(700), Duration.ZERO)));

            float pitch = 0.6f + 1.2f * frame / total;
            for (Player o : Bukkit.getOnlinePlayers()) {
                o.playSound(o.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, pitch);
            }
            Bukkit.getScheduler().runTaskLater(this,
                    () -> roulette(pool, targets, fayot, frame + 1, total, after), 3L + frame);
        } else {
            reveal(targets, fayot);
            if (after != null) {
                Bukkit.getScheduler().runTaskLater(this, after, 140L);
            }
        }
    }

    private String namesOf(List<UUID> targets) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) sb.append(" & ");
            sb.append(nameOf(targets.get(i)));
        }
        return sb.toString();
    }

    // Bruit de huee : plusieurs "hmmm" graves qui se chevauchent
    private void playBoo() {
        for (int i = 0; i < 10; i++) {
            final int n = i;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                for (Player o : Bukkit.getOnlinePlayers()) {
                    float pitch = 0.5f + ThreadLocalRandom.current().nextFloat() * 0.35f;
                    Sound sound = (n % 3 == 0) ? Sound.ENTITY_ZOMBIE_AMBIENT : Sound.ENTITY_VILLAGER_NO;
                    o.playSound(o.getLocation(), sound, 0.9f, pitch);
                }
            }, i * 3L);
        }
    }

    private void reveal(List<UUID> targets, boolean fayot) {
        String names = namesOf(targets);
        boolean plural = targets.size() > 1;

        if (fayot) {
            Bukkit.getServer().showTitle(Title.title(
                    Component.text("★ " + names + " ★", NamedTextColor.GREEN),
                    Component.text(plural ? "FAYOTS : kit de départ offert" : "FAYOT : kit de départ offert",
                            NamedTextColor.GOLD),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))));
            teacherSay(Jokes.fayot(names, plural), true);
            for (Player o : Bukkit.getOnlinePlayers()) {
                o.playSound(o.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            }
            for (UUID id : targets) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    giveFayotKit(player);
                }
            }
        } else {
            Bukkit.getServer().showTitle(Title.title(
                    Component.text("☠ " + names + " ☠", NamedTextColor.RED),
                    Component.text(plural ? "CANCRES : malus surprise" : "CANCRE : malus surprise",
                            NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4), Duration.ofMillis(500))));
            teacherSay(Jokes.cancre(names, plural), true);
            playBoo();
            for (UUID id : targets) {
                startCancreMalus(id);
            }
        }
    }

    private void giveFayotKit(Player p) {
        for (String entry : getConfig().getStringList("fayot.kit")) {
            String[] parts = entry.split(":");
            Material material = Material.matchMaterial(parts[0].trim());
            if (material == null) {
                getLogger().warning("Objet inconnu dans fayot.kit : " + entry);
                continue;
            }
            int amount = parts.length > 1 ? parseInt(parts[1], 1) : 1;
            Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(material, Math.max(1, amount)));
            for (ItemStack rest : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
        }
        p.sendMessage(Component.text("★ Kit de fayot reçu !", NamedTextColor.GREEN));
    }

    private void startCancreMalus(UUID id) {
        final int duration = getConfig().getInt("cancre.duration-seconds", 600);
        final int interval = Math.max(5, getConfig().getInt("cancre.interval-seconds", 30));
        final List<String> effects = getConfig().getStringList("cancre.effects");
        if (effects.isEmpty() || duration <= 0) return;

        BukkitTask previous = malusTasks.remove(id);
        if (previous != null) {
            previous.cancel();
        }

        BukkitTask task = new BukkitRunnable() {
            int elapsed = 0;

            @Override
            public void run() {
                elapsed += interval;
                Player p = Bukkit.getPlayer(id);
                if (resetting || elapsed > duration) {
                    cancel();
                    malusTasks.remove(id);
                    if (p != null && !resetting) {
                        p.sendMessage(Component.text("Le malus du cancre est terminé... jusqu'à la prochaine fois.",
                                NamedTextColor.GRAY));
                    }
                    return;
                }
                if (p != null) {
                    applyRandomMalus(p, effects);
                }
            }
        }.runTaskTimer(this, interval * 20L, interval * 20L);
        malusTasks.put(id, task);
    }

    @SuppressWarnings("deprecation")
    private void applyRandomMalus(Player p, List<String> effects) {
        String entry = effects.get(ThreadLocalRandom.current().nextInt(effects.size()));
        String[] parts = entry.split(":");
        String key = parts[0].trim().toLowerCase(Locale.ROOT);
        PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.minecraft(key));
        if (type == null) return;

        int seconds = parts.length > 1 ? parseInt(parts[1], 5) : 5;
        int amplifier = parts.length > 2 ? parseInt(parts[2], 0) : 0;
        p.addPotionEffect(new PotionEffect(type, seconds * 20, amplifier, false, true, true));
        p.sendActionBar(Component.text("☠ Malus du cancre : " + effectLabel(key), NamedTextColor.RED));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
    }

    private String effectLabel(String key) {
        return switch (key) {
            case "nausea" -> "nausée";
            case "slowness" -> "lenteur";
            case "mining_fatigue" -> "fatigue de minage";
            case "blindness" -> "cécité";
            case "weakness" -> "faiblesse";
            case "darkness" -> "obscurité";
            default -> key.replace('_', ' ');
        };
    }

    private static int parseInt(String text, int def) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ---------- Blagues sur la facon de mourir ----------

    private static String pick(String... options) {
        return options[ThreadLocalRandom.current().nextInt(options.length)];
    }

    private Entity killerOf(EntityDamageEvent ev) {
        if (!(ev instanceof EntityDamageByEntityEvent byEntity)) return null;
        Entity damager = byEntity.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return damager;
    }

    private String causeName(Player victim, EntityDamageEvent ev) {
        if (ev == null) return "INCONNUE";
        Entity killer = killerOf(ev);
        if (killer != null && !killer.equals(victim)) {
            return killer.getType().name();
        }
        return ev.getCause().name();
    }

    private String joke(Player victim, EntityDamageEvent ev) {
        if (ev == null) {
            return Jokes.death(victim.getName(), "INCONNUE", "", 0, stage());
        }
        int blocks = (int) Math.round(ev.getFinalDamage() + 3.0);
        return Jokes.death(victim.getName(), ev.getCause().name(), detailOf(victim, ev), blocks, stage());
    }

    // ---------- Commandes ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        if (name.equals("uhcreset")) {
            startReset(null, null, null);
            return true;
        }
        if (name.equals("uhcpack")) {
            heads.rebuild(new ArrayList<>(Bukkit.getOnlinePlayers()), sender);
            return true;
        }
        if (name.equals("uhcstats")) {
            sender.sendMessage(statsLine());
            sender.sendMessage(Component.text("Durée de la run : " + formatClock(survivedSeconds),
                    NamedTextColor.GRAY));
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                sender.sendMessage(nameTag(p.getName(), NamedTextColor.YELLOW)
                        .append(Component.text(" : ", NamedTextColor.YELLOW))
                        .append(noteComponent(id))
                        .append(Component.text("   perdus " + hearts(heartsLost.getOrDefault(id, 0.0))
                                + " ❤   kills " + kills.getOrDefault(id, 0), NamedTextColor.GRAY)));
            }
            return true;
        }
        if (name.equals("uhcnotes")) {
            if (args.length >= 1 && args[0].equalsIgnoreCase("reset")) {
                if (!sender.hasPermission("uhc.reset")) {
                    sender.sendMessage(Component.text("Réservé aux opérateurs.", NamedTextColor.RED));
                    return true;
                }
                if (args.length >= 2) {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage(Component.text("Joueur introuvable (il doit être connecté).",
                                NamedTextColor.RED));
                        return true;
                    }
                    scores.put(target.getUniqueId(), maxScore());
                    sender.sendMessage(Component.text("Note de " + target.getName() + " remise à "
                            + note(maxScore()) + ".", NamedTextColor.GREEN));
                } else {
                    scores.clear();
                    sender.sendMessage(Component.text("Toutes les notes sont remises à " + note(maxScore()) + ".",
                            NamedTextColor.GREEN));
                }
                saveStats();
                return true;
            }
            for (Component line : gradeLines()) {
                sender.sendMessage(line);
            }
            return true;
        }
        if (name.equals("uhctest")) {
            if (!(sender instanceof Player tester)) {
                sender.sendMessage("Commande réservée aux joueurs.");
                return true;
            }
            String mode = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            UUID id = tester.getUniqueId();
            names.put(id, tester.getName());
            final List<UUID> me = List.of(id);

            // Meme seul, la roulette defile (on ajoute des faux noms)
            List<String> pool = new ArrayList<>();
            for (Player o : Bukkit.getOnlinePlayers()) {
                pool.add(o.getName());
            }
            pool.add("Steve");
            pool.add("Alex");
            pool.add("Creeper");
            pool.add("Zombie");
            Collections.shuffle(pool);

            switch (mode) {
                case "fayot" -> roulette(pool, me, true, 0, 14, null);
                case "cancre" -> roulette(pool, me, false, 0, 14, null);
                case "deux" -> roulette(pool, me, true, 0, 14, () -> roulette(pool, me, false, 0, 14, null));
                case "prof" -> {
                    teacherSay("Test de la bulle du professeur : elle s'affiche en haut, au centre de l'écran.", true);
                    Jokes.Ctx t = new Jokes.Ctx();
                    t.stage = stage();
                    t.minutes = survivedSeconds / 60;
                    t.caps = teamCaps.size();
                    t.totalCaps = CAPS.length;
                    t.clumsy = tester.getName();
                    t.clumsyHearts = 4.5;
                    t.lowHealth = tester.getName();
                    t.lowHearts = 1.5;
                    t.nextObjective = "Fondre du fer";
                    t.sinceCapMinutes = 12;
                    teacherSay(Jokes.ambient(t), true);
                    teacherSay(Jokes.death(tester.getName(), "FALL", "", 14, stage()), true);
                }
                case "stop" -> {
                    for (BukkitTask task : malusTasks.values()) {
                        task.cancel();
                    }
                    malusTasks.clear();
                    tester.sendMessage(Component.text("Malus du cancre arrêté.", NamedTextColor.GRAY));
                }
                default -> tester.sendMessage(Component.text("Usage : /uhctest fayot | cancre | deux | prof | stop",
                        NamedTextColor.GRAY));
            }
            return true;
        }
        if (name.equals("uhcruns")) {
            sender.sendMessage(Component.text("=== MEILLEURES RUNS (objectifs, puis rapidité) ===", NamedTextColor.GOLD));
            if (runs.isEmpty()) {
                sender.sendMessage(Component.text("Aucune run terminée pour le moment.", NamedTextColor.GRAY));
            }
            for (int i = 0; i < Math.min(10, runs.size()); i++) {
                Run r = runs.get(i);
                sender.sendMessage(Component.text((i + 1) + ". run n°" + r.number + " : " + runText(r),
                        i == 0 ? NamedTextColor.YELLOW : NamedTextColor.GRAY));
            }
            if (!bestSplits.isEmpty()) {
                sender.sendMessage(Component.text("Meilleurs temps par objectif :", NamedTextColor.GOLD));
                for (String[] cap : CAPS) {
                    Integer t = bestSplits.get(cap[0]);
                    if (t != null) {
                        sender.sendMessage(Component.text(" - " + cap[2] + " : " + format(t), NamedTextColor.GRAY));
                    }
                }
            }
            return true;
        }
        if (name.equals("uhcglobal")) {
            sender.sendMessage(Component.text("=== STATS DEPUIS LE DÉBUT ===", NamedTextColor.GOLD));
            sender.sendMessage(Component.text(globalSummary(), NamedTextColor.GRAY));
            for (UUID id : sortedIds()) {
                sender.sendMessage(globalLine(id));
            }
            List<Map.Entry<String, Integer>> top = new ArrayList<>(causes.entrySet());
            top.sort((a, b) -> b.getValue() - a.getValue());
            if (!top.isEmpty()) {
                StringBuilder sb = new StringBuilder("Morts les plus fréquentes : ");
                for (int i = 0; i < Math.min(3, top.size()); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(top.get(i).getKey().toLowerCase(Locale.ROOT).replace('_', ' '))
                            .append(" (").append(top.get(i).getValue()).append(")");
                }
                sender.sendMessage(Component.text(sb.toString(), NamedTextColor.RED));
            }
            return true;
        }
        return false;
    }
}
