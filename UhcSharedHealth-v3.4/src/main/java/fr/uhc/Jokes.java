package fr.uhc;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Toutes les repliques du professeur. Les textes contiennent des balises :
 * {n} pseudo, {h} coeurs perdus, {p} points perdus, {b} hauteur de chute en blocs,
 * {t} minutes de run, {c} objectifs faits, {tc} objectifs au total.
 * Modifie-les librement : ce n'est que du texte (garde chaque phrase sous ~90 caracteres
 * si possible, les phrases plus longues sont coupees en plusieurs bulles).
 */
final class Jokes {

    private Jokes() {
    }

    // ---------- Anti-repetition : on evite de reservir les dernieres blagues d'une categorie ----------

    private static final Map<String, ArrayDeque<Integer>> RECENT = new HashMap<>();

    // La memoire est sauvegardee dans stats.yml : on ne revoit pas les memes phrases d'une run a l'autre
    static Map<String, java.util.List<Integer>> exportMemory() {
        Map<String, java.util.List<Integer>> out = new HashMap<>();
        for (Map.Entry<String, ArrayDeque<Integer>> e : RECENT.entrySet()) {
            out.put(e.getKey(), new java.util.ArrayList<>(e.getValue()));
        }
        return out;
    }

    static void importMemory(Map<String, java.util.List<Integer>> memory) {
        RECENT.clear();
        for (Map.Entry<String, java.util.List<Integer>> e : memory.entrySet()) {
            RECENT.put(e.getKey(), new ArrayDeque<>(e.getValue()));
        }
    }

    private static String fresh(String category, String... options) {
        if (options.length == 0) return "";
        String cat = category.replace('.', '_').replace(':', '_');
        ArrayDeque<Integer> recent = RECENT.computeIfAbsent(cat, k -> new ArrayDeque<>());
        // On evite les 3/4 des dernieres phrases de la categorie (tant qu'il en reste d'autres)
        int keep = Math.min(options.length - 1, Math.max(1, options.length * 3 / 4));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int index = rnd.nextInt(options.length);
        for (int tries = 0; tries < 30 && (recent.contains(index) || index >= options.length); tries++) {
            index = rnd.nextInt(options.length);
        }
        recent.addLast(index);
        while (recent.size() > keep) {
            recent.removeFirst();
        }
        return options[index];
    }

    private static String num(double v) {
        return Math.abs(v - Math.round(v)) < 0.05
                ? String.valueOf(Math.round(v))
                : String.format(Locale.ROOT, "%.1f", v).replace('.', ',');
    }

    // Accords : "1 point", "0,4 point", "3 points" / "1 cœur", "0,5 cœur", "4 cœurs"
    private static String parse(String s, String name, double hearts, double points, int blocks) {
        String pts = num(points) + (Math.abs(points) >= 2 ? " points" : " point");
        String hrt = num(hearts) + (Math.abs(hearts) >= 2 ? " cœurs" : " cœur");
        s = s.replace("{p} points", "{PTS}").replace("{p} point", "{PTS}")
                .replace("{h} cœurs", "{HRT}").replace("{h} cœur", "{HRT}")
                .replace("{PTS}", pts).replace("{HRT}", hrt);
        return s.replace("{n}", name)
                .replace("{h}", num(hearts))
                .replace("{p}", num(points))
                .replace("{b}", String.valueOf(blocks));
    }


    // ---------- Objectifs d'equipe (meme ordre que CAPS dans UhcPlugin) ----------

    private static final String[][] CAP = {
            {"{n} a trouvé de la pierre. L'âge de pierre commence : pour une fois, vous êtes dans les temps.",
                    "De la pierre pour {n}. On se rapproche du jour où vous saurez creuser sans mourir.",
                    "{n} tape dans la roche : première vraie progression. Je note, sans m'emballer."},
            {"{n} a fondu du fer. Du métal, des outils, de l'espoir. Ne gâchez rien, par pitié.",
                    "Du fer ! {n} vient de sortir l'équipe de la préhistoire. Mention « passable ».",
                    "Le fer est là. Les lingots sont à {n}, les responsabilités aussi."},
            {"Pioche en fer pour {n}. Enfin un outil qui ne se casse pas au premier regard de travers.",
                    "{n} brandit une pioche en fer : on creuse plus vite, donc on meurt plus profond.",
                    "L'équipe passe au fer, grâce à {n}. Les cailloux tremblent. Un peu."},
            {"{n} a trouvé des diamants. Gardez-les loin de la lave, sinon ce sera « catastrophique ».",
                    "Des diamants ! {n} brille pour une fois, et ce n'est pas à cause d'un incendie.",
                    "{n} sort les diamants. La chance sourit à la classe, la gravité attend son tour."},
            {"{n} ouvre la porte du Nether. Là-bas tout veut vous tuer, mais au moins ça change.",
                    "Direction l'enfer avec {n}. Emportez de l'eau, de l'orgueil et un testament.",
                    "Le Nether s'ouvre grâce à {n}. Bienvenue dans la section « mauvaise idée »."},
            {"{n} a déniché une forteresse. Squelettes, blazes, mauvaises idées : le menu complet.",
                    "Une forteresse du Nether ! {n} a visiblement décidé de rendre la leçon intéressante.",
                    "Forteresse repérée par {n}. Si ça sent le brûlé, c'est normal."},
            {"{n} a décroché une blaze rod. Un blaze de moins, une brochette de moins. Bien joué.",
                    "Blaze rod en poche pour {n}. Évitez de finir en barbecue, c'est une suggestion.",
                    "{n} a battu un blaze. Il paraît qu'il a eu chaud, lui aussi."},
            {"{n} a repéré le stronghold. Dernière ligne droite : c'est là que tout le monde fait des bêtises.",
                    "Le stronghold est trouvé par {n}. Le moment de cesser de vous marcher dessus.",
                    "{n} voit le stronghold. La fin approche, ou la catastrophe, ça dépend de vous."},
            {"{n} entre dans l'End. Plus de lit, plus de retour, plus d'excuses.",
                    "L'End s'ouvre pour {n}. Le dragon vous attend, et il a de meilleures notes que vous.",
                    "{n} saute dans l'End. Je ne dirai rien de plus, sinon : sneak près du vide."},
            {"{n} a tué le dragon ! Contre toute attente, la classe décroche son diplôme. Je suis bluffé.",
                    "Le dragon est mort. Bravo à {n}… et à ceux qui ont regardé, très fort.",
                    "{n} termine le dragon. Je retire tout ce que j'ai dit. Presque tout."}
    };

    static String cap(int index, String name) {
        if (index < 0 || index >= CAP.length) return "";
        return fresh("cap" + index, CAP[index]).replace("{n}", name);
    }

    // ---------- Premier coffre / feu ami / coeurs regeneres ----------

    static String chest(String name) {
        return fresh("chest",
                "{n} ouvre le premier coffre. Au moins, quelqu'un sait où est le butin.",
                "Premier coffre pour {n} : il court plus vite vers un butin que vers un coéquipier en danger.",
                "{n} fouille le coffre comme s'il touchait un héritage. Gloutonnerie notée.",
                "Le premier coffre est pour {n}. La classe prend des notes. Sur sa cupidité.",
                "{n} ouvre un coffre. Oh, la surprise : il y avait quelque chose dedans. Quelle chance.",
                "Premier coffre : {n} se sert d'abord. Les autres, on verra, la solidarité est à la carte.",
                "{n} a trouvé un coffre. Il ne partagera pas, mais on l'aime quand même. Presque.",
                "Un coffre ! {n} pense « trésor ». Le prof pense « piège ». On verra qui a raison."
        ).replace("{n}", name);
    }

    static String friendly(String attacker, String victim) {
        return fresh("friendly",
                "{a} frappe {v}. Même équipe, même plan, même cerveau… enfin, plus pour longtemps.",
                "{a} vient de découvrir que {v} fait partie de son équipe. Dommage que ce soit après le coup.",
                "Feu ami de {a} sur {v} : une trahison à -3 points, un classique de la mauvaise ambiance.",
                "{v} n'est pas un zombie, {a}. Les zombies, au moins, ne vous aiment pas dès le départ.",
                "{a} a trouvé l'ennemi : {v}. La classe est atterrée, moi aussi.",
                "{a} frappe {v}. On a dit « en équipe », pas « en équipe contre {v} ».",
                "Un coup de {a} sur {v} : un entraînement à la trahison, sans le moindre talent.",
                "{a} cogne {v}. Je rappelle que les monstres sont dehors, pas à l'intérieur."
        ).replace("{a}", attacker).replace("{v}", victim);
    }

    static String regen(String name) {
        return fresh("regen",
                "{n} a retrouvé des cœurs. Un miracle, ou juste un peu de bon sens.",
                "{n} se soigne enfin. Ses cœurs ont du mal à y croire.",
                "Cœurs récupérés par {n} : on respire. Les autres aussi, un peu.",
                "{n} a mangé la bonne pomme. Pour une fois, c'est lui qui a réfléchi.",
                "Des cœurs en plus pour {n}. Ne les gaspillez pas dans la lave, merci.",
                "{n} remonte la pente. Pas trop vite, la gravité vous regarde."
        ).replace("{n}", name);
    }

    // ---------- Ambiance : situation de la run au moment de la remarque ----------

    static final class Ctx {
        int stage;              // 0 = debut ... 4 = fin de partie
        int minutes;            // minutes de run
        int caps;               // objectifs faits par l'equipe
        int totalCaps;
        int sinceCapMinutes;    // minutes depuis le dernier objectif
        String nextObjective;   // prochain objectif (null si tous faits)
        String clumsy;          // joueur qui a perdu le plus de coeurs
        double clumsyHearts;
        String lowHealth;       // joueur a 3 coeurs ou moins (null sinon)
        double lowHearts;
        String best;            // meilleure et moins bonne note
        String worst;
        double bestScore;
        double worstScore;
        boolean alone;
    }

