package com.kairos.store;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** esito dell'aggiornamento dello store senza conferma (se riesce lo riceve già la versione nuova) */
public class QuietDone extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android vuole comunque la conferma: con il cliente fuori dallo store non si mostra nulla; alla prossima
            // apertura l'aggiornamento torna come prima
            int sid = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            if (sid >= 0) {
                try { c.getPackageManager().getPackageInstaller().abandonSession(sid); } catch (Throwable ignored) { }
            }
        }
    }
}
