package fr.uhc;

import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Partie "sans Bukkit" de la gestion des tetes : decoupe des visages, creation du pack
 * de textures (police avec une image par joueur) et mini serveur HTTP qui envoie le pack.
 */
final class PackBuilder {

    /** Premier caractere (zone privee Unicode) utilise pour les tetes. */
    static final int FIRST_GLYPH = 0xE000;
    static final int PACK_FORMAT = 46; // Minecraft 1.21.4

    private PackBuilder() {
    }

    // ---------- Skins ----------

    static BufferedImage readSkin(URL url) throws IOException {
        URLConnection c = url.openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream()) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) throw new IOException("image illisible");
            return img;
        }
    }

    /** Visage 8x8 (case 8,8) avec la 2e couche (cheveux, casque : case 40,8) par-dessus. */
    static BufferedImage face(BufferedImage skin) throws IOException {
        if (skin.getWidth() < 48 || skin.getHeight() < 16) {
            throw new IOException("skin trop petit (" + skin.getWidth() + "x" + skin.getHeight() + ")");
        }
        // Skins HD : on se ramene a l'echelle 64
        int scale = Math.max(1, skin.getWidth() / 64);
        BufferedImage out = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int base = skin.getRGB((8 + x) * scale, (8 + y) * scale);
                int hat = skin.getRGB((40 + x) * scale, (8 + y) * scale);
                int ha = hat >>> 24;
                int r;
                int g;
                int b;
                if (ha == 0) {
                    r = (base >> 16) & 255;
                    g = (base >> 8) & 255;
                    b = base & 255;
                } else {
                    r = (((base >> 16) & 255) * (255 - ha) + ((hat >> 16) & 255) * ha) / 255;
                    g = (((base >> 8) & 255) * (255 - ha) + ((hat >> 8) & 255) * ha) / 255;
                    b = ((base & 255) * (255 - ha) + (hat & 255) * ha) / 255;
                }
                out.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    // ---------- Pack ----------

    /**
     * Cree le zip du pack a partir de tous les fichiers "pseudo.png" du dossier heads.
     * Retourne l'index de chaque pseudo (en minuscules) : le caractere est FIRST_GLYPH + index.
     */
    static Map<String, Integer> buildPack(File headsDir, File zipFile, Map<String, byte[]> extra) throws IOException {
        File[] files = headsDir.listFiles((d, n) -> n.toLowerCase().endsWith(".png"));
        if (files == null) files = new File[0];
        Arrays.sort(files);

        Map<String, Integer> index = new LinkedHashMap<>();
        List<byte[]> images = new ArrayList<>();
        for (File f : files) {
            String name = f.getName().substring(0, f.getName().length() - 4).toLowerCase();
            index.put(name, images.size());
            images.add(Files.readAllBytes(f.toPath()));
        }

        StringBuilder json = new StringBuilder("{\"providers\":[");
        for (int i = 0; i < images.size(); i++) {
            if (i > 0) json.append(',');
            json.append("{\"type\":\"bitmap\",\"file\":\"uhc:font/head_").append(i).append(".png\"")
                    .append(",\"ascent\":7,\"height\":8,\"chars\":[\"\\u")
                    .append(String.format("%04X", FIRST_GLYPH + i)).append("\"]}");
        }
        json.append("]}");

        File parent = zipFile.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(zipFile.getPath() + ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp.toPath()))) {
            put(zip, "pack.mcmeta", ("{\"pack\":{\"pack_format\":" + PACK_FORMAT
                    + ",\"description\":\"UHC - tetes des joueurs\"}}").getBytes(StandardCharsets.UTF_8));
            put(zip, "assets/uhc/font/heads.json", json.toString().getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < images.size(); i++) {
                put(zip, "assets/uhc/textures/font/head_" + i + ".png", images.get(i));
            }
            // Fichiers supplementaires : sons, textures de barres, flash de degats...
            if (extra != null) {
                for (Map.Entry<String, byte[]> e : new java.util.TreeMap<>(extra).entrySet()) {
                    put(zip, e.getKey(), e.getValue());
                }
            }
        }
        Files.move(tmp.toPath(), zipFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return index;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(0L);
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }

    static byte[] sha1(File file) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file.toPath()));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IOException(ex);
        }
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ---------- Mini serveur HTTP ----------

    static final String ZIP_PATH = "/UhcHeads.zip";

    /** Envoie le zip du pack (relu a chaque requete, donc toujours a jour). */
    static HttpServer serve(int port, File zipFile) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(ZIP_PATH, exchange -> {
            try {
                if (!zipFile.isFile()) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] data = Files.readAllBytes(zipFile.toPath());
                exchange.getResponseHeaders().add("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(data);
                }
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "uhc-pack-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        return server;
    }
}
