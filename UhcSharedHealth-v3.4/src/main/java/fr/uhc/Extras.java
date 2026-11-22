package fr.uhc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * v3.6 : tout ce qui est "en plus" : /uhcprof, succes (badges), surnoms, memoire du professeur,
 * reactions au chat, replay texte et graphique de fin de run.
 * Donnees conservees dans plugins/UhcSharedHealth/extras.yml (supprime ce fichier pour tout remettre a zero).
 */
final class Extras implements Listener {

    /** Fait parler le professeur (important = false : ignore s'il parle deja). */
    interface Say {
        void say(String text, boolean important);
    }

    // id, nom, description
    private static final String[][] BADGES = {
            {"intact_iron", "Intouchable", "Fondre du fer sans perdre un seul coeur"},
            {"fast_diamond", "Taupe express", "Trouver des diamants en moins de 15 min"},
            {"fast_nether", "Pressé", "Entrer dans le Nether en moins de 25 min"},
            {"adventurer", "Aventurier", "Faire 5 objectifs en une seule run"},
            {"dragon", "Tueur de dragon", "Tuer l'Ender Dragon"},
            {"survivor30", "Increvable", "Survivre 30 minutes"},
            {"survivor60", "Marathonien", "Survivre 1 heure"},
            {"balance5", "Équilibriste", "5 runs de 10 min ou plus d'affilée sans chute"},
            {"hunter50", "Chasseur", "Tuer 50 monstres (au total)"},
            {"veteran10", "Habitué", "Atteindre la tentative n°10"},
    };

    // cause -> titre dans le Tab (apres 3 erreurs du meme type)
    private static final Map<String, String> NICKS = new HashMap<>();
    // cause -> "X <phrase>"
    private static final Map<String, String> PHRASES = new HashMap<>();

    static {
        NICKS.put("FALL", "Le Funambule");
        NICKS.put("LAVA", "Baigneur de Lave");
        NICKS.put("FIRE", "Pyromane");
        NICKS.put("HOT_FLOOR", "Pyromane");
        NICKS.put("DROWNING", "Poisson Rouge");
        NICKS.put("ENTITY_EXPLOSION", "Ami des Creepers");
        NICKS.put("BLOCK_EXPLOSION", "Ami des Creepers");
        NICKS.put("SUFFOCATION", "La Taupe");
        NICKS.put("STARVATION", "Ventre Vide");
        NICKS.put("CONTACT", "Câlin de Cactus");
        NICKS.put("FREEZE", "Le Glaçon");
        NICKS.put("FRIENDLY", "Maladroit Dangereux");
        NICKS.put("VOID", "Parachutiste Raté");

        PHRASES.put("FALL", "tombe de haut");
        PHRASES.put("LAVA", "finit dans la lave");
        PHRASES.put("FIRE", "prend feu");
        PHRASES.put("HOT_FLOOR", "marche sur un sol brûlant");
        PHRASES.put("DROWNING", "oublie de respirer");
        PHRASES.put("ENTITY_EXPLOSION", "se fait exploser");
        PHRASES.put("BLOCK_EXPLOSION", "se fait exploser");
        PHRASES.put("SUFFOCATION", "s'étouffe dans un mur");
        PHRASES.put("STARVATION", "oublie de manger");
        PHRASES.put("CONTACT", "enlace un cactus");
        PHRASES.put("FREEZE", "se transforme en glaçon");
        PHRASES.put("FRIENDLY", "frappe un coéquipier");
        PHRASES.put("VOID", "tombe dans le vide");
    }

    private static final class PData {
        String name = "";
        final Map<String, Integer> mistakes = new HashMap<>();
        final Map<String, Integer> deaths = new HashMap<>();
        final Map<String, Integer> lastDeathAttempt = new HashMap<>();
        final Set<String> badges = new LinkedHashSet<>();
        boolean profOff = false;
        boolean voiceOff = false;
        String voice = "";            // voix choisie ("" = voix par defaut du serveur)
        String nick = "";
        int totalKills = 0;
    }

