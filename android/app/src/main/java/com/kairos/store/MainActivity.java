package com.kairos.store;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Kairos Store: mostra la pagina dello store e scarica/installa gli APK
 * (link Downloader aftv.news/CODICE o link diretti .apk).
 * Store APERTO (come IBO Player): nessun codice del venditore e nessuna autorizzazione,
 * «Installa» scarica subito. Le versioni precedenti ricordavano il server delle autorizzazioni
 * e bloccavano i download: questa versione lo dimentica all'avvio.
 */
public class MainActivity extends Activity {

    private static final int REQ_UNKNOWN_SOURCES = 42;
    private static final String LOCAL_PAGE = "file:///android_asset/index.html";

    // ---- indirizzi dei download: pagina dello store, aggiornamento dello store, APK delle app (vedi StoreLinks) ----
    /** indirizzo dei download, il dominio dedicato: l'unico da cambiare */
    static final String DL_BASE = "https://store-k.download";
    /** domini di riserva annunciati dal file dell'aggiornamento («mirrors», vedi StoreLinks), uno per riga */
    private static final String PREF_MIRRORS = "dl_mirrors";
    /** basi dei download: DL_BASE, poi le riserve (BuildConfig.DL_BASE2 e i domini annunciati dal file dell'aggiornamento,
     *  quelli ricordati all'apertura); niente pannello né GitHub. Si fissano in onCreate */
    private volatile List<String> bases = StoreLinks.bases(DL_BASE, BuildConfig.DL_BASE2);
    /** pagine dello store in ordine: le basi, GitHub Pages (storeUrl), poi la copia nell'APK */
    private volatile List<String> pages = StoreLinks.pages(bases, BuildConfig.STORE_URL);
    /** file brevi (aggiornamento dello store): connessione al massimo 5 s, così un dominio non attivo fa passare in
     *  fretta alla base successiva */
    private static final int FEED_CONNECT_MS = 5000, FEED_READ_MS = 10000;
    /** la pagina dello store non ha ancora risposto dopo questo tempo: si passa alla successiva */
    private static final long PAGE_WAIT_MS = 12000L;
    /** il file dell'aggiornamento letto da meno di un minuto vale per la pagina e per il controllo dell'aggiornamento */
    private static final long FEED_FRESH_MS = 60_000L;

    private WebView web;
    private File pendingApk;
    private String pendingStatusId;
    private volatile boolean busy = false;
    /** true mentre lo store è in primo piano (gli aggiornamenti e l'installer non partono quando il cliente usa un'altra app) */
    private volatile boolean resumed = false;
    private volatile boolean destroyed = false;
    /** schermata dello store visibile (tra onStart e onStop) */
    private volatile boolean started = false;
    /** Android 12+: versione nuova dello store già scaricata, si installa da sola quando il cliente esce (Quiet.java) */
    private File selfReady;
    private int selfCode;
    private final Runnable quietRun = () -> {
        File f = selfReady;
        int c = selfCode;
        if (started || f == null) return;
        selfReady = null;
        Context app = getApplicationContext();
        Quiet.tried(app, c); // un tentativo per versione: se non riesce, alla prossima apertura torna la conferma di Android
        new Thread(() -> Quiet.install(app, f, c), "kx-quiet").start();
    };
    /** APK scaricato mentre lo store era in sottofondo: l'installazione parte quando il cliente torna nello store */
    private File deferredApk;
    private String deferredStatusId;

    // ---- aggiornamenti ----
    private static final long REFRESH_AFTER_MS = 5 * 60 * 1000L; // ricarica la lista se l'app torna in primo piano dopo 5 minuti
    private long lastRefresh = 0;
    private boolean updateCheckDone = false;

    // ---- ritorno automatico dalla schermata "installa app sconosciute" ----
    private final Handler poll = new Handler(Looper.getMainLooper());
    private Runnable permWatcher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(100); // la pagina si adatta da sola allo schermo: niente ingrandimento del testo di sistema
        s.setMediaPlaybackRequiresUserGesture(false);

        // store aperto: via il ricordo della vecchia autorizzazione (con lei i download venivano bloccati)
        prefs().edit().remove(PREF_SERVER).apply();

        // basi dei download: il dominio dedicato, la riserva compilata e quelle annunciate dal file dell'aggiornamento
        ArrayList<String> extra = new ArrayList<>();
        extra.add(BuildConfig.DL_BASE2);
        for (String m : prefs().getString(PREF_MIRRORS, "").split("\n")) if (!m.trim().isEmpty()) extra.add(m.trim());
        bases = StoreLinks.bases(DL_BASE, extra);
        pages = StoreLinks.pages(bases, BuildConfig.STORE_URL);

