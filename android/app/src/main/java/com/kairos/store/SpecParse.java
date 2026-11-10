package com.kairos.store;

import java.util.Locale;

/**
 * Pezzi della scheda del dispositivo («App consigliata», 11 novembre 2026) senza Android: la provano i test JVM.
 */
final class SpecParse {
    private SpecParse() { }

    /** testo pulito: niente caratteri di controllo, senza spazi ai lati */
    static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
    }

    private static boolean none(String s) {
        return s.isEmpty() || s.equalsIgnoreCase("unknown");
    }

    /** produttore e modello del chip («Mediatek» + «MT8695» → «Mediatek MT8695»; senza ripetere il produttore) */
    static String join(String maker, String model) {
        String a = clean(maker), b = clean(model);
        if (none(b)) return none(a) ? "" : a;
        if (none(a) || b.toLowerCase(Locale.ROOT).startsWith(a.toLowerCase(Locale.ROOT))) return b;
        return a + " " + b;
    }

    /** nome del chip da /proc/cpuinfo («Hardware : Amlogic S905X3»); "" se non c'è */
    static String socFromCpuinfo(String txt) {
        if (txt == null) return "";
        for (String line : txt.split("\n")) {
            int i = line.indexOf(':');
            if (i < 0) continue;
            if (line.substring(0, i).trim().equalsIgnoreCase("hardware")) {
                String v = clean(line.substring(i + 1));
                if (!none(v)) return v;
            }
        }
        return "";
    }

    /** numero da un file del sistema (es. la frequenza massima in kHz); 0 se non si legge */
    static long num(String s) {
        try { return Long.parseLong(s == null ? "" : s.trim()); } catch (Exception e) { return 0; }
    }

    /** kHz → GHz con un decimale (1704000 → 1.7) */
    static double ghz(long khz) {
        return khz <= 0 ? 0 : Math.round(khz / 100000.0) / 10.0;
    }

    /** decoder software (di Google/Android): non contano per H.265 e 4K */
    static boolean softCodec(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.endsWith(".sw");
    }
}
