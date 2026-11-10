package com.kairos.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Indirizzi da cui lo store prende la sua pagina, il suo aggiornamento e gli APK (logica pura, senza Android: la provano i
 * test JVM).
 *
 * Basi, in ordine (dal 10 novembre 2026 niente più pannello né GitHub negli aggiornamenti e nei download):
 * 1) l'indirizzo dei download {@link MainActivity#DL_BASE} (store-k.download), il dominio dedicato;
 * 2) le riserve, con gli stessi percorsi /store/ e /get/...: BuildConfig.DL_BASE2 (variabile KAIROS_DL_BASE2 del
 *    repository, vuota finché non c'è un secondo dominio) e i domini che il file dell'aggiornamento annuncia in «mirrors»
 *    (lo store li ricorda e li usa dall'apertura dopo: un dominio di riserva comprato dopo arriva anche agli store già
 *    installati, senza una versione nuova).
 * Per la pagina, dopo le basi: GitHub Pages (variabile STORE_URL, se c'è) e la copia dentro l'APK. Si passa alla base
 * successiva solo se quella prima non risponde, risponde con un errore o con un file non valido.
 */
final class StoreLinks {
    private StoreLinks() { }

    /** base valida: solo https e solo il dominio (porta facoltativa), senza percorso */
    private static final Pattern BASE = Pattern.compile("^https://[a-z0-9.-]+\\.[a-z]{2,}(:\\d{1,5})?$", Pattern.CASE_INSENSITIVE);

    /** basi https valide, in ordine e senza doppioni (senza la barra finale); al massimo 4 */
    static List<String> bases(String... list) {
        return valid(list == null ? null : Arrays.asList(list), 4);
    }

    /** basi: [first] (il dominio dei download), poi le riserve [extra]; al massimo 4 */
    static List<String> bases(String first, List<String> extra) {
        ArrayList<String> all = new ArrayList<>();
        all.add(first);
        if (extra != null) all.addAll(extra);
        return valid(all, 4);
    }

    /** domini di riserva annunciati dal file dell'aggiornamento («mirrors»): solo https validi, senza doppioni, al massimo 3 */
    static List<String> mirrors(List<String> list) {
        return valid(list, 3);
    }

    private static List<String> valid(List<String> list, int max) {
        ArrayList<String> out = new ArrayList<>();
        if (list == null) return out;
        for (String b : list) {
            if (b == null) continue;
            String s = b.trim();
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            if (BASE.matcher(s).matches() && !out.contains(s)) out.add(s);
            if (out.size() >= max) break;
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
     * successive. I link delle app degli altri (fuori dalle basi) restano soli.
     */
    static List<String> apkUrls(String url, List<String> bases) {
        ArrayList<String> out = new ArrayList<>();
        if (url != null && !url.trim().isEmpty()) out.add(url.trim());
        int at = baseOf(url, bases);
        if (at >= 0) {
            String path = url.trim().substring(bases.get(at).length());
            if (path.startsWith("/get/")) for (int i = at + 1; i < bases.size(); i++) add(out, bases.get(i) + path);
        }
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