        web.addJavascriptInterface(new Bridge(), "KairosStore");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // pagina dello store non raggiungibile: la successiva (riserva, GitHub Pages), alla fine la copia nell'APK
                if (isCurrentPage(failingUrl)) nextPage();
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                // la pagina dello store risponde con un errore (es. 502, 404): stessa cosa
                if (request != null && request.isForMainFrame() && response != null && response.getStatusCode() >= 400
                        && isCurrentPage(String.valueOf(request.getUrl()))) {
                    nextPage();
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageUrl = url == null ? "" : url;
                // la pagina dello store ha risposto: niente passaggio alla successiva per il tempo scaduto
                if (isCurrentPage(url)) poll.removeCallbacks(pageWatchdog);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageUrl = url == null ? "" : url;
                // A ogni apertura: controlla in automatico se c'è una nuova versione dello store
                if (!updateCheckDone) {
                    updateCheckDone = true;
                    checkStoreUpdate(false);
                }
            }
        });
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) ->
                startDownload(url, "App", null));

        loadStore(false, null);
        web.requestFocus();
        poll.postDelayed(periodicCheck, PERIODIC_MS);
    }

    // ---- controllo periodico della nuova versione dello store mentre resta aperto ----
    private static final long PERIODIC_MS = 30 * 60 * 1000L;
    private final Runnable periodicCheck = new Runnable() {
        @Override
        public void run() {
            // solo con lo store davanti: in sottofondo non scarica e non apre l'installer sopra l'app che il cliente sta usando
            if (resumed && !busy && pendingApk == null) checkStoreUpdate(false);
            poll.postDelayed(this, PERIODIC_MS);
        }
    };

    // ---- pagina dello store: la prima base che risponde bene, poi le successive, alla fine la copia nell'APK ----
    private int pageIdx = 0;
    private int pageGen = 0;
    private boolean pageSkipIntro = false;
    private String pageMsg = null;
    /** la pagina in caricamento non ha risposto entro PAGE_WAIT_MS (connessione appesa): la successiva */
    private final Runnable pageWatchdog = this::nextPage;

    /**
     * Carica sempre la versione più recente della lista app (niente copia vecchia in memoria). Prima si sceglie la base:
     * la prima che risponde con il file dell'aggiornamento valido, così un dominio non ancora attivo o parcheggiato non
     * arriva mai nella pagina; lo stesso file serve poi al controllo dell'aggiornamento, senza chiederlo di nuovo.
     */
    private void loadStore(boolean skipIntro, String msg) {
        if (destroyed) return;
        lastRefresh = System.currentTimeMillis();
        pageSkipIntro = skipIntro;
        pageMsg = msg;
        final int gen = ++pageGen;
        web.clearCache(true);
        new Thread(() -> {
            StoreFeed f = freshBaseFeed();
            final int start = f != null ? f.base : bases.size(); // nessuna base: GitHub Pages, poi la copia nell'APK
            runOnUiThread(() -> {
                if (destroyed || gen != pageGen) return;
                pageIdx = start;
                loadPage();
            });
        }).start();
    }

    /** carica la pagina dello store numero pageIdx; finita la lista, la copia inclusa nell'APK */
    private void loadPage() {
        if (destroyed) return;
        poll.removeCallbacks(pageWatchdog);
        if (pageIdx >= pages.size()) {
            web.loadUrl(LOCAL_PAGE);
            return;
        }
        String base = pages.get(pageIdx);
        String url = base + (base.contains("?") ? "&" : "?") + "t=" + lastRefresh;
        if (pageSkipIntro) url += "&intro=0";
        if (pageMsg != null) url += "&msg=" + pageMsg;
        poll.postDelayed(pageWatchdog, PAGE_WAIT_MS);
        web.loadUrl(url);
    }

    private void nextPage() {
        if (destroyed || pageIdx >= pages.size()) return;
        pageIdx++;
        loadPage();
    }

    /** [u] è la pagina dello store in caricamento adesso */
    private boolean isCurrentPage(String u) {
        return u != null && pageIdx < pages.size() && u.startsWith(pages.get(pageIdx));
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        appsCacheAt = 0; // app installate o tolte mentre si era fuori: si rileggono
        // download finito mentre lo store era in sottofondo (o chiuso con Indietro): si installa adesso
        if (prefs().contains(PREF_DEFERRED)) {
            File f = new File(prefs().getString(PREF_DEFERRED, ""));
            if (deferredApk == null && f.isFile() && System.currentTimeMillis() - f.lastModified() < 30 * 60 * 1000L) deferredApk = f;
            prefs().edit().remove(PREF_DEFERRED).apply();
        }
        if (deferredApk != null) {
            File apk = deferredApk;
            String sid = deferredStatusId;
            deferredApk = null;
            install(apk, sid);
        }
        // tornando da un'installazione o disinstallazione: aggiorna i pulsanti delle app
        if (web != null) web.evaluateJavascript("window.kairosRefreshInstalled && window.kairosRefreshInstalled()", null);
        // App rimasta aperta in sottofondo: al ritorno aggiorna lista e versione
        if (web != null && lastRefresh > 0 && !busy && pendingApk == null
                && System.currentTimeMillis() - lastRefresh > REFRESH_AFTER_MS) {
            updateCheckDone = false;
            loadStore(true, null);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        poll.removeCallbacks(quietRun); // tornato nello store: l'aggiornamento aspetta la prossima uscita
    }

    @Override
    protected void onStop() {
        started = false;
        if (selfReady != null) scheduleQuiet();
        super.onStop();
    }

    /** uscito dallo store: tra 10 secondi, se non torna, la versione nuova dello store si installa da sola */
    private void scheduleQuiet() {
        poll.removeCallbacks(quietRun);
        poll.postDelayed(quietRun, 10_000);
    }

    /** true = il link lo gestisce l'app (download), false = lo apre la WebView */
    private boolean handleUrl(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains("aftv.news/") || u.matches(".*\\.apk([?#].*)?$")) {
            startDownload(url, "App", null);
            return true;
        }
        if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("file://")) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) { }
        return true;
    }

    /** Indirizzo della pagina aperta */
    private volatile String pageUrl = "";
    /** scheda del dispositivo già letta (deviceSpecs) */
    private volatile String specsCache;

    /** Oggetto visibile dalla pagina come window.KairosStore */
    private class Bridge {
        @JavascriptInterface
        public void install(String url, String name, String statusId) {
            runOnUiThread(() -> startDownload(url, name, statusId));
        }

        /** (pagine vecchie dello store) download «autorizzato»: con lo store aperto parte e basta */
        @JavascriptInterface
        public void installApproved(String url, String name, String statusId, String server, String id, String token) {
            runOnUiThread(() -> startDownload(url, name, statusId));
        }

        /** Codice casuale di questa installazione: il pannello conta così gli store installati e i download */
        @JavascriptInterface
        public String deviceId() {
            return installId();
        }

        /** Modello del dispositivo (riepilogo dello store nel pannello e «App consigliata») */
        @JavascriptInterface
        public String deviceName() {
            String m = (Build.MANUFACTURER + " " + Build.MODEL).trim();
            if (getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) m += " (Fire TV)";
            else if (isTelevision()) m += " (Android TV)";
            return m;
        }

        /** Memoria totale del dispositivo in MB: serve allo Store per consigliare l'app adatta */
        @JavascriptInterface
        public long totalRamMb() {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                return mi.totalMem / (1024L * 1024L);
            } catch (Throwable t) { return 0; }
        }

        /** scheda del dispositivo per «App consigliata» (11 novembre 2026): processore, RAM, memoria interna, Android,
         *  schermo, H.265 e 4K, in JSON (vedi DeviceSpecs). Si legge una volta per apertura dello store. */
        @JavascriptInterface
        public String deviceSpecs() {
            String c = specsCache;
            if (c == null) {
                try { c = DeviceSpecs.json(MainActivity.this); } catch (Throwable t) { c = "{}"; }
                specsCache = c;
            }
            return c;
        }

        /** (pagine vecchie dello store) server delle autorizzazioni: lo store è aperto, non si ricorda più nulla */
        @JavascriptInterface
        public void setApprovalServer(String server) {
            prefs().edit().remove(PREF_SERVER).apply();
        }

        @JavascriptInterface
        public String approvalServer() {
            return "";
        }

        @JavascriptInterface
        public boolean isTv() {
            return isTelevision();
        }

        /** Pulsante AGGIORNA: controlla nuova versione dello store e ricarica la lista app */
        @JavascriptInterface
        public void refresh() {
            runOnUiThread(() -> checkStoreUpdate(true));
        }

        @JavascriptInterface
        public String appVersion() {
            return BuildConfig.VERSION_NAME;
        }

        /** Nome del pacchetto dell'app se è installata, altrimenti "" */
        @JavascriptInterface
        public String installedPackage(String name, String hint) {
            return findPackage(name, hint);
        }

        /** App distribuite solo dagli store ufficiali: Amazon Appstore su Fire TV, Google Play sugli altri */
        @JavascriptInterface
        public void openStore(String pkg, String asin, String statusId) {
            runOnUiThread(() -> openOfficialStore(pkg, asin, statusId));
        }

        /** Apre la richiesta di disinstallazione del sistema */
        @JavascriptInterface
        public void uninstall(String pkg) {
            runOnUiThread(() -> {
                if (pkg == null || pkg.isEmpty() || !isInstalled(pkg)) return;
                try {
                    Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
                    startActivity(i);
                } catch (Exception e) {
                    try { // riserva: scheda dell'app nelle impostazioni, con il pulsante Disinstalla
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
                    } catch (Exception ignored) { }
                }
            });
        }
    }

    // ------------------------------------------------------------ app installate

    private SharedPreferences prefs() {
        return getSharedPreferences("kairos", MODE_PRIVATE);
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Trova il pacchetto di un'app: 1) indicato nella lista, 2) ricordato quando lo store
     * l'ha installata, 3) cercando tra le app installate quella con lo stesso nome.
     */
    private String findPackage(String name, String hint) {
        if (hint != null && !hint.isEmpty() && isInstalled(hint)) return hint;
        String key = norm(name);
        if (key.isEmpty()) return "";
        String saved = prefs().getString("pkg_" + key, "");
        if (!saved.isEmpty() && isInstalled(saved)) return saved;
        try {
            String partial = "";
            for (String[] a : userApps()) {
                String label = a[0];
                if (label.equals(key)) return a[1];
                if (partial.isEmpty() && key.length() >= 4 && (label.startsWith(key) || key.startsWith(label) && label.length() >= 4)) {
                    partial = a[1];
                }
            }
            return partial;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * App installate dal cliente: {nome normalizzato, pacchetto}. La pagina chiede ogni app dello store una per una
     * (15 e più): leggere ogni volta tutte le app e i loro nomi bloccava la pagina per qualche secondo sulle chiavette
     * da 1 GB. Ora l'elenco si legge una volta e vale qualche secondo (e si rilegge quando si torna nello store).
     */
    private List<String[]> appsCache;
    private volatile long appsCacheAt = 0;

    private synchronized List<String[]> userApps() {
        long now = System.currentTimeMillis();
        if (appsCache != null && now - appsCacheAt < 5000) return appsCache;
        PackageManager pm = getPackageManager();
        java.util.ArrayList<String[]> list = new java.util.ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (ai.packageName.equals(getPackageName())) continue;
            String label = norm(String.valueOf(pm.getApplicationLabel(ai)));
            if (!label.isEmpty()) list.add(new String[]{label, ai.packageName});
        }
        appsCache = list;
        appsCacheAt = now;
        return list;
    }

    /** Ricorda il pacchetto contenuto nell'APK scaricato, per riconoscere l'app in seguito. */
    private void rememberPackage(String name, File apk) {
        try {
            if (name == null || "KairosStore".equals(name) || "App".equals(name)) return;
            PackageInfo pi = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (pi != null && pi.packageName != null) {
                prefs().edit().putString("pkg_" + norm(name), pi.packageName).apply();
            }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ aggiornamento dello store

    private static final String UPD = "stUpd";

    /** versione dello store letta: N di 1.0.N, link dell'APK e indice della base che ha risposto */
    private static final class StoreFeed {
        final int build;
        final String apk;
        final int base;

        StoreFeed(int build, String apk, int base) {
            this.build = build;
            this.apk = apk;
            this.base = base;
        }
    }

    /** ultimo controllo delle basi (null = nessuna ha risposto bene) e quando: vale per la pagina e per l'aggiornamento */
    private volatile StoreFeed lastFeed;
    private volatile long lastFeedAt = 0;

    /**
     * Controllo dell'aggiornamento dello store: /get/store.json dal dominio dei download e, solo se non risponde bene,
     * dalle riserve; niente pannello né GitHub. L'APK si scarica dal link del file che ha risposto e, se non riesce,
     * dalle basi successive.
     */
    private void checkStoreUpdate(boolean manual) {
        if (busy) {
            if (manual) status(UPD, "Attendi: è già in corso un download…");
            return;
        }
        if (manual) status(UPD, "Controllo aggiornamenti…");
        new Thread(() -> {
            // pulsante AGGIORNA: sempre dalla rete; all'apertura vale il file appena letto per scegliere la pagina
            StoreFeed f = manual ? baseFeedNow() : freshBaseFeed();
            if (f == null) {
                if (manual) {
                    status(UPD, "Controllo aggiornamenti non riuscito. Ricarico la lista delle app…");
                    runOnUiThread(() -> web.postDelayed(() -> loadStore(true, null), 1800));
                }
                return;
            }
            if (f.build > BuildConfig.VERSION_CODE) {
                final List<String> apks = StoreLinks.apkUrls(f.apk, bases);
                status(UPD, "Nuova versione di Kairos Store (1.0." + f.build + "): aggiornamento in corso…");
                runOnUiThread(() -> beginDownload(apks, "KairosStore", UPD)); // aggiornamento dello store
            } else if (manual) {
                runOnUiThread(() -> loadStore(true, "ok"));
            }
        }).start();
    }

    /**
     * file dell'aggiornamento dalla prima base che risponde bene (200, JSON con versione e link https); null = nessuna.
     * I domini di riserva che porta («mirrors») si ricordano per l'apertura dopo; senza la lista restano quelli di prima.
     */
    private StoreFeed baseFeed() {
        List<String> feeds = StoreLinks.feeds(bases);
        for (int i = 0; i < feeds.size(); i++) {
            try {
                JSONObject j = new JSONObject(httpText(feeds.get(i), null));
                int n = StoreLinks.build(j.optString("version", ""));
                String apk = j.optString("url", "");
                if (n > 0 && apk.startsWith("https://")) {
                    saveMirrors(j.optJSONArray("mirrors"));
                    return new StoreFeed(n, apk, i);
                }
            } catch (Exception ignored) {
                // nessuna risposta, errore, HTML (dominio parcheggiato) o JSON non valido: la base successiva
            }
        }
        return null;
    }

    /** [baseFeed] dalla rete adesso, ricordato per la pagina e per il controllo dell'aggiornamento */
    private StoreFeed baseFeedNow() {
        StoreFeed f = baseFeed();
        lastFeed = f;
        lastFeedAt = System.currentTimeMillis();
        return f;
    }

    /** come [baseFeedNow], ma vale quello letto meno di un minuto fa: la pagina e l'aggiornamento non lo chiedono due volte */
    private StoreFeed freshBaseFeed() {
        if (lastFeedAt > 0 && System.currentTimeMillis() - lastFeedAt < FEED_FRESH_MS) return lastFeed;
        return baseFeedNow();
    }

    /** domini di riserva del file dell'aggiornamento («mirrors»): validi, salvati solo se cambiano; null = il file non li ha */
    private void saveMirrors(JSONArray a) {
        if (a == null) return;
        ArrayList<String> list = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) list.add(a.optString(i, ""));
        String v = TextUtils.join("\n", StoreLinks.mirrors(list));
        if (!v.equals(prefs().getString(PREF_MIRRORS, ""))) prefs().edit().putString(PREF_MIRRORS, v).apply();
    }

    /** GET di un file breve (al massimo 256 KB): il testo se la risposta è 200, altrimenti un'eccezione */
    private static String httpText(String url, String accept) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(FEED_CONNECT_MS);
            c.setReadTimeout(FEED_READ_MS);
            c.setRequestProperty("User-Agent", "KairosStore/" + BuildConfig.VERSION_NAME);
            if (accept != null) c.setRequestProperty("Accept", accept);
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("risposta " + code);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 256 * 1024) throw new Exception("risposta troppo grande");
                }
            }
            return bos.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private boolean isTelevision() {
        UiModeManager ui = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        PackageManager pm = getPackageManager();
        return (ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION)
                || pm.hasSystemFeature("android.software.leanback")
                || pm.hasSystemFeature("amazon.hardware.fire_tv");
    }

    // ------------------------------------------------------------ identità dell'installazione

    /** chiave in cui le versioni precedenti ricordavano il server delle autorizzazioni (ora si cancella) */
    private static final String PREF_SERVER = "approval_server";
    private static final String PREF_DID = "did";
    /** APK scaricato da installare alla prossima apertura dello store */
    private static final String PREF_DEFERRED = "deferred_apk";

    private synchronized String installId() {
        String d = prefs().getString(PREF_DID, "");
        if (d.matches("[A-Za-z0-9_-]{16,64}")) return d;
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        d = Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        prefs().edit().putString(PREF_DID, d).apply();
        return d;
    }

    /**
     * Ingresso di tutti i download chiesti dalla pagina o dai link (install(), link .apk / aftv.news,
     * download della WebView). Store aperto: nessuna autorizzazione, il download parte subito.
     * Un'app Kairos dal dominio dei download che non si scarica si riprova con lo stesso link sulle riserve.
     */
    private void startDownload(String url, String name, String statusId) {
        beginDownload(StoreLinks.apkUrls(url, bases), name, statusId);
    }

    // ------------------------------------------------------------ download

    /** scarica il primo di [urls] che riesce; se nessuno riesce, il messaggio del primo errore */
    private void beginDownload(List<String> urls, String name, String statusId) {
        if (destroyed) return;
        if (busy) {
            status(statusId, "Attendi: è già in corso un download…");
            return;
        }
        busy = true;
        status(statusId, "Connessione…");
        new Thread(() -> {
            String err = null;
            try {
                for (String u : urls) {
                    try {
                        download(u, name, statusId);
                        return;
                    } catch (DownloadError e) {
                        if (err == null) err = e.getMessage();
                        if (e.local) break; // memoria piena: da un'altra base non cambierebbe niente
                    } catch (Exception e) {
                        if (err == null) err = "Download non riuscito. Controlla la connessione e riprova.";
                    }
                }
                status(statusId, err != null ? err : "Download non riuscito. Controlla la connessione e riprova.");
            } finally {
                busy = false;
            }
        }).start();
    }

    /** errore del download con il messaggio da mostrare al cliente */
    private static class DownloadError extends Exception {
        private static final long serialVersionUID = 1L;
        /** problema del dispositivo (memoria piena): un'altra base non cambierebbe niente */
        final boolean local;
        DownloadError(String msg) { this(msg, false); }
        DownloadError(String msg, boolean local) {
            super(msg);
            this.local = local;
        }
    }

    private void download(String startUrl, String name, String statusId) throws Exception {
        String current = startUrl;
        HttpURLConnection c = null;
        // Segue i reindirizzamenti (aftv.news -> link reale dell'APK), anche http <-> https
        for (int i = 0; i < 10; i++) {
            c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "KairosStore/1.0 (Android)");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                c = null; // (se i reindirizzamenti non finiscono, sotto si ferma con un errore)
                if (loc == null) throw new Exception("reindirizzamento non valido");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            if (code >= 400) {
                c.disconnect();
                // link dell'app rotto o release senza file (il pannello risponde 502): non è colpa della connessione
                throw new DownloadError("Download non disponibile al momento. Riprova più tardi.");
            }
            break;
        }
        if (c == null) throw new Exception("troppi reindirizzamenti");

        String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
        String path = current.toLowerCase(Locale.ROOT);
        if (type.startsWith("text/html") && !path.contains(".apk")) {
            // Il link porta a una pagina web invece che al file: non la mostriamo (si prova la base successiva, se c'è)
            c.disconnect();
            throw new DownloadError("Download non disponibile al momento. Riprova più tardi.");
        }

        File dir = getExternalFilesDir(null) != null
                ? new File(getExternalFilesDir(null), "apk")
                : new File(getCacheDir(), "apk");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("impossibile creare la cartella");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        String safe = (name == null ? "app" : name).replaceAll("[^A-Za-z0-9._-]", "_");
        File out = new File(dir, safe + ".apk");

        long total = c.getContentLength();
        // memoria piena: lo si dice subito, invece di «controlla la connessione» a metà download
        long free = dir.getUsableSpace();
        if (total > 0 && free > 0 && free < total + 10L * 1024 * 1024) {
            c.disconnect();
            throw new DownloadError("Download non riuscito: spazio insufficiente (servono circa "
                    + (total / (1024 * 1024) + 10) + " MB liberi). Libera memoria e riprova.", true);
        }
        long done = 0;
        int lastPct = -1;
        byte[] head = new byte[4];
        boolean complete = false;
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                for (int k = 0; k < n && done + k < 4; k++) head[(int) (done + k)] = buf[k];
                try {
                    os.write(buf, 0, n);
                } catch (java.io.IOException e) {
                    if (dir.getUsableSpace() < 2L * 1024 * 1024 || String.valueOf(e.getMessage()).contains("ENOSPC")) {
                        throw new DownloadError("Download non riuscito: la memoria del dispositivo è piena. Libera spazio e riprova.", true);
                    }
                    throw e;
                }
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct != lastPct && pct % 5 == 0) {
                        lastPct = pct;
                        status(statusId, "Download " + pct + "%");
                    }
                } else if (done % (1024 * 1024) < n) {
                    status(statusId, "Download " + (done / (1024 * 1024)) + " MB");
                }
            }
            complete = true;
        } finally {
            c.disconnect();
            if (!complete) //noinspection ResultOfMethodCallIgnored
                out.delete(); // niente file a metà che occupano la memoria
        }
        // connessione caduta prima della fine: il file sarebbe un APK rotto («errore durante l'analisi del pacchetto»)
        if (done == 0 || (total > 0 && done < total)) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new DownloadError("Download non riuscito: si è interrotto. Controlla la connessione e riprova.");
        }
        // un APK comincia sempre con «PK»: altrimenti il link ha dato una pagina o un messaggio, non l'app
        if (head[0] != 'P' || head[1] != 'K') {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new DownloadError("Download non disponibile al momento. Riprova più tardi.");
        }
        rememberPackage(name, out);
        status(statusId, "Download completato. Avvio installazione…");
        runOnUiThread(() -> install(out, statusId));
    }

    // ------------------------------------------------------------ installazione

    private void install(File apk, String statusId) {
        // Android 12+: l'aggiornamento dello store si installa da solo, senza conferma, quando il cliente esce dallo store
        int sc = Quiet.selfCode(this, apk);
        if (sc > 0 && Quiet.ok(this, sc)) {
            selfReady = apk;
            selfCode = sc;
            status(statusId, "Kairos Store 1.0." + sc + " è pronto: si aggiorna da solo quando esci dallo store.");
            if (!started) scheduleQuiet();
            return;
        }
        if (destroyed || !resumed) {
            // il cliente è uscito dallo store durante il download: l'installer non si apre sopra un'altra app
            // (da Android 10 il sistema lo bloccherebbe e l'installazione andrebbe persa); parte quando torna qui,
            // anche se nel frattempo lo store è stato chiuso (il file resta valido 30 minuti)
            deferredApk = apk;
            deferredStatusId = statusId;
            prefs().edit().putString(PREF_DEFERRED, apk.getAbsolutePath()).apply();
            status(statusId, "Download completato: l'installazione parte quando torni in Kairos Store.");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            pendingApk = apk;
            pendingStatusId = statusId;
            status(statusId, "Attiva l'interruttore per Kairos Store: lo store torna qui da solo.");
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_UNKNOWN_SOURCES);
                startPermissionWatcher();
                return;
            } catch (Exception e) {
                // Alcune Fire TV non hanno questa schermata: proviamo comunque.
                // Niente più APK «in attesa del permesso», altrimenti lo store non si aggiornerebbe più al ritorno.
                pendingApk = null;
                status(statusId, "Se l'installazione è bloccata: Impostazioni > Il mio Fire TV > "
                        + "Opzioni sviluppatore > Installa app sconosciute > Kairos Store.");
            }
        }
        launchInstaller(apk, statusId);
    }

    private void launchInstaller(File apk, String statusId) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(this, getPackageName() + ".files", apk);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(apk);
            }
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            status(statusId, "Conferma l'installazione sullo schermo.");
        } catch (Exception e) {
            status(statusId, "Impossibile avviare l'installazione: " + e.getMessage());
        }
    }

    /**
     * Mentre è aperta la schermata del permesso controlla ogni mezzo secondo se l'utente
     * ha attivato l'interruttore: appena lo fa, chiude la schermata, riporta lo store
     * in primo piano e avvia l'installazione, senza dover premere Indietro.
     */
    private void startPermissionWatcher() {
        stopPermissionWatcher();
        final long start = System.currentTimeMillis();
        permWatcher = new Runnable() {
            @Override
            public void run() {
                if (pendingApk == null) {
                    permWatcher = null;
                    return;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        && getPackageManager().canRequestPackageInstalls()) {
                    permWatcher = null;
                    File apk = pendingApk;
                    pendingApk = null;
                    try {
                        finishActivity(REQ_UNKNOWN_SOURCES); // chiude la schermata del permesso
                    } catch (Exception ignored) { }
                    bringToFront();                          // riporta lo store in primo piano
                    status(pendingStatusId, "Permesso attivato. Avvio installazione…");
                    poll.postDelayed(() -> launchInstaller(apk, pendingStatusId), 400);
                    return;
                }
                if (System.currentTimeMillis() - start < 5 * 60 * 1000L) {
                    poll.postDelayed(this, 500);
                } else {
                    permWatcher = null;
                }
            }
        };
        poll.postDelayed(permWatcher, 500);
    }

    /** Riporta lo store davanti a tutto, con più metodi perché ogni versione di Fire OS si comporta diversamente. */
    private void bringToFront() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.moveTaskToFront(getTaskId(), ActivityManager.MOVE_TASK_WITH_HOME);
        } catch (Exception ignored) { }
        try {
            Intent back = new Intent(MainActivity.this, MainActivity.class);
            back.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(back);
        } catch (Exception ignored) { }
    }

    private void stopPermissionWatcher() {
        if (permWatcher != null) {
            poll.removeCallbacks(permWatcher);
            permWatcher = null;
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        poll.removeCallbacks(periodicCheck);
        poll.removeCallbacks(pageWatchdog);
        stopPermissionWatcher();
        // libera la WebView (e con lei la memoria della pagina): uscendo con Indietro restava in memoria a ogni apertura
        if (web != null) {
            try {
                web.stopLoading();
                web.removeJavascriptInterface("KairosStore");
                web.destroy();
            } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UNKNOWN_SOURCES) stopPermissionWatcher();
        if (requestCode == REQ_UNKNOWN_SOURCES && pendingApk != null) {
            File apk = pendingApk;
            pendingApk = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && getPackageManager().canRequestPackageInstalls()) {
                launchInstaller(apk, pendingStatusId);
            } else {
                status(pendingStatusId, "Permesso non concesso: installazione annullata.");
            }
        }
    }

    // ------------------------------------------------------------ store ufficiali

    private void openOfficialStore(String pkg, String asin, String statusId) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9._]{3,150}")) { status(statusId, "App non disponibile"); return; }
        boolean amazon = "Amazon".equalsIgnoreCase(Build.MANUFACTURER);
        boolean validAsin = asin != null && asin.matches("[A-Z0-9]{10}");
        java.util.ArrayList<String[]> tries = new java.util.ArrayList<>();
        if (amazon) {
            tries.add(new String[]{"amzn://apps/android?p=" + pkg, "Apro Amazon Appstore…"});
            if (validAsin) tries.add(new String[]{"amzn://apps/android?asin=" + asin, "Apro Amazon Appstore…"});
        }
        tries.add(new String[]{"market://details?id=" + pkg, "Apro lo store…"});
        if (!amazon) tries.add(new String[]{"amzn://apps/android?p=" + pkg, "Apro Amazon Appstore…"});
        tries.add(new String[]{"https://play.google.com/store/apps/details?id=" + pkg, "Apro Google Play…"});
        for (String[] t : tries) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(t[0]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                status(statusId, t[1]);
                new Handler(Looper.getMainLooper()).postDelayed(() -> status(statusId, ""), 6000);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) { }
        }
        status(statusId, "Store non disponibile su questo dispositivo");
    }

    // ------------------------------------------------------------ varie

    private void status(String statusId, String text) {
        if (statusId == null || destroyed) return;
        String js = "window.kairosStatus && window.kairosStatus("
                + JSONObject.quote(statusId) + "," + JSONObject.quote(text) + ")";
        runOnUiThread(() -> { if (!destroyed) web.evaluateJavascript(js, null); });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() > 0) return true; // tasto tenuto premuto: una sola azione
            // prima chiude l'eventuale finestra aperta nella pagina, poi torna indietro / esce
            web.evaluateJavascript("(window.kairosBack && window.kairosBack()) ? 1 : 0", v -> {
                if ("1".equals(v)) return;
                // sulla pagina dello store Indietro esce: la cronologia ha solo le ricariche (AGGIORNA, ritorno dopo
                // 5 minuti) o, senza internet, la pagina online non raggiungibile, che riportava sempre qui
                // (si poteva uscire solo con Home). Da una pagina esterna aperta da un link si torna allo store.
                if (!onStorePage() && web.canGoBack()) web.goBack();
                else onBackPressed();
            });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** true se la WebView mostra la pagina dello store (da una delle basi, da GitHub Pages o la copia nell'APK) */
    private boolean onStorePage() {
        String u = web.getUrl();
        if (u == null || u.startsWith(LOCAL_PAGE) || u.startsWith("about:")) return true;
        for (String p : pages) if (u.startsWith(p)) return true;
        return false;
    }
}
