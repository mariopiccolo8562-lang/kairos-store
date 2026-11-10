package com.kairos.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** Store: store-k.download, poi le riserve; niente pannello né GitHub. Si passa alla base dopo solo se quella prima fallisce */
public class StoreLinksTest {
    private static final String DL = "https://store-k.download";
    private static final String RES = "https://riserva-k.download";
    private static final String RES2 = "https://riserva2-k.download";
    private static final String PAGES = "https://mariopiccolo8562-lang.github.io/kairos-store/";
    private final List<String> bases = StoreLinks.bases(DL, Arrays.asList("", RES, RES2));

    @Test
    public void basiInOrdineSenzaDoppioni() {
        assertEquals(Arrays.asList(DL, RES, RES2), bases);
        assertEquals(Collections.singletonList(DL), StoreLinks.bases(DL + "/", Collections.singletonList("")));
        assertEquals(Collections.singletonList(DL), StoreLinks.bases(DL, Arrays.asList(DL + "/", null)));
        // solo https e solo il dominio: http, percorsi e indirizzi senza schema si scartano
        assertEquals(Collections.singletonList(RES), StoreLinks.bases("http://store-k.download", Arrays.asList(RES + "/x", "riserva-k.download", RES)));
        assertEquals(Collections.emptyList(), StoreLinks.bases("", (List<String>) null));
        // al massimo 4 basi
        assertEquals(Arrays.asList(DL, "https://a-k.download", "https://b-k.download", "https://c-k.download"),
                StoreLinks.bases(DL, Arrays.asList("https://a-k.download", "https://b-k.download", "https://c-k.download", "https://d-k.download")));
        // niente pannello né GitHub
        String all = String.valueOf(StoreLinks.feeds(StoreLinks.bases(DL, Collections.singletonList(""))));
        assertFalse(all.contains("panel-kairos.com"));
        assertFalse(all.contains("github.com"));
    }

    @Test
    public void riserveAnnunciateDalFile() {
        assertEquals(Arrays.asList(DL, RES, "https://a-k.download"),
                StoreLinks.mirrors(Arrays.asList(DL, RES + "/", "http://x-k.download", RES, null, "https://a-k.download", "https://b-k.download")));
        assertEquals(Collections.emptyList(), StoreLinks.mirrors(Collections.emptyList()));
        assertEquals(Collections.emptyList(), StoreLinks.mirrors(null));
    }

    @Test
    public void pagineDelloStore() {
        assertEquals(Arrays.asList(DL + "/store/", RES + "/store/", RES2 + "/store/", PAGES), StoreLinks.pages(bases, PAGES));
        List<String> soloDl = StoreLinks.bases(DL, Collections.singletonList(""));
        assertEquals(Arrays.asList(DL + "/store/", PAGES), StoreLinks.pages(soloDl, PAGES));
        assertEquals(Collections.singletonList(DL + "/store/"), StoreLinks.pages(soloDl, ""));
        // indirizzo online uguale a una base (variabile STORE_URL): il doppione si salta
        assertEquals(Collections.singletonList(DL + "/store/"), StoreLinks.pages(soloDl, DL + "/store/"));
    }

    @Test
    public void fileDellAggiornamento() {
        assertEquals(Arrays.asList(DL + "/get/store.json", RES + "/get/store.json", RES2 + "/get/store.json"), StoreLinks.feeds(bases));
    }

    @Test
    public void apkDelloStoreDalLinkDelFilePoiBasiSuccessive() {
        assertEquals(Arrays.asList(DL + "/get/store.apk", RES + "/get/store.apk", RES2 + "/get/store.apk"), StoreLinks.apkUrls(DL + "/get/store.apk", bases));
        // file dalla riserva (dominio dei download giù): il dominio dei download non si riprova
        assertEquals(Arrays.asList(RES + "/get/store.apk", RES2 + "/get/store.apk"), StoreLinks.apkUrls(RES + "/get/store.apk", bases));
        // senza riserve: solo il link del file
        assertEquals(Collections.singletonList(DL + "/get/store.apk"),
                StoreLinks.apkUrls(DL + "/get/store.apk", StoreLinks.bases(DL, Collections.singletonList(""))));
    }

    @Test
    public void appDalloStore() {
        // app Kairos dalla pagina servita da store-k.download: se non riesce, lo stesso link sulle riserve
        assertEquals(Arrays.asList(DL + "/get/tv.apk", RES + "/get/tv.apk", RES2 + "/get/tv.apk"), StoreLinks.apkUrls(DL + "/get/tv.apk", bases));
        // un link del pannello non è una base: resta solo (la pagina dello store non li dà più)
        assertEquals(Collections.singletonList("https://panel-kairos.com/get/tv.apk"), StoreLinks.apkUrls("https://panel-kairos.com/get/tv.apk", bases));
        // app degli altri e codici Downloader: nessun altro tentativo
        assertEquals(Collections.singletonList("https://apk.orbixplay.com/firestick.apk"), StoreLinks.apkUrls("https://apk.orbixplay.com/firestick.apk", bases));
        assertEquals(Collections.singletonList("https://aftv.news/618101"), StoreLinks.apkUrls("https://aftv.news/618101", bases));
        // la pagina dello store non è un APK da riprovare altrove
        assertEquals(Collections.singletonList(DL + "/store/x.apk"), StoreLinks.apkUrls(DL + "/store/x.apk", bases));
    }

    @Test
    public void baseDiUnLink() {
        assertEquals(0, StoreLinks.baseOf(DL + "/store/?t=1", bases));
        assertEquals(1, StoreLinks.baseOf(RES + "/get/store.json", bases));
        assertEquals(-1, StoreLinks.baseOf(PAGES, bases));
        assertEquals(-1, StoreLinks.baseOf("https://store-k.downloads.example/x", bases));
        assertEquals(-1, StoreLinks.baseOf("https://panel-kairos.com/get/store.json", bases));
    }

    @Test
    public void numeroDellaVersione() {
        assertEquals(131, StoreLinks.build("1.0.131"));
        assertEquals(131, StoreLinks.build("v1.0.131"));
        assertEquals(12, StoreLinks.build("v1.0.12"));
        assertEquals(0, StoreLinks.build(""));
        assertEquals(0, StoreLinks.build(null));
        assertEquals(0, StoreLinks.build("1.0.99999999999"));
    }
}
