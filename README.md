# kairos-store
Kairos Store - store per le mie app Android

## Store aperto (come IBO Player)

Lo store è **aperto**: chi installa Kairos Store vede subito tutte le app e preme **Installa** (o **Scarica** dal browser), e il download parte. Niente codice del venditore all'avvio, niente «Richiedi autorizzazione», nessun blocco dopo codici sbagliati, per nessuno (titolare o agenti).

Per le app Kairos non cambia nulla: il cliente apre l'app, manda la **foto del codice** che vede sulla TV e il titolare (o l'agente) lo attiva dal pannello Kairos Control.

- **Cosa resta**: elenco delle app (da `docs/apps.json`, modificabile dal *Pannello Kairos Store*), pulsante **App consigliata per questo dispositivo** (regole dal pannello Kairos Control → *Riepilogo store*), pulsante **AGGIORNA** e aggiornamento automatico dello store.
- **Conteggi**: la pagina avvisa il pannello quando lo store si apre e quando parte un download (`/api/store/hello` e `/api/store/download` su `panel-kairos.com`). Servono solo al titolare per vedere quanti store ci sono e quanti scaricano (pannello → **Riepilogo store**); se il pannello non risponde, lo store scarica lo stesso.
- **Store già installati (versioni fino alla 1.0.129)**: la pagina dello store la scaricano da GitHub Pages a ogni apertura, quindi vedono subito quella nuova, senza codice; la pagina spegne anche il blocco dei download che l'APK vecchio ricordava. Se per qualche motivo l'APK vecchio mostra la sua copia interna della pagina (store senza internet verso GitHub), il pannello approva all'istante ogni richiesta e accetta qualunque codice. In più lo store si aggiorna da solo alla versione nuova alla prima apertura (controllo delle release su GitHub).
- Il blocco `"approval"` in `apps.json` non serve più: se c'è, viene ignorato.

> I link degli APK e i codici Downloader scritti in `apps.json` sono pubblici (il repository è pubblico): con lo store aperto è voluto.

## Pannello (worker/)

Il **PANNELLO KAIROS STORE** (`https://kairos-store.<account>.workers.dev`) è il Worker Cloudflare in `worker/index.js`.
Ogni modifica a quella cartella viene pubblicata da sola da GitHub Actions (*Deploy pannello (Worker)*), che usa i
segreti del repository `CLOUDFLARE_API_TOKEN` e `CLOUDFLARE_ACCOUNT_ID`. Da ora il codice si cambia qui, non in
*Edit code* su Cloudflare: una modifica fatta lì verrebbe sovrascritta al deploy successivo.
