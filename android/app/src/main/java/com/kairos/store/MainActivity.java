package com.kairos.store;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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
            public void onPageFinished(WebView view, String url) {
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
    }

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
    private class Bridge {
        @JavascriptInterface
        public void install(String url, String name, String statusId) {
            runOnUiThread(() -> startDownload(url, name, statusId));
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
                    runOnUiThread(() -> startDownload(apk, "KairosStore", UPD));
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

    // ------------------------------------------------------------ download

    private void startDownload(String url, String name, String statusId) {
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
        status(statusId, "Download completato. Avvio installazione…");
        runOnUiThread(() -> install(out, statusId));
    }

    // ------------------------------------------------------------ installazione

    private void install(File apk, String statusId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            pendingApk = apk;
            pendingStatusId = statusId;
            status(statusId, "Consenti a Kairos Store di installare app, poi torna indietro.");
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_UNKNOWN_SOURCES);
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
