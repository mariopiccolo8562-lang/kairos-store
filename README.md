# KAIROS — App IPTV + Pannello

| Parte | Dove gira | Cartella |
|---|---|---|
| **App** per Fire TV, Android TV, Google TV, telefoni e tablet (APK) | Compilata da **GitHub** | `android/` + `player/` |
| **Pannello** di gestione + API per le app | **Cloudflare** (Worker + database D1) | `cloudflare/` |

Ogni volta che modifichi qualcosa e la carichi su GitHub:
- l'**APK** viene ricompilato da solo (scheda *Actions*);
- il **pannello** viene ripubblicato da solo su Cloudflare.

---

## 1. Carica il progetto su GitHub

1. Crea un repository **privato** su GitHub, ad esempio `kairos-iptv`.
2. Carica tutti i file di questa cartella: *Add file → Upload files*, oppure con `git push`.

## 2. Prepara i segreti (una volta sola)

1. Su GitHub apri **Actions → "Prepara segreti" → Run workflow**.
2. A fine esecuzione scarica l'allegato `segreti-kairos-DA-ELIMINARE` e apri `LEGGIMI-SEGRETI.txt`.
3. Vai su **Settings → Secrets and variables → Actions → New repository secret** e crea:

| Secret | Valore |
|---|---|
| `ANDROID_KEYSTORE_B64` | il contenuto del file `ANDROID_KEYSTORE_B64.txt` |
| `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`, `ANDROID_KEY_ALIAS` | i valori del file |
| `ENC_KEY` | dal file (cifra le playlist nel database) |
| `ADMIN_BOOTSTRAP_PASS` | dal file (password del primo accesso al pannello) |

4. Conserva una copia di `release.jks` e del file dei segreti in un posto sicuro, poi **elimina l'esecuzione** del workflow.

> ⚠️ Se perdi la chiave di firma non potrai più aggiornare l'app sui dispositivi dei clienti. Se cambi `ENC_KEY` le playlist salvate non saranno più leggibili.

## 3. Collega Cloudflare

1. Su Cloudflare: **My Profile → API Tokens → Create Token**, modello **"Edit Cloudflare Workers"**. Aggiungi anche il permesso **Account → D1 → Edit** e crea il token.
2. Copia l'**Account ID** (lo trovi nella home del tuo account Cloudflare, colonna a destra).
3. Su GitHub crea i secret `CLOUDFLARE_API_TOKEN` e `CLOUDFLARE_ACCOUNT_ID`.
4. **Actions → "Pannello Cloudflare" → Run workflow**. Il workflow crea il database, le tabelle e pubblica il pannello.
5. Il pannello sarà su `https://kairos-panel.<tuo-sottodominio>.workers.dev/panel/`.
6. Primo accesso: utente `admin`, password = `ADMIN_BOOTSTRAP_PASS`. **Subito dopo:**
   - cambia la password da *Impostazioni → Account amministratore*;
   - attiva la **verifica in due passaggi** da *Impostazioni → Sicurezza*.

**Dominio personalizzato (consigliato).** Se hai un dominio su Cloudflare, togli il `#` dalla riga `routes` in `cloudflare/wrangler.toml` e metti il tuo indirizzo, ad esempio `tv.tuodominio.it`. Ti serve anche per Cloudflare Access (punto 5).

## 4. Compila l'app