    private static final String[][] AMBIENT = {
            {
                    "{t} minutes de run et aucun objectif validé. Les cailloux, eux, ont de la patience.",
                    "Je vois du bois, de l'agitation et zéro pierre. C'est une stratégie ou une panne ?",
                    "Premier conseil du prof : taper les arbres, c'est gratuit. Mourir, ça coûte beaucoup plus cher.",
                    "La classe explore, la classe se perd, la classe n'a rien trouvé. Normal, c'est le début.",
                    "Pensez à manger. Je dis ça par expérience : vous avez la mémoire d'un poisson rouge.",
                    "{t} minutes de run : si c'était un devoir, vous chercheriez encore votre stylo.",
                    "Le premier objectif est à portée de main, littéralement. Il suffit de se baisser.",
                    "Un arbre, ça s'attaque, ça ne se contemple pas. Allez, un peu d'énergie.",
                    "Vous marchez beaucoup pour quelqu'un qui n'a encore rien fait. Chapeau pour l'endurance.",
                    "Je note votre rythme : on dirait un escargot qui hésite devant une côte.",
                    "La nuit viendra, avec ses monstres. Ce serait bien d'avoir un abri avant. Je ne dis rien.",
                    "Le tutoriel est terminé, la vraie leçon commence. Elle fait mal, en général.",
                    "Rappel : sans outils, vous êtes de la nourriture avec des jambes.",
                    "Si quelqu'un a une idée, qu'il lève la main. Je commence à m'inquiéter."
            },
            {
                    "{c} objectif sur {tc}. C'est un début, comme un enfant qui apprend à marcher : il tombe beaucoup.",
                    "Un peu de progrès, un peu de fer : ne vous emballez pas, le plus dur est devant.",
                    "Vous avancez ? Doucement. Je vois des étoiles, mais pas encore de bonnes notes.",
                    "{c}/{tc} objectifs. Des enfants de maternelle auraient fini plus vite, mais ils ne creusent pas.",
                    "Premiers résultats : encourageants, pour peu que personne ne finisse dans la lave.",
                    "Le fer arrive, les ennuis aussi. L'un ne va jamais sans l'autre.",
                    "On a un début d'organisation. J'insiste sur « début ».",
                    "Les outils s'améliorent, la prudence beaucoup moins. Je surveille.",
                    "Vous avez de quoi creuser. Reste à savoir où : c'est toujours là que ça se gâte.",
                    "Une équipe qui progresse, ça fait plaisir. Ça ne durera pas, mais ça fait plaisir.",
                    "Bonne dynamique. Si quelqu'un tombe, on fera semblant de ne pas avoir vu.",
                    "Vous ressemblez presque à des survivants. Presque.",
                    "Je vous vois creuser comme des taupes : beaucoup d'enthousiasme, peu de plan.",
                    "À ce rythme, vous aurez des diamants avant la fin de la semaine. Je plaisante, plus ou moins."
            },
            {
                    "Pas mal du tout. Je dis ça rarement : profitez-en, ça ne durera pas.",
                    "{c} objectifs sur {tc}. Si vous continuez, je vais devoir vous féliciter. Ça m'ennuie.",
                    "Vous commencez à ressembler à des joueurs. N'allez pas trop vite : la gravité surveille.",
                    "Les diamants brillent, vos cœurs aussi : à ne pas confondre avec la lave.",
                    "Du niveau, de l'équipement… il ne manque que la prudence. Elle est en rupture de stock.",
                    "De beaux outils ne vous rendent pas immortels. Demandez à ceux qui sont passés avant vous.",
                    "Le plus dur reste à faire : ne pas tout gâcher par excès de confiance.",
                    "Je vois du progrès. Je vois aussi des bêtises à venir. La balance est équilibrée.",
                    "Une équipe efficace, c'est une équipe qui ne se tire pas dessus. À méditer.",
                    "Ne vous endormez pas sur vos lauriers : ils sont en laine, ils brûlent vite.",
                    "On a de la matière. Reste à ne pas la perdre dans un trou stupide.",
                    "Vous êtes presque crédibles. Je note avec une pointe d'inquiétude.",
                    "Équipement correct, nerfs d'acier, cervelle en option. Le pari est lancé.",
                    "À ce niveau-là, une erreur coûte cher. Mais je vous fais confiance, c'est mon plus gros défaut."
            },
            {
                    "On entre dans la zone dangereuse : le Nether ne pardonne pas les mauvaises décisions.",
                    "{c}/{tc} objectifs. Reste le plus dur : ne pas tout gâcher en trois secondes.",
                    "Vous êtes à un cheveu de finir. Aucun cheveu n'a jamais été aussi fragile.",
                    "Beaucoup d'objectifs, peu de marge. La suite se joue sur un coup de chance ou de bêtise.",
                    "Je ne dirai pas que vous êtes bons. Je dirai que vous êtes presque convaincants.",
                    "Les blazes vous attendent. Ils ont de bien meilleurs réflexes que vous.",
                    "Le Nether, c'est comme un examen : tout le monde panique, mais certains ont révisé.",
                    "Emportez du blé, des blocs et un minimum de bon sens. Les deux premiers sont faciles.",
                    "Un pas de travers dans la lave et on recommence tout. Je dis ça, je ne dis rien.",
                    "Vous êtes ici grâce à votre talent. Et un peu grâce à ma patience.",
                    "Ne vous disputez pas ici, je vous en supplie. La lave n'a pas besoin d'aide.",
                    "La suite est la plus belle partie de la run. Et la plus risquée. Les deux vont ensemble.",
                    "Quelqu'un peut vérifier que tout le monde a son armure ? C'est pour moi que je demande.",
                    "Vous êtes loin, très loin. Ce serait dommage de tout perdre sur un saut mal calculé."
            },
            {
                    "On y est presque : l'End, le dragon, la gloire… ou le désastre. Les paris sont ouverts.",
                    "{c}/{tc} objectifs, à un souffle de la victoire. Ne marchez surtout pas dans le vide, pour voir.",
                    "Je suis fier de vous. À peu près. Ne gâchez pas ça avec une chute stupide.",
                    "Dernière ligne droite : un bon conseil, évitez de vous disputer devant le dragon.",
                    "Le dragon vous regarde. Il hésite entre le rire et la compassion.",
                    "Tout le monde s'est donné à fond. Pas de bêtises maintenant : le prof a du cœur, pas des nerfs d'acier.",
                    "Dans l'End, la moindre erreur est définitive. Pensez à regarder où vous mettez les pieds.",
                    "Si vous gagnez, je vous félicite. Si vous perdez, je vous félicite aussi, avec moins de conviction.",
                    "Le dragon n'a pas peur de vous. C'est à ça que sert votre courage : à compenser.",
                    "Un conseil de vieux professeur : visez la tête, évitez le vide, respirez.",
                    "La fin est proche. Ne laissez pas votre dernier exploit être une chute dans le vide.",
                    "Tout dépend de vous maintenant. Je le dis avec beaucoup de pitié et un peu d'espoir.",
                    "Il ne reste qu'un dragon entre vous et la gloire. Les dragons sont rarement raisonnables.",
                    "À ce stade, je ne vous demande plus d'être bons. Seulement de ne pas être ridicules."
            }
    };

    private static final String[] CLUMSY = {
            "{n} a déjà perdu {h} cœurs. À ce rythme, il aura des cicatrices avant d'avoir un diplôme.",
            "Petit rappel : {n} détient le record de cœurs perdus ({h}). Un exploit, mais pas du bon côté.",
            "{n}, {h} cœurs perdus. Prends une chaise, une tisane et un casque pour la prochaine fois.",
            "Mention spéciale pour {n} : {h} cœurs égarés. On ne perd pas autant par hasard, il y met du talent.",
            "{n} saigne plus que la moitié de la classe réunie. {h} cœurs : un chiffre qui a du style.",
            "{n} a perdu {h} cœurs. Ses cœurs demandent à changer de joueur.",
            "{n} a un talent rare : perdre des cœurs sans jamais en gagner. {h} au compteur.",
            "{h} cœurs de perdus pour {n}. Les autres n'ont presque rien perdu : l'écart est d'ordre pédagogique.",
            "{n} saigne pour la science. Résultat de l'expérience : {h} cœurs, zéro conclusion.",
            "On retrouve {n} au fond de la classe avec {h} cœurs en moins. Ça ne s'arrange pas.",
            "{n}, {h} cœurs perdus. À ce stade, ce n'est plus de l'étourderie, c'est une vocation.",
            "Mention « courageux mais inconscient » pour {n} : {h} cœurs laissés sur le chemin."
    };

    private static final String[] LOW = {
            "{n} est à {h} cœurs. La classe observe, le prof prie un peu.",
            "Il reste {h} cœurs à {n}. Un coup de vent et c'est fini.",
            "{n} avec {h} cœurs : il est encore debout par principe, pas par raison.",
            "Attention : {n} est à {h} cœurs. Une pomme dorée s'impose. Ou un miracle.",
            "{n} est à {h} cœurs. Ça rappelle des souvenirs douloureux, et ce n'est que le début.",
            "{h} cœurs pour {n}. La survie est un sport d'équipe : quelqu'un peut le soigner ?",
            "{n} a {h} cœurs. Prenez une profonde inspiration, et surtout ne sautez pas.",
            "Rappel : {n} est à {h} cœurs. Si quelqu'un a de quoi le soigner, c'est le moment de briller."
    };

