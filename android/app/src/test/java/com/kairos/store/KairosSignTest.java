package com.kairos.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** «Solo APK firmati da te»: le app Kairos solo con la firma di Kairos; le app degli altri senza controlli */
public class KairosSignTest {
    private static final String PRO = "75B75AD7B3D4F0801B55BBE937C08C45109C305C418BF155313A33DB01A2309B";
    private static final String TV = "2B71C7CB75A33E72BCD7AA4AD33647CD56B210EC9988C7635CD80C26FC438986";
    private static final String ALTRA = "00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF";

    @Test
    public void appKairosConLaSuaFirma() {
        assertNull(KairosSign.check("com.kairos.player", Collections.singletonList(PRO)));
        assertNull(KairosSign.check("com.kairos.tv", Collections.singletonList(TV)));
        assertNull(KairosSign.check("com.kairos.xc", Collections.singletonList(TV)));        // stessa chiave di TV
        // come la scrive keytool: con i due punti e in minuscolo
        assertNull(KairosSign.check("com.kairos.premium", Collections.singletonList("27:b3:54:67:c3:56:78:6c:0a:56:57:5b:7a:8d:84:55:4f:74:4d:9d:c6:69:11:17:5f:11:19:1b:76:80:9f:49")));
    }

    @Test
    public void appKairosConUnAltraFirma() {
        assertEquals(KairosSign.MSG, KairosSign.check("com.kairos.player", Collections.singletonList(ALTRA)));
        assertEquals(KairosSign.MSG, KairosSign.check("com.kairos.premium", Collections.singletonList(TV)));   // chiave di un'altra app Kairos
        assertEquals(KairosSign.MSG, KairosSign.check("com.kairos.store", Arrays.asList("CDAA7695421CDEB84D9DE803F124B8EC0231E2667C731CD0528CE5A4A3465CCE", ALTRA))); // due firmatari: tutti Kairos
        assertEquals(KairosSign.MSG, KairosSign.check("com.kairos.nova", Collections.singletonList("")));
    }

    @Test
    public void appDegliAltriEFirmaIlleggibile() {
        assertNull(KairosSign.check("com.orbixplay.atv", Collections.singletonList(ALTRA)));
        assertNull(KairosSign.check("com.kairos.nuova", Collections.singletonList(ALTRA))); // pacchetto Kairos non in elenco
        assertNull(KairosSign.check(null, Collections.singletonList(ALTRA)));
        assertNull(KairosSign.check("com.kairos.player", Collections.<String>emptyList())); // Android non l'ha letta: decide lui
        assertNull(KairosSign.check("com.kairos.player", null));
    }

    @Test
    public void tutteLeAppKairosHannoUnImpronta() {
        assertEquals(8, KairosSign.PINS.size());
        for (String[] p : KairosSign.PINS.values()) for (String x : p) assertEquals(64, x.replaceAll("[^0-9A-F]", "").length());
    }

    @Test
    public void improntaSha256() {
        // SHA-256 di «abc» (vettore di prova noto)
        assertEquals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", KairosSign.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }
}
