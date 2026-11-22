package fr.uhc;

import org.bukkit.Sound;

import java.util.List;
import java.util.Locale;

/**
 * Les voix du professeur. Chaque voix est un petit bruit repete pendant qu'il parle.
 * - Voix du jeu : "sound" = un son de Minecraft (pack = null).
 * - Voix du pack (v4.1) : "pack" = prefixe des fichiers du pack (ex : v_humain -> v_humain1.ogg ... v_humain4.ogg), "variants" fichiers ;
 *   "sound" = son du jeu de remplacement pour un joueur qui n'a pas accepte le pack.
 * "every" = un bruit toutes les N tranches de 3 lettres ; 0 = muet.
 * Chaque joueur peut choisir sa voix ; sinon c'est la voix par defaut du serveur (teacher.voice / menu Reglages).
 */
final class Voices {

    record Voice(String id, String label, Sound sound, String pack, int variants, int every, float volume, float pitch,
                 float range) {
    }

    static final List<Voice> ALL = List.of(
            new Voice("villager", "Villageois", Sound.ENTITY_VILLAGER_AMBIENT, null, 0, 5, 0.8f, 0.8f, 0.45f),
            new Voice("blip", "Petit bip", Sound.BLOCK_NOTE_BLOCK_BIT, null, 0, 1, 0.6f, 0.9f, 0.7f),
            new Voice("talk", "Synthèse (pack)", Sound.BLOCK_NOTE_BLOCK_BIT, "talk", 4, 1, 1.0f, 0.88f, 0.34f),
            new Voice("cochon", "Cochon", Sound.ENTITY_PIG_AMBIENT, null, 0, 5, 0.8f, 0.8f, 0.6f),
            new Voice("chat", "Chat", Sound.ENTITY_CAT_AMBIENT, null, 0, 6, 0.8f, 0.9f, 0.5f),
            new Voice("poule", "Poule", Sound.ENTITY_CHICKEN_AMBIENT, null, 0, 3, 0.7f, 0.8f, 0.6f),
            new Voice("loup", "Loup", Sound.ENTITY_WOLF_AMBIENT, null, 0, 7, 0.8f, 0.9f, 0.4f),
            new Voice("perroquet", "Perroquet", Sound.ENTITY_PARROT_AMBIENT, null, 0, 4, 0.8f, 0.9f, 0.5f),
            new Voice("zombie", "Zombie", Sound.ENTITY_ZOMBIE_AMBIENT, null, 0, 6, 0.7f, 0.7f, 0.5f),
            new Voice("piglin", "Piglin", Sound.ENTITY_PIGLIN_AMBIENT, null, 0, 6, 0.8f, 0.8f, 0.5f),
            new Voice("enderman", "Enderman", Sound.ENTITY_ENDERMAN_AMBIENT, null, 0, 8, 0.6f, 0.9f, 0.5f),
            new Voice("chauve-souris", "Chauve-souris", Sound.ENTITY_BAT_AMBIENT, null, 0, 4, 0.6f, 0.9f, 0.6f),
            new Voice("golem", "Golem de fer", Sound.ENTITY_IRON_GOLEM_HURT, null, 0, 7, 0.7f, 0.6f, 0.4f),
            new Voice("cloche", "Cloche", Sound.BLOCK_NOTE_BLOCK_BELL, null, 0, 2, 0.7f, 0.7f, 0.9f),
            new Voice("flute", "Flûte", Sound.BLOCK_NOTE_BLOCK_FLUTE, null, 0, 2, 0.8f, 0.7f, 0.9f),
            new Voice("xylophone", "Xylophone", Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, null, 0, 2, 0.8f, 0.7f, 0.9f),
            new Voice("robot", "Robot", Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE, null, 0, 2, 0.7f, 0.6f, 0.8f),
            new Voice("basse", "Basse", Sound.BLOCK_NOTE_BLOCK_BASS, null, 0, 2, 1.0f, 0.7f, 0.6f),
            // ----- voix du pack (fichiers .ogg generes, dans pack/sounds) -----
            new Voice("humain", "Humain (babillage)", Sound.ENTITY_VILLAGER_AMBIENT, "v_humain", 4, 2, 1.0f, 0.95f, 0.1f),
            new Voice("aigu", "Aigu (enfant)", Sound.ENTITY_VILLAGER_AMBIENT, "v_aigu", 4, 2, 1.0f, 0.95f, 0.1f),
            new Voice("grave", "Grave (ogre)", Sound.ENTITY_VILLAGER_AMBIENT, "v_grave", 4, 3, 1.0f, 0.95f, 0.1f),
            new Voice("gnome", "Gnome (très aigu)", Sound.ENTITY_VILLAGER_AMBIENT, "v_gnome", 4, 2, 0.6f, 0.95f, 0.1f),
            new Voice("androide", "Androïde", Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE, "v_androide", 4, 2, 1.0f, 0.95f, 0.1f),
            new Voice("alien", "Alien", Sound.ENTITY_ENDERMAN_AMBIENT, "v_alien", 4, 3, 0.75f, 0.95f, 0.1f),
            new Voice("fantome", "Fantôme", Sound.ENTITY_VILLAGER_AMBIENT, "v_fantome", 4, 4, 1.0f, 0.95f, 0.1f),
            new Voice("retro", "Rétro (8 bits)", Sound.BLOCK_NOTE_BLOCK_BIT, "v_retro", 4, 1, 0.4f, 0.95f, 0.1f),
            new Voice("perso", "Perso (tes fichiers .ogg)", Sound.BLOCK_NOTE_BLOCK_BIT, "v_perso", 4, 2, 1.0f, 0.95f, 0.1f),
            new Voice("muet", "Muet", null, null, 0, 0, 0.0f, 1.0f, 0.0f)
    );

