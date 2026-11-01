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
                if (failingUrl != null && failingUrl.equals(BuildConfig.STORE_URL)) {
                    view.loadUrl(LOCAL_PAGE);
                }
            }
        });
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) ->
                startDownload(url, "App", null));

        String start = BuildConfig.STORE_URL.isEmpty() ? LOCAL_PAGE : BuildConfig.STORE_URL;
        web.loadUrl(start);
        web.requestFocus();
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