    private final JavaPlugin plugin;
    private final Say say;
    private final BooleanSupplier busy;                 // vrai pendant l'ecran de fin
    private final IntSupplier clock;                    // secondes de la run
    private final Function<String, String> causeLabel;  // cle de cause -> libelle
    private final Supplier<String> teacherName;
    private final Supplier<String> nextGoal;            // libelle du prochain objectif (ou null)
    private final File file;

    private final Map<UUID, PData> data = new HashMap<>();
    private int fallFreeStreak = 0;
    private boolean dirty = false;
    private int saveTimer = 0;

    // Etat de la run en cours (le serveur redemarre a chaque run : rien a remettre a zero)
    private final double[] bleed = new double[240];     // coeurs perdus par minute
    private final Map<String, Integer> runMistakes = new HashMap<>();
    private boolean runHadFall = false;
    private String firstCap = null;
    private String lastCap = null;
    private boolean done30 = false;
    private boolean done60 = false;
    private boolean debrisSaid = false;
    private final Set<UUID> greeted = new HashSet<>();

    private long lastRemark = 0L;
    private final Map<String, Long> cooldowns = new HashMap<>();

    Extras(JavaPlugin plugin, Say say, BooleanSupplier busy, IntSupplier clock,
           Function<String, String> causeLabel, Supplier<String> teacherName, Supplier<String> nextGoal) {
        this.plugin = plugin;
        this.say = say;
        this.busy = busy;
        this.clock = clock;
        this.causeLabel = causeLabel;
        this.teacherName = teacherName;
        this.nextGoal = nextGoal;
        this.file = new File(plugin.getDataFolder(), "extras.yml");
    }

    // ---------------------------------------------------------------- demarrage / sauvegarde

    void enable() {
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    void disable() {
        save();
    }

    private PData pd(UUID id, String name) {
        PData d = data.computeIfAbsent(id, k -> new PData());
        if (name != null && !name.isEmpty()) d.name = name;
        return d;
    }

    private void load() {
        data.clear();
        if (!file.exists()) return;
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        fallFreeStreak = c.getInt("fall-free-streak", 0);
        ConfigurationSection ps = c.getConfigurationSection("players");
        if (ps == null) return;
        for (String key : ps.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            ConfigurationSection s = ps.getConfigurationSection(key);
            if (s == null) continue;
            PData d = new PData();
            d.name = s.getString("name", "");
            d.profOff = s.getBoolean("prof-off", false);
            d.voiceOff = s.getBoolean("voice-off", false);
            d.voice = s.getString("voice", "");
            d.nick = s.getString("nick", "");
            d.totalKills = s.getInt("kills", 0);
            d.badges.addAll(s.getStringList("badges"));
            readCounts(s.getConfigurationSection("mistakes"), d.mistakes);
            readCounts(s.getConfigurationSection("deaths"), d.deaths);
            readCounts(s.getConfigurationSection("last-death-attempt"), d.lastDeathAttempt);
            data.put(id, d);
        }
    }

    private void readCounts(ConfigurationSection s, Map<String, Integer> into) {
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            into.put(k, s.getInt(k, 0));
        }
    }

