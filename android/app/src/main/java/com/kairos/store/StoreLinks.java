package com.kairos.store;

import java.util.ArrayList;
import java.util.List;

/**
 * Indirizzi da cui lo store prende la sua pagina, il suo aggiornamento e gli APK (logica pura, senza Android: la provano i
 * test JVM).
 *
 * Basi, in ordine: 1) l'indirizzo dei download {@link MainActivity#DL_BASE} (store-k.download), il dominio dedicato;
 * 2) il pannello {@link MainActivity#PANEL} (si salta se è uguale); 3) la riserva di oggi su GitHub (GitHub Pages per la
 * pagina, l'ultima release per l'aggiornamento), che con il repository privato fallirà senza danni. Si passa alla base
 * successiva solo se quella prima non risponde, risponde con un errore o con un file non valido.
 */
final class StoreLinks {
    private StoreLinks() { }

    /** basi https, in ordine e senza doppioni (senza la barra finale) */
    static List<String> bases(String... list) {
        ArrayList<String> out = new ArrayList<>();
        for (String b : list) {
            if (b == null) continue;
            String s = b.trim();
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            if (s.startsWith("https://") && !out.contains(s)) out.add(s);
        }
        return out;
    }

    /** pagine dello store da provare: {@code <base>/store/} per ogni base, poi [ghPages] (GitHub Pages, se c'è) */
    static List<String> pages(List<String> bases, String ghPages) {
        ArrayList<String> out = new ArrayList<>();
        for (String b : bases) add(out, b + "/store/");
        String g = ghPages == null ? "" : ghPages.trim();
        if (g.startsWith("https://") || g.startsWith("http://")) add(out, g);
        return out;
    }

    /** file dell'aggiornamento dello store: {@code <base>/get/store.json} per ogni base */
    static List<String> feeds(List<String> bases) {
        ArrayList<String> out = new ArrayList<>();
        for (String b : bases) add(out, b + "/get/store.json");
        return out;
    }

    /** indice della base di [url] tra [bases]; -1 se il link non sta su nessuna */
    static int baseOf(String url, List<String> bases) {
        if (url == null) return -1;
        for (int i = 0; i < bases.size(); i++) if (url.startsWith(bases.get(i) + "/")) return i;
        return -1;
    }

    /**
     * Da dove scaricare un APK, in ordine: [url]; se è un link {@code /get/...} di una base, lo stesso percorso sulle basi
     * successive; alla fine [ghApk] (vuoto = nessuno). I link delle app degli altri (fuori dalle basi) restano soli.
     */
    static List<String> apkUrls(String url, List<String> bases, String ghApk) {
        ArrayList<String> out = new ArrayList<>();
        if (url != null && !url.trim().isEmpty()) out.add(url.trim());
        int at = baseOf(url, bases);
        if (at >= 0) {
            String path = url.trim().substring(bases.get(at).length());
            if (path.startsWith("/get/")) for (int i = at + 1; i < bases.size(); i++) add(out, bases.get(i) + path);
        }
        if (ghApk != null && ghApk.startsWith("https://")) add(out, ghApk);
        return out;
    }

    /** numero N della versione 1.0.N (es. «1.0.131» o il tag «v1.0.131» → 131); 0 se non c'è */
    static int build(String version) {
        if (version == null) return 0;
        String v = version.trim();
        String num = v.substring(v.lastIndexOf('.') + 1).replaceAll("[^0-9]", "");
        if (num.isEmpty() || num.length() > 9) return 0;
        return Integer.parseInt(num);
    }

    private static void add(List<String> out, String u) {
        if (!out.contains(u)) out.add(u);
    }
}
