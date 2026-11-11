package com.kairos.store;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * «Solo APK firmati da te» (11 novembre 2026): un'app Kairos si installa dallo store solo se l'APK scaricato è firmato con
 * la chiave di Kairos. Qui c'è l'impronta SHA-256 del certificato di firma di ogni app Kairos, letta dalle release
 * pubblicate (uguale nelle versioni precedenti). Le app degli altri (OrbixPlay, IboPro…) non hanno un'impronta qui e non si
 * controllano. Un APK con il pacchetto di un'app Kairos ma un'altra firma è un APK sostituito o falso: non si installa.
 *
 * Se una chiave cambia, prima va aggiornato lo store con l'impronta nuova, altrimenti rifiuta le versioni nuove di quell'app.
 * Logica pura, senza Android: la provano i test JVM.
 */
final class KairosSign {
    private KairosSign() { }

    /** stessa chiave per Kairos TV e Kairos XC */
    private static final String K_TV = "2B71C7CB75A33E72BCD7AA4AD33647CD56B210EC9988C7635CD80C26FC438986";

    /** pacchetto → impronte SHA-256 ammesse (maiuscole, senza «:») */
    static final Map<String, String[]> PINS;
    static {
        Map<String, String[]> m = new HashMap<>();
        m.put("com.kairos.player", new String[]{"75B75AD7B3D4F0801B55BBE937C08C45109C305C418BF155313A33DB01A2309B"}); // Kairos Pro
        m.put("com.kairos.tv", new String[]{K_TV});
        m.put("com.kairos.xc", new String[]{K_TV});
        m.put("com.kairos.premium", new String[]{"27B35467C356786C0A56575B7A8D84554F744D9DC66911175F11191B76809F49"});
        m.put("com.kairos.lite", new String[]{"8EBFCA13187125FF9287642A701F878F79D805F433F063BBFB1522AE957A4038"});
        m.put("com.kairos.plus", new String[]{"B8924B44B889788D5CE0A7D0D1B831BE18AC89B7B2A9325D97BAEFFBBF808BB0"});
        m.put("com.kairos.nova", new String[]{"7AED4961F7D88C3E3980621020BBBDFC4F6354C53F2D810D565330FACFE2AD3B"});
        m.put("com.kairos.store", new String[]{"CDAA7695421CDEB84D9DE803F124B8EC0231E2667C731CD0528CE5A4A3465CCE"});
        PINS = Collections.unmodifiableMap(m);
    }

    static final String MSG = "Installazione bloccata: questo file non è firmato da Kairos (potrebbe essere stato sostituito). "
            + "Riprova più tardi o avvisa il tuo rivenditore.";

    /**
     * null se l'APK si può installare, altrimenti il messaggio per il cliente. [pkg] = pacchetto dell'APK, [certs] = impronte
     * SHA-256 dei firmatari. Firma che Android non è riuscito a leggere (elenco vuoto): decide l'installazione di Android.
     */
    static String check(String pkg, List<String> certs) {
        if (pkg == null) return null;
        String[] pin = PINS.get(pkg);
        if (pin == null) return null;                       // app degli altri: niente da controllare
        if (certs == null || certs.isEmpty()) return null;
        for (String c : certs) {                            // ogni firmatario deve essere Kairos
            String x = c == null ? "" : c.replace(":", "").trim().toUpperCase(Locale.ROOT);
            boolean ok = false;
            for (String p : pin) if (p.equals(x)) { ok = true; break; }
            if (!ok) return MSG;
        }
        return null;
    }

    /** impronta SHA-256 (maiuscole, senza separatori) di un certificato in DER */
    static String sha256(byte[] der) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(der);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02X", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
