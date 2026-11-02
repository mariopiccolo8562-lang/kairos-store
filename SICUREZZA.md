# Protezioni attive

## Accesso al pannello
- **Password con hash PBKDF2-SHA256** (100.000 iterazioni, sale casuale): nel database la password non c'è mai in chiaro.
- **Verifica in due passaggi (TOTP)**: funziona con Google Authenticator, Microsoft Authenticator e Authy. Ogni codice è usabile una sola volta (anti-replay).
- **Blocco dei tentativi**:
  - massimo 5 tentativi al minuto per IP, con il rate limiting nativo di Cloudflare;
  - dopo 5 errori consecutivi l'IP resta bloccato per 15 minuti.
- **Turnstile** anti-bot sul login (se configurato).
- **Cloudflare Access / Zero Trust** davanti a `/panel` e `/api/admin` (se configurato). Il Worker verifica anche la firma del token di Access (RS256), quindi non basta aggirare il proxy.
- **Messaggi neutri**: la risposta non rivela se un nome utente esiste. Anche i tempi di risposta sono uniformi, quindi non lo rivelano neppure quelli.

## Sessione
- Cookie `__Host-` con `Secure`, `HttpOnly` e `SameSite=Strict`, valido 12 ore. JavaScript non lo può leggere.
- Nel database si salva solo l'**hash** del token di sessione.
- **Protezione CSRF**: ogni modifica richiede un header dedicato e controlla l'origine (`Origin` / `Sec-Fetch-Site`).
- Cambiando la password si chiudono le altre sessioni. C'è anche il pulsante "Chiudi le altre sessioni".

## Dati
- **Le credenziali delle playlist sono cifrate con AES-256-GCM** nel database D1. La chiave `ENC_KEY` è un segreto del Worker.
- Gli URL delle playlist vengono controllati: sono ammessi solo `http` e `https`.
- Il registro **Attività** traccia accessi, accessi falliti (con IP) e modifiche. Le voci più vecchie di 90 giorni vengono cancellate da sole.

## App dei clienti
- Ogni installazione genera un **segreto casuale da 256 bit**. Il codice dispositivo resta legato a quel segreto, quindi un codice copiato non funziona su un'altra TV.
- **Rate limiting** sulle richieste delle app e limite massimo di dispositivi "in attesa", contro chi prova a riempire il database.
- L'app riceve solo le **sue** playlist, e solo se l'abbonamento è attivo e non scaduto.
- L'app non può navigare fuori dai propri file.

## Header HTTP
- `Content-Security-Policy` restrittiva: niente script inline; il pannello si collega solo a sé stesso, a Turnstile e ai font Google.
- `Strict-Transport-Security` (HSTS), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy`, `Cross-Origin-Opener-Policy`.
- `X-Robots-Tag: noindex`: il pannello non finisce su Google.
- Le API non vengono mai messe in cache.

## Manutenzione automatica
- Ogni notte un cron del Worker cancella sessioni scadute, blocchi vecchi, log oltre 90 giorni e dispositivi mai attivati e non visti da 30 giorni.
