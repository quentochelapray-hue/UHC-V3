package fr.uhc;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Interface dessinee avec le pack : barre d'objectifs (icones, chrono, crane) et bulle du professeur.
 * Tout passe par des glyphes d'une police "uhc:ui" affichee dans des barres de boss rendues invisibles.
 * v3.5 : le texte de la bulle utilise une police a LARGEUR FIXE (5 px par caractere, gras 6 px) : on connait donc
 * exactement la largeur de chaque ligne, ce qui permet de caler la bulle dans le coin gauche de l'ecran.
 */
final class Ui {
    static final Key FONT = Key.key("uhc", "ui");
    static final Key FONT_U = Key.key("uhc", "ui_a");   // texte : ligne du HAUT d'une rangee
    static final Key FONT_L = Key.key("uhc", "ui_b");   // texte : ligne du BAS d'une rangee (deux lignes par rangee)
    static final int ROW = 19;          // hauteur d'une barre de boss (pixels)
    static final int PANEL_W = 112;     // largeur du panneau de la bulle (le glyphe avance de PANEL_W + 1)
    static final int PORTRAIT = 36;     // place reservee au portrait (image de 32 px), a gauche du panneau
    static final int PORTRAIT_IMG = 32;
    static final int TEXT_X = 7;        // marge du texte dans le panneau
    static final int ADV = 5;           // avance d'un caractere de la police du texte
    static final int ADV_BOLD = 6;      // le gras ajoute 1 pixel
    static final int TOTAL = PORTRAIT + PANEL_W + 1;                // largeur d'une rangee de la bulle
    static final int LINE_CHARS = (PANEL_W - 2 * TEXT_X) / ADV;     // caracteres par ligne
    static final int OBJECTIVES = 10;

    static final char SLOT_IDLE = '\uE200', SLOT_DONE = '\uE201', SLOT_CUR = '\uE202';
    static final char ICON = '\uE210';
    static final char SKULL = '\uE230', STAR = '\uE231';
    static final char DIGIT = '\uE240', COLON = '\uE24A', DOT = '\uE24B', COMMA = '\uE24C';
    static final char BUB_TOP = '\uE250', BUB_MID = '\uE251', BUB_BOT = '\uE252', BUB_SINGLE = '\uE253';
    static final char PORTRAIT_GLYPH = '\uE260'; // 3 images : repos, bouche ouverte, tete haussee
    static final char PAD_POS = '\uE300', PAD_NEG = '\uE310';

    /** Caracteres de la police du texte (dans l'ordre de l'image talk.png, 16 par rangee). */
    static final String TEXT_CHARS = " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~\u00e0\u00e2\u00e4\u00e7\u00e9\u00e8\u00ea\u00eb\u00ee\u00ef\u00f4\u00f6\u00f9\u00fb\u00fc\u00ff\u0153\u00c0\u00c2\u00c7\u00c9\u00c8\u00ca\u00cb\u00ce\u00cf\u00d4\u00d9\u00db\u0152\u00b0\u20ac\u00ab\u00bb\u00b7\u2026\u2019\u2013\u2014\u2191\u2197\u2192\u2198\u2193\u2199\u2190\u2196";

    // Vraies textures du jeu (le pack les reference, il ne les copie pas)
    static final String[] ICON_TEXTURES = {
            "minecraft:block/cobblestone.png", "minecraft:item/iron_ingot.png", "minecraft:item/iron_pickaxe.png",
            "minecraft:item/diamond.png", "minecraft:block/obsidian.png", "minecraft:block/nether_bricks.png",
            "minecraft:item/blaze_rod.png", "minecraft:item/ender_eye.png", "minecraft:block/end_stone.png",
            "minecraft:item/end_crystal.png"};

    // Largeur d'avance de chaque icone (largeur visible + 1). 17 = texture pleine de 16 pixels de large.
    // Si une icone est decalee en jeu, change la valeur dans config.yml (pack.icon-advances).
    private static final int[] ICON_ADVANCE = {17, 17, 17, 17, 17, 17, 17, 17, 17, 17};
    private static int hudShiftY = 0;   // decalage vertical de la barre d'objectifs (+ = vers le bas)

