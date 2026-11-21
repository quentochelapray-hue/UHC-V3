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
 */
final class Ui {
    static final Key FONT = Key.key("uhc", "ui");
    static final int ROW = 19;          // hauteur d'une barre de boss (pixels)
    static final int PANEL_W = 252;     // largeur du panneau de la bulle (le glyphe avance de PANEL_W + 1)
    static final int PORTRAIT = 52;     // place reservee au portrait, a gauche du panneau
    static final int TEXT_X = 8;        // marge du texte dans le panneau
    static final int OBJECTIVES = 10;

    static final char SLOT_IDLE = '\uE200', SLOT_DONE = '\uE201', SLOT_CUR = '\uE202';
    static final char ICON = '\uE210';
    static final char SKULL = '\uE230', STAR = '\uE231';
    static final char DIGIT = '\uE240', COLON = '\uE24A', DOT = '\uE24B', COMMA = '\uE24C';
    static final char BUB_TOP = '\uE250', BUB_MID = '\uE251', BUB_BOT = '\uE252';
    static final char PORTRAIT_GLYPH = '\uE260'; // 3 images : repos, bouche ouverte, tete haussee
    static final char PAD_POS = '\uE300', PAD_NEG = '\uE310';

    // Vraies textures du jeu (le pack les reference, il ne les copie pas)
    static final String[] ICON_TEXTURES = {
            "minecraft:block/cobblestone.png", "minecraft:item/iron_ingot.png", "minecraft:item/iron_pickaxe.png",
            "minecraft:item/diamond.png", "minecraft:block/obsidian.png", "minecraft:block/nether_bricks.png",
            "minecraft:item/blaze_rod.png", "minecraft:item/ender_eye.png", "minecraft:block/end_stone.png",
            "minecraft:item/end_crystal.png"};

    // Largeur d'avance de chaque icone (largeur visible + 1). 17 = texture pleine de 16 pixels de large.
    // Si une icone est decalee en jeu, change la valeur dans config.yml (pack.icon-advances).
    private static final int[] ICON_ADVANCE = {17, 17, 17, 17, 17, 17, 17, 17, 17, 17};

    static void setIconAdvances(List<Integer> values) {
        for (int i = 0; i < ICON_ADVANCE.length && values != null && i < values.size(); i++) {
            ICON_ADVANCE[i] = Math.max(8, Math.min(20, values.get(i)));
        }
    }

    private Ui() {
    }

    /** Glyphe : fichier, hauteur, ascent (decalage vertical). */
    record Glyph(String file, char ch, int height, int ascent) {
    }