    /** Noms des sons du pack utilises par les voix : v_humain1, v_humain2, ... (sans "talk", deja connu, ni "perso"). */
    static List<String> packSounds() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (Voice v : ALL) {
            if (v.pack() == null || v.pack().equals("talk") || v.pack().equals(PERSO)) continue;
            for (int i = 1; i <= v.variants(); i++) out.add(v.pack() + i);
        }
        return out;
    }

    static final String PERSO = "v_perso";

    /** Vrai si l'op a depose v_perso1.ogg dans plugins/UhcSharedHealth/sounds/ (mis a jour par HeadPack). */
    static volatile boolean persoReady = false;

    /** Voix proposables aux joueurs (la voix "perso" seulement si les fichiers existent). */
    static List<Voice> selectable(boolean withMute) {
        java.util.ArrayList<Voice> out = new java.util.ArrayList<>();
        for (Voice v : ALL) {
            if (v.id().equals("muet") && !withMute) continue;
            if (v.id().equals("perso") && !persoReady) continue;
            out.add(v);
        }
        return out;
    }

    private Voices() {
    }

    /** La voix demandee (villageois si l'identifiant est inconnu ; "off" = muet pour les anciennes sauvegardes). */
    static Voice get(String id) {
        String k = id == null ? "" : id.toLowerCase(Locale.ROOT);
        if (k.equals("off")) k = "muet";
        for (Voice v : ALL) {
            if (v.id().equals(k)) return v;
        }
        return ALL.get(0);
    }

    static boolean has(String id) {
        if (id == null) return false;
        String k = id.toLowerCase(Locale.ROOT);
        for (Voice v : ALL) {
            if (v.id().equals(k)) return true;
        }
        return false;
    }

    /** Liste des identifiants separes par des virgules (sans "muet" si withMute est faux). */
    static String names(boolean withMute) {
        StringBuilder sb = new StringBuilder();
        for (Voice v : selectable(withMute)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(v.id());
        }
        return sb.toString();
    }
}
