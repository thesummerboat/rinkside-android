# Installera Rinkside på telefonen med Obtainium

En kort steg-för-steg-guide. Du behöver bara göra detta en gång. Sedan kommer nya versioner av sig själva via Obtainium.

## Vad är Obtainium?

Obtainium är en app som hämtar och uppdaterar andra appar direkt från GitHub, utan Google Play. Rinkside-appen byggs automatiskt på GitHub, och Obtainium lägger den på din telefon.

## 1. Installera Obtainium

1. Hämta Obtainium från [github.com/ImranR98/Obtainium/releases](https://github.com/ImranR98/Obtainium/releases) (välj filen som slutar på `.apk`, oftast `app-release.apk`), eller via F-Droid / IzzyOnDroid om du har någon av dem.
2. Öppna filen och tillåt att din webbläsare (eller filhanteraren) får installera appar om Android frågar.

## 2. Lägg till Rinkside i Obtainium

1. Öppna Obtainium och tryck på **Lägg till app** (Add app).
2. Klistra in den här adressen i fältet **App-källans URL**:

   ```
   https://github.com/thesummerboat/rinkside-android
   ```

3. Tryck på **Lägg till** (Add).

**Om GitHub-repot är privat** (det ser du på att Obtainium säger "not found" eller liknande): du måste först ge Obtainium en åtkomstnyckel.

1. På GitHub: din profilbild → **Settings** → **Developer settings** → **Personal access tokens** → **Fine-grained tokens** → **Generate new token**.
2. Välj bara repot `rinkside-android`. Under **Repository permissions** sätt **Contents** till *Read-only*. Spara nyckeln (en lång text som börjar på `github_pat_`).
3. I Obtainium: **Inställningar** → **Källspecifika inställningar** → **GitHub** → **Personal access token**, och klistra in nyckeln.
4. Gör om steg 1–3 ovan.

## 3. Installera appen

1. Tryck på **Installera** för Rinkside.
2. Första gången frågar Android om Obtainium får installera appar. Tryck **Inställningar** och slå på **Tillåt från den här källan**. Gå tillbaka och tryck **Installera** igen.
3. Bekräfta installationen. Klart.

> Finns det ingen version att installera? Då har ingen version släppts än. Det behövs en så kallad "tagg" på GitHub (till exempel `v0.1.0`), se längst ned.

## 4. Första start

1. Öppna **Rinkside**. Det ser ut som webbsidan, fast som en app.
2. Tryck **Tillåt** när Android frågar om notiser.
3. **Följ ett lag** i appen på samma sätt som på webbsidan. Appen läser av vilka lag du följer och när de spelar. Utan följda lag kommer inga notiser.

## 5. Testa att notiserna fungerar

Tryck och håll på Rinkside-ikonen på hemskärmen. Två genvägar dyker upp:

- **Notiser**: visar status och inställningar.
- **Testa notiser**: öppnar samma skärm, direkt vid testknapparna.

På skärmen **Notiser**:

1. Överst ska det stå att notiser och exakta alarm är **tillåtna**. Står det något annat, tryck på knappen bredvid och slå på det.
2. Tryck **Undanta från batterioptimering** och välj **Tillåt**. Det minskar risken att Android stoppar bevakningen under en match.
3. Prova knapparna:
   - **Testa: matchstart**
   - **Testa: mål**
   - **Testa: bortdömt mål**
   - **Testa: matchslut**

   Då kommer en vanlig notis för varje.
4. Tryck **Simulera en match**. Appen spelar upp en påhittad SHL-match (start, mål, bortdömt mål, kvittering, förlängning, slut) med ett steg var tredje sekund och skickar riktiga notiser. Det är samma kod som används under en riktig match.
5. **Starta bevakning nu** startar bevakningen direkt för dina följda matcher, så du slipper vänta på att den startar av sig själv.

Under en riktig match startar bevakningen av sig själv 10 minuter före matchstart. Du ser en tyst notis, "Rinkside följer N matcher", så länge den pågår.

## 6. Uppdateringar

Obtainium kollar efter nya versioner själv. När en ny version finns visas en **uppdatera**-knapp i Obtainium (och du kan låta den uppdatera automatiskt i inställningarna). Dina följda lag finns kvar.

Uppdateringen går bara att installera om den är signerad med samma nyckel som den du redan har. Därför är det viktigt att nyckelfilen (`rinkside-release.jks`) finns i säkerhetskopia.

## Om något inte fungerar

- **Inga notiser alls.** Kolla på skärmen *Notiser* att notiser är tillåtna och att du följer minst ett lag. Öppna Rinkside en gång efter att du följt laget, så att appen får veta det.
- **Notisen kommer sent eller uteblir.** Slå på *Undanta från batterioptimering* och stäng av batterisparläge under matchen. Vissa telefontillverkare (Xiaomi, Huawei, OnePlus, Samsung m.fl.) stoppar bakgrundsappar extra hårt. Lås gärna appen i listan över senaste appar.
- **Bevakningen hinner inte med.** Appen kollar var 15:e sekund, och bara medan en match med ett följt lag pågår. Det går inte snabbare än så.
- **"Appen är inte installerad" / "Signaturen stämmer inte" vid uppdatering.** Det betyder att den nya versionen är signerad med en annan nyckel än den installerade. Avinstallera Rinkside och installera igen (följda lag och inställningar nollställs).

## För den som släpper nya versioner

En ny version släpps genom att skapa en tagg på GitHub:

```
git tag v0.1.0
git push origin v0.1.0
```

GitHub bygger då en signerad app och lägger den under **Releases**, där Obtainium hittar den. Byggets fyra hemligheter (nyckelfilen och lösenorden) ska ligga under *Settings → Secrets and variables → Actions*. De finns beskrivna i `README.txt` som hör till nyckelfilen. Saknas någon, avbryts bygget med ett tydligt felmeddelande, och ingen osignerad app publiceras.
