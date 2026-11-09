package com.kairos.store;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** Store: store-k.download, poi il pannello, poi GitHub; si passa alla base dopo solo se quella prima fallisce */
public class StoreLinksTest {
    private static final String DL = "https://store-k.download";
    private static final String PANEL = "https://panel-kairos.com";
    private static final String PAGES = "https://mariopiccolo8562-lang.github.io/kairos-store/";
    private static final String GH_APK = "https://github.com/mariopiccolo8562-lang/kairos-store/releases/latest/download/KairosStore.apk";
    private final List<String> bases = StoreLinks.bases(DL, PANEL);

    @Test
    public void basiInOrdineSenzaDoppioni() {
        assertEquals(Arrays.asList(DL, PANEL), StoreLinks.bases(DL + "/", PANEL));
        assertEquals(Collections.singletonList(PANEL), StoreLinks.bases(PANEL, PANEL));
        assertEquals(Collections.singletonList(PANEL), StoreLinks.bases("http://store-k.download", PANEL, null, ""));
    }

    @Test
    public void pagineDelloStore() {
        assertEquals(Arrays.asList(DL + "/store/", PANEL + "/store/", PAGES), StoreLinks.pages(bases, PAGES));
        // indirizzo online uguale al pannello (variabile STORE_URL): il doppione si salta
        assertEquals(Arrays.asList(DL + "/store/", PANEL + "/store/"), StoreLinks.pages(bases, PANEL + "/store/"));
        assertEquals(Arrays.asList(DL + "/store/", PANEL + "/store/"), StoreLinks.pages(bases, ""));
    }

    @Test
    public void fileDellAggiornamento() {
        assertEquals(Arrays.asList(DL + "/get/store.json", PANEL + "/get/store.json"), StoreLinks.feeds(bases));
    }

    @Test
    public void apkDelloStoreDalLinkDelFilePoiBasiSuccessivePoiGitHub() {
        assertEquals(Arrays.asList(DL + "/get/store.apk", PANEL + "/get/store.apk", GH_APK), StoreLinks.apkUrls(DL + "/get/store.apk", bases, GH_APK));
        // file dal pannello (dominio dei download giù): il dominio dei download non si riprova
        assertEquals(Arrays.asList(PANEL + "/get/store.apk", GH_APK), StoreLinks.apkUrls(PANEL + "/get/store.apk", bases, GH_APK));
        assertEquals(Collections.singletonList(GH_APK), StoreLinks.apkUrls(GH_APK, bases, GH_APK));
    }

    @Test
    public void appDalloStore() {
        // app Kairos dalla pagina servita da store-k.download: se non riesce, lo stesso link sul pannello
        assertEquals(Arrays.asList(DL + "/get/tv.apk", PANEL + "/get/tv.apk"), StoreLinks.apkUrls(DL + "/get/tv.apk", bases, ""));
        assertEquals(Collections.singletonList(PANEL + "/get/tv.apk"), StoreLinks.apkUrls(PANEL + "/get/tv.apk", bases, ""));
        // app degli altri e codici Downloader: nessun altro tentativo
        assertEquals(Collections.singletonList("https://apk.orbixplay.com/firestick.apk"), StoreLinks.apkUrls("https://apk.orbixplay.com/firestick.apk", bases, ""));
        assertEquals(Collections.singletonList("https://aftv.news/618101"), StoreLinks.apkUrls("https://aftv.news/618101", bases, ""));
        // la pagina dello store non è un APK da riprovare altrove
        assertEquals(Collections.singletonList(DL + "/store/x.apk"), StoreLinks.apkUrls(DL + "/store/x.apk", bases, ""));
    }

    @Test
    public void baseDiUnLink() {
        assertEquals(0, StoreLinks.baseOf(DL + "/store/?t=1", bases));
        assertEquals(1, StoreLinks.baseOf(PANEL + "/get/store.json", bases));
        assertEquals(-1, StoreLinks.baseOf(PAGES, bases));
        assertEquals(-1, StoreLinks.baseOf("https://store-k.downloads.example/x", bases));
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