    static void setIconAdvances(List<Integer> values) {
        for (int i = 0; i < ICON_ADVANCE.length && values != null && i < values.size(); i++) {
            ICON_ADVANCE[i] = Math.max(8, Math.min(20, values.get(i)));
        }
    }

    static void setHudShiftY(int px) {
        hudShiftY = Math.max(-6, Math.min(12, px));
    }

    private Ui() {
    }

    /** Glyphe : fichier, hauteur, ascent (decalage vertical). */
    record Glyph(String file, char ch, int height, int ascent) {
    }

    static List<Glyph> glyphs() {
        int s = hudShiftY;
        List<Glyph> g = new ArrayList<>();
        g.add(new Glyph("slot_idle", SLOT_IDLE, 20, 8 - s));
        g.add(new Glyph("slot_done", SLOT_DONE, 20, 8 - s));
        g.add(new Glyph("slot_cur", SLOT_CUR, 20, 8 - s));
        for (int i = 0; i < OBJECTIVES; i++) {
            g.add(new Glyph(ICON_TEXTURES[i], (char) (ICON + i), 16, 6 - s)); // 2 px de marge en haut comme en bas
        }
        g.add(new Glyph("skull", SKULL, 9, 2 - s));
        g.add(new Glyph("star", STAR, 9, 2 - s));
        for (int i = 0; i < 10; i++) {
            g.add(new Glyph("d_" + i, (char) (DIGIT + i), 7, 1 - s));
        }
        g.add(new Glyph("d_colon", COLON, 7, 1 - s));
        g.add(new Glyph("d_dot", DOT, 7, 1 - s));
        g.add(new Glyph("d_comma", COMMA, 7, 1 - s));
        g.add(new Glyph("bub_top", BUB_TOP, 19, 14));
        g.add(new Glyph("bub_mid", BUB_MID, 19, 14));
        g.add(new Glyph("bub_bot", BUB_BOT, 19, 14));
        g.add(new Glyph("bub_single", BUB_SINGLE, 19, 14));
        for (int f = 0; f < 3; f++) {
            g.add(new Glyph("portrait" + f, (char) (PORTRAIT_GLYPH + f), PORTRAIT_IMG, 14));
        }
        return g;
    }

    /** Fichiers supplementaires de textures (hors glyphes unitaires). */
    static List<String> extraTextures() {
        return List.of("talk");
    }

    private static String jsonChar(char c) {
        if (c == '"') return "\\\"";
        if (c == '\\') return "\\\\";
        if (c >= 32 && c < 127) return String.valueOf(c);
        return "\\u" + String.format(Locale.ROOT, "%04x", (int) c);
    }

    /** Contenu de assets/uhc/font/ui.json */
    static String fontJson() {
        StringBuilder sb = new StringBuilder("{\"providers\":[");
        for (Glyph g : glyphs()) {
            sb.append("{\"type\":\"bitmap\",\"file\":\"")
                    .append(g.file().contains(":") ? g.file() : "uhc:font/ui/" + g.file() + ".png").append("\",")
                    .append("\"ascent\":").append(g.ascent()).append(",\"height\":").append(g.height())
                    .append(",\"chars\":[\"\\u").append(String.format(Locale.ROOT, "%04X", (int) g.ch())).append("\"]},");
        }
        sb.append("{\"type\":\"space\",\"advances\":{");
        for (int k = 0; k < 8; k++) {
            int v = 1 << k;
            sb.append("\"\\u").append(String.format(Locale.ROOT, "%04X", PAD_POS + k)).append("\":").append(v).append(',');
            sb.append("\"\\u").append(String.format(Locale.ROOT, "%04X", PAD_NEG + k)).append("\":-").append(v);
            if (k < 7) sb.append(',');
        }
        sb.append("}}]}");
        return sb.toString();
    }

