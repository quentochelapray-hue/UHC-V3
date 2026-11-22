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
import org.bukkit.entity.FallingBlock;
import org.bukkit.inventory.meta.PotionMeta;
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
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private static final long FINALE_TICKS = 900L; // 45 secondes : le professeur a le temps de tout dire

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
    private Extras extras;   // v3.6 : succes, surnoms, memoire du professeur, /uhcprof
    private Team team;       // v3.7 : stock, reperes, alerte de vie
    private Menu menu;       // v3.7 : menu interactif (Shift+F)
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
            if (extras != null) {
                double lostTotal = 0.0;
                for (double v : heartsLost.values()) lostTotal += v;
                String capLabel = key;
                for (String[] cap : CAPS) {
                    if (cap[0].equals(key)) capLabel = cap[2];
                }
                extras.onCap(key, capLabel, survivedSeconds, teamCaps.size(), lostTotal);
            }
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
        UUID victim = null;
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
    private final Set<String> placedChests = new HashSet<>();   // coffres poses par un joueur (ne comptent pas)
    private int quipTimer = 180;

    // Le professeur : personnage qui commente la run (bulle dans le coin gauche, voix, machine a ecrire)
    private final java.util.ArrayDeque<String> teacherQueue = new java.util.ArrayDeque<>();
    private BossBar teacherBar;               // barre de texte pour les joueurs qui n'ont pas le pack
    private TeacherBubble bubble = null;

    private static final class TeacherBubble {
        final List<String> lines;
        final String text;
        final String flat;                    // texte sans retours a la ligne : sert a compter les lettres
        final int len;
        final int total;                      // duree totale d'affichage (ticks)
        int revealed = 0;                     // lettres deja ecrites
        double revealedD = 0.0;
        int pause = 0;                        // pause apres la ponctuation (ticks)
        int age = 0;
        int blip = 0;
        final java.util.Set<UUID> fallback = new java.util.HashSet<>();

        TeacherBubble(List<String> lines, String text, int total) {
            this.lines = lines;
            this.text = text;
            this.flat = String.join("", lines);
            this.len = flat.length();
            this.total = total;
        }
    }

    // Reactions du professeur
    private final Map<UUID, Integer> advancementsByPlayer = new HashMap<>();
    private final Map<UUID, Long> praiseTimes = new HashMap<>();
    private long lastPraise = 0L;
    private UUID lastLeader = null;
    private int lastMilestoneMinute = 0;
    private boolean firstKillDone = false;
    private String worstName = null;          // pire incident de la run (palmares de fin)
    private double worstHearts = 0.0;
    private String worstCause = "";
    private int worstAt = 0;                  // moment (secondes de run) du pire incident
    private double worstPoints = 0.0;
    private final Set<UUID> mlgDone = new HashSet<>();            // joueurs qui ont reussi un MLG pendant cette run
    private final Map<UUID, Integer> clutchWatch = new HashMap<>();   // MLG en cours de verification (hauteur de chute)
    private final Map<UUID, Integer> clutchCount = new HashMap<>();
    private final Map<UUID, Integer> clutchBest = new HashMap<>();
    private final Map<UUID, Double> oresMined = new HashMap<>();      // minerais utiles (pour le badge Mineur)
    private final Map<UUID, Double> healedHearts = new HashMap<>();   // coeurs soignes par pommes d'or / potions (badge Soigneur)
    private boolean nearDeathActive = false;
    private int nearDeathAt = 0;
    private long lastNearDeath = 0L;
    private boolean recapDone = false;
    private String lastDeadName = null;       // memoire de la derniere run (recap a la connexion)
    private String lastDeadCause = "";
    private String lastMvpName = "";
    private String deathDetail = "";        // detail de la cause de la mort (stalagmite, cactus...)
    private String voiceOverride = null;      // voix par defaut choisie avec /uhcprof voix global <nom>
    private final Map<UUID, Integer> voiceTicks = new HashMap<>();   // rythme de la voix de chaque joueur
    private final Map<UUID, Map<String, Integer>> totalBadges = new HashMap<>();
    private boolean packAutoDone = false;
    private long introUntil = 0L;

    // ---------- Demarrage ----------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Ajoute dans config.yml les nouveaux reglages manquants, sans toucher a ceux que tu as modifies
        getConfig().options().copyDefaults(true);
        saveConfig();
        getDataFolder().mkdirs();
        Ui.setIconAdvances(getConfig().getIntegerList("pack.icon-advances"));
        Ui.setHudShiftY(getConfig().getInt("ui.objectives-shift-y", 0));
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
        extras = new Extras(this, this::teacherSay, () -> resetting, () -> survivedSeconds,
                this::causeLabel, this::teacherName, this::nextGoalLabel);
        extras.enable();
        team = new Team(this, this::teacherSay, () -> resetting, this::nextGoalKey);
        team.enable();
        menu = new Menu(this, team, extras, this::voiceMode, this::setVoice, this::setPersonalVoice);
        menu.enable();
        Bukkit.getPluginManager().registerEvents(this, this);
        applyWorldRules();

        Bukkit.getScheduler().runTaskTimer(this, this::everySecond, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::updateRadar, 20L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, this::tickTeacher, 5L, 2L);
    }

    @Override
    public void onDisable() {
        saveStats();
        if (extras != null) extras.disable();
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
            tickReactions();
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

    // ---------- Reactions du professeur : paliers de duree, classement, felicitations ----------

    private static final int[] MILESTONES = {10, 20, 30, 45, 60, 90, 120, 180};

    private void tickReactions() {
        if (!getConfig().getBoolean("teacher.enabled", true)) return;

        // La vie remonte apres un presque-mort
        if (nearDeathActive) {
            double min = 20.0;
            for (Player o : Bukkit.getOnlinePlayers()) min = Math.min(min, o.getHealth());
            if (min >= 8.0) {
                nearDeathActive = false;
                if (survivedSeconds - nearDeathAt >= 8) teacherSay(Jokes.recovered(), false);
            }
        }

        // Recap de la derniere run, une fois, quand l'animation du fayot / cancre est terminee
        if (!recapDone && !runs.isEmpty() && survivedSeconds >= 6 && introApplied
                && System.currentTimeMillis() > introUntil && !Bukkit.getOnlinePlayers().isEmpty()) {
            recapDone = true;
            Run last = null;
            for (Run r : runs) {
                if (last == null || r.number > last.number) last = r;
            }
            if (last != null) {
                String best = runText(runs.get(0));
                teacherSay(Jokes.recap(format(last.seconds), last.caps, CAPS.length, note(last.score), lastDeadName,
                        lastDeadCause, best, lastMvpName), true);
            }
        }

        // Paliers de duree : 10, 20, 30, 45 minutes...
        if (survivedSeconds % 60 == 0) {
            int minutes = survivedSeconds / 60;
            for (int m : MILESTONES) {
                if (minutes == m && lastMilestoneMinute < m) {
                    lastMilestoneMinute = m;
                    teacherSay(Jokes.milestone(m), false);
                }
            }
        }

        // Changement de leader au classement des notes (il faut au moins 2 joueurs)
        if (survivedSeconds % 20 == 0 && Bukkit.getOnlinePlayers().size() >= 2) {
            UUID best = null;
            double bestScore = -1.0;
            boolean tie = false;
            for (Player o : Bukkit.getOnlinePlayers()) {
                double sc = score(o.getUniqueId());
                if (sc > bestScore + 0.05) {
                    bestScore = sc;
                    best = o.getUniqueId();
                    tie = false;
                } else if (Math.abs(sc - bestScore) <= 0.05) {
                    tie = true;
                }
            }
            if (best != null && !tie) {
                if (lastLeader != null && !lastLeader.equals(best) && Bukkit.getPlayer(lastLeader) != null
                        && survivedSeconds > 120) {
                    teacherSay(Jokes.leaderChange(nameOf(best), nameOf(lastLeader)), false);
                }
                lastLeader = best;
            }
        }
    }

    // Un joueur reussit un objectif : le professeur titille celui qui est le plus en retard
    private void teaseSlacker(Player doer) {
        if (Bukkit.getOnlinePlayers().size() < 2 || ThreadLocalRandom.current().nextInt(100) >= 55) return;
        Player slacker = null;
        int least = Integer.MAX_VALUE;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(doer)) continue;
            int c = advancementsByPlayer.getOrDefault(o.getUniqueId(), 0);
            if (c < least) {
                least = c;
                slacker = o;
            }
        }
        if (slacker != null) {
            teacherSay(Jokes.tease(doer.getName(), slacker.getName()), true);
        }
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
                ConfigurationSection bs = s.getConfigurationSection("badges");
                if (bs != null) {
                    Map<String, Integer> bm = new HashMap<>();
                    for (String bk : bs.getKeys(false)) bm.put(bk, bs.getInt(bk, 0));
                    totalBadges.put(id, bm);
                }
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
        lastDeadName = c.getString("last-run.dead", null);
        lastDeadCause = c.getString("last-run.cause", "");
        lastMvpName = c.getString("last-run.mvp", "");
        voiceOverride = c.getString("teacher-voice", null);
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
            Map<String, Integer> bm = totalBadges.get(id);
            if (bm != null) {
                for (Map.Entry<String, Integer> be : bm.entrySet()) {
                    c.set(base + ".badges." + be.getKey(), be.getValue());
                }
            }
        }
        for (Map.Entry<String, Integer> entry : causes.entrySet()) {
            c.set("causes." + entry.getKey(), entry.getValue());
        }

        for (Map.Entry<UUID, Double> entry : scores.entrySet()) {
            c.set("scores." + entry.getKey(), entry.getValue());
        }
        c.set("last-run.applied", introApplied);
        c.set("last-run.dead", lastDeadName);
        c.set("last-run.cause", lastDeadCause);
        c.set("last-run.mvp", lastMvpName);
        c.set("teacher-voice", voiceOverride);
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
                        + " / mauvais -" + hearts(totalPointsLost.getOrDefault(id, 0.0)), NamedTextColor.GREEN))
                .append(badgesText(id).isEmpty() ? Component.empty()
                        : Component.text("   badges : " + badgesText(id), NamedTextColor.GOLD));
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

    // Duree sans nouveau degat apres laquelle l'incident est "fini" : courte pour un coup unique (chute, explosion),
    // un peu plus longue pour les degats qui reviennent par a-coups (feu, poison...).
    private long windowMs(String key) {
        double def = cfg("penalties.incident-window-seconds", 1.0);
        double sec = cfg("penalties.window-by-cause." + key, def);
        return (long) (Math.max(0.2, sec) * 1000.0);
    }

    // Les petites chutes (quelques blocs) sont sanctionnees un peu plus durement : un bonus qui s'efface vers 2 cœurs
    private double smallFallBonus(double hearts) {
        double limit = cfg("penalties.small-fall-hearts", 2.0);
        double bonus = cfg("penalties.small-fall-bonus", 0.55);
        if (hearts >= limit) return 0.0;
        return bonus * (limit - hearts) / Math.max(0.1, limit - 0.5);
    }

    private double incidentTotal(String key, String tier, Incident inc) {
        double total = tierRate(tier) * Math.pow(inc.hearts, tierExponent(tier));
        if (key.equals("FALL")) total += smallFallBonus(inc.hearts);
        return total;
    }

    // Sanction progressive : une seule annonce a la fin de l'incident (total des points perdus)
    private void scheduleIncidentAnnounce(Player victim, Incident inc, String key, String tier) {
        if (inc.task != null) {
            inc.task.cancel();
        }
        final UUID id = victim.getUniqueId();
        long ticks = Math.max(6L, windowMs(key) / 50L);
        inc.task = Bukkit.getScheduler().runTaskLater(this, () -> {
            inc.task = null;
            if (resetting) return;
            Player p = Bukkit.getPlayer(id);
            if (p == null) return;

            // Les degats se sont arretes : on calcule le total UNE seule fois
            double total = incidentTotal(key, tier, inc);
            double lost = total - inc.applied;
            if (lost < 0.05) return;
            inc.applied = total;

            if (inc.hearts > worstHearts) {
                worstHearts = inc.hearts;
                worstName = p.getName();
                worstCause = causeLabel(key, inc.detail);
                worstAt = survivedSeconds;
                worstPoints = lost;
            }
            addPoints(p, -lost, tierLabel(tier), "dmg:" + key, null, false);
            announcePoints(p, -lost, tierLabel(tier) + " : " + causeLabel(key, inc.detail),
                    Jokes.damage(p.getName(), key, inc.detail, inc.hearts, lost, stage(), mlgDone.contains(id)),
                    inc.hearts);
        }, ticks);
    }

    // Mort : les incidents en cours sont comptes tout de suite (sinon leurs points seraient perdus)
    private void flushIncidents() {
        for (Map.Entry<String, Incident> en : new ArrayList<>(incidents.entrySet())) {
            Incident inc = en.getValue();
            if (inc.task == null || inc.victim == null) continue;
            inc.task.cancel();
            inc.task = null;
            Player p = Bukkit.getPlayer(inc.victim);
            if (p == null) continue;
            String key = en.getKey().substring(en.getKey().indexOf(':') + 1);
            String tier = tierOf(key);
            double total = incidentTotal(key, tier, inc);
            double lost = total - inc.applied;
            if (lost >= 0.05) {
                inc.applied = total;
                if (inc.hearts > worstHearts) {
                    worstHearts = inc.hearts;
                    worstName = p.getName();
                    worstCause = causeLabel(key, inc.detail);
                    worstAt = survivedSeconds;
                    worstPoints = lost;
                }
                addPoints(p, -lost, tierLabel(tier), "dmg:" + key, null, false);
            }
        }
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
        advancementsByPlayer.merge(p.getUniqueId(), 1, Integer::sum);

        // Ce que dira le professeur : s'il manque un objectif PLUS FACILE (diamants avant le fer...), il le releve
        String capLine = capIndex >= 0 ? Jokes.cap(capIndex, p.getName()) : null;
        if (capIndex >= 0 && rank == 1) {
            int missingIndex = -1;
            int missingCount = 0;
            for (int i = 0; i < capIndex; i++) {
                if (!teamCaps.contains(CAPS[i][0])) {
                    missingIndex = i;      // l'etape manquante la plus proche de celle qui vient d'etre reussie
                    missingCount++;
                }
            }
            if (missingIndex >= 0) {
                String special = Jokes.outOfOrder(p.getName(), capIndex, missingIndex);
                if (special != null && !special.isEmpty()) capLine = special;
            }
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
                    teacherSay(capLine, true);
                    teaseSlacker(p);
                } else if (key.equals("adventure/sleep_in_bed")) {
                    teacherSay(Jokes.bed(p.getName()), true);
                } else {
                    teacherSay(Jokes.collective(p.getName(), label), true);
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
                teacherSay(capLine, true);
                teaseSlacker(p);
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
        deathDetail = detailOf(p, e);
        double fatal = Math.max(0.0, Math.min(e.getFinalDamage(), p.getHealth()));
        if (fatal > 0) {
            heartsLost.merge(p.getUniqueId(), fatal / 2.0, Double::sum);
            totalHeartsLost.merge(p.getUniqueId(), fatal / 2.0, Double::sum);
            applyDamagePenalty(p, e, fatal);
        }
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
            if (e.getCause() == EntityDamageEvent.DamageCause.FALL) {
                clutchWatch.remove(p.getUniqueId());   // degats de chute : le MLG en cours est rate
            }
            heartsLost.merge(p.getUniqueId(), damage / 2.0, Double::sum);
            totalHeartsLost.merge(p.getUniqueId(), damage / 2.0, Double::sum);
            names.put(p.getUniqueId(), p.getName());
            playHurtSound(p);
            applyDamagePenalty(p, e, damage);
            checkNearDeath(p, damage);
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
                if (extras != null) extras.onMistake(attacker.getUniqueId(), attacker.getName(), "FRIENDLY");
                addPoints(attacker, -cfg("penalties.friendly-fire-points", 3.0),
                        "coup porté à " + victim.getName(), "FRIENDLY",
                        Jokes.friendly(attacker.getName(), victim.getName()), true);
            }
            return;
        }

        String key = ev.getCause().name();
        if (key.equals("FIRE_TICK") || key.equals("CAMPFIRE")) key = "FIRE";
        if (key.equals("FALL") && onStalagmite(victim)) key = "CONTACT";   // empale sur une stalagmite, pas une simple chute

        // Sanction progressive : les degats de meme cause rapproches forment un "incident".
        // Les points sont calcules et annonces UNE SEULE FOIS, quand les degats s'arretent.
        String tier = tierOf(key);
        long window = windowMs(key);
        long now = System.currentTimeMillis();
        Incident incident = incidents.computeIfAbsent(victim.getUniqueId() + ":" + key, x -> new Incident());
        if (now - incident.last > window) {
            incident.hearts = 0.0;
            incident.applied = 0.0;
            incident.announced = 0.0;
            incident.detail = "";
            if (extras != null) extras.onMistake(victim.getUniqueId(), victim.getName(), key);
        }
        if (extras != null) extras.onDamage(survivedSeconds, damage / 2.0);
        incident.hearts += damage / 2.0;
        incident.victim = victim.getUniqueId();
        incident.last = now;
        String extra = detailOf(victim, ev);
        if (!extra.isEmpty()) {
            incident.detail = extra;
        }

        // Retour IMMEDIAT : les cœurs perdus s'affichent tout de suite (au-dessus de la vie). Les points, eux, sont
        // calcules a la fin des degats et ajoutes sur cette meme ligne.
        Bukkit.getServer().sendActionBar(nameTag(victim.getName(), NamedTextColor.WHITE)
                .append(Component.text("  -" + hearts(incident.hearts) + " ❤", NamedTextColor.RED)));

        scheduleIncidentAnnounce(victim, incident, key, tier);
    }

    // Le joueur est dans / sur une pointe de dripstone (stalagmite) ?
    private boolean onStalagmite(Player p) {
        org.bukkit.block.Block feet = p.getLocation().getBlock();
        return feet.getType() == Material.POINTED_DRIPSTONE
                || feet.getRelative(0, -1, 0).getType() == Material.POINTED_DRIPSTONE;
    }

    // Quel bloc piquant est a l'origine d'un contact ? (cactus, baies, stalagmite) ; "" si on ne sait pas
    private String nearbyHazard(Player p) {
        org.bukkit.block.Block base = p.getLocation().getBlock();
        boolean cactus = false;
        boolean berry = false;
        boolean drip = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Material m = base.getRelative(dx, dy, dz).getType();
                    if (m == Material.POINTED_DRIPSTONE) drip = true;
                    else if (m == Material.SWEET_BERRY_BUSH) berry = true;
                    else if (m == Material.CACTUS) cactus = true;
                }
            }
        }
        // le bloc DANS lequel ou SUR lequel on se trouve passe en premier
        if (base.getType() == Material.SWEET_BERRY_BUSH) return "SWEET_BERRY_BUSH";
        if (onStalagmite(p)) return "POINTED_DRIPSTONE";
        if (cactus) return "CACTUS";
        if (berry) return "SWEET_BERRY_BUSH";
        if (drip) return "POINTED_DRIPSTONE";
        return "";
    }

    // Detail utile aux blagues : monstre, bloc qui blesse (cactus, baies, stalagmite...) ou bloc dans lequel on etouffe
    private String detailOf(Player victim, EntityDamageEvent ev) {
        Entity killer = killerOf(ev);
        if (killer instanceof FallingBlock fb) {
            return "BLOCK:" + fb.getBlockData().getMaterial().name();   // enclume, stalactite : le BLOC compte, pas le mot "bloc"
        }
        if (killer != null && !killer.equals(victim)) {
            return killer.getType().name();
        }
        String cause = ev.getCause().name();
        if (cause.equals("FALL")) {
            return onStalagmite(victim) ? "BLOCK:POINTED_DRIPSTONE" : "";
        }
        if (cause.equals("CONTACT")) {
            if (ev instanceof EntityDamageByBlockEvent byBlock && byBlock.getDamager() != null) {
                String t = byBlock.getDamager().getType().name();
                if (t.equals("CACTUS") || t.equals("SWEET_BERRY_BUSH") || t.equals("POINTED_DRIPSTONE")) {
                    return "BLOCK:" + t;
                }
            }
            String near = nearbyHazard(victim);
            return near.isEmpty() ? "" : "BLOCK:" + near;
        }
        if (ev instanceof EntityDamageByBlockEvent byBlock && byBlock.getDamager() != null) {
            return "BLOCK:" + byBlock.getDamager().getType().name();
        }
        if (cause.equals("SUFFOCATION")) {
            return "HEAD:" + victim.getEyeLocation().getBlock().getType().name();
        }
        return "";
    }

    // ---------- Le professeur : bulles de dialogue en haut de l'ecran ----------

    // Libelle du prochain objectif d'equipe (ou null si tout est fait)
    private String nextGoalLabel() {
        for (String[] cap : CAPS) {
            if (!teamCaps.contains(cap[0])) return cap[2];
        }
        return null;
    }

    // Cle du prochain objectif d'equipe (ex : "stronghold") ou null si tout est fait
    private String nextGoalKey() {
        for (String[] cap : CAPS) {
            if (!teamCaps.contains(cap[0])) return cap[0];
        }
        return null;
    }

    private String teacherName() {
        return getConfig().getString("teacher.name", "ProfB");
    }

    // Nombre de lignes de texte que l'ecran peut afficher : le client ne dessine qu'un nombre limite de barres de boss
    // (tant que le total des hauteurs reste sous 1/3 de l'ecran). Le HUD et le radar en prennent 2 ; chaque barre de la
    // bulle contient 2 lignes, dont la premiere est le nom du professeur.
    private int bubbleMaxLines() {
        int h = getConfig().getInt("ui.gui-height", 360);
        int bars = (int) Math.ceil(Math.max(0.0, h / 3.0 - 12.0) / 19.0);
        int slots = Math.max(2, 2 * (bars - 2));
        int max = Math.max(1, getConfig().getInt("teacher.max-lines", 7));
        return Math.max(1, Math.min(max, slots - 1));
    }

    // important = false : la remarque est ignoree si le professeur est deja en train de parler
    private void teacherSay(String text, boolean important) {
        if (text == null || text.isBlank()) return;
        if (!getConfig().getBoolean("teacher.enabled", true)) return;
        if (!important && (bubble != null || !teacherQueue.isEmpty())) return;
        // On coupe par phrases entieres : une bulle ne s'arrete jamais au milieu d'une phrase
        for (String part : Ui.splitBubbles(text, Ui.LINE_CHARS, bubbleMaxLines())) {
            if (teacherQueue.size() < 8) {
                teacherQueue.add(part);
            }
        }
    }

    // Le professeur se tait tout de suite (ex : ecran de fin)
    private void teacherClear() {
        teacherQueue.clear();
        endBubble();
    }

    private void endBubble() {
        bubble = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (teacherBar != null) p.hideBossBar(teacherBar);
            hideBubble(p);
        }
    }

    // Toutes les 2 ticks : ecrit la bulle lettre par lettre (avec les petits bruits de voix), puis la retire
    private void tickTeacher() {
        if (!getConfig().getBoolean("teacher.enabled", true)) return;
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();

        TeacherBubble b = bubble;
        if (b != null) {
            b.age += 2;
            if (b.revealed < b.len) {
                advanceReveal(b, online);
            }
            if (b.age >= b.total) {
                bubble = null;
                if (teacherQueue.isEmpty()) {
                    endBubble();
                }
            } else {
                boolean talking = b.revealed < b.len;
                int frame = talking ? 1 + (b.age / 4) % 2 : 0;   // bouche qui s'ouvre et se ferme
                if (teacherBar != null) {
                    teacherBar.progress(Math.max(0f, Math.min(1f, 1f - b.age / (float) b.total)));
                }
                renderBubble(b, frame, online);
            }
        }

        if (bubble == null && !teacherQueue.isEmpty() && !online.isEmpty()) {
            startBubble(teacherQueue.poll(), online);
        }
    }

    private void startBubble(String part, Collection<? extends Player> online) {
        List<String> lines = Ui.wrap(part, Ui.LINE_CHARS);
        double cps = Math.max(8.0, getConfig().getDouble("teacher.chars-per-second", 42.0));
        double hold = Math.max(0.3, getConfig().getDouble("teacher.hold-ticks-per-char", 0.9));
        int base = Math.max(0, getConfig().getInt("teacher.hold-base-ticks", 24));
        int len = String.join("", lines).length();
        // duree = temps d'ecriture + temps de lecture (plus il y a de lettres, plus on laisse de temps)
        int total = (int) (len * 20.0 / cps) + base + (int) (len * hold);
        TeacherBubble b = new TeacherBubble(lines, part, total);
        bubble = b;

        Component text = Component.text(teacherName() + " : ", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(part, NamedTextColor.WHITE));
        if (teacherBar == null) {
            teacherBar = BossBar.bossBar(text, 1.0f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
        } else {
            teacherBar.name(text);
            teacherBar.progress(1.0f);
        }
        renderBubble(b, 0, online);
    }

    private void advanceReveal(TeacherBubble b, Collection<? extends Player> online) {
        if (b.pause > 0) {
            b.pause -= 2;
            return;
        }
        double cps = Math.max(8.0, getConfig().getDouble("teacher.chars-per-second", 42.0));
        b.revealedD += cps / 10.0;                       // 2 ticks = 1/10 de seconde
        int target = Math.min(b.len, (int) b.revealedD);
        while (b.revealed < target) {
            char c = b.flat.charAt(b.revealed);
            b.revealed++;
            int every = 3;
            if (Character.isLetter(c)) {
                b.blip++;
                if (b.blip >= every) {                   // un petit bruit de voix tous les N lettres
                    b.blip = 0;
                    teacherBlip(online);
                }
            }
            boolean nextDot = b.revealed < b.len && b.flat.charAt(b.revealed) == '.';
            if ((c == '.' || c == '!' || c == '?') && !nextDot) {
                b.pause = 8;                              // respiration apres une phrase
                break;
            }
            if (c == ',' || c == ';' || c == ':') {
                b.pause = 4;
                break;
            }
        }
        b.revealedD = b.revealed;
    }

    // Voix par defaut du serveur (pour les joueurs qui n'ont pas choisi la leur) : essai a tous ceux qui l'entendent
    private void setVoice(String v) {
        voiceOverride = v;
        saveStats();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (extras == null || extras.voiceOf(p.getUniqueId()).isEmpty()) testVoice(p, v);
        }
    }

    // Voix personnelle d'un joueur ("" = voix par defaut du serveur) : essai pour lui seul
    private void setPersonalVoice(Player p, String v) {
        if (extras != null) extras.setVoice(p.getUniqueId(), p.getName(), v);
        testVoice(p, v.isEmpty() ? voiceMode() : v);
    }

    // Joue quelques bruits de cette voix a ce joueur seulement
    private void testVoice(Player p, String voiceId) {
        final Voices.Voice v = Voices.get(voiceId);
        if (v.every() <= 0) return;
        final float volume = (float) Math.max(0.3, getConfig().getDouble("teacher.voice-volume", 0.5));
        for (int i = 0; i < 4; i++) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) playVoice(p, v, volume);
            }, 6L + i * 5L);
        }
    }

    private String voiceMode() {
        String v = voiceOverride != null ? voiceOverride : getConfig().getString("teacher.voice", "villager");
        return v.toLowerCase(Locale.ROOT);
    }

    private void playVoice(Player p, Voices.Voice v, float volume) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        float pitch = v.pitch() + r.nextFloat() * v.range();
        if (v.pack() != null) {   // voix du pack : un des fichiers .ogg (ou le son du jeu de remplacement sans pack)
            heads.play(p, v.pack() + (1 + r.nextInt(v.variants())), v.sound(), volume * v.volume(), pitch);
            return;
        }
        p.playSound(p.getLocation(), v.sound(), volume * v.volume(), pitch);
    }

    // Appele toutes les 3 lettres ecrites : chaque joueur entend SA voix (ou celle du serveur) a son propre rythme
    private void teacherBlip(Collection<? extends Player> online) {
        float volume = (float) getConfig().getDouble("teacher.voice-volume", 0.5);
        if (volume <= 0.0f) return;
        String global = voiceMode();
        for (Player p : online) {
            UUID id = p.getUniqueId();
            if (extras != null && extras.voiceOff(id)) continue;   // /uhcprof voix off (par joueur)
            String own = extras == null ? "" : extras.voiceOf(id);
            Voices.Voice v = Voices.get(own.isEmpty() ? global : own);
            if (v.every() <= 0) continue;
            int n = voiceTicks.merge(id, 1, Integer::sum);
            if (n < v.every()) continue;
            voiceTicks.put(id, 0);
            playVoice(p, v, volume);
        }
    }

    // Dessine la bulle pour chaque joueur : portrait + panneau dans le coin gauche (avec le pack),
    // ou simple barre violette en haut de l'ecran (sans le pack)
    private void renderBubble(TeacherBubble b, int frame, Collection<? extends Player> online) {
        int guiWidth = getConfig().getInt("ui.gui-width", 640);
        int margin = getConfig().getInt("teacher.margin", 4);
        List<Component> rows = Ui.bubble(teacherName(), b.lines, b.revealed, frame, guiWidth, margin);

        for (Player p : online) {
            UUID id = p.getUniqueId();
            if (extras != null && extras.profOff(id)) {
                // /uhcprof off : ce joueur ne voit pas les bulles
                if (b.fallback.remove(id) && teacherBar != null) p.hideBossBar(teacherBar);
                hideBubble(p);
                continue;
            }
            if (!heads.hasUi(p)) {
                if (teacherBar != null && b.fallback.add(id)) {
                    hideBubble(p);
                    p.showBossBar(teacherBar);
                }
                continue;
            }
            if (b.fallback.remove(id) && teacherBar != null) {
                p.hideBossBar(teacherBar); // le joueur vient de charger le pack
            }
            List<BossBar> bars = bubbleBars.get(id);
            if (bars == null || bars.size() != rows.size()) {
                hideBubble(p);
                bars = new ArrayList<>();
                for (Component c : rows) {
                    BossBar bar = BossBar.bossBar(c, 1.0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
                    bars.add(bar);
                    p.showBossBar(bar);
                }
                bubbleBars.put(id, bars);
            } else {
                for (int i = 0; i < rows.size(); i++) {
                    bars.get(i).name(rows.get(i));
                }
            }
        }
    }

    private String causeLabel(String key, String detail) {
        String d = detail == null ? "" : detail;
        if (key.equals("CONTACT")) {
            if (d.contains("DRIPSTONE")) return "stalagmite";
            if (d.contains("BERRY")) return "buisson de baies";
            if (d.contains("CACTUS")) return "cactus";
            return "contact piquant";
        }
        if (key.equals("FALLING_BLOCK") && d.contains("DRIPSTONE")) return "stalactite";
        if (key.equals("FALLING_BLOCK") && d.contains("ANVIL")) return "enclume";
        if (key.equals("SUFFOCATION") && (d.contains("SAND") || d.contains("GRAVEL") || d.contains("POWDER"))) {
            return "enseveli";
        }
        return causeLabel(key);
    }

    private String causeLabel(String key) {
        return switch (key) {
            case "FALL" -> "chute";
            case "LAVA" -> "bain de lave";
            case "FIRE" -> "brûlure";
            case "DROWNING" -> "noyade";
            case "STARVATION" -> "faim";
            case "SUFFOCATION" -> "étouffement";
            case "CONTACT" -> "contact piquant";
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
            if (extras != null) extras.onKill(killer.getUniqueId(), killer.getName());
            totalKills.merge(killer.getUniqueId(), 1, Integer::sum);
            names.put(killer.getUniqueId(), killer.getName());

            String type = e.getEntityType().name();
            String label = type.toLowerCase(Locale.ROOT).replace('_', ' ');
            if (type.equals("WITHER") || type.equals("ELDER_GUARDIAN") || type.equals("WARDEN") || type.equals("RAVAGER")) {
                teacherSay(Jokes.bossKill(killer.getName(), label), true);
            } else if (!firstKillDone) {
                firstKillDone = true;
                teacherSay(Jokes.firstKill(killer.getName(), label), false);
            }
        }
    }

    // ---------- Felicitations du professeur : bons reflexes ----------

    private boolean praiseReady(Player p, long perPlayerMillis) {
        long now = System.currentTimeMillis();
        return now - praiseTimes.getOrDefault(p.getUniqueId(), 0L) >= perPlayerMillis;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (resetting || !getConfig().getBoolean("teacher.enabled", true)) return;
        Player p = e.getPlayer();
        Material m = e.getItem().getType();
        // Soins apportes a l'equipe (badge Soigneur)
        double heal = 0.0;
        if (m == Material.GOLDEN_APPLE) heal = 2.0;
        else if (m == Material.ENCHANTED_GOLDEN_APPLE) heal = 4.0;
        else if (m == Material.POTION && e.getItem().getItemMeta() instanceof PotionMeta pm && pm.getBasePotionType() != null) {
            String t = String.valueOf(pm.getBasePotionType()).toUpperCase(Locale.ROOT);
            if (t.contains("HEALING")) heal = t.contains("STRONG") ? 4.0 : 2.0;
            else if (t.contains("REGENERATION")) heal = 1.5;
        }
        if (heal > 0.0) healedHearts.merge(p.getUniqueId(), heal, Double::sum);
        if (!praiseReady(p, 20_000L)) return;
        long now = System.currentTimeMillis();
        ThreadLocalRandom r = ThreadLocalRandom.current();

        String line = null;
        boolean strong = false;
        if (m == Material.GOLDEN_APPLE || m == Material.ENCHANTED_GOLDEN_APPLE) {
            line = Jokes.eatGolden(p.getName());
            strong = true;
        } else if (m == Material.MILK_BUCKET) {
            if (p.hasPotionEffect(PotionEffectType.POISON) || p.hasPotionEffect(PotionEffectType.WITHER)
                    || p.hasPotionEffect(PotionEffectType.NAUSEA) || p.hasPotionEffect(PotionEffectType.WEAKNESS)
                    || p.hasPotionEffect(PotionEffectType.BLINDNESS)) {
                line = Jokes.milk(p.getName());
                strong = true;
            }
        } else if (m == Material.APPLE) {
            if (now - lastPraise > 45_000L && r.nextInt(100) < 70) line = Jokes.eatApple(p.getName());
        } else if (m.isEdible() && p.getFoodLevel() <= 10) {
            if (now - lastPraise > 90_000L && r.nextInt(100) < 45) line = Jokes.eatSmart(p.getName());
        }
        if (line == null) return;
        praiseTimes.put(p.getUniqueId(), now);
        lastPraise = now;
        teacherSay(line, strong);
    }

    // Seau d'eau pose EN PLEINE CHUTE (MLG) : on verifie quelques instants plus tard que le joueur n'a subi aucun degat de
    // chute, puis on recompense selon la HAUTEUR (plus c'est haut, plus c'est beau).
    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (resetting || e.getBucket() != Material.WATER_BUCKET) return;
        Player p = e.getPlayer();
        float fall = p.getFallDistance();
        if (fall < 5.0f || clutchWatch.containsKey(p.getUniqueId())) return;
        final UUID id = p.getUniqueId();
        final int blocks = Math.round(fall);
        clutchWatch.put(id, blocks);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Integer pending = clutchWatch.remove(id);
            Player q = Bukkit.getPlayer(id);
            if (pending == null || q == null || q.isDead() || resetting) return; // degats subis ou plus la : pas de MLG
            double pts = cfg("bonus.clutch-base", 0.3) + cfg("bonus.clutch-per-block", 0.06) * Math.max(0, pending - 5);
            pts = Math.min(pts, cfg("bonus.clutch-max", 2.0));
            addPoints(q, pts, "MLG de " + pending + " blocs", "clutch");
            mlgDone.add(id);
            clutchCount.merge(id, 1, Integer::sum);
            clutchBest.merge(id, pending, Math::max);
            lastPraise = System.currentTimeMillis();
            teacherSay(Jokes.clutch(q.getName(), pending), true);
            for (Player o : Bukkit.getOnlinePlayers()) {
                heads.play(o, "cap", Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.3f);
            }
        }, 40L);
    }

    // Minerais utiles minés (badge Mineur)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMineOre(BlockBreakEvent e) {
        if (resetting || e.getPlayer().getGameMode() != org.bukkit.GameMode.SURVIVAL) return;
        String m = e.getBlock().getType().name();
        double v = 0.0;
        if (m.endsWith("IRON_ORE") || m.endsWith("GOLD_ORE")) v = 1.0;
        else if (m.endsWith("DIAMOND_ORE") || m.equals("ANCIENT_DEBRIS")) v = 3.0;
        else if (m.endsWith("EMERALD_ORE")) v = 2.0;
        if (v > 0.0) {
            oresMined.merge(e.getPlayer().getUniqueId(), v, Double::sum);
        }
    }

    // Presque mort : il reste un demi-coeur apres un coup (la vie est partagee)
    private void checkNearDeath(Player p, double damage) {
        double remaining = p.getHealth() - damage;
        if (remaining <= 0.0 || remaining > 1.0) return;
        nearDeathActive = true;
        nearDeathAt = survivedSeconds;
        long now = System.currentTimeMillis();
        if (now - lastNearDeath < 60_000L) return;
        lastNearDeath = now;
        teacherSay(Jokes.nearDeath(p.getName(), remaining / 2.0), true);
    }

    // Totem d'immortalite active : la mort a ete evitee de justesse
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent e) {
        if (resetting || !(e.getEntity() instanceof Player p)) return;
        teacherSay(Jokes.totem(p.getName()), true);
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
        final Player chatter = e.getPlayer();
        final String plainMsg = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(e.message());
        Bukkit.getScheduler().runTask(this, () -> {
            if (extras != null) extras.onChat(chatter, plainMsg);
        });
        e.renderer(ChatRenderer.viewerUnaware((source, displayName, message) ->
                Component.empty()
                        .append(heads.head(sender))
                        .append(Component.text("<", NamedTextColor.WHITE))
                        .append(displayName)
                        .append(Component.text("> ", NamedTextColor.WHITE))
                        .append(message)));
    }

    // ---------- Premier coffre ouvert ----------
    // Seuls les coffres du monde comptent : un coffre pose par un joueur est ignore.

    private String blockKey(org.bukkit.block.Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceChest(BlockPlaceEvent e) {
        Material t = e.getBlockPlaced().getType();
        if (t == Material.CHEST || t == Material.TRAPPED_CHEST) {
            placedChests.add(blockKey(e.getBlockPlaced()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakChest(BlockBreakEvent e) {
        placedChests.remove(blockKey(e.getBlock()));
    }

    private boolean isPlacedChest(InventoryHolder holder) {
        if (holder instanceof Chest c) {
            return placedChests.contains(blockKey(c.getBlock()));
        }
        if (holder instanceof DoubleChest d) {
            InventoryHolder left = d.getLeftSide();
            InventoryHolder right = d.getRightSide();
            return (left instanceof Chest lc && placedChests.contains(blockKey(lc.getBlock())))
                    || (right instanceof Chest rc && placedChests.contains(blockKey(rc.getBlock())));
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onChestOpen(InventoryOpenEvent e) {
        if (resetting || chestFirstTaken) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        InventoryHolder holder = e.getInventory().getHolder();
        if (!(holder instanceof Chest) && !(holder instanceof DoubleChest)) return;
        if (isPlacedChest(holder)) return; // coffre pose par un joueur : pas de bonus, et le "premier coffre" reste disponible

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
                if (heads.hasUi(p)) {
                    setRadar(p, Component.text(" "), BossBar.Color.WHITE); // ligne vide : la bulle reste a la meme place
                } else {
                    hideRadar(p);
                }
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

            setRadar(p, text, BossBar.Color.BLUE);
        }
    }

    private void setRadar(Player p, Component text, BossBar.Color color) {
        BossBar bar = radars.get(p.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(text, 1.0f, color, BossBar.Overlay.PROGRESS);
            radars.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
        } else {
            bar.name(text);
            if (bar.color() != color) bar.color(color);
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
                text = Ui.objectives(flags, next, hudClock(survivedSeconds), deaths + 1, note(sc), noteColor(sc),
                        getConfig().getInt("ui.objectives-shift-x", 0));
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
                // Une barre ajoutee apres les autres s'affiche en dessous : on remet le radar sous le HUD
                BossBar radar = radars.get(p.getUniqueId());
                if (radar != null) {
                    p.hideBossBar(radar);
                    p.showBossBar(radar);
                }
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
                    .append(noteComponent(p.getUniqueId()))
                    .append(Component.text(extras == null || extras.nick(p.getUniqueId()).isEmpty()
                            ? "" : "  « " + extras.nick(p.getUniqueId()) + " »", NamedTextColor.LIGHT_PURPLE)));
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
            if (i > 0) sb.append(", ");
            sb.append(nameOf(order.get(i))).append(" ").append(note(score(order.get(i))));
        }
        return sb.append('.').toString();
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
        flushIncidents();   // les points des degats en cours comptent dans les notes finales
        resetting = true;
        teacherClear();

        // MVP et badges de la run (ils sont aussi gardes en memoire pour le recap de la prochaine connexion)
        Awards aw = buildAwards();
        lastDeadName = dead != null ? dead.getName() : null;
        lastDeadCause = dead != null && cause != null ? causeLabel(cause, deathDetail) : "";
        StringBuilder mvpNames = new StringBuilder();
        for (UUID id : aw.mvp) {
            if (mvpNames.length() > 0) mvpNames.append(" et ");
            mvpNames.append(nameOf(id));
        }
        lastMvpName = mvpNames.toString();
        for (Map.Entry<String, List<UUID>> en : aw.badges.entrySet()) {
            for (UUID id : en.getValue()) {
                totalBadges.computeIfAbsent(id, k -> new HashMap<>()).merge(en.getKey(), 1, Integer::sum);
            }
        }

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
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * 95, 0, false, false, false));
        }

        Title.Times times = Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(6), Duration.ofMillis(800));

        // A l'ecran : seulement l'essentiel (qui, combien de temps, quel score). Le professeur raconte tout le reste.
        String shortStats = format(time) + "  ·  " + note(run.score) + " pts";

        if (dead != null) {
            String name = dead.getName();

            Bukkit.getServer().showTitle(Title.title(
                    Component.text("FIN DE RUN", NamedTextColor.RED),
                    nameTag(name, NamedTextColor.GOLD).append(Component.text("  ·  " + shortStats, NamedTextColor.GOLD)),
                    times));

            // Le professeur raconte : la mort, le verdict, puis (selon le temps) replay du pire moment, MVP, badges, records
            String verdict = Jokes.runVerdict(record, run.caps, CAPS.length, run.score,
                    runs.isEmpty() ? run.score : runs.get(0).score);
            teacherSay(joke, true);
            teacherSay(verdict, true);
            int budget = getConfig().getInt("teacher.end-speech-ticks", 900) - estimateTicks(joke) - estimateTicks(verdict);

            List<String> extra = new ArrayList<>();
            if (worstName != null && worstHearts >= 2.0) {
                extra.add(Jokes.replay(worstName, format(worstAt), worstHearts, worstPoints, worstCause));
            }
            if (!aw.mvp.isEmpty()) {
                extra.add(Jokes.mvp(lastMvpName, aw.mvpReason, aw.mvpIsFayot));
            }
            String badges = badgeSentence(aw);
            if (!badges.isEmpty()) extra.add(badges);
            for (int i = 0; i < Math.min(2, splitRecords.size()); i++) {
                extra.add("Record battu : " + splitRecords.get(i) + ".");
            }
            for (String line : extra) {
                int t = estimateTicks(line);
                if (t <= budget) {
                    teacherSay(line, true);
                    budget -= t;
                }
            }
            if (extras != null) {
                extras.onRunEnd(dead.getUniqueId(), dead.getName(), cause, deaths, time, run.caps, CAPS.length,
                        heartsByName());
            }
        } else {
            Bukkit.getServer().showTitle(Title.title(
                    Component.text("RESET", NamedTextColor.RED),
                    Component.text(shortStats, NamedTextColor.GOLD),
                    times));
            teacherSay("Reset manuel de la partie. " + Jokes.runVerdict(record, run.caps, CAPS.length, run.score,
                    runs.isEmpty() ? run.score : runs.get(0).score), true);
            if (extras != null) {
                extras.onRunEnd(null, null, null, deaths, time, run.caps, CAPS.length, heartsByName());
            }
        }

        // On attend que le professeur ait FINI de parler avant de deconnecter tout le monde (securite : 90 s maximum)
        final int[] waited = {0};
        final int[] idle = {0};
        final boolean[] titled = {false};
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            waited[0] += 10;
            boolean busy = bubble != null || !teacherQueue.isEmpty();
            if (waited[0] >= 200 && !busy) idle[0] += 10;
            else idle[0] = 0;
            if (idle[0] >= 10 && !titled[0]) {
                titled[0] = true;
                Bukkit.getServer().showTitle(Title.title(
                        Component.text("NOUVELLE RUN", NamedTextColor.GREEN),
                        Component.text("Reconnecte-toi dans une trentaine de secondes", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(300))));
            }
            if (idle[0] >= 90 || waited[0] >= 1800) {
                task.cancel();
                finishReset();
            }
        }, 10L, 10L);
    }

    // Duree approximative (ticks) pendant laquelle le professeur parlera pour dire ce texte
    private int estimateTicks(String text) {
        double cps = Math.max(8.0, getConfig().getDouble("teacher.chars-per-second", 42.0));
        double hold = Math.max(0.3, getConfig().getDouble("teacher.hold-ticks-per-char", 0.9));
        int base = Math.max(0, getConfig().getInt("teacher.hold-base-ticks", 24));
        int total = 0;
        for (String part : Ui.splitBubbles(text, Ui.LINE_CHARS, bubbleMaxLines())) {
            int len = part.length();
            total += (int) (len * 20.0 / cps) + base + (int) (len * hold);
        }
        return total;
    }

    // ---------- MVP et badges ----------

    private static final class Awards {
        final List<UUID> mvp = new ArrayList<>();
        String mvpReason = "";
        boolean mvpIsFayot = false;
        final Map<String, List<UUID>> badges = new java.util.LinkedHashMap<>();
    }

    private static final String[][] BADGES = {
            {"MINEUR", "Mineur"}, {"SOIGNEUR", "Soigneur"}, {"CHASSEUR", "Chasseur"},
            {"ECLAIREUR", "Éclaireur"}, {"CASCADEUR", "Cascadeur"}, {"SURVIVANT", "Survivant"}};

    private String badgeLabel(String key) {
        for (String[] b : BADGES) {
            if (b[0].equals(key)) return b[1];
        }
        return key;
    }

    private void awardMax(Awards aw, String key, List<UUID> ids, java.util.function.ToDoubleFunction<UUID> value, double min) {
        double max = 0.0;
        for (UUID id : ids) max = Math.max(max, value.applyAsDouble(id));
        if (max < min) return;
        List<UUID> winners = new ArrayList<>();
        for (UUID id : ids) {
            if (Math.abs(value.applyAsDouble(id) - max) < 1e-6) winners.add(id); // egalite : meme badge pour tous
        }
        aw.badges.put(key, winners);
    }

    private Awards buildAwards() {
        Awards aw = new Awards();
        List<UUID> ids = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) ids.add(p.getUniqueId());
        for (UUID id : runPlayers) {
            if (!ids.contains(id)) ids.add(id);
        }
        if (ids.size() < 2) return aw; // seul : rien a comparer

        Map<UUID, Integer> pioneer = new HashMap<>();
        for (UUID id : advancementFirst.values()) pioneer.merge(id, 1, Integer::sum);

        awardMax(aw, "MINEUR", ids, id -> oresMined.getOrDefault(id, 0.0), 3.0);
        awardMax(aw, "SOIGNEUR", ids, id -> healedHearts.getOrDefault(id, 0.0), 2.0);
        awardMax(aw, "CHASSEUR", ids, id -> (double) kills.getOrDefault(id, 0), 5.0);
        awardMax(aw, "ECLAIREUR", ids, id -> (double) pioneer.getOrDefault(id, 0), 3.0);
        awardMax(aw, "CASCADEUR", ids, id -> (double) clutchBest.getOrDefault(id, 0), 5.0);

        // Survivant : le moins de coeurs perdus (au moins 1 coeur d'ecart avec le pire)
        double minLost = Double.MAX_VALUE;
        double maxLost = 0.0;
        double sumLost = 0.0;
        for (UUID id : ids) {
            double l = heartsLost.getOrDefault(id, 0.0);
            minLost = Math.min(minLost, l);
            maxLost = Math.max(maxLost, l);
            sumLost += l;
        }
        double avgLost = sumLost / ids.size();
        if (maxLost - minLost >= 1.0) {
            List<UUID> winners = new ArrayList<>();
            for (UUID id : ids) {
                if (heartsLost.getOrDefault(id, 0.0) - minLost < 0.05) winners.add(id);
            }
            aw.badges.put("SURVIVANT", winners);
        }

        // MVP : celui qui a le plus aide la run (pas forcement le fayot : les criteres sont differents)
        UUID best = null;
        double bestScore = -1e9;
        Map<UUID, Double> mvpScores = new HashMap<>();
        for (UUID id : ids) {
            double sc = pioneer.getOrDefault(id, 0) * 1.2 + oresMined.getOrDefault(id, 0.0) * 0.35
                    + healedHearts.getOrDefault(id, 0.0) * 0.9 + kills.getOrDefault(id, 0) * 0.18
                    + clutchCount.getOrDefault(id, 0) * 1.5 + clutchBest.getOrDefault(id, 0) * 0.03
                    - heartsLost.getOrDefault(id, 0.0) * 0.45;
            mvpScores.put(id, sc);
            if (sc > bestScore) {
                bestScore = sc;
                best = id;
            }
        }
        if (best == null || bestScore <= 0.5) return aw;
        for (UUID id : ids) {
            if (Math.abs(mvpScores.get(id) - bestScore) < 0.05) aw.mvp.add(id);
        }

        // La raison : ce qui a le plus pese dans son score
        UUID w = aw.mvp.get(0);
        double cOres = oresMined.getOrDefault(w, 0.0) * 0.35;
        double cHeal = healedHearts.getOrDefault(w, 0.0) * 0.9;
        double cPio = pioneer.getOrDefault(w, 0) * 1.2;
        double cKill = kills.getOrDefault(w, 0) * 0.18;
        double cClutch = clutchCount.getOrDefault(w, 0) * 1.5 + clutchBest.getOrDefault(w, 0) * 0.03;
        double cSurv = Math.max(0.0, avgLost - heartsLost.getOrDefault(w, 0.0)) * 0.45;
        double top = Math.max(Math.max(Math.max(cOres, cHeal), Math.max(cPio, cKill)), Math.max(cClutch, cSurv));
        if (top == cClutch && cClutch > 0) {
            aw.mvpReason = clutchCount.getOrDefault(w, 0) > 1 ? "pour ses MLG" : "pour son MLG de " + clutchBest.getOrDefault(w, 0) + " blocs";
        } else if (top == cOres && cOres > 0) {
            aw.mvpReason = "pour avoir miné le plus de minerais utiles";
        } else if (top == cHeal && cHeal > 0) {
            aw.mvpReason = "pour avoir soigné l'équipe";
        } else if (top == cPio && cPio > 0) {
            aw.mvpReason = "pour avoir ouvert la voie aux objectifs";
        } else if (top == cSurv && cSurv > 0) {
            aw.mvpReason = "pour avoir perdu le moins de cœurs";
        } else {
            aw.mvpReason = "pour avoir nettoyé les monstres";
        }

        double maxNote = 0.0;
        for (UUID id : ids) maxNote = Math.max(maxNote, score(id));
        aw.mvpIsFayot = Math.round(score(w) * 10) == Math.round(maxNote * 10);
        return aw;
    }

    private String badgeSentence(Awards aw) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<UUID>> en : aw.badges.entrySet()) {
            for (UUID id : en.getValue()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(nameOf(id)).append(' ').append(badgeLabel(en.getKey()).toLowerCase(Locale.FRENCH));
            }
        }
        return sb.length() == 0 ? "" : "Badges de la run : " + sb + ".";
    }

    // "Mineur x2, Soigneur x1" : badges gagnes depuis le debut
    private String badgesText(UUID id) {
        Map<String, Integer> m = totalBadges.get(id);
        if (m == null || m.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String[] b : BADGES) {
            Integer n = m.get(b[0]);
            if (n == null || n <= 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(b[1]).append(" x").append(n);
        }
        return sb.toString();
    }

    private Map<String, Double> heartsByName() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, Double> en : heartsLost.entrySet()) {
            if (en.getValue() > 0.0) out.merge(nameOf(en.getKey()), en.getValue(), Double::sum);
        }
        return out;
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

    // Bruit de huee : la foule du pack (boo.ogg) + plusieurs "hmmm" graves qui se chevauchent
    private void playBoo() {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (heads.hasPack(o)) {
                heads.play(o, "boo", Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            }
        }
        for (int i = 0; i < 10; i++) {
            final int n = i;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                for (Player o : Bukkit.getOnlinePlayers()) {
                    float pitch = 0.5f + ThreadLocalRandom.current().nextFloat() * 0.35f;
                    Sound sound = (n % 3 == 0) ? Sound.ENTITY_ZOMBIE_AMBIENT : Sound.ENTITY_VILLAGER_NO;
                    o.playSound(o.getLocation(), sound, heads.hasPack(o) ? 0.35f : 0.9f, pitch);
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
            return Jokes.death(victim.getName(), "INCONNUE", "", 0, stage(), false);
        }
        int blocks = (int) Math.round(ev.getFinalDamage() + 3.0);
        String key = ev.getCause().name();
        if (key.equals("FALL") && onStalagmite(victim)) key = "CONTACT";
        return Jokes.death(victim.getName(), key, detailOf(victim, ev), blocks, stage(), mlgDone.contains(victim.getUniqueId()));
    }

    // ---------- Commandes ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        if (name.equals("uhcreset")) {
            startReset(null, null, null);
            return true;
        }
        if (name.equals("uhcmenu")) {
            if (sender instanceof Player mp) {
                menu.open(mp, Menu.PAGE_CMDS);
            } else {
                sender.sendMessage("Commande réservée aux joueurs.");
            }
            return true;
        }
        if (name.equals("uhcstock") || name.equals("uhcpos") || name.equals("uhcmark") || name.equals("uhcmarks")
                || name.equals("uhcend")) {
            return team.command(sender, name, args);
        }
        if (name.equals("uhcbadges")) {
            return extras.command(sender, name, args);
        }
        if (name.equals("uhcprof")) {
            String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
            boolean isOp = sender.hasPermission("uhc.reset");
            // Voix : /uhcprof voix <nom> (la tienne) | voix off|on (couper pour toi) | voix global <nom> (par defaut, op)
            if (sub.equals("voix")) {
                String arg = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
                if (arg.equals("on") || arg.equals("off")) {
                    return extras.command(sender, name, args);
                }
                if (arg.equals("global")) {
                    String gid = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
                    if (!isOp) {
                        sender.sendMessage(Component.text("Réservé aux opérateurs.", NamedTextColor.RED));
                    } else if (Voices.has(gid) && (!gid.equals("perso") || Voices.persoReady)) {
                        setVoice(gid);
                        sender.sendMessage(Component.text("Voix par défaut du serveur : " + gid + ".", NamedTextColor.GREEN));
                    } else {
                        sender.sendMessage(Component.text("Voix : " + Voices.names(true), NamedTextColor.GRAY));
                    }
                    return true;
                }
                if (Voices.has(arg) && !arg.equals("muet") && (!arg.equals("perso") || Voices.persoReady)
                        && sender instanceof Player vp) {
                    setPersonalVoice(vp, arg);
                    sender.sendMessage(Component.text("Ta voix du professeur : " + arg + ".", NamedTextColor.GREEN));
                    return true;
                }
                if (arg.equals("defaut") && sender instanceof Player vp2) {
                    setPersonalVoice(vp2, "");
                    sender.sendMessage(Component.text("Tu écoutes la voix par défaut du serveur (" + voiceMode() + ").",
                            NamedTextColor.GREEN));
                    return true;
                }
                sender.sendMessage(Component.text("Voix : " + Voices.names(false) + ". Choisis la tienne avec /uhcprof voix <nom>"
                        + " (defaut = voix du serveur, off = couper). Voix du serveur actuelle : " + voiceMode(), NamedTextColor.GRAY));
                return true;
            }
            if (sub.equals("stop")) {
                if (!isOp) {
                    sender.sendMessage(Component.text("Réservé aux opérateurs.", NamedTextColor.RED));
                    return true;
                }
                teacherClear();
                sender.sendMessage(Component.text("Le professeur se tait.", NamedTextColor.GRAY));
                return true;
            }
            // Reglages personnels et succes : /uhcprof off|on|voix off|voix on|badges|surnom
            if (args.length == 0 || List.of("off", "on", "badges", "surnom", "titre").contains(sub)) {
                return extras.command(sender, name, args);
            }
            // Sinon : le professeur dit le texte (op)
            if (!isOp) {
                sender.sendMessage(Component.text("Réservé aux opérateurs.", NamedTextColor.RED));
                return true;
            }
            teacherSay(String.join(" ", args).replace("{n}", sender.getName()), true);
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
                                + " ❤   kills " + kills.getOrDefault(id, 0), NamedTextColor.GRAY))
                        .append(badgesText(id).isEmpty() ? Component.empty()
                                : Component.text("   badges : " + badgesText(id), NamedTextColor.GOLD)));
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
                    teacherSay("Test de la bulle du professeur : elle s'affiche dans le coin gauche de l'écran, avec sa voix.", true);
                    teacherSay(Jokes.outOfOrder(tester.getName(), 3, 1), true);
                    teacherSay(Jokes.eatGolden(tester.getName()), true);
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
