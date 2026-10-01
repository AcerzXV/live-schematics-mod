# Live Schematics payment watcher (Fabric mod)

Client-side mod. While you're logged into DonutSMP on the payment account, it reads server messages like
"Steve paid you $100,000" and credits the matching deposit on the website.

## Easiest: let GitHub build the jar for you
Upload this whole folder to a new PRIVATE GitHub repo (including `.github/workflows/build.yml`).
Open the repo's **Actions** tab, wait for "Build mod" to finish (a few minutes), open the run, and download
the `live-schematics-mod` file from **Artifacts**. Unzip it to get the jar.

## Or build it yourself
Easiest: generate a template at https://fabricmc.net/develop (pick the Minecraft version DonutSMP uses,
mappings = Yarn), then copy this project's `src/` folder over the template's `src/` (delete the template's
example code). The template brings the correct Gradle files and wrapper.
Then run `./gradlew build` (Java 21 needed). The mod jar is in `build/libs/` (use the one without "-sources").

## Install
1. Install Fabric Loader and Fabric API for your Minecraft version.
2. Put the jar in `.minecraft/mods/`.
3. Start the game once, close it, then open `.minecraft/config/live-schematics.json`.
4. Paste your Supabase SECRET key into `serviceKey`. Keep this file private, never share it.
5. Start the game, join DonutSMP, and have another account /pay you. Check `logs/latest.log` for the line
   (debug is on). If the format differs from "X paid you $Y", edit `payRegex` in the config.
   Group 1 = sender name, group 2 = amount.
6. Test a full deposit from the website, then set `"debug": false`.

## Notes
- The game must stay open and logged in on the account named as `BOT_NAME` in the website's index.html.
- Only server messages count (not player chat), and payments above `maxAutoAmount` need manual confirm.