    static List<Glyph> glyphs() {
        List<Glyph> g = new ArrayList<>();
        g.add(new Glyph("slot_idle", SLOT_IDLE, 19, 7));
        g.add(new Glyph("slot_done", SLOT_DONE, 19, 7));
        g.add(new Glyph("slot_cur", SLOT_CUR, 19, 7));
        for (int i = 0; i < OBJECTIVES; i++) {
            g.add(new Glyph(ICON_TEXTURES[i], (char) (ICON + i), 16, 5));
        }
        g.add(new Glyph("skull", SKULL, 9, 2));
        g.add(new Glyph("star", STAR, 9, 2));
        for (int i = 0; i < 10; i++) {
            g.add(new Glyph("d_" + i, (char) (DIGIT + i), 7, 1));
        }
        g.add(new Glyph("d_colon", COLON, 7, 1));
        g.add(new Glyph("d_dot", DOT, 7, 1));
        g.add(new Glyph("d_comma", COMMA, 7, 1));
        g.add(new Glyph("bub_top", BUB_TOP, 19, 14));
        g.add(new Glyph("bub_mid", BUB_MID, 19, 14));
        g.add(new Glyph("bub_bot", BUB_BOT, 19, 14));
        for (int f = 0; f < 3; f++) {
            g.add(new Glyph("portrait" + f, (char) (PORTRAIT_GLYPH + f), 48, 14));
        }
        return g;
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

    /** done[i] : objectif i fait ; current : index de l'objectif en cours (-1 si tout est fait). */
    static Component objectives(boolean[] done, int current, String clock, int deaths, String note, TextColor noteColor) {
        Component row = Component.empty();
        for (int i = 0; i < OBJECTIVES; i++) {
            boolean d = i < done.length && done[i];
            boolean cur = i == current;
            char slot = cur ? SLOT_CUR : d ? SLOT_DONE : SLOT_IDLE;
            // objectif pas encore fait : icone assombrie (couleur grise appliquee au glyphe)
            Component icon = Component.text(String.valueOf((char) (ICON + i)),
                    d || cur ? NamedTextColor.WHITE : TextColor.color(0x707070)).font(FONT);
            row = row.append(glyph(slot)).append(pad(-18)).append(icon).append(pad(20 - ICON_ADVANCE[i]));
        }
        row = row.append(pad(8)).append(digitComp(clock, TextColor.color(0x55FFFF)))
                .append(pad(8)).append(glyph(SKULL)).append(pad(3))
                .append(digitComp(String.valueOf(deaths), NamedTextColor.WHITE));
        if (note != null && !note.isEmpty()) {
            row = row.append(pad(9)).append(glyph(STAR)).append(pad(3)).append(digitComp(note, noteColor));
        }
        return row;
    }

    // ---------- bulle du professeur ----------

    /** Largeur approximative (pixels) d'un texte dans la police par defaut de Minecraft. */
    static int width(String s, boolean bold) {
        int w = 0;
        for (char c : s.toCharArray()) {
            int cw;
            switch (c) {
                case 'i', '!', ',', '.', ':', ';', '|', '\'' -> cw = 2;
                case 'l', '`', 'ï', 'î' -> cw = 3;
                case 'I', 't', ' ', '[', ']', '’' -> cw = 4;
                case 'f', 'k', '(', ')', '<', '>', '*', '"', '{', '}' -> cw = 5;
                case '@', '~' -> cw = 7;
                default -> cw = 6;
            }
            // cw = largeur du glyphe + 1 (espacement) ; le gras ajoute 1 pixel
            w += cw + (bold ? 1 : 0);
        }
        return w;
    }

    static List<String> wrap(String text, int maxChars) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
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

    private static Component textRow(char panel, char portrait, String text, boolean bold, TextColor color) {
        Component row = Component.empty();
        row = portrait != 0 ? row.append(glyph(portrait)).append(pad(PORTRAIT - 49)) : row.append(pad(PORTRAIT));
        row = row.append(glyph(panel));
        int total = PORTRAIT + PANEL_W + 1;
        int start = PORTRAIT + TEXT_X;
        int w = width(text, bold);
        Component t = Component.text(text, color);
        if (bold) t = t.decoration(TextDecoration.BOLD, true);
        row = row.append(pad(start - total)).append(t).append(pad(total - (start + w)));
        return row;
    }

    /** Une barre de boss par rangee : nom du professeur, puis 1 ou 2 lignes de texte. */
    static List<Component> bubble(String name, String text, int lineChars, int frame) {
        List<String> lines = wrap(text, lineChars);
        for (int n = lineChars + 1; lines.size() > 2 && n <= 40; n++) {
            lines = wrap(text, n);
        }
        if (lines.size() > 2) {
            lines = new ArrayList<>(lines.subList(0, 2));
            lines.set(1, lines.get(1) + "…");
        }
        if (lines.isEmpty()) lines = List.of("...");
        List<Component> rows = new ArrayList<>();
        rows.add(textRow(BUB_TOP, (char) (PORTRAIT_GLYPH + Math.max(0, Math.min(2, frame))), name, true, TextColor.color(0x55FFFF)));
        for (int i = 0; i < lines.size(); i++) {
            boolean last = i == lines.size() - 1;
            rows.add(textRow(last ? BUB_BOT : BUB_MID, (char) 0, lines.get(i), false, NamedTextColor.WHITE));
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