1. Su GitHub: **Settings → Secrets and variables → Actions → Variables**, crea:
   - `KAIROS_SERVER_URL` = l'indirizzo del pannello **senza** `/panel`, ad esempio `https://tv.tuodominio.it`;
   - facoltative: `APP_NAME` (nome sotto l'icona, predefinito "KAIROS") e `APP_ID` (es. `it.miotv.player`, da non cambiare più dopo la prima pubblicazione).
2. Per pubblicare una versione: **Releases → Draft a new release**, tag `v1.0.0`, poi *Publish*. Dopo pochi minuti l'APK `kairos.apk` compare allegato alla release.
3. Il link fisso all'ultima versione è sempre:
   `https://github.com/TUO-UTENTE/kairos-iptv/releases/latest/download/kairos.apk`

   > Se il repository è **privato**, i clienti non possono scaricare da GitHub. In quel caso carica l'APK sul tuo store Kairos o su un hosting pubblico, e metti quel link in *Impostazioni → Link download APK*.
4. Crea il codice **Downloader** (su aftv.news) che punta a quel link, e scrivilo in *Impostazioni → Codice Downloader* del pannello.

## 5. Protezioni Cloudflare (dashboard)

Il pannello ha già le sue protezioni interne (vedi `SICUREZZA.md`). Queste aggiungono un secondo muro davanti:

1. **Cloudflare Access (Zero Trust)**. Richiede un dominio personalizzato.
   - Zero Trust → Access → Applications → *Add application → Self-hosted*.
   - Domini: `tv.tuodominio.it/panel` **e** `tv.tuodominio.it/api/admin` (aggiungi anche `tv.tuodominio.it/api/public`).
   - ⚠️ Non proteggere tutto il dominio: le app dei clienti usano `/api/player`.
   - Policy: *Allow* solo la tua email, con codice via email o login Google.
   - Copia l'**Application Audience (AUD) Tag**. Su GitHub crea le variabili `ACCESS_AUD` (quel valore) e `ACCESS_TEAM_DOMAIN` (es. `tuoteam.cloudflareaccess.com`).
   - Rilancia "Pannello Cloudflare". Da quel momento il Worker verifica anche lui il token di Access: chi aggira Cloudflare viene comunque bloccato.
2. **Turnstile** (anti-bot sulla pagina di accesso).
   - Dashboard → Turnstile → *Add widget* con il dominio del pannello.
   - Variabile `TURNSTILE_SITEKEY` = la Site Key; secret `TURNSTILE_SECRET` = la Secret Key. Poi rilancia il workflow.
3. **Security → WAF** del dominio:
   - ⚠️ **Bot Fight Mode: SPENTO.** Blocca anche le richieste delle app (che non possono risolvere le sfide anti-bot) e l'app non si collega più al pannello.
   - Security Level **Medium** (non High, per lo stesso motivo).
   - regola personalizzata 1 — *se URI Path inizia con `/api/player` → **Skip*** (salta: Security Level, Browser Integrity Check, regole successive): così le app non vengono mai bloccate;
   - regola personalizzata 2 — *se URI Path contiene `/panel` e Paese non è Italia → Block* (adatta i paesi alle tue esigenze).
4. **SSL/TLS**: modalità *Full (strict)*, **Always Use HTTPS** attivo, **HSTS** attivo, TLS minimo 1.2.

## Come funziona per i clienti

1. Installano l'app (codice Downloader) e la aprono: compare un **codice** tipo `KXAB-7Q2M`.
2. Nel pannello il dispositivo appare in **"In attesa"**: premi **Attiva**, scegli la durata e assegni la playlist (M3U o Xtream).
3. L'app si sblocca da sola in pochi secondi.

Il codice resta **legato a quel dispositivo**: se qualcuno lo copia su un'altra TV, non funziona. Se un cliente cambia TV o reinstalla l'app, apri il dispositivo nel pannello e premi **Sblocca**.

## Struttura

```
android/            progetto Android (Kotlin, WebView) — Fire TV / Android TV / telefoni
player/index.html   interfaccia dell'app (stile Netflix), copiata nell'APK durante la build
cloudflare/
  src/worker.js     API + sicurezza
  migrations/       tabelle del database D1
  public/panel/     pannello (HTML + JS)
  wrangler.toml     configurazione Cloudflare
.github/workflows/  compilazione APK, pubblicazione pannello, generazione segreti
```

L'app è solo un lettore e non contiene canali: i contenuti arrivano dalle playlist che assegni. Assicurati di avere i diritti per distribuirli.