    /**
     * Police du texte de la bulle (image talk.png : cellules de 13x48 pixels affichees a l'echelle 1/3, soit 5 px d'avance).
     * Deux versions qui ne different que par la hauteur : la ligne du haut et la ligne du bas d'une meme rangee.
     * (la ligne de base tombe a 0 px ou a 9 px du haut de la rangee : 9 px d'interligne au lieu de 19)
     */
    static String textFontJson(boolean upper) {
        StringBuilder chars = new StringBuilder(TEXT_CHARS);
        while (chars.length() % 16 != 0) chars.append(' ');
        StringBuilder sb = new StringBuilder("{\"providers\":[{\"type\":\"bitmap\",\"file\":\"uhc:font/ui/talk.png\",\"ascent\":")
                .append(upper ? 15 : 6).append(",\"height\":16,\"chars\":[");
        for (int r = 0; r < chars.length() / 16; r++) {
            if (r > 0) sb.append(',');
            sb.append('"');
            for (int c = 0; c < 16; c++) sb.append(jsonChar(chars.charAt(r * 16 + c)));
            sb.append('"');
        }
        return sb.append("]}]}").toString();
    }

    // ---------- briques ----------

    static String padChars(int n) {
        StringBuilder sb = new StringBuilder();
        int left = Math.abs(n);
        char base = n < 0 ? PAD_NEG : PAD_POS;
        while (left > 0) {
            int step = Math.min(left, 255);
            for (int k = 0; k < 8; k++) {
                if ((step & (1 << k)) != 0) sb.append((char) (base + k));
            }
            left -= step;
        }
        return sb.toString();
    }

    private static Component pad(int n) {
        return n == 0 ? Component.empty() : Component.text(padChars(n), NamedTextColor.WHITE).font(FONT);
    }

    private static Component glyph(char c) {
        return Component.text(String.valueOf(c), NamedTextColor.WHITE).font(FONT);
    }