    private static final String[] STUCK = {
            "Cela fait {m} minutes que rien n'avance. Prochain objectif : {obj}. Il ne viendra pas tout seul.",
            "{m} minutes sans objectif. {obj} vous attend, avec une patience qui m'impressionne.",
            "Je ne veux pas vous presser, mais {m} minutes sans progrès… {obj}, ça vous dit quelque chose ?",
            "{m} minutes de pause pédagogique. Reprenez : {obj}, ce n'est pas si compliqué.",
            "Cela fait {m} minutes que {obj} vous nargue. Allez le chercher.",
            "On tourne en rond depuis {m} minutes. {obj} est à l'autre bout de votre patience.",
            "{m} minutes sans rien : un record, mais pas le bon. Prochain objectif : {obj}.",
            "La classe fait une pause de {m} minutes. Dommage, {obj} n'aime pas attendre."
    };

    private static final String[] NEXT = {
            "Prochain objectif : {obj}. Ça ne va pas se faire en le regardant.",
            "Le programme du jour : {obj}. Je vous laisse libres, mais pas trop.",
            "Petit rappel : {obj}. Les notes ne se font pas toutes seules.",
            "Pensez à {obj}. Je dis ça sans ironie. Presque.",
            "{obj} : voilà ce qui vous attend. Bon courage, ou bonne chance, au choix.",
            "Objectif en cours : {obj}. Si c'est trop dur, dites-le, je ferai semblant d'être surpris.",
            "Un indice : {obj}, c'est maintenant. Les autres, on verra plus tard.",
            "Je rappelle que {obj} compte pour les notes. Je rappelle aussi que vous n'avez rien dit."
    };

    private static final String[] RIVALRY = {
            "{best} mène avec {bs}, {worst} suit à {ws}. L'écart se creuse, la jalousie aussi.",
            "{best} a {bs}, {worst} a {ws}. Je ne juge pas, j'écris juste ça dans le carnet.",
            "Au classement : {best} en tête ({bs}), {worst} à la traîne ({ws}). Rien de surprenant.",
            "{worst}, tu es à {ws} quand {best} est à {bs}. Ça se rattrape, ou pas.",
            "{best} fait des efforts, {worst} fait de la figuration. {bs} contre {ws}, les chiffres parlent.",
            "La classe se divise : {best} ({bs}) et {worst} ({ws}). Les parents seront prévenus.",
            "{best} est premier. {worst} est… présent. {bs} à {ws}, c'est un début de leçon.",
            "Bulletin de mi-parcours : {best} {bs}, {worst} {ws}. Commentaire : « peut mieux faire », pour tous les deux."
    };

    private static final String[] TIME = {
            "{t} minutes de run déjà. Le temps passe vite quand on s'amuse. Ou quand on souffre.",
            "Déjà {t} minutes. Je commence à m'attacher, c'est mauvais signe.",
            "{t} minutes que vous êtes en vie. Un exploit, vu le niveau.",
            "Le chronomètre tourne : {t} minutes. Chaque seconde compte pour le score de la run.",
            "{t} minutes de survie. Si c'était un cours, il serait fini, et vous n'auriez rien compris.",
            "Déjà {t} minutes : on ne lâche rien. Surtout pas la main du coéquipier au bord du vide.",
            "Après {t} minutes, la fatigue se fait sentir. Ça tombe bien, les erreurs aussi.",
            "{t} minutes : la run est bien entamée. Restez concentrés, moi je prends un thé."
    };

    private static final String[] ALONE = {
            "Un seul élève en classe. Mauvaise nouvelle : les notes ne se partagent pas.",
            "Tout seul ? Tu fais de la solitude un art. Le prof est ravi de te tenir compagnie.",
            "Seul face au monde. Quand tu mourras, personne ne te rattrapera. Pense à ça.",
            "Un joueur, un prof, beaucoup d'ennuis potentiels. On se serre les coudes.",
            "Pas de coéquipier aujourd'hui ? Le Wither et moi sommes là, ne t'inquiète pas.",
            "En solo, chaque erreur est ton erreur. Quelle liberté. Quelle responsabilité."
    };

    static String ambient(Ctx c) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        java.util.List<String> kinds = new java.util.ArrayList<>();
        kinds.add("stage");
        kinds.add("stage");
        if (c.clumsy != null && c.clumsyHearts >= 2.0) kinds.add("clumsy");
        if (c.lowHealth != null) {
            kinds.add("low");
            kinds.add("low");
        }
        if (c.nextObjective != null && c.sinceCapMinutes >= 8 && c.minutes >= 8) kinds.add("stuck");
        if (c.nextObjective != null) kinds.add("next");
        if (c.best != null && c.worst != null && !c.best.equals(c.worst) && c.bestScore - c.worstScore >= 3.0) {
            kinds.add("rivalry");
        }
        if (c.minutes >= 30) kinds.add("time");
        if (c.alone) kinds.add("alone");

