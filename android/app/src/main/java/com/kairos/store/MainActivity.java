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
import android.util.Base64;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

/**
 * Kairos Store: mostra la pagina dello store e scarica/installa gli APK
 * (link Downloader aftv.news/CODICE o link diretti .apk).
 */
public class MainActivity extends Activity {

    private static final int REQ_UNKNOWN_SOURCES = 42;
    private static final String LOCAL_PAGE = "file:///android_asset/index.html";

    private WebView web;
    private File pendingApk;
    private String pendingStatusId;
    private volatile boolean busy = false;

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
                // Store online non raggiungibile: usa la copia inclusa nell'APK
                if (failingUrl != null && !BuildConfig.STORE_URL.isEmpty()
                        && failingUrl.startsWith(BuildConfig.STORE_URL)) {
                    view.loadUrl(LOCAL_PAGE);
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageUrl = url == null ? "" : url;
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
            if (!busy && pendingApk == null) checkStoreUpdate(false);
            poll.postDelayed(this, PERIODIC_MS);
        }
    };

    /** Carica sempre la versione più recente della lista app (niente copia vecchia in memoria). */
    private void loadStore(boolean skipIntro, String msg) {
        lastRefresh = System.currentTimeMillis();
        if (BuildConfig.STORE_URL.isEmpty()) {
            web.loadUrl(LOCAL_PAGE);
            return;
        }
        web.clearCache(true);
        String url = BuildConfig.STORE_URL + (BuildConfig.STORE_URL.contains("?") ? "&" : "?") + "t=" + lastRefresh;
        if (skipIntro) url += "&intro=0";
        if (msg != null) url += "&msg=" + msg;
        web.loadUrl(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // tornando da un'installazione o disinstallazione: aggiorna i pulsanti delle app
        if (web != null) web.evaluateJavascript("window.kairosRefreshInstalled && window.kairosRefreshInstalled()", null);
        // App rimasta aperta in sottofondo: al ritorno aggiorna lista e versione
        if (web != null && lastRefresh > 0 && !busy && pendingApk == null
                && System.currentTimeMillis() - lastRefresh > REFRESH_AFTER_MS) {
            updateCheckDone = false;
            loadStore(true, null);
        }
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

    /** Oggetto visibile dalla pagina come window.KairosStore */
    /** Indirizzo della pagina aperta: le funzioni dell'autorizzazione rispondono solo alla pagina dello store */
    private volatile String pageUrl = "";

    private boolean isTrustedPage() {
        String u = pageUrl;
        if (u.startsWith("file:///android_asset/")) return true;
        String base = BuildConfig.STORE_URL;
        if (base.isEmpty()) return false;
        int q = base.indexOf('?');
        if (q >= 0) base = base.substring(0, q);
        int h = base.indexOf('#');
        if (h >= 0) base = base.substring(0, h);
        return !base.isEmpty() && u.startsWith(base);
    }

    private class Bridge {
        @JavascriptInterface
        public void install(String url, String name, String statusId) {
            runOnUiThread(() -> startDownload(url, name, statusId));
        }

        /**
         * Download autorizzato dal venditore: prima di scaricare lo store conferma l'autorizzazione
         * con il server (vale solo per questo dispositivo, questo link e per 30 minuti).
         */
        @JavascriptInterface
        public void installApproved(String url, String name, String statusId, String server, String id, String token) {
            // il server è quello ricordato; una pagina diversa dallo store non può sceglierne un altro
            final String stored = storedApprovalServer();
            final String srv = !stored.isEmpty() ? stored : (isTrustedPage() ? server : "");
            runOnUiThread(() -> startApprovedDownload(url, name, statusId, srv, id, token));
        }

        /** Codice casuale di questa installazione, usato nelle richieste di autorizzazione */
        @JavascriptInterface
        public String deviceId() {
            return installId();
        }

        /** Modello del dispositivo, mostrato al venditore nella richiesta */
        @JavascriptInterface
        public String deviceName() {
            String m = (Build.MANUFACTURER + " " + Build.MODEL).trim();
            if (getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) m += " (Fire TV)";
            else if (isTelevision()) m += " (Android TV)";
            return m;
        }

        /** Server delle autorizzazioni indicato in apps.json ("" = nessuna autorizzazione richiesta) */
        @JavascriptInterface
        public void setApprovalServer(String server) {
            if (!isTrustedPage()) return; // solo la pagina dello store può attivare o disattivare l'autorizzazione
            String s = cleanServer(server);
            if (s.isEmpty()) prefs().edit().remove(PREF_SERVER).apply();
            else prefs().edit().putString(PREF_SERVER, s).apply();
        }

        @JavascriptInterface
        public String approvalServer() {
            return storedApprovalServer();
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
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            String partial = "";
            for (ApplicationInfo ai : apps) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (ai.packageName.equals(getPackageName())) continue;
                String label = norm(String.valueOf(pm.getApplicationLabel(ai)));
                if (label.isEmpty()) continue;
                if (label.equals(key)) return ai.packageName;
                if (partial.isEmpty() && key.length() >= 4 && (label.startsWith(key) || key.startsWith(label) && label.length() >= 4)) {
                    partial = ai.packageName;
                }
            }
            return partial;
        } catch (Exception e) {
            return "";
        }
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

    private void checkStoreUpdate(boolean manual) {
        if (BuildConfig.GITHUB_REPO.isEmpty()) {
            if (manual) loadStore(true, "ok");
            return;
        }
        if (busy) {
            if (manual) status(UPD, "Attendi: è già in corso un download…");
            return;
        }
        if (manual) status(UPD, "Controllo aggiornamenti…");
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "https://api.github.com/repos/" + BuildConfig.GITHUB_REPO + "/releases/latest").openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "KairosStore/" + BuildConfig.VERSION_NAME);
                int code = c.getResponseCode();
                if (code != 200) {
                    c.disconnect();
                    throw new Exception("risposta " + code);
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                } finally {
                    c.disconnect();
                }
                String tag = new JSONObject(bos.toString("UTF-8")).optString("tag_name", ""); // es. "v1.0.12"
                String num = tag.substring(tag.lastIndexOf('.') + 1).replaceAll("[^0-9]", "");
                int latest = num.isEmpty() ? 0 : Integer.parseInt(num);
                if (latest > BuildConfig.VERSION_CODE) {
                    String apk = "https://github.com/" + BuildConfig.GITHUB_REPO + "/releases/latest/download/KairosStore.apk";
                    status(UPD, "Nuova versione di Kairos Store (1.0." + latest + "): aggiornamento in corso…");
                    runOnUiThread(() -> beginDownload(apk, "KairosStore", UPD)); // aggiornamento dello store: niente autorizzazione
                } else if (manual) {
                    runOnUiThread(() -> loadStore(true, "ok"));
                }
            } catch (Exception e) {
                if (manual) {
                    status(UPD, "Controllo aggiornamenti non riuscito. Ricarico la lista delle app…");
                    runOnUiThread(() -> web.postDelayed(() -> loadStore(true, null), 1800));
                }
            }
        }).start();
    }

    private boolean isTelevision() {
        UiModeManager ui = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        PackageManager pm = getPackageManager();
        return (ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION)
                || pm.hasSystemFeature("android.software.leanback")
                || pm.hasSystemFeature("amazon.hardware.fire_tv");
    }

    // ------------------------------------------------------------ autorizzazione del venditore

    private static final String PREF_SERVER = "approval_server";
    private static final String PREF_DID = "did";

    /** Server delle autorizzazioni ricordato ("" = l'autorizzazione non è attiva) */
    private String storedApprovalServer() {
        return prefs().getString(PREF_SERVER, "");
    }

    /** Solo https://dominio[:porta], senza percorsi */
    private static String cleanServer(String s) {
        if (s == null) return "";
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.matches("(?i)https://[a-z0-9.-]+(:\\d{1,5})?") ? s : "";
    }

    private synchronized String installId() {
        String d = prefs().getString(PREF_DID, "");
        if (d.matches("[A-Za-z0-9_-]{16,64}")) return d;
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        d = Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        prefs().edit().putString(PREF_DID, d).apply();
        return d;
    }

    /** L'APK di Kairos Store stesso (aggiornamento automatico): non serve l'autorizzazione */
    private boolean isSelfUpdate(String url) {
        return !BuildConfig.GITHUB_REPO.isEmpty() && url != null
                && url.equals("https://github.com/" + BuildConfig.GITHUB_REPO + "/releases/latest/download/KairosStore.apk");
    }

    /**
     * Ingresso di tutti i download chiesti dalla pagina o dai link (install(), link .apk / aftv.news,
     * download della WebView). Se l'autorizzazione è attiva questi download sono bloccati:
     * passano solo quelli confermati dal server con installApproved().
     */
    private void startDownload(String url, String name, String statusId) {
        if (!storedApprovalServer().isEmpty() && !isSelfUpdate(url)) {
            status(statusId, "Per scaricare questa app serve l'autorizzazione del venditore.");
            if (url != null) {
                web.evaluateJavascript("window.kairosNeedApproval && window.kairosNeedApproval("
                        + JSONObject.quote(url) + ")", null);
            }
            return;
        }
        beginDownload(url, name, statusId);
    }

    private void startApprovedDownload(String url, String name, String statusId, String server, String id, String token) {
        if (busy) {
            status(statusId, "Attendi: è già in corso un download…");
            return;
        }
        final String srv = cleanServer(server);
        if (srv.isEmpty() || url == null || id == null || token == null) {
            status(statusId, "Autorizzazione non valida. Richiedila di nuovo.");
            return;
        }
        final String link = url.trim();
        busy = true;
        status(statusId, "Verifica dell'autorizzazione…");
        new Thread(() -> {
            try {
                String err = consumeApproval(srv, id, token, link);
                if (err != null) {
                    status(statusId, err);
                    return;
                }
                prefs().edit().putString(PREF_SERVER, srv).apply();
                status(statusId, "Connessione…");
                download(link, name, statusId);
            } catch (Exception e) {
                status(statusId, "Download non riuscito. Controlla la connessione e riprova.");
            } finally {
                busy = false;
            }
        }).start();
    }

    /** Il server conferma (e conta) l'autorizzazione per questo dispositivo e questo link: null = ok */
    private String consumeApproval(String server, String id, String token, String url) {
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject();
            body.put("id", id);
            body.put("did", installId());
            body.put("token", token);
            body.put("url", url);
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new URL(server + "/api/store/consume").openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(out.length);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "KairosStore/" + BuildConfig.VERSION_NAME);
            try (OutputStream os = c.getOutputStream()) {
                os.write(out);
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (InputStream is = in) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1 && bos.size() < 65536) bos.write(buf, 0, n);
                }
                text = bos.toString("UTF-8");
            }
            JSONObject j = text.trim().startsWith("{") ? new JSONObject(text) : new JSONObject();
            if (code == 200 && j.optBoolean("ok") && url.equals(j.optString("appUrl"))) return null;
            // ogni autorizzazione vale per un solo download: revocata o già usata → serve una nuova richiesta
            String st = j.optString("status", "");
            if ("revoked".equals(st)) return "Autorizzazione revocata dal venditore.";
            if ("used".equals(st)) return "Autorizzazione già usata: richiedila di nuovo.";
            String err = j.optString("error", "");
            if (err.length() > 160) err = err.substring(0, 160);
            return err.isEmpty() ? "Autorizzazione non valida o scaduta. Richiedila di nuovo." : err;
        } catch (Exception e) {
            return "Impossibile verificare l'autorizzazione: controlla la connessione e riprova.";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------ download

    private void beginDownload(String url, String name, String statusId) {
        if (busy) {
            status(statusId, "Attendi: è già in corso un download…");
            return;
        }
        busy = true;
        status(statusId, "Connessione…");
        new Thread(() -> {
            try {
                download(url, name, statusId);
            } catch (Exception e) {
                status(statusId, "Download non riuscito. Controlla la connessione e riprova.");
            } finally {
                busy = false;
            }
        }).start();
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
                if (loc == null) throw new Exception("reindirizzamento non valido");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            if (code >= 400) throw new Exception("il server ha risposto " + code);
            break;
        }
        if (c == null) throw new Exception("troppi reindirizzamenti");

        String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
        String path = current.toLowerCase(Locale.ROOT);
        if (type.startsWith("text/html") && !path.contains(".apk")) {
            // Il link porta a una pagina web invece che al file: non la mostriamo
            c.disconnect();
            status(statusId, "Download non disponibile al momento. Riprova più tardi.");
            return;
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
        long done = 0;
        int lastPct = -1;
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                os.write(buf, 0, n);
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
        } finally {
            c.disconnect();
        }
        rememberPackage(name, out);
        status(statusId, "Download completato. Avvio installazione…");
        runOnUiThread(() -> install(out, statusId));
    }

    // ------------------------------------------------------------ installazione

    private void install(File apk, String statusId) {
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
            } catch (ActivityNotFoundException e) {
                // Alcune Fire TV non hanno questa schermata: proviamo comunque
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
        poll.removeCallbacks(periodicCheck);
        stopPermissionWatcher();
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

    // ------------------------------------------------------------ varie

    private void status(String statusId, String text) {
        if (statusId == null) return;
        String js = "window.kairosStatus && window.kairosStatus("
                + JSONObject.quote(statusId) + "," + JSONObject.quote(text) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() > 0) return true; // tasto tenuto premuto: una sola azione
            // prima chiude la finestra aperta nella pagina (es. autorizzazione), poi torna indietro / esce
            web.evaluateJavascript("(window.kairosBack && window.kairosBack()) ? 1 : 0", v -> {
                if ("1".equals(v)) return;
                if (web.canGoBack()) web.goBack();
                else onBackPressed();
            });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