    static String digits(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') sb.append((char) (DIGIT + (c - '0')));
            else if (c == ':') sb.append(COLON);
            else if (c == '.') sb.append(DOT);
            else if (c == ',') sb.append(COMMA);
        }
        return sb.toString();
    }

    private static Component digitComp(String s, TextColor color) {
        return Component.text(digits(s), color).font(FONT);
    }

    // ---------- barre d'objectifs ----------

    // largeur (pixels) d'un nombre ecrit avec les glyphes de chiffres : chiffre = 6, ':' '.' ',' = 3
    private static int digitsWidth(String s) {
        int w = 0;
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') w += 6;
            else if (c == ':' || c == '.' || c == ',') w += 3;
        }
        return w;
    }

    /**
     * done[i] : objectif i fait ; current : index de l'objectif en cours (-1 si tout est fait).
     * Les 10 icones sont centrees sur l'ecran : on compense la largeur du chrono, du crane et de l'etoile (a droite).
     * shiftX : decalage supplementaire en pixels (+ = vers la droite), 0 par defaut.
     */
    static Component objectives(boolean[] done, int current, String clock, int deaths, String note, TextColor noteColor,
                                int shiftX) {
        boolean hasNote = note != null && !note.isEmpty();
        int right = 8 + digitsWidth(clock) + 8 + 10 + 3 + digitsWidth(String.valueOf(deaths))
                + (hasNote ? 9 + 10 + 3 + digitsWidth(note) : 0);
        // Un pad de tete de largeur "right" decale le contenu de right/2 : les icones se retrouvent au centre
        int lead = right + 2 * shiftX;
        Component row = lead != 0 ? pad(lead) : Component.empty();
        for (int i = 0; i < OBJECTIVES; i++) {
            boolean d = i < done.length && done[i];
            boolean cur = i == current;
            char slot = cur ? SLOT_CUR : d ? SLOT_DONE : SLOT_IDLE;
            // objectif pas encore fait : icone assombrie (couleur grise appliquee au glyphe)
            Component icon = Component.text(String.valueOf((char) (ICON + i)),
                    d || cur ? NamedTextColor.WHITE : TextColor.color(0x707070)).font(FONT);
            // cadre : 20 px (avance 21) ; l'icone de 16 px y entre avec 2 px de marge de chaque cote ; 22 px par objectif
            row = row.append(glyph(slot)).append(pad(-19)).append(icon).append(pad(20 - ICON_ADVANCE[i]));
        }
        row = row.append(pad(8)).append(digitComp(clock, TextColor.color(0x55FFFF)))
                .append(pad(8)).append(glyph(SKULL)).append(pad(3))
                .append(digitComp(String.valueOf(deaths), NamedTextColor.WHITE));
        if (hasNote) {
            row = row.append(pad(9)).append(glyph(STAR)).append(pad(3)).append(digitComp(note, noteColor));
        }
        return row;
    }

    // ---------- texte de la bulle ----------

    /** Remplace les caracteres absents de la police par un equivalent (sinon une case "manquant" s'afficherait). */
    static String clean(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (TEXT_CHARS.indexOf(c) >= 0) {
                sb.append(c);
                continue;
            }
            switch (c) {
                case '\u201c', '\u201d', '\u00ab' -> sb.append('"');
                case '\u2018', '\u2032' -> sb.append('\'');
                case '\u00a0', '\u202f', '\t' -> sb.append(' ');
                case '\u2026' -> sb.append("...");
                case '\u2013', '\u2014' -> sb.append('-');
                case '\u00e6' -> sb.append("ae");
                case '\u00c6' -> sb.append("AE");
                case '\u2764', '\u2665' -> sb.append("coeur");
                default -> sb.append('?');
            }
        }
        return sb.toString();
    }

    /** Largeur exacte (pixels) d'un texte de la police de la bulle. */
    static int width(String s, boolean bold) {
        return s.length() * (bold ? ADV_BOLD : ADV);
    }

    /** Coupe un texte en lignes de maxChars caracteres maximum, sans couper un mot. */
    static List<String> wrap(String text, int maxChars) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            while (word.length() > maxChars) { // mot geant : on le coupe (cas tres rare)
                if (cur.length() > 0) {
                    lines.add(cur.toString());
                    cur.setLength(0);
                }
                lines.add(word.substring(0, maxChars));
                word = word.substring(maxChars);
            }
            if (cur.length() > 0 && cur.length() + 1 + word.length() > maxChars) {
                lines.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    private static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean end = c == '.' || c == '!' || c == '?';
            if (end && (i + 1 == text.length() || text.charAt(i + 1) == ' ')) {
                // "..." et "?!" : on laisse la ponctuation suivante dans la meme phrase
                if (i + 1 < text.length() && (text.charAt(i + 1) == '.' || text.charAt(i + 1) == '!' || text.charAt(i + 1) == '?')) {
                    continue;
                }
                out.add(text.substring(start, i + 1).trim());
                start = i + 1;
            }
        }
        if (start < text.length()) {
            String rest = text.substring(start).trim();
            if (!rest.isEmpty()) out.add(rest);
        }
        return out;
    }

    /** Coupe une phrase trop longue a une virgule / deux-points / tiret, sinon entre deux mots. */
    private static List<String> splitLongSentence(String s, int lineChars, int maxLines) {
        List<String> parts = new ArrayList<>();
        String rest = s;
        while (wrap(rest, lineChars).size() > maxLines) {
            int best = -1;
            int limit = lineChars * maxLines;
            for (int i = Math.min(limit, rest.length() - 1); i > limit / 3; i--) {
                char c = rest.charAt(i);
                if ((c == ',' || c == ';' || c == ':') && i + 1 < rest.length() && rest.charAt(i + 1) == ' '
                        && wrap(rest.substring(0, i + 1), lineChars).size() <= maxLines) {
                    best = i + 1;
                    break;
                }
            }
            if (best < 0) {
                for (int i = Math.min(limit - 2, rest.length() - 1); i > limit / 3; i--) {
                    if (rest.charAt(i) == ' ' && wrap(rest.substring(0, i), lineChars).size() <= maxLines) {
                        best = i;
                        break;
                    }
                }
            }
            if (best < 0) best = Math.min(limit - 2, rest.length());
            String left = rest.substring(0, best).trim();
            char last = left.charAt(left.length() - 1);
            boolean clean = last == ',' || last == ';' || last == ':';   // coupure propre : pas de points de suspension
            parts.add(clean ? left : left + "...");
            rest = rest.substring(best).trim();
            if (!clean && !rest.isEmpty()) rest = "..." + rest;
            if (rest.length() <= 3) break;
        }
        if (!rest.isEmpty() && rest.length() > 3) parts.add(rest);
        return parts;
    }

    /**
     * Decoupe une replique en bulles de maxLines lignes au plus, SANS jamais couper au milieu d'une phrase
     * (on regroupe des phrases entieres ; une phrase trop longue est coupee a une virgule).
     */
    static List<String> splitBubbles(String raw, int lineChars, int maxLines) {
        String text = clean(raw == null ? "" : raw).trim();
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) return out;
        String current = "";
        for (String s : sentences(text)) {
            String candidate = current.isEmpty() ? s : current + " " + s;
            if (wrap(candidate, lineChars).size() <= maxLines) {
                current = candidate;
                continue;
            }
            if (!current.isEmpty()) {
                out.add(current);
                current = "";
            }
            if (wrap(s, lineChars).size() <= maxLines) {
                current = s;
            } else {
                out.addAll(splitLongSentence(s, lineChars, maxLines));
            }
        }
        if (!current.isEmpty()) out.add(current);
        return out;
    }

    /** Pad de tete qui cale une rangee de largeur TOTAL a "margin" pixels du bord gauche d'un ecran de guiWidth pixels. */
    private static int leadPad(int guiWidth, int margin) {
        int lead = 2 * margin + TOTAL - guiWidth;
        if (((lead + TOTAL) & 1) != 0) lead -= 1; // largeur totale paire : pas de demi-pixel
        return lead;
    }

    private static Component text(String t, TextColor color, Key font, boolean bold) {
        Component c = Component.text(t, color).font(font);
        return bold ? c.decoration(TextDecoration.BOLD, true) : c;
    }

    // Une rangee (= une barre de boss de 19 px) : panneau + DEUX lignes de texte (haut et bas)
    private static Component textRow(char panel, char portrait, String upper, boolean upperBold, TextColor upperColor,
                                     String lower, int lead) {
        Component row = Component.empty();
        row = row.append(pad(lead));
        row = portrait != 0
                ? row.append(glyph(portrait)).append(pad(PORTRAIT - (PORTRAIT_IMG + 1)))
                : row.append(pad(PORTRAIT));
        row = row.append(glyph(panel));
        int start = PORTRAIT + TEXT_X;
        row = row.append(pad(start - TOTAL));
        int wu = width(upper, upperBold);
        int wl = width(lower, false);
        if (!upper.isEmpty()) row = row.append(text(upper, upperColor, FONT_U, upperBold));
        row = row.append(pad(-wu));                      // on revient au debut de la rangee pour la ligne du bas
        if (!lower.isEmpty()) row = row.append(text(lower, NamedTextColor.WHITE, FONT_L, false));
        return row.append(pad(TOTAL - (start + wl)));
    }

    /**
     * Une barre de boss par rangee de 19 px ; chaque rangee contient deux lignes : la premiere ligne est le NOM du
     * professeur, les suivantes son texte.
     * revealed : nombre de caracteres deja "ecrits" (effet machine a ecrire) ; -1 = tout.
     */
    static List<Component> bubble(String name, List<String> lines, int revealed, int frame, int guiWidth, int margin) {
        int lead = leadPad(guiWidth, margin);
        List<String> slots = new ArrayList<>();
        slots.add(clean(name));
        int left = revealed < 0 ? Integer.MAX_VALUE : revealed;
        for (String line : lines) {
            slots.add(line.length() <= left ? line : line.substring(0, Math.max(0, left)));
            left = Math.max(0, left - line.length());
        }
        int bars = (slots.size() + 1) / 2;
        List<Component> rows = new ArrayList<>();
        for (int b = 0; b < bars; b++) {
            char panel = bars == 1 ? BUB_SINGLE : b == 0 ? BUB_TOP : b == bars - 1 ? BUB_BOT : BUB_MID;
            String up = slots.get(2 * b);
            String lo = 2 * b + 1 < slots.size() ? slots.get(2 * b + 1) : "";
            char portrait = b == 0 ? (char) (PORTRAIT_GLYPH + Math.max(0, Math.min(2, frame))) : (char) 0;
            rows.add(textRow(panel, portrait, up, b == 0, TextColor.color(0x55FFFF), lo, lead));
        }
        return rows;
    }

    /** Pour les tests : liste nom -> code. */
    static Map<String, Integer> codes() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Glyph g : glyphs()) m.put(g.file(), (int) g.ch());
        return m;
    }
}