        String kind = kinds.get(rnd.nextInt(kinds.size()));
        String text;
        switch (kind) {
            case "clumsy":
                text = fresh("clumsy", CLUMSY);
                break;
            case "low":
                text = fresh("low", LOW).replace("{n}", c.lowHealth).replace("{h}", num(c.lowHearts));
                return text;
            case "stuck":
                text = fresh("stuck", STUCK);
                break;
            case "next":
                text = fresh("next", NEXT);
                break;
            case "rivalry":
                text = fresh("rivalry", RIVALRY);
                break;
            case "time":
                text = fresh("time", TIME);
                break;
            case "alone":
                text = fresh("alone", ALONE);
                break;
            default:
                int s = Math.max(0, Math.min(AMBIENT.length - 1, c.stage));
                text = fresh("ambient" + s, AMBIENT[s]);
                break;
        }
        return text
                .replace("{n}", c.clumsy == null ? "quelqu'un" : c.clumsy)
                .replace("{h}", num(c.clumsyHearts))
                .replace("{m}", String.valueOf(c.sinceCapMinutes))
                .replace("{obj}", c.nextObjective == null ? "le suivant" : c.nextObjective)
                .replace("{best}", c.best == null ? "?" : c.best)
                .replace("{worst}", c.worst == null ? "?" : c.worst)
                .replace("{bs}", num(c.bestScore))
                .replace("{ws}", num(c.worstScore))
                .replace("{t}", String.valueOf(c.minutes))
                .replace("{c}", String.valueOf(c.caps))
                .replace("{tc}", String.valueOf(c.totalCaps));
    }

    // ---------- Verdict de la run (objectifs + vitesse) a la fin ----------

    static String runVerdict(boolean record, int objectives, int totalCaps, double score, double best) {
        String s = num(score);
        String b = num(best);
        String d = num(Math.max(0, best - score));
        String o = String.valueOf(objectives);
        String tc = String.valueOf(totalCaps);
        String line;
        if (record) {
            line = fresh("verdict_record",
                    "Nouvelle meilleure run : {s} points ! C'est du jamais-vu, et probablement pas à refaire.",
                    "{s} points, nouveau record. Le prof applaudit, doucement, pour ne pas vous gêner.",
                    "Meilleure run jusqu'ici : {s} points. Ne vous endormez pas dessus.",
                    "{s} points : vous venez d'écrire l'histoire de cette classe. Elle est courte, mais c'est la vôtre.");
        } else if (objectives == 0) {
            line = fresh("verdict_zero",
                    "Zéro objectif, {s} points. Un classique de la run ratée.",
                    "Aucun objectif atteint. C'est de l'art contemporain : on ne comprend rien, mais c'est audacieux.",
                    "Aucun objectif ? Même les poules font mieux. Elles pondent, au moins.");
        } else {
            line = fresh("verdict_normal",
                    "Score de run : {s} points, record à {b}. Il manque {d} points pour entrer dans l'histoire.",
                    "{s} points ce coup-ci, le record est à {b}. Encore {d} points à aller chercher.",
                    "{o}/{tc} objectifs, {s} points. Pas mal, mais le record, c'est {b}.",
                    "{o}/{tc} objectifs en bonne compagnie, mais le record reste à {b}. Ce sera pour la prochaine.");
        }
        return line.replace("{s}", s).replace("{b}", b).replace("{d}", d).replace("{o}", o).replace("{tc}", tc);
    }

    // ---------- Debut de run : fayot et cancre (egalite = tous les joueurs concernes) ----------

    static String introLine() {
        return fresh("intro",
                "Silence, la classe ! Je rends les copies de la dernière run.",
                "Asseyez-vous. J'ai les notes de la dernière run, et certains vont moins rire.",
                "Bonjour la classe. Distribution des prix et des punitions : ne soyez pas timides.",
                "Le verdict de la dernière run est tombé. Il y aura des heureux. Et des autres.");
    }

    static String fayot(String names, boolean plural) {
        String[] pool = plural
                ? new String[]{
                "{n} : fayots à égalité. Même récompense pour tous, le prof est équitable, pas courageux.",
                "Ex æquo en tête : {n}. Un kit pour chacun, et des chevilles qui enflent pour tout le monde.",
                "{n} sont les chouchous de la dernière run. Le fond de la classe prend des notes, de jalousie."}
                : new String[]{
                "{n} est le fayot de la dernière run. Premier de la classe, et il le sait : un kit pour le chouchou.",
                "Bravo {n}, le chouchou du prof. Voilà de quoi commencer, c'est cadeau, ne le répétez pas.",
                "Meilleure note : {n}. Un kit en bois, du pain, et des chevilles qui enflent.",
                "{n} avait rendu ses devoirs avant tout le monde. Le prof est ému, la classe est jalouse."};
        return fresh(plural ? "fayotP" : "fayotS", pool).replace("{n}", names);
    }

    static String cancre(String names, boolean plural) {
        String[] pool = plural
                ? new String[]{
                "{n} : cancres à égalité. Même sanction pour tous, c'est ça, la justice scolaire.",
                "Le fond de la classe est complet : {n}. Les effets arrivent, installez-vous confortablement.",
                "{n} ont gagné la queue du classement. À plusieurs, la honte pèse moins lourd. Pas la punition."}
                : new String[]{
                "{n} est le cancre de la dernière run. Le malus tombe, applaudissements discrets.",
                "Dernier de la classe : {n}. Je lui souhaite une run pleine de nausées, c'est pédagogique.",
                "{n} a collectionné les mauvais points comme d'autres les cartes Pokémon. La punition est servie.",
                "Le cancre, c'est {n}. Il s'en doutait. Nous aussi."};
        return fresh(plural ? "cancreP" : "cancreS", pool).replace("{n}", names);
    }

    // ---------- Degats pendant la run (le joueur est encore en vie) ----------

    static String damage(String name, String cause, String detail, double hearts, double points, int stage, boolean mlg) {
        if (mlg && (cause.equals("LAVA") || cause.startsWith("FIRE") || cause.equals("CAMPFIRE") || cause.equals("FALL"))
                && ThreadLocalRandom.current().nextInt(100) < 55) {
            return mlgIrony(name, cause, false, (int) Math.round(hearts * 2.0 + 3.0));
        }
        return damage(name, cause, detail, hearts, points, stage);
    }

    static String damage(String name, String cause, String detail, double hearts, double points, int stage) {
        int blocks = (int) Math.round(hearts * 2.0 + 3.0);
        String[] mob = detailIsMob(detail) ? mobPool(detail, false) : null;
        String[] pool = mob != null ? mob : causePool(cause, detail, false);
        return parse(fresh("dmg:" + cause + ":" + detail, pool), name, hearts, points, blocks);
    }

    // ---------- Mort (ecran de fin) : plus pique, le joueur est mort ----------

    static String death(String name, String cause, String detail, int blocks, int stage, boolean mlg) {
        if (mlg && (cause.equals("LAVA") || cause.startsWith("FIRE") || cause.equals("CAMPFIRE"))) {
            return mlgIrony(name, cause, true, blocks);
        }
        if (mlg && cause.equals("FALL") && ThreadLocalRandom.current().nextInt(100) < 70) {
            return mlgIrony(name, cause, true, blocks);
        }
        return death(name, cause, detail, blocks, stage);
    }

    static String death(String name, String cause, String detail, int blocks, int stage) {
        String[] mob = detailIsMob(detail) ? mobPool(detail, true) : null;
        String[] pool = mob != null ? mob : causePool(cause, detail, true);
        return parse(fresh("death:" + cause + ":" + detail, pool), name, 0, 0, blocks);
    }

    private static boolean detailIsMob(String detail) {
        return detail != null && !detail.isEmpty() && !detail.startsWith("BLOCK:") && !detail.startsWith("HEAD:");
    }

    private static boolean headIsLoose(String detail) {
        if (detail == null || !detail.startsWith("HEAD:")) return false;
        String block = detail.substring(5);
        return block.contains("SAND") || block.contains("GRAVEL") || block.contains("POWDER");
    }

    // ----- Causes. Regle : "-{p} point" et "{h} cœur" s'accordent tout seuls (voir parse). -----

    private static String[] causePool(String cause, String detail, boolean dead) {
        switch (cause) {
            case "FALL":
                return dead ? new String[]{
                        "{n} a mesuré {b} blocs de gravité. La gravité a gagné, comme prévu.",
                        "{b} blocs de chute et zéro seau d'eau : les leçons de physique se paient cash, {n}.",
                        "{n} voulait voler. Il a réussi pendant {b} blocs. L'atterrissage, lui, était moins brillant.",
                        "Le sol attendait {n} depuis {b} blocs. Il n'a pas été déçu.",
                        "À {b} blocs de haut, {n} a pensé « ça passe ». Spoiler : ça ne passe pas.",
                        "{n} a fait confiance à ses jambes sur {b} blocs. Ses jambes ont démissionné.",
                        "Un seau d'eau, ça pèse trois fois rien. {n} l'aurait su si son inventaire en avait contenu un.",
                        "{n} a vérifié que la gravité marche encore. Elle marche. Son run, beaucoup moins."
                } : new String[]{
                        "{n} se prend {b} blocs de gravité en pleine tête. -{p} point, et un peu de dignité en moins.",
                        "Chute de {b} blocs : {n} a testé le sol. Verdict : toujours aussi dur. -{p} point.",
                        "{n} s'est un peu trop approché du bord. {h} cœur en moins, -{p} point.",
                        "Un seau d'eau aurait coûté zéro cœur. {n} a préféré en perdre {h} cœur. -{p} point.",
                        "{n} a atterri sur les pieds, puis sur le reste. -{p} point, un classique.",
                        "Le sol a rattrapé {n}. Il n'est pas vindicatif, il est juste très dur. -{p} point.",
                        "{n} saute de haut sans filet. {h} cœur perdu, -{p} point : le saut de l'ange version nulle."
                };
            case "LAVA":
                return dead ? new String[]{
                        "{n} a plongé dans la lave comme dans une piscine. Elle faisait 1 200 degrés, il l'a vu trop tard.",
                        "La lave, c'est chaud. {n} l'a vérifié personnellement, histoire d'être sûr.",
                        "{n} a cru que c'était de l'eau orange. C'est la dernière erreur de sa run.",
                        "{n} a fait fondre la run en direct. C'est de l'art, mais très cuit.",
                        "Inventaire plein, instinct de survie vide. La lave a tout pris, {n} avec.",
                        "Un panneau « attention lave » n'aurait servi à rien : {n} fonce toujours dedans.",
                        "La lave a avalé {n} et son équipement. Elle digère les deux sans effort."
                } : new String[]{
                        "{n} barbote dans la lave : {h} cœur en fumée, -{p} point. Il aime le chaud, c'est son droit.",
                        "{n} goûte la lave. Pas besoin de sel, elle est déjà bien relevée. -{p} point.",
                        "La lave ne ment jamais, {n} : ça brûle. {h} cœur perdu, -{p} point.",
                        "{n} fait du sauna avec un volcan. -{p} point, et une sérieuse envie de fraîcheur.",
                        "{n} marche dans la lave en pensant que c'est un tapis. -{p} point : ce n'en est pas un.",
                        "La lave a touché {n}. {h} cœur parti en fumée, -{p} point. Un seau d'eau, ça existe."
                };
            case "FIRE":
                return dead ? new String[]{
                        "{n} avait la tête en feu. Littéralement, cette fois.",
                        "{n} a ignoré l'eau à trois blocs de lui. Le feu a apprécié sa confiance.",
                        "Cuit à point : {n}, comme son avenir dans cette équipe.",
                        "{n} aurait pu éteindre le feu. Il a préféré en faire un spectacle.",
                        "{n} a pris feu et n'a rien fait. Le seul truc qui brûlait bien, c'était lui.",
                        "{n} a couru dans tous les sens en flammes. Pas dans la bonne direction : l'eau était à gauche."
                } : new String[]{
                        "{n} prend feu et ne cherche pas l'eau. {h} cœur grillé, -{p} point. Quelle volonté.",
                        "{n} fait griller son équipement et ses cœurs. -{p} point. Le feu apprécie le sacrifice.",
                        "Un bloc d'eau coûte une seconde, le feu {h} cœur. {n} a choisi : -{p} point.",
                        "{n} brûle. Les cœurs aussi. -{p} point, et une odeur de bacon dans la pièce.",
                        "{n} s'est allumé comme une torche. Il manque juste la guimauve. -{p} point."
                };
            case "DROWNING":
                return dead ? new String[]{
                        "{n} a oublié qu'il ne respirait pas sous l'eau. Les poissons ont pris des notes.",
                        "{n} a bu la tasse, puis le lac, puis la run.",
                        "{n} cherchait de l'air. Il y en avait trois blocs plus haut : c'était à tenter.",
                        "Noyé : {n} a découvert que l'eau, c'est mouillé et pas respirable.",
                        "{n} a compté ses bulles jusqu'au bout. Il s'est arrêté à zéro."
                } : new String[]{
                        "{n} manque d'air. L'air est au-dessus de l'eau, pas dedans. -{p} point.",
                        "{n} fait de l'apnée involontaire : {h} cœur en moins, -{p} point.",
                        "{n} nage avec confiance. Les poumons ne partagent pas son optimisme. -{p} point.",
                        "{n} trouve l'eau trop profonde et trop mouillée. -{p} point : les poissons le regardent, gênés."
                };
            case "STARVATION":
                return dead ? new String[]{
                        "{n} est mort de faim avec un monde entier de vaches autour. Les vaches sont vexées.",
                        "Régime strict de {n} : zéro calorie, zéro run.",
                        "{n} a oublié de manger. Son estomac a démissionné, puis lui.",
                        "{n} s'est éteint de faim à côté de mille sources de nourriture. Un talent rare."
                } : new String[]{
                        "{n} a faim et ça se voit : {h} cœur en moins, -{p} point. Il y a des vaches partout.",
                        "{n} se laisse mourir de faim à côté d'un champ de blé. -{p} point, et un vrai talent pour l'ironie.",
                        "L'estomac de {n} crie à l'aide, ses cœurs aussi. -{p} point : mangez quelque chose."
                };
            case "SUFFOCATION":
                if (headIsLoose(detail)) {
                    return dead ? new String[]{
                            "{n} a pris du sable sur la tête. Enseveli, comme son sens de l'orientation.",
                            "Le gravier s'est invité chez {n}. Il n'avait pas prévu d'aussi bonne compagnie.",
                            "{n} a creusé sous du sable en espérant que ça tienne. Spoiler : non.",
                            "Enseveli sous le sable : {n} voulait voir le fond, il a vu le noir.",
                            "{n} a creusé sous un bloc qui tombe. Un bloc qui tombe, c'est sa fonction."
                    } : new String[]{
                            "Le sable s'effondre sur {n}. {h} cœur en moins, -{p} point. On ne creuse pas sous le sable.",
                            "{n} se fait enterrer par du gravier. -{p} point, et une leçon de géologie.",
                            "{n} a oublié que le sable tombe. Il l'apprend à ses frais : -{p} point.",
                            "Du sable sur la tête de {n}. Une plage sans soleil, sans eau, sans vacances. -{p} point."
                    };
                }
                return dead ? new String[]{
                        "{n} s'est coincé dans un mur. Le mur n'a même pas bougé.",
                        "{n} a voulu faire corps avec la pierre. Mission accomplie, malheureusement.",
                        "Étouffé dans un bloc : {n} avait besoin de place, il n'a trouvé que du rocher.",
                        "{n} a oublié que les blocs ne s'écartent pas quand on pousse."
                } : new String[]{
                        "{n} est coincé dans un bloc. {h} cœur en moins, -{p} point : il aime les espaces restreints.",
                        "{n} fait la bise à la pierre. -{p} point. Elle ne l'a pas lâché.",
                        "{n} s'étouffe dans un mur. Décor réussi, performance ratée. -{p} point.",
                        "{n} s'est téléporté dans la matière. -{p} point : c'est la matière qui gagne."
                };
            case "CONTACT":
                if (detail != null && detail.contains("BERRY")) {
                    return dead ? new String[]{
                            "{n} est mort pour des baies. Pas une once de dignité, mais un bon dessert.",
                            "Un buisson de baies a eu raison de {n}. Il voulait un snack, il a eu une leçon.",
                            "{n} s'est fait piquer par des baies sucrées. La vie est cruelle, la confiture aussi.",
                            "{n} est mort en cueillant des baies. Il laisse derrière lui un buisson très fier."
                    } : new String[]{
                            "{n} se fait attaquer par un buisson de baies. {h} cœur, -{p} point : la gourmandise a un prix.",
                            "Les baies piquent, {n}. On les cueille, on ne les câline pas. -{p} point.",
                            "{n} s'est battu contre un buisson et le buisson a gagné. -{p} point.",
                            "Un buisson de baies pique {n}. -{p} point : la nature se défend, même contre les gourmands."
                    };
                }
                if (detail != null && detail.contains("DRIPSTONE")) {
                    return dead ? new String[]{
                            "{n} atterrit sur une stalagmite. La pointe de la grotte n'a même pas eu à bouger.",
                            "Une stalagmite a eu raison de {n}. Elle attendait là depuis des siècles, patiente.",
                            "Fin de run sur une pointe de dripstone : ce n'est pas la grotte qui attaque, c'est {n} qui lui tombe dessus.",
                            "{n} finit sur une stalagmite. La spéléologie, ce n'est pas pour tout le monde."
                    } : new String[]{
                            "{n} se fait embrocher par une stalagmite. {h} cœur en moins, -{p} point : regarde où tu atterris.",
                            "Une stalagmite pique {n}. -{p} point : en grotte, on regarde aussi le sol.",
                            "{n} saute sur une pointe de dripstone. {h} cœur, -{p} point : la roche n'a pas bougé, elle.",
                            "Stalagmite pour {n} : -{p} point. Les grottes ont des dents, il fallait le savoir."
                    };
                }
                if (detail != null && detail.contains("CACTUS")) {
                    return dead ? new String[]{
                            "{n} a fait un câlin à un cactus. Le cactus a été clair : pas d'accord.",
                            "Piqué par un cactus : {n} a perdu contre une plante qui ne bouge pas.",
                            "{n} se croit invincible. Un cactus vient de lui rappeler le contraire.",
                            "{n} est mort sur un cactus. Dans le désert, c'est la tradition."
                    } : new String[]{
                            "{n} serre un cactus dans ses bras. {h} cœur en moins, -{p} point : l'amour fait mal.",
                            "{n} s'attaque à un cactus. Un cactus ! -{p} point, et zéro excuse.",
                            "Le cactus piquait déjà quand {n} est arrivé. -{p} point pour la curiosité.",
                            "{n} a trouvé un cactus. Le cactus a trouvé {n}. -{p} point pour chacun."
                    };
                }
                return dead ? new String[]{
                        "Le décor a gagné contre {n}, sans même chercher à se battre.",
                        "{n} s'est fait piquer par un élément du décor. Le décor est vexé d'avoir autant impressionné."
                } : new String[]{
                        "{n} se fait mal sur un bloc piquant. {h} cœur, -{p} point. Le décor ne fait pas de cadeau.",
                        "{n} touche à ce qu'il ne fallait pas. -{p} point. Ça pique, c'est le principe."
                };
            case "HOT_FLOOR":
                return dead ? new String[]{
                        "Le sol est devenu lave et {n} n'a pas pensé à s'accroupir. Prochaine fois : sneak.",
                        "{n} a marché sur du magma comme sur un tapis. Le tapis n'a pas aimé."
                } : new String[]{
                        "{n} marche sur du magma. Les chaussures, ça existe, mais pas ici. -{p} point.",
                        "Sol brûlant : {n} danse malgré lui. {h} cœur, -{p} point. Appuie sur sneak."
                };
            case "FLY_INTO_WALL":
                return dead ? new String[]{
                        "{n} a testé l'élytre à pleine vitesse contre un mur. Le mur est en pleine forme.",
                        "Ça vole, ça vole… et puis ça tape. {n} vient de rencontrer un mur."
                } : new String[]{
                        "{n} a rencontré un mur en élytre. Le mur n'a rien. {h} cœur en moins, -{p} point.",
                        "Un mur, ça se voit, {n}. Même à pleine vitesse. -{p} point."
                };
            case "VOID":
                return dead ? new String[]{
                        "{n} a regardé dans le vide. Le vide a regardé {n}, puis a avalé la run.",
                        "Tombé dans le vide : {n} descend toujours, c'est sa spécialité."
                } : new String[]{
                        "{n} flirte avec le vide. Le vide dit oui. -{p} point.",
                        "Le vide a attrapé {n} par les pieds. -{p} point."
                };
            case "LIGHTNING":
                return dead ? new String[]{
                        "La foudre a choisi {n} parmi tout le serveur. Faut croire que ça se mérite.",
                        "{n} est la preuve que le destin a de l'humour. Noir, mais de l'humour."
                } : new String[]{
                        "La foudre tombe sur {n}. {h} cœur en moins, -{p} point : le ciel a ses favoris.",
                        "{n} reçoit la foudre. Pas de chance, pas de parapluie. -{p} point."
                };
            case "POISON":
                return dead ? new String[]{
                        "{n} a avalé un truc suspect. L'instinct de survie était en congé.",
                        "Empoisonné : {n} a cru que c'était mangeable. Une erreur de jugement, très courante.",
                        "{n} s'est fait empoisonner et n'a pas pensé au lait. Le lait est vexé."
                } : new String[]{
                        "{n} est empoisonné. {h} cœur en moins, -{p} point. Le lait, ça sert à ça.",
                        "{n} se fait empoisonner et regarde faire. Un bon verre de lait aurait réglé ça. -{p} point."
                };
            case "MAGIC":
                return dead ? new String[]{
                        "{n} a pris une potion de dégâts pour une potion de soin. L'étiquette, ça se lit.",
                        "Une potion mal choisie a fait le travail du monstre. {n} se sera tué avec élégance."
                } : new String[]{
                        "{n} reçoit un sort. {h} cœur en moins, -{p} point. La magie ne fait pas de cadeaux.",
                        "La magie a parlé, {n} a pris cher. -{p} point."
                };
            case "WITHER":
                return dead ? new String[]{
                        "{n} s'est effrité comme un vieux sablé. Le Wither n'a rien pardonné.",
                        "Le Wither a fait de {n} un petit tas de cendres, très discrètes, comme ses chances."
                } : new String[]{
                        "{n} se dessèche. Le Wither n'est jamais loin. {h} cœur, -{p} point.",
                        "{n} fane à vue d'œil. -{p} point. Un bon repas aurait aidé."
                };
            case "FALLING_BLOCK":
                if (detail != null && detail.contains("DRIPSTONE")) {
                    return dead ? new String[]{
                            "Une stalactite se décroche sur {n}. La grotte a décidé de gagner la partie.",
                            "{n} se fait embrocher par le plafond d'une grotte. Ça arrive quand on ne lève jamais les yeux."
                    } : new String[]{
                            "Une stalactite tombe sur {n}. {h} cœur en moins, -{p} point : le plafond se défend.",
                            "{n} prend une stalactite en pleine tête. -{p} point : en grotte, on lève aussi les yeux."
                    };
                }
                return dead ? new String[]{
                        "Une enclume est tombée sur {n}. Un appel du ciel : tu es renvoyé.",
                        "Un bloc lui est tombé dessus : la gravité, toujours elle."
                } : new String[]{
                        "Un bloc tombe sur {n}. {h} cœur en moins, -{p} point. Il faut lever la tête.",
                        "{n} reçoit un bloc sur la tête. La gravité n'a pas de rancune, juste du talent. -{p} point."
                };
            case "BLOCK_EXPLOSION":
            case "ENTITY_EXPLOSION":
                return dead ? new String[]{
                        "{n} a mis la mèche tout seul. Le boum est de lui, la fin de run aussi.",
                        "Une explosion de trop : {n} a fait péter le plan, et le reste avec."
                } : new String[]{
                        "{n} se prend une explosion. {h} cœur en moins, -{p} point. Ça va péter.",
                        "Boum. {n} aussi. -{p} point, et les oreilles qui sifflent."
                };
            case "FREEZE":
                return dead ? new String[]{
                        "{n} a refroidi les ambitions de l'équipe, et son propre corps avec.",
                        "Gelé sur place : {n} avait peur du Nether, il a trouvé pire."
                } : new String[]{
                        "{n} gèle sur place. -{p} point. Un bonnet aurait suffi.",
                        "{n} se transforme en glaçon. {h} cœur, -{p} point."
                };
            case "KILL":
            case "SUICIDE":
            case "CUSTOM":
                return dead ? new String[]{
                        "{n} s'est tué lui-même d'une commande. La fin de run la plus rapide, aucun effort.",
                        "{n} a tapé /kill. Le plus honnête des abandons."
                } : new String[]{
                        "{n} a pris un coup sans raison valable. -{p} point."
                };
            default:
                return dead ? new String[]{
                        "Mort mystérieuse. Même le prof n'a pas la réponse, et le prof a toujours une réponse.",
                        "{n} est mort sans qu'on sache comment. Un talent, vraiment."
                } : new String[]{
                        "{n} perd {h} cœur. -{p} point. Le prof prend note.",
                        "Aïe pour {n} : {h} cœur en moins, -{p} point."
                };
        }
    }

    // ----- Monstres -----

    private static String[] mobPool(String mob, boolean dead) {
        switch (mob) {
            case "PLAYER":
                return dead ? new String[]{
                        "Trahi par un ami : les vrais ennemis sont dans la team.",
                        "{n} a été tué par un coéquipier. La confiance, c'est comme les blocs : ça se pose, ça se casse.",
                        "Le coup de grâce est venu de son propre camp. Chapeau pour l'ambiance."
                } : new String[]{
                        "{n} se fait frapper par un ami. -{p} point… pour l'autre."
                };
            case "CREEPER":
                return dead ? new String[]{
                        "Sssss… BOUM. Le creeper n'a pas fait semblant.",
                        "{n} a offert un câlin à un creeper. Il a eu une explosion de tendresse.",
                        "Un creeper, c'est un ami qui explose. {n} vient de l'apprendre.",
                        "{n} a entendu le sifflement. Et a choisi de rester. Chapeau.",
                        "Le creeper n'a rien dit. Enfin si : « Sssss ». {n} n'a pas compris.",
                        "{n} a perdu contre un tas de poudre verte qui avance. L'humiliation est complète."
                } : new String[]{
                        "Un creeper explose sur {n}. {h} cœur en moins, -{p} point. Il lui avait dit bonjour.",
                        "Sssss… BOUM pour {n}. -{p} point, et les oreilles qui bourdonnent.",
                        "{n} reçoit un creeper en pleine face. -{p} point : on recule, on ne fait pas la bise.",
                        "Un creeper s'invite chez {n}. -{p} point : on ne le laisse jamais s'approcher, ce n'est pas un ami."
                };
            case "ZOMBIE":
            case "HUSK":
            case "ZOMBIE_VILLAGER":
                return dead ? new String[]{
                        "Un zombie a eu raison de {n}. Il visait la cervelle, il a trouvé de quoi se nourrir à peine.",
                        "Un zombie a tout pris à {n}, même la run.",
                        "{n} s'est fait manger par un zombie. Au moins, le zombie a mangé, lui.",
                        "{n} a perdu face à un mort-vivant qui ne court même pas. Honorable, non ?"
                } : new String[]{
                        "Un zombie mord {n}. {h} cœur, -{p} point. Il y a pire comme adversaire, mais pas beaucoup.",
                        "{n} se fait mâchonner par un zombie. -{p} point : il fallait l'esquiver.",
                        "Un zombie fait des câlins à {n}. -{p} point, de la tendresse mal placée."
                };
            case "SKELETON":
            case "STRAY":
            case "BOGGED":
                return dead ? new String[]{
                        "Un squelette a désossé {n}. Il n'avait pas la moelle.",
                        "Un squelette a visé {n} et ne l'a pas raté. Dommage, c'était le seul qui savait viser.",
                        "{n} s'est fait tirer dessus par un tas d'os. Les os ont gagné.",
                        "{n} s'est fait cribler de flèches. Un squelette, ça n'a pas de viscères, mais ça a de la précision."
                } : new String[]{
                        "Une flèche pour {n}. {h} cœur, -{p} point. Un squelette, ça se contourne.",
                        "{n} sert de cible à un squelette. -{p} point, et un peu de tir à l'arc forcé.",
                        "{n} marche droit devant un squelette. -{p} point : l'esquive, ça s'apprend."
                };
            case "SPIDER":
            case "CAVE_SPIDER":
                return dead ? new String[]{
                        "Une araignée a pris {n} dans sa toile. Il s'est fait avoir.",
                        "{n} a perdu contre une araignée. Huit pattes, zéro pitié.",
                        "{n} s'est fait sauter dessus par un animal grand comme un chat. Le chat est vexé."
                } : new String[]{
                        "Une araignée saute sur {n}. {h} cœur, -{p} point. Huit pattes, une seule leçon.",
                        "{n} se fait piquer par une araignée. -{p} point.",
                        "Une araignée escalade {n}. -{p} point : un coup d'épée, pas un câlin."
                };
            case "ENDERMAN":
                return dead ? new String[]{
                        "{n} a regardé un enderman dans les yeux. Mauvaise idée, élémentaire.",
                        "Un enderman a téléporté {n} dans l'au-delà. Voyage sans retour.",
                        "{n} a fixé un enderman. Un classique : on ne regarde pas, c'est tout."
                } : new String[]{
                        "{n} fixe un enderman : colère noire. {h} cœur, -{p} point.",
                        "Ne regarde pas un enderman, {n}. -{p} point."
                };
            case "BLAZE":
                return dead ? new String[]{
                        "Un blaze a mis le feu à {n}. C'était chaud.",
                        "Boules de feu, rien que pour {n}. Un traitement de faveur."
                } : new String[]{
                        "Un blaze arrose {n} de feu. {h} cœur, -{p} point.",
                        "{n} se prend une boule de feu. -{p} point, un peu de fumée en prime."
                };
            case "GHAST":
                return dead ? new String[]{
                        "Un ghast a fait pleurer {n}, puis exploser.",
                        "{n} a renvoyé la boule de feu… ou pas. Dans le doute, elle l'a tué."
                } : new String[]{
                        "Un ghast lance une boule de feu sur {n}. {h} cœur, -{p} point.",
                        "{n} prend une boule de feu. Renvoie-la la prochaine fois. -{p} point."
                };
            case "PIGLIN":
            case "PIGLIN_BRUTE":
            case "ZOMBIFIED_PIGLIN":
            case "HOGLIN":
                return dead ? new String[]{
                        "Un piglin a plumé {n}. Même sans or, il a tout perdu.",
                        "{n} a fâché les cochons. Les cochons ont un très mauvais caractère."
                } : new String[]{
                        "Un piglin frappe {n}. {h} cœur, -{p} point. Porte de l'or, c'est la règle.",
                        "{n} énerve un cochon. -{p} point."
                };
            case "WARDEN":
                return dead ? new String[]{
                        "Le Warden a entendu {n}. Silence, on tourne… plus.",
                        "{n} a réveillé le Warden. Il n'était pas du matin."
                } : new String[]{
                        "Le Warden frappe {n}. -{p} point. Sneak, ce n'est pas pour faire joli.",
                        "{n} fait du bruit devant le Warden. {h} cœur perdu, -{p} point."
                };
            case "WITCH":
                return dead ? new String[]{
                        "Une sorcière a jeté un sort à {n}. Sort de run.",
                        "{n} a perdu contre une sorcière. Sa potion préférée : celle qui fait mal."
                } : new String[]{
                        "Une sorcière arrose {n} de potions. {h} cœur, -{p} point.",
                        "{n} se prend une potion. -{p} point."
                };
            case "PHANTOM":
                return dead ? new String[]{
                        "Un phantom a eu {n}. Il aurait dû dormir cette nuit.",
                        "{n} n'a pas dormi, un phantom l'a puni. La nature est cruelle."
                } : new String[]{
                        "Un phantom plonge sur {n}. {h} cœur, -{p} point. Un lit, ça existe.",
                        "{n} se fait harceler par un phantom. -{p} point."
                };
            case "DROWNED":
                return dead ? new String[]{
                        "Un noyé a noyé {n}. Ça s'arrose.",
                        "{n} a perdu contre un cadavre aquatique. Le niveau était moyen."
                } : new String[]{
                        "Un noyé frappe {n}. {h} cœur, -{p} point.",
                        "{n} se prend le trident d'un noyé. -{p} point."
                };
            case "WITHER_SKELETON":
                return dead ? new String[]{
                        "Un wither skeleton a eu {n}. Sa run s'est fanée.",
                        "{n} a croisé un squelette noir. Il n'a pas aimé la rencontre."
                } : new String[]{
                        "Un wither skeleton frappe {n}. {h} cœur, -{p} point."
                };
            case "PILLAGER":
            case "VINDICATOR":
            case "EVOKER":
            case "RAVAGER":
                return dead ? new String[]{
                        "Un pillard a pillé {n}. Sa run en premier.",
                        "{n} a affronté un pillard. Il a perdu son butin et son avenir."
                } : new String[]{
                        "Un pillard attaque {n}. {h} cœur, -{p} point."
                };
            case "SLIME":
            case "MAGMA_CUBE":
                return dead ? new String[]{
                        "Un slime a eu {n}. C'est gluant comme fin.",
                        "{n} s'est fait manger par de la gelée. Il aura au moins eu un dernier dessert."
                } : new String[]{
                        "Un slime saute sur {n}. {h} cœur, -{p} point."
                };
            case "ENDER_DRAGON":
                return dead ? new String[]{
                        "Le dragon a eu {n}. Fin de partie, littéralement.",
                        "{n} a défié le dragon. Le dragon n'a pas apprécié l'audace."
                } : new String[]{
                        "Le dragon charge {n}. {h} cœur, -{p} point."
                };
            case "WITHER":
                return dead ? new String[]{
                        "Le Wither a eu {n} : il a fini par se faner.",
                        "{n} a réveillé le Wither. Mauvais réveil pour tout le monde."
                } : new String[]{
                        "Le Wither flétrit {n}. {h} cœur, -{p} point."
                };
            case "IRON_GOLEM":
                return dead ? new String[]{
                        "Un golem de fer a battu {n}. Il était fer-ocement en colère.",
                        "{n} a embêté un golem. Le golem n'a pas de sens de l'humour."
                } : new String[]{
                        "Un golem de fer frappe {n}. {h} cœur, -{p} point."
                };
            case "WOLF":
                return dead ? new String[]{
                        "Un loup a montré les crocs à {n}. Fin de la meute.",
                        "{n} a fâché un loup. Le loup a une excellente mémoire."
                } : new String[]{
                        "Un loup mord {n}. {h} cœur, -{p} point. Évite de le frapper la prochaine fois."
                };
            case "BEE":
                return dead ? new String[]{
                        "Une abeille a piqué {n}. Pas de miel pour lui.",
                        "{n} a perdu contre une abeille. Le miel est pour les autres."
                } : new String[]{
                        "Une abeille pique {n}. {h} cœur, -{p} point."
                };
            case "GUARDIAN":
            case "ELDER_GUARDIAN":
                return dead ? new String[]{
                        "Un gardien a gardé {n}. À jamais.",
                        "{n} a visité un monument marin. Le gardien n'aimait pas les visiteurs."
                } : new String[]{
                        "Un gardien tire sur {n}. {h} cœur, -{p} point."
                };
            case "SILVERFISH":
                return dead ? new String[]{
                        "Un poisson d'argent a eu {n}. La run a fini à la pêche.",
                        "Un petit insecte a battu {n}. Le plus humiliant est que ça se voit."
                } : new String[]{
                        "Des poissons d'argent mordent {n}. {h} cœur, -{p} point."
                };
            case "TNT":
                return dead ? new String[]{
                        "{n} a voulu allumer la mèche. Il a allumé la fin de la run.",
                        "TNT et {n} : une bonne idée, jusqu'à l'explosion."
                } : new String[]{
                        "{n} s'amuse avec la TNT. {h} cœur, -{p} point."
                };
            default:
                return null;
        }
    }

    // =====================================================================
    // v3.5 : reactions du professeur (objectifs dans le desordre, taquineries, felicitations...)
    // =====================================================================

    // Objectifs dans l'ordre : pierre, fer fondu, pioche en fer, diamants, Nether, forteresse, blaze rod, stronghold, End, dragon
    private static final String[] DONE_VERB = {"ramasse de la pierre", "fond du fer", "fabrique une pioche en fer",
            "trouve des diamants", "ouvre le Nether", "déniche une forteresse", "ramène une blaze rod",
            "trouve le stronghold", "entre dans l'End", "tue le dragon"};
    private static final String[] WITHOUT = {"sans avoir ramassé de pierre", "sans avoir fondu de fer", "sans pioche en fer",
            "sans diamants", "sans avoir ouvert le Nether", "sans forteresse", "sans blaze rod", "sans stronghold",
            "sans passer par l'End"};

    /**
     * Objectif fait alors qu'une etape PLUS FACILE manque encore (ex : diamants avant le fer).
     * doneIndex = objectif reussi, missingIndex = l'etape manquante la plus proche (avant doneIndex).
     */
    static String outOfOrder(String name, int doneIndex, int missingIndex) {
        if (doneIndex < 1 || doneIndex >= DONE_VERB.length || missingIndex < 0 || missingIndex >= WITHOUT.length) return null;
        String line;
        if (doneIndex == 3 && (missingIndex == 1 || missingIndex == 2)) {
            // les diamants ne se minent qu'avec une pioche en fer : sans fer, c'est du butin de coffre, un echange ou de la chance
            line = fresh("ooo_diamond",
                    "{n} trouve des diamants sans avoir touché au fer. Un coffre, un échange ou un miracle ? Je surveille.",
                    "Des diamants avant le fer ? {n} saute les chapitres. L'examen final sera vite expédié.",
                    "Diamants pour {n}, pioche en fer pour personne. Soit du butin, soit de la triche. Je regarde.",
                    "{n} sort un diamant du chapeau avant le moindre lingot de fer. Magicien ou pilleur de coffres ?");
        } else if (doneIndex == 4) {
            line = fresh("ooo_nether",
                    "{n} ouvre le Nether {w}. Courage ou inconscience, la nuance se joue en cœurs.",
                    "Direction l'enfer {w}, c'est {n} qui décide. Emportez au moins un seau d'eau.",
                    "Le Nether {w}, vraiment ? {n} aime commencer par la fin.");
        } else {
            line = fresh("ooo_generic",
                    "{n} {d} {w}. Original, je l'avoue.",
                    "{n} {d} {w}. Le programme est plus une suggestion qu'une règle, ici.",
                    "Tiens, {n} {d} {w}. On peut sauter des étapes, pas les conséquences.",
                    "{n} {d} {w}. Je ne sais pas si je dois applaudir ou m'inquiéter.");
        }
        return line.replace("{n}", name).replace("{d}", DONE_VERB[doneIndex]).replace("{w}", WITHOUT[missingIndex]);
    }

    /** Taquinerie : celui qui avance contre celui qui traine. */
    static String tease(String doer, String slacker) {
        return fresh("tease",
                "Pendant que {a} rafle les objectifs, {b} contemple le paysage. C'est aussi une stratégie.",
                "{a} avance, {b} suit de loin. Très loin. Je ne vois même plus {b} sur l'écran.",
                "{b}, {a} vient de te mettre une longueur d'avance. Disons plutôt une rivière.",
                "{a} fait du bon travail. {b}, lui, fait de la figuration. Tout le monde a son rôle.",
                "À ce rythme, {b} aura fini la run au moment où {a} aura déjà rédigé ses mémoires.",
                "{b}, tu peux demander des conseils à {a}. Ou, au minimum, le suivre de moins loin.")
                .replace("{a}", doer).replace("{b}", slacker);
    }

    static String leaderChange(String newLeader, String oldLeader) {
        return fresh("leader",
                "{a} passe devant {b} au classement. Enfin un peu de rivalité dans cette classe.",
                "Changement en tête : {a} double {b}. L'un savoure, l'autre prend des notes. De rage.",
                "{a} dépasse {b} aux points. La couronne change de tête, la pression aussi.",
                "Tiens, {a} est devant {b}. Rassurez-vous {b}, la run n'est pas finie. Pour votre bonheur ou votre malheur.")
                .replace("{a}", newLeader).replace("{b}", oldLeader);
    }

    static String eatGolden(String name) {
        return fresh("eat_golden",
                "Une pomme d'or ! {n} réfléchit enfin comme quelqu'un qui veut survivre. Je suis ému.",
                "{n} croque une pomme d'or : voilà un réflexe de survie. Je note, avec un sourire discret.",
                "Bravo {n} : la pomme d'or, c'est le seul fruit qui répare vraiment les erreurs.",
                "{n} se soigne avec de l'or. Pour une fois, on dépense l'argent intelligemment.")
                .replace("{n}", name);
    }

    static String eatApple(String name) {
        return fresh("eat_apple",
                "{n} mange une pomme. Une par jour éloigne le médecin. Pour le prof, ça ne marche pas : je suis toujours là.",
                "Une pomme pour {n} : le sens des priorités est en train de se réveiller. Doucement.",
                "{n} a pensé à manger. C'est un début de carrière brillant.",
                "Bravo {n}, tu as pensé à te nourrir. La faim, ça tue plus de joueurs que les creepers.")
                .replace("{n}", name);
    }

    static String eatSmart(String name) {
        return fresh("eat_smart",
                "{n} mange avant que la barre de faim crie. La survie, c'est aussi ça. Bien joué.",
                "Un repas pour {n} : l'estomac et le prof applaudissent en chœur.",
                "{n} se nourrit au bon moment. Mention « sens des responsabilités ».",
                "Bien vu {n} : manger avant le drame, c'est le truc que personne ne fait. Bravo.")
                .replace("{n}", name);
    }

    static String milk(String name) {
        return fresh("milk",
                "Du lait pour effacer les effets ! {n} a lu le cours. Je n'en reviens pas.",
                "{n} boit du lait : le bon réflexe contre le poison. Il y a peut-être de l'espoir pour cette classe.",
                "Un seau de lait pour {n}. Les vaches sont fières, le prof aussi. Un peu.")
                .replace("{n}", name);
    }

    // MLG : seau d'eau pose en pleine chute (blocks = hauteur de la chute)
    static String clutch(String name, int blocks) {
        String line;
        if (blocks >= 45) {
            line = fresh("clutch_huge",
                    "{b} blocs de chute et un seau d'eau au bon moment ! {n}, je n'ai plus de mots. Ça n'arrive jamais.",
                    "Un MLG de {b} blocs ! {n} vient de réaliser le plus beau geste de la run. J'applaudis debout.",
                    "{b} blocs, un seau, et {n} qui atterrit sans une égratignure. Les dieux du MLG sont avec vous.");
        } else if (blocks >= 25) {
            line = fresh("clutch_high",
                    "MLG de {b} blocs réussi par {n} ! Voilà un geste qui se mérite.",
                    "{n} pose l'eau à {b} blocs du sol et ne perd pas un cœur. Beau travail, vraiment.",
                    "{b} blocs de chute, un seau d'eau au bon moment : {n} sait ce qu'il fait. Ça me rassure et ça m'agace.");
        } else if (blocks >= 12) {
            line = fresh("clutch_mid",
                    "Seau d'eau en pleine chute : {n} réussit son MLG de {b} blocs. Propre.",
                    "{n} s'en sort avec un MLG à {b} blocs. Le réflexe est là, je note.",
                    "{b} blocs et un seau d'eau : {n} atterrit sans une égratignure. Bien joué.");
        } else {
            line = fresh("clutch_small",
                    "Un petit MLG de {b} blocs pour {n}. Petit, mais propre.",
                    "{n} se sauve avec un seau d'eau à {b} blocs du sol. C'est le geste qui compte.",
                    "Seau d'eau posé juste à temps par {n} : {b} blocs de chute et zéro dégât. Réflexe validé.");
        }
        return line.replace("{n}", name).replace("{b}", String.valueOf(blocks));
    }

    /** Fait ironique : un joueur qui a reussi un MLG se fait avoir par la lave, le feu ou une chute. */
    static String mlgIrony(String name, String cause, boolean dead, int blocks) {
        switch (cause) {
            case "LAVA":
                return fresh(dead ? "mlg_lava_d" : "mlg_lava",
                        "{n} sait faire des MLG avec de l'eau, et visiblement avec de la lave aussi. Mais celui-là ne se rate qu'une fois.",
                        "Le seau d'eau, c'est pour les MLG. La lave, c'est pour ceux qui ne regardent pas devant eux. {n} cumule les deux.",
                        "Un MLG à l'eau, {n} sait faire. À la lave, visiblement aussi, mais le résultat est moins flatteur.")
                        .replace("{n}", name);
            case "FIRE":
            case "FIRE_TICK":
            case "CAMPFIRE":
                return fresh(dead ? "mlg_fire_d" : "mlg_fire",
                        "L'eau n'est pas faite que pour les MLG, {n}. Elle éteint aussi le feu. Et oui, le feu, ça brûle.",
                        "{n} sait réussir un MLG parfait, mais pas éteindre ses propres flammes. L'eau sert à plus d'une chose.",
                        "Rappel : l'eau ne sert pas qu'aux MLG. Elle éteint aussi les flammes. Et oui, ça brûle, {n}.")
                        .replace("{n}", name);
            default:
                return fresh(dead ? "mlg_fall_d" : "mlg_fall",
                        "Un MLG réussi plus tôt, et maintenant {b} blocs de chute pour {n}. La régularité, ce n'est pas son truc.",
                        "{n} sait faire des MLG, mais pas tout le temps. Cette chute de {b} blocs n'avait pas prévu de rendez-vous.",
                        "Le MLG de tout à l'heure, c'était du talent ou de la chance ? Cette chute de {b} blocs répond à {n}.")
                        .replace("{n}", name).replace("{b}", String.valueOf(blocks));
        }
    }

    static String totem(String name) {
        return fresh("totem",
                "Le totem sauve {n} in extremis ! La mort a perdu aujourd'hui, le prof aussi, il voulait du spectacle.",
                "{n} était mort. Le totem a dit non. Remerciez-le, il travaille bien mieux que vous.",
                "Totem activé pour {n} : une seconde chance. Les prochaines seront moins gratuites.")
                .replace("{n}", name);
    }

    static String milestone(int minutes) {
        String m = String.valueOf(minutes);
        if (minutes >= 90) {
            return fresh("mile_long",
                    "Déjà " + m + " minutes de survie ! Je commence à croire à votre existence. Ne gâchez pas ça.",
                    m + " minutes. Même mes plus anciens élèves n'avaient pas tenu aussi longtemps. Presque félicitations.");
        }
        if (minutes >= 45) {
            return fresh("mile_mid",
                    m + " minutes de survie : l'équipe tient. Pour un résultat honorable, il reste surtout à ne pas faire d'erreur.",
                    "Déjà " + m + " minutes. Je suis presque fier. Presque, restez concentrés.");
        }
        return fresh("mile_short",
                m + " minutes sans catastrophe : un exploit, à cette échelle. Applaudissements polis.",
                "Déjà " + m + " minutes. Les cours avancent. Les élèves, un peu moins.",
                m + " minutes de run : le suspense monte, les erreurs aussi.");
    }

    static String firstKill(String name, String mob) {
        return fresh("first_kill",
                "Premier monstre tué par {n} : un {m} de moins. Un peu de courage, ça fait du bien.",
                "{n} ouvre le tableau de chasse avec un {m}. Le sang de la classe est baptisé.",
                "Un {m} s'effondre, {n} savoure. Il lui a fallu du temps, mais il l'a fait.")
                .replace("{n}", name).replace("{m}", mob);
    }

    static String bossKill(String name, String mob) {
        return fresh("boss_kill",
                "{n} a abattu un {m} ! Là, je n'ai rien à redire. J'ai bien cherché pourtant.",
                "Un {m} de moins grâce à {n}. Le prof applaudit, doucement, pour ne pas gâcher son effet.",
                "{n} vient de terrasser un {m}. Mention « excellent », écrite en tout petit.")
                .replace("{n}", name).replace("{m}", mob);
    }

    static String bed(String name) {
        return fresh("bed",
                "{n} dort dans un lit : récompense collective. Reposez-vous, la classe reprend bientôt.",
                "Un lit pour {n}, des points pour toute la classe. Voilà le genre de décision que j'apprécie.",
                "{n} a trouvé le sommeil. Les phantoms, eux, cherchent une autre victime.")
                .replace("{n}", name);
    }

    static String collective(String name, String label) {
        return fresh("collective",
                "{n} réussit « {l} » : toute la classe empoche les points. Un travail d'équipe, ça existe donc.",
                "Défi collectif réussi par {n} (« {l} ») : tout le monde gagne. Je n'y crois pas non plus.")
                .replace("{n}", name).replace("{l}", label);
    }

    /** Palmares de fin de run (lu par le professeur). Chaque element peut etre null. */
    static String palmares(String worstName, double worstHearts, String worstCause, String hunter, int kills) {
        StringBuilder sb = new StringBuilder();
        if (worstName != null && worstHearts >= 2.0) {
            sb.append(fresh("palm_worst",
                    "Moment le plus honteux : {n}, {h} cœurs perdus d'un coup ({c}).",
                    "Mention spéciale à {n} : {h} cœurs envolés en une seule erreur ({c}).",
                    "Le prix de la maladresse revient à {n} : {h} cœurs d'un coup ({c}).")
                    .replace("{n}", worstName).replace("{h}", num(worstHearts)).replace("{c}", worstCause));
        }
        if (hunter != null && kills >= 3) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(fresh("palm_hunter",
                    "Chasseur de la run : {n}, {k} monstres abattus.",
                    "Au tableau de chasse, {n} domine avec {k} monstres.")
                    .replace("{n}", hunter).replace("{k}", String.valueOf(kills)));
        }
        return sb.toString();
    }

    // =====================================================================
    // v3.6 : presque-mort, recap de la derniere run, MVP, replay
    // =====================================================================

    static String nearDeath(String name, double hearts) {
        return fresh("neardeath",
                "{n} s'accroche à {h} cœur. Même moi, je retiens mon souffle.",
                "{h} cœur pour toute l'équipe ! {n}, ne bouge plus, ne respire plus, ne fais plus rien.",
                "Il reste {h} cœur après ça. {n} vient de frôler la fin de run de très près.",
                "{n} survit avec {h} cœur. Ce n'est pas un exploit, c'est un miracle, et ça se mange avec une pomme d'or.")
                .replace("{n}", name).replace("{h}", num(hearts));
    }

    static String recovered() {
        return fresh("recovered",
                "Les cœurs remontent. L'équipe revient de loin, et je suis presque déçu de ne pas avoir de drame à raconter.",
                "Ça va mieux : la vie remonte. La run n'est pas finie, malheureusement pour mon suspense.",
                "Rescapés. Je ne sais pas si c'est du talent ou de la chance, mais je prends les deux.");
    }

    static String recap(String time, int caps, int total, String score, String dead, String cause, String best, String mvp) {
        String line = fresh("recap",
                "Rappel de la dernière run : {t}, {c}/{k} objectifs, {s} points. {d} y a mis fin : {x}.",
                "Souvenez-vous : la dernière run a duré {t}, pour {c}/{k} objectifs et {s} points. Fin de partie signée {d} : {x}.",
                "Petit retour en arrière : {t} de survie, {c}/{k} objectifs, {s} points. Le coupable : {d} ({x}).");
        line = line.replace("{t}", time).replace("{c}", String.valueOf(caps)).replace("{k}", String.valueOf(total))
                .replace("{s}", score).replace("{d}", dead == null ? "le destin" : dead)
                .replace("{x}", cause == null || cause.isEmpty() ? "cause inconnue" : cause);
        if (mvp != null && !mvp.isEmpty()) line += " MVP : " + mvp + ".";
        if (best != null && !best.isEmpty()) line += " Record à battre : " + best + ".";
        return line;
    }

    static String mvp(String name, String reason, boolean alsoFayot) {
        String line = fresh("mvp",
                "MVP de la run : {n}, {r}.",
                "Le MVP, c'est {n} : {r}. Applaudissements, mais dans le calme.",
                "Joueur le plus précieux de la run : {n}, {r}.")
                .replace("{n}", name).replace("{r}", reason);
        if (alsoFayot) line += " Et en plus, premier de la classe. Ça devient indécent.";
        return line;
    }

    static String replay(String name, String time, double hearts, double points, String cause) {
        return fresh("replay",
                "Replay du pire moment, à {t} : {n} perd {h} cœurs d'un coup ({c}), soit {p} points.",
                "À {t}, le pire moment de la run : {h} cœurs envolés pour {n} ({c}). Coût : {p} points.",
                "Ralenti sur le moment le plus honteux, à {t} : {n}, {c}, {h} cœurs de moins.")
                .replace("{n}", name).replace("{t}", time).replace("{h}", num(hearts))
                .replace("{p}", num(points)).replace("{c}", cause);
    }
}
