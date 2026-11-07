# kairos-store
Kairos Store - store per le mie app Android

## Autorizzazione dei download

Puoi fare in modo che nessuno scarichi un'app dallo store senza il tuo ok.

1. Prima prepara il pannello Cloudflare (repository **kairos-iptv**, guida al punto *"Approvazione download dello store"*: chiave Resend `RESEND_KEY` ed email).
2. Qui apri `docs/apps.json` e, dentro `"store"`, metti l'indirizzo del pannello **senza** `/panel`:
   ```json
   "approval": { "server": "https://kairos-panel.TUO-SOTTODOMINIO.workers.dev", "on": true }
   ```
   Salva (Commit). La pagina dello store si aggiorna da sola in circa un minuto.
3. Da quel momento, quando un cliente preme **Installa**:
   - lo store mostra *"Per scaricare questa app serve l'autorizzazione del venditore"* e il pulsante **Richiedi autorizzazione**;
   - a te arriva un'email con app, dispositivo e un **codice di 6 cifre**, e la richiesta compare nel pannello (pagina **Download store**);
   - premi **Autorizza** (email o pannello) e il download parte da solo, oppure detti il codice al cliente che lo inserisce nello store (sulle TV c'è un tastierino da usare con le frecce del telecomando).
4. Per tornare ai download liberi: `"server": ""` oppure `"on": false`. Se invece spegni l'interruttore nel pannello, i download partono subito ma restano registrati.

L'app Kairos Store (dalla nuova versione, che si aggiorna da sola) controlla anche lei l'autorizzazione con il server prima di scaricare: un download non autorizzato viene bloccato anche se arriva da un link. L'aggiornamento dello store stesso non chiede autorizzazione.

> I link degli APK e i codici Downloader scritti in `apps.json` restano pubblici (il repository è pubblico): chi li copia può scaricare fuori dallo store. Con l'autorizzazione attiva i link non compaiono più nei pulsanti della pagina, ma restano leggibili in `apps.json`.

## Store intestati ai venditori (attivazione)

Se nel pannello Kairos Control scegli **Solo store attivati, senza conferma**, alla prima apertura lo store mostra **«Attiva lo store»**: si scrive una volta il **codice del venditore** (quello dell'agente o del titolare) e da lì ogni download parte da solo, intestato a quel venditore; nello header compare *«Store di <nome>»* (toccalo per cambiare venditore). Uno store non attivato non scarica. Il link `?v=CODICE` precompila il codice nelle richieste ma **non attiva** lo store. Guida completa nel README di **kairos-iptv**, sezione *Store intestati ai venditori*.

## Pannello (worker/)

Il **PANNELLO KAIROS STORE** (`https://kairos-store.<account>.workers.dev`) è il Worker Cloudflare in `worker/index.js`.
Ogni modifica a quella cartella viene pubblicata da sola da GitHub Actions (*Deploy pannello (Worker)*), che usa i
segreti del repository `CLOUDFLARE_API_TOKEN` e `CLOUDFLARE_ACCOUNT_ID`. Da ora il codice si cambia qui, non in
*Edit code* su Cloudflare: una modifica fatta lì verrebbe sovrascritta al deploy successivo.
