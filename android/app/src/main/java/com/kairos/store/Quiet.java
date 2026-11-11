package com.kairos.store;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Aggiornamento di Kairos Store senza conferma (11 novembre 2026, scelta del titolare): con Android 12 e successivi la
 * versione nuova dello store, già scaricata, si installa da sola con PackageInstaller quando il cliente esce dallo store
 * (vedi MainActivity.onStop), senza nessuna schermata. Android lo permette solo se lo store aggiorna se stesso, ha il
 * permesso «Installa app sconosciute», dichiara UPDATE_PACKAGES_WITHOUT_USER_ACTION ed è costruito per un Android
 * abbastanza recente (targetSdk 35). Un tentativo per versione: se Android chiede comunque la conferma o non riesce, alla
 * prossima apertura l'aggiornamento torna come prima (conferma di Android). Le app installate dallo store chiedono
 * sempre la conferma la prima volta (regola di Android); i loro aggiornamenti li fa ogni app da sola.
 * Su Android 11 e precedenti (tutte le Fire TV) non cambia nulla.
 */
final class Quiet {
    private Quiet() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("kx_quiet", Context.MODE_PRIVATE);
    }

    /** su questo dispositivo si può provare */
    static boolean possible(Context c) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            return c.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            return false;
        }
    }

    /** versione dell'APK [apk] se è lo store stesso, altrimenti 0 */
    @SuppressWarnings("deprecation")
    static int selfCode(Context c, File apk) {
        try {
            PackageInfo p = c.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (p == null || !c.getPackageName().equals(p.packageName)) return 0;
            return Build.VERSION.SDK_INT >= 28 ? (int) p.getLongVersionCode() : p.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** la versione [code] si può lasciare all'installazione senza conferma (nessun tentativo già fatto) */
    static boolean ok(Context c, int code) {
        return code > BuildConfig.VERSION_CODE && possible(c) && prefs(c).getInt("tried", 0) != code;
    }

    static void tried(Context c, int code) {
        prefs(c).edit().putInt("tried", code).apply();
    }

    /** installa [apk] senza conferma (da un thread di lavoro); falso se non è partita */
    static boolean install(Context c, File apk, int code) {
        if (Build.VERSION.SDK_INT < 31) return false;
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        int id = -1;
        try {
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            p.setAppPackageName(c.getPackageName());
            p.setSize(apk.length());
            p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            id = pi.createSession(p);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                Intent done = new Intent(c, QuietDone.class).putExtra("code", code);
                PendingIntent pend = PendingIntent.getBroadcast(c, id, done, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                s.commit(pend.getIntentSender());
            }
            return true;
        } catch (Throwable t) {
            if (id >= 0) {
                try { pi.abandonSession(id); } catch (Throwable ignored) { }
            }
            Log.w("KairosStore", "quiet", t);
            return false;
        }
    }
}