    private void save() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("fall-free-streak", fallFreeStreak);
        for (Map.Entry<UUID, PData> en : data.entrySet()) {
            String base = "players." + en.getKey();
            PData d = en.getValue();
            c.set(base + ".name", d.name);
            c.set(base + ".prof-off", d.profOff);
            c.set(base + ".voice-off", d.voiceOff);
            c.set(base + ".voice", d.voice);
            c.set(base + ".nick", d.nick);
            c.set(base + ".kills", d.totalKills);
            c.set(base + ".badges", new ArrayList<>(d.badges));
            for (Map.Entry<String, Integer> m : d.mistakes.entrySet()) c.set(base + ".mistakes." + m.getKey(), m.getValue());
            for (Map.Entry<String, Integer> m : d.deaths.entrySet()) c.set(base + ".deaths." + m.getKey(), m.getValue());
            for (Map.Entry<String, Integer> m : d.lastDeathAttempt.entrySet()) {
                c.set(base + ".last-death-attempt." + m.getKey(), m.getValue());
            }
        }
        try {
            c.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible d'enregistrer extras.yml : " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- acces pour le plugin

    boolean profOff(UUID id) {
        PData d = data.get(id);
        return d != null && d.profOff;
    }

    boolean voiceOff(UUID id) {
        PData d = data.get(id);
        return d != null && (d.profOff || d.voiceOff);
    }

    /** Voix choisie par ce joueur ("" = celle du serveur). */
    String voiceOf(UUID id) {
        PData d = data.get(id);
        return d == null || d.voice == null ? "" : d.voice;
    }

    void setVoice(UUID id, String name, String voice) {
        PData d = pd(id, name);
        d.voice = voice == null ? "" : voice;
        dirty = true;
        save();
    }

    boolean voiceMuted(UUID id) {
        PData d = data.get(id);
        return d != null && d.voiceOff;
    }

    /** Bascule les bulles (et la voix) du professeur pour ce joueur. Renvoie vrai si elles sont maintenant coupees. */
    boolean toggleProf(UUID id, String name) {
        PData d = pd(id, name);
        d.profOff = !d.profOff;
        dirty = true;
        save();
        return d.profOff;
    }

    /** Bascule la voix seule. Renvoie vrai si elle est maintenant coupee. */
    boolean toggleVoice(UUID id, String name) {
        PData d = pd(id, name);
        d.voiceOff = !d.voiceOff;
        dirty = true;
        save();
        return d.voiceOff;
    }

    /** Succes du joueur : {nom, description, "1" si debloque sinon "0"}. */
    List<String[]> badgeStates(UUID id) {
        PData d = data.get(id);
        List<String[]> out = new ArrayList<>();
        for (String[] b : BADGES) {
            boolean ok = d != null && d.badges.contains(b[0]);
            out.add(new String[]{b[1], b[2], ok ? "1" : "0"});
        }
        return out;
    }

    String nick(UUID id) {
        PData d = data.get(id);
        return d == null ? "" : d.nick;
    }

    // ---------------------------------------------------------------- outils

    private static String fmt(int sec) {
        int h = sec / 3600;
        int m = (sec % 3600) / 60;
        int s = sec % 60;
        if (h > 0) return h + ":" + (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    private static String pick(String... options) {
        return options[ThreadLocalRandom.current().nextInt(options.length)];
    }

    private static String num(double v) {
        double r = Math.round(v * 10.0) / 10.0;
        if (r == Math.floor(r)) return String.valueOf((long) r);
        return String.valueOf(r).replace('.', ',');
    }

    // une remarque spontanee au plus toutes les 15 s, et une meme remarque au plus toutes les cooldown ms
    private boolean canRemark(String key, long cooldownMs) {
        long now = System.currentTimeMillis();
        if (now - lastRemark < 15_000L) return false;
        Long last = cooldowns.get(key);
        if (last != null && now - last < cooldownMs) return false;
        lastRemark = now;
        cooldowns.put(key, now);
        return true;
    }

    private String labelOf(String key) {
        String l = causeLabel.apply(key);
        return l == null ? key.toLowerCase(Locale.ROOT) : l;
    }

    // ---------------------------------------------------------------- succes

    private String[] badgeInfo(String id) {
        for (String[] b : BADGES) {
            if (b[0].equals(id)) return b;
        }
        return null;
    }

    private void award(UUID id, String name, String badgeId) {
        PData d = pd(id, name);
        if (!d.badges.add(badgeId)) return;
        String[] info = badgeInfo(badgeId);
        if (info == null) return;
        dirty = true;
        Bukkit.getServer().sendMessage(Component.text("★ Succès débloqué : ", NamedTextColor.GOLD)
                .append(Component.text(name, NamedTextColor.YELLOW))
                .append(Component.text(" — " + info[1], NamedTextColor.GOLD))
                .append(Component.text(" (" + info[2] + ")", NamedTextColor.GRAY)));
    }

    private void awardAll(String badgeId) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            award(p.getUniqueId(), p.getName(), badgeId);
        }
    }

    // ---------------------------------------------------------------- evenements du plugin

    /** Un objectif d'equipe vient d'etre fait. */
    void onCap(String key, String label, int sec, int capsCount, double totalHeartsLost) {
        String entry = label + " à " + fmt(sec);
        if (firstCap == null) firstCap = entry;
        lastCap = entry;
        if (key.equals("iron") && totalHeartsLost < 0.05) awardAll("intact_iron");
        if (key.equals("diamond") && sec <= 900) awardAll("fast_diamond");
        if (key.equals("nether") && sec <= 1500) awardAll("fast_nether");
        if (key.equals("dragon")) awardAll("dragon");
        if (capsCount >= 5) awardAll("adventurer");
    }

    /** Des degats viennent d'etre subis (en coeurs). */
    void onDamage(int sec, double hearts) {
        int idx = Math.max(0, Math.min(bleed.length - 1, sec / 60));
        bleed[idx] += hearts;
    }

    /** Un nouvel incident de degats commence (une chute, un feu...). */
    void onMistake(UUID id, String name, String key) {
        PData d = pd(id, name);
        int n = d.mistakes.merge(key, 1, Integer::sum);
        runMistakes.merge(key, 1, Integer::sum);
        if (key.equals("FALL")) runHadFall = true;
        dirty = true;

        // Surnom : le type d'erreur le plus frequent (au moins 3 fois)
        String bestKey = null;
        int best = 0;
        for (Map.Entry<String, Integer> en : d.mistakes.entrySet()) {
            if (NICKS.containsKey(en.getKey()) && en.getValue() > best) {
                best = en.getValue();
                bestKey = en.getKey();
            }
        }
        if (bestKey != null && best >= 3) {
            String title = NICKS.get(bestKey);
            if (!title.equals(d.nick)) {
                d.nick = title;
                if (!busy.getAsBoolean()) {
                    say.say(name + " reçoit le titre de « " + title + " ». Il l'a mérité, "
                            + best + " fois.", false);
                }
            }
        }

        // Memoire : "3e fois que X ..."
        if (n >= 3 && (n == 3 || n % 5 == 0) && !busy.getAsBoolean() && canRemark("mem:" + id + key, 60_000L)) {
            String phrase = PHRASES.get(key);
            if (phrase == null) phrase = "est victime de « " + labelOf(key) + " »";
            say.say(n + "e fois que " + name + " " + phrase + ". "
                    + pick("Tu as pensé à regarder devant toi ?", "C'est presque une spécialité.",
                    "À ce stade, c'est un talent.", "Je note, je note."), false);
        }
    }

    /** Un joueur a tue un monstre. */
    void onKill(UUID id, String name) {
        PData d = pd(id, name);
        d.totalKills++;
        dirty = true;
        if (d.totalKills >= 50) award(id, name, "hunter50");
    }

    /** Fin de run : memoire des morts, succes, replay et graphique. */
    void onRunEnd(UUID deadId, String deadName, String causeKey, int attempt, int seconds,
                  int caps, int capsTotal, Map<String, Double> heartsByPlayer) {
        // 1. Memoire des morts
        if (deadId != null && causeKey != null) {
            PData d = pd(deadId, deadName);
            int count = d.deaths.merge(causeKey, 1, Integer::sum);
            Integer prev = d.lastDeathAttempt.put(causeKey, attempt);
            dirty = true;
            if (prev != null) {
                String line = deadName + ", encore « " + labelOf(causeKey) + " ». Déjà ta mort à la tentative "
                        + prev + (count >= 3 ? ", et c'est ta " + count + "e fois." : ".");
                say.say(line, true);
            }
        }

        // 2. Succes de fin de run
        if (seconds >= 600) {
            if (runHadFall) fallFreeStreak = 0;
            else fallFreeStreak++;
            if (fallFreeStreak >= 5) awardAll("balance5");
        }
        if (attempt >= 10) awardAll("veteran10");

        // 3. Replay : le professeur resume la run
        StringBuilder a = new StringBuilder("Replay de la tentative " + attempt + " : " + fmt(seconds)
                + ", " + caps + "/" + capsTotal + " objectifs.");
        if (firstCap != null && lastCap != null && !firstCap.equals(lastCap)) {
            a.append(" Premier objectif : ").append(firstCap).append(". Dernier : ").append(lastCap).append(".");
        } else if (firstCap != null) {
            a.append(" Seul objectif : ").append(firstCap).append(".");
        } else {
            a.append(" Aucun objectif. Respectons une minute de silence.");
        }
        say.say(a.toString(), true);

        int worstMin = -1;
        double worstVal = 0.0;
        double total = 0.0;
        for (int i = 0; i < bleed.length; i++) {
            total += bleed[i];
            if (bleed[i] > worstVal) {
                worstVal = bleed[i];
                worstMin = i;
            }
        }
        if (worstMin >= 0 && worstVal >= 0.5) {
            say.say("Pire moment : la minute " + (worstMin + 1) + ", avec " + num(worstVal) + " ❤ perdus. Au total, "
                    + num(total) + " ❤ ont été gaspillés.", true);
        }
        String topCause = null;
        int topN = 0;
        for (Map.Entry<String, Integer> en : runMistakes.entrySet()) {
            if (en.getValue() > topN) {
                topN = en.getValue();
                topCause = en.getKey();
            }
        }
        if (topCause != null && topN >= 2) {
            say.say("Erreur la plus répétée : « " + labelOf(topCause) + " », " + topN + " fois.", true);
        }

        // 4. Graphique dans le chat
        sendChart(heartsByPlayer, seconds);

        if (dirty) save();
    }

    private void sendChart(Map<String, Double> hearts, int seconds) {
        Component out = Component.text("──── Bilan de la run ────", NamedTextColor.GOLD);
        double max = 0.0;
        for (double v : hearts.values()) max = Math.max(max, v);
        if (!hearts.isEmpty()) {
            out = out.append(Component.newline()).append(Component.text("Cœurs perdus par joueur", NamedTextColor.GRAY));
            List<Map.Entry<String, Double>> rows = new ArrayList<>(hearts.entrySet());
            rows.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
            for (Map.Entry<String, Double> en : rows) {
                int len = max <= 0.0 ? 0 : (int) Math.round(en.getValue() / max * 20.0);
                out = out.append(Component.newline())
                        .append(Component.text(en.getKey() + " ", NamedTextColor.YELLOW))
                        .append(Component.text("█".repeat(len), NamedTextColor.RED))
                        .append(Component.text(" " + num(en.getValue()) + " ❤", NamedTextColor.GRAY));
            }
        }
        int minutes = Math.max(1, Math.min(bleed.length, seconds / 60 + 1));
        int step = Math.max(1, (minutes + 29) / 30);       // 30 colonnes au maximum
        List<Double> cols = new ArrayList<>();
        for (int i = 0; i < minutes; i += step) {
            double sum = 0.0;
            for (int j = i; j < Math.min(minutes, i + step); j++) sum += bleed[j];
            cols.add(sum);
        }
        double cmax = 0.0;
        for (double v : cols) cmax = Math.max(cmax, v);
        if (cmax > 0.0) {
            String levels = "▁▂▃▄▅▆▇█";
            StringBuilder sb = new StringBuilder();
            for (double v : cols) {
                if (v <= 0.0) {
                    sb.append('·');
                } else {
                    int lv = (int) Math.round(v / cmax * (levels.length() - 1));
                    sb.append(levels.charAt(Math.max(0, Math.min(levels.length() - 1, lv))));
                }
            }
            out = out.append(Component.newline())
                    .append(Component.text("Dégâts au fil du temps (1 colonne = " + step + " min)", NamedTextColor.GRAY))
                    .append(Component.newline())
                    .append(Component.text(sb.toString(), NamedTextColor.RED))
                    .append(Component.text("  0 → " + fmt(seconds), NamedTextColor.DARK_GRAY));
        }
        Bukkit.getServer().sendMessage(out);
    }

    // ---------------------------------------------------------------- horloge

    private void tick() {
        if (busy.getAsBoolean()) return;
        int sec = clock.getAsInt();
        if (!done30 && sec >= 1800) {
            done30 = true;
            awardAll("survivor30");
        }
        if (!done60 && sec >= 3600) {
            done60 = true;
            awardAll("survivor60");
        }
        saveTimer++;
        if (saveTimer >= 120) {
            saveTimer = 0;
            if (dirty) save();
        }
    }

    // ---------------------------------------------------------------- chat

    /** Appelle sur le thread principal. */
    void onChat(Player p, String raw) {
        if (busy.getAsBoolean() || raw == null) return;
        String m = raw.toLowerCase(Locale.ROOT).trim();
        if (m.isEmpty() || m.startsWith("/")) return;
        String name = p.getName();
        String prof = teacherName.get().toLowerCase(Locale.ROOT);
        boolean addressed = m.contains(prof) || m.matches(".*\\bprof(esseur)?\\b.*");

        if (addressed) {
            if (!canRemarkDirect("addr", 8_000L)) return;
            String reply;
            if (m.matches(".*(nul|naze|bouffon|stupide|debile|débile|con\\b|idiot).*")) {
                reply = pick("Venant de quelqu'un qui a perdu des cœurs sur une chute, c'est touchant, " + name + ".",
                        "Les notes ne mentent pas, " + name + ". Les tiennes non plus.",
                        "Je n'ai pas entendu. Je note quand même.");
            } else if (m.contains("merci")) {
                reply = pick("De rien, " + name + ". Je suis payé en cœurs perdus.",
                        "Ne me remercie pas, survis plutôt.");
            } else if (m.matches(".*(aide|help|secours|comment|quoi faire|objectif).*")) {
                reply = helpLine(name);
            } else {
                reply = pick("Oui, " + name + " ? Je corrige des copies, là.",
                        "Tu m'as appelé ? J'espère que c'est pour une bonne nouvelle.",
                        "Je suis là, " + name + ". Malheureusement pour tes notes.");
            }
            say.say(reply, true);
            return;
        }

        // Remarques non sollicitees : rares, avec delai
        boolean chance = ThreadLocalRandom.current().nextInt(100) < 65;
        if (m.matches(".*\\b(gg|bravo|bien joue|bien joué)\\b.*")) {
            if (chance && canRemark("gg", 90_000L)) {
                say.say(pick("GG ? Ne vous félicitez pas trop vite, " + name + ".",
                        "Des applaudissements ? Gardez-les pour la fin de la run.",
                        "Je suis presque ému. Presque."), false);
            }
        } else if (m.matches(".*(\\baide\\b|\\bhelp\\b|au secours|je sais pas quoi faire|on fait quoi).*")) {
            if (canRemark("help", 60_000L)) say.say(helpLine(name), false);
        } else if (m.matches(".*(\\bnoob\\b|t'es nul|t es nul|\\bez\\b|c'est nul).*")) {
            if (chance && canRemark("noob", 90_000L)) {
                say.say(pick("Qui traite qui de nul ? Les notes parlent d'elles-mêmes.",
                        "Les insultes, c'est bien. Les cœurs, c'est mieux.",
                        "Un peu de respect, " + name + ". Ou au moins un peu de talent."), false);
            }
        } else if (m.matches(".*(\\bmdr\\b|\\bptdr\\b|\\blol\\b|\\bxd\\b).*")) {
            if (ThreadLocalRandom.current().nextInt(100) < 30 && canRemark("lol", 120_000L)) {
                say.say(pick("Rire, c'est bien. Perdre des cœurs, moins.",
                        "Qu'est-ce qui est drôle ? Je veux rire aussi.",
                        "Vous rirez moins au classement."), false);
            }
        } else if (m.matches(".*(j'ai faim|\\bfaim\\b|a manger|à manger).*")) {
            if (chance && canRemark("faim", 120_000L)) {
                say.say(pick("La nourriture ne tombe pas du ciel. Enfin, parfois les moutons, si.",
                        "Un steak, ça se cuit. Pas besoin d'un diplôme."), false);
            }
        } else if (m.matches(".*(j'ai peur|\\bpeur\\b|help me|je vais mourir).*")) {
            if (chance && canRemark("peur", 120_000L)) {
                say.say(pick(name + ", la peur est une excellente conseillère. Écoute-la.",
                        "Si tu meurs, on meurt tous. Aucune pression."), false);
            }
        } else if (m.matches(".*(\\brip\\b|je suis mort|on est mort).*")) {
            if (chance && canRemark("rip", 120_000L)) {
                say.say(pick("Pas encore. Mais je sens que ça vient.", "Les derniers mots les plus célèbres."), false);
            }
        }
    }

    // reponse directe : seul un delai court (pas le delai global des remarques spontanees)
    private boolean canRemarkDirect(String key, long cooldownMs) {
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(key);
        if (last != null && now - last < cooldownMs) return false;
        cooldowns.put(key, now);
        return true;
    }

    private String helpLine(String name) {
        String goal = nextGoal.get();
        if (goal == null) return "Plus aucun objectif à donner, " + name + ". Tu as tout fait. Étonnant.";
        return pick("Objectif suivant : « " + goal + " ». Je ne le répéterai pas.",
                name + ", l'objectif d'équipe, c'est « " + goal + " ». C'est écrit en haut de l'écran.");
    }

    // ---------------------------------------------------------------- reactions diverses

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PData d = pd(p.getUniqueId(), p.getName());
        if (busy.getAsBoolean() || !greeted.add(p.getUniqueId())) return;
        int totalDeaths = 0;
        String topCause = null;
        int topN = 0;
        for (Map.Entry<String, Integer> en : d.deaths.entrySet()) {
            totalDeaths += en.getValue();
            if (en.getValue() > topN) {
                topN = en.getValue();
                topCause = en.getKey();
            }
        }
        if (totalDeaths >= 3 && topCause != null && topN >= 2 && clock.getAsInt() < 300
                && canRemark("join:" + p.getUniqueId(), 600_000L)) {
            final String cause = topCause;
            final int n = topN;
            // petit delai : on laisse le joueur apparaitre
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (busy.getAsBoolean()) return;
                say.say(p.getName() + " est de retour. Sa mort préférée : « " + labelOf(cause) + " » (" + n
                        + " fois). On ne change pas une équipe qui perd.", false);
            }, 100L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (busy.getAsBoolean() || clock.getAsInt() < 120) return;
        if (ThreadLocalRandom.current().nextInt(100) < 60 && canRemark("quit", 180_000L)) {
            say.say(e.getPlayer().getName() + " abandonne en pleine run. "
                    + pick("Déserteur.", "La porte est par là, apparemment.", "On le note dans le bulletin."), false);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (busy.getAsBoolean() || debrisSaid) return;
        if (e.getBlock().getType() == Material.ANCIENT_DEBRIS) {
            debrisSaid = true;
            say.say(e.getPlayer().getName() + " a trouvé des débris antiques. Du netherite : enfin un truc solide dans cette équipe.",
                    false);
        }
    }

    // ---------------------------------------------------------------- commandes

    boolean command(CommandSender sender, String cmdName, String[] args) {
        String[] a = args;
        if (cmdName.equals("uhcbadges")) {
            a = new String[args.length + 1];
            a[0] = "badges";
            System.arraycopy(args, 0, a, 1, args.length);
        }

        if (a.length >= 1 && a[0].equalsIgnoreCase("badges")) {
            showBadges(sender, a.length >= 2 ? a[1] : null);
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Commande réservée aux joueurs.");
            return true;
        }
        PData d = pd(p.getUniqueId(), p.getName());

        if (a.length >= 1 && a[0].equalsIgnoreCase("off")) {
            d.profOff = true;
            dirty = true;
            save();
            p.sendMessage(Component.text("Bulles et voix du professeur coupées pour toi. (/uhcprof on pour les remettre)",
                    NamedTextColor.YELLOW));
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("on")) {
            d.profOff = false;
            d.voiceOff = false;
            dirty = true;
            save();
            p.sendMessage(Component.text("Le professeur est de retour (bulles et voix).", NamedTextColor.GREEN));
            return true;
        }
        if (a.length >= 1 && (a[0].equalsIgnoreCase("voix") || a[0].equalsIgnoreCase("voice"))) {
            if (a.length < 2 || !(a[1].equalsIgnoreCase("on") || a[1].equalsIgnoreCase("off"))) {
                p.sendMessage(Component.text("/uhcprof voix off|on : couper ou remettre la voix pour toi. "
                        + "Choisir une voix : /uhcprof voix <nom>", NamedTextColor.GRAY));
                return true;
            }
            boolean off = a[1].equalsIgnoreCase("off");
            d.voiceOff = off;
            dirty = true;
            save();
            p.sendMessage(Component.text(off ? "Voix du professeur coupée pour toi (les bulles restent)."
                    : "Voix du professeur remise.", NamedTextColor.YELLOW));
            return true;
        }
        if (a.length >= 1 && (a[0].equalsIgnoreCase("surnom") || a[0].equalsIgnoreCase("titre"))) {
            p.sendMessage(Component.text(d.nick.isEmpty()
                    ? "Tu n'as pas encore de surnom : fais 3 fois la même erreur."
                    : "Ton surnom : « " + d.nick + " »", NamedTextColor.YELLOW));
            return true;
        }

        String state = d.profOff ? "coupé (bulles + voix)" : d.voiceOff ? "bulles oui, voix coupée" : "bulles + voix";
        p.sendMessage(Component.text("Professeur : " + state, NamedTextColor.GOLD));
        p.sendMessage(Component.text("/uhcprof off  ·  /uhcprof on  ·  /uhcprof voix off|on  ·  /uhcprof badges [pseudo]  ·  /uhcprof surnom",
                NamedTextColor.GRAY));
        return true;
    }

    private void showBadges(CommandSender sender, String who) {
        UUID id = null;
        String name = null;
        if (who == null && sender instanceof Player self) {
            id = self.getUniqueId();
            name = self.getName();
        } else if (who != null) {
            for (Map.Entry<UUID, PData> en : data.entrySet()) {
                if (en.getValue().name.equalsIgnoreCase(who)) {
                    id = en.getKey();
                    name = en.getValue().name;
                    break;
                }
            }
        }
        if (id == null) {
            sender.sendMessage(Component.text("Joueur inconnu. Utilise /uhcprof badges <pseudo>.", NamedTextColor.RED));
            return;
        }
        PData d = data.get(id);
        int have = d == null ? 0 : d.badges.size();
        sender.sendMessage(Component.text("Succès de " + name + " : " + have + "/" + BADGES.length, NamedTextColor.GOLD));
        for (String[] b : BADGES) {
            boolean ok = d != null && d.badges.contains(b[0]);
            sender.sendMessage(Component.text((ok ? "★ " : "☆ ") + b[1] + " — " + b[2],
                    ok ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY));
        }
    }
}
