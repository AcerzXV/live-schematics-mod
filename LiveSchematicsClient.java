package com.liveschematics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live Schematics payment watcher (client-side only).
 * Reads server messages like "Steve paid you $100,000" and credits the matching deposit on the website.
 */
public class LiveSchematicsClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("live-schematics");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Saved to .minecraft/config/live-schematics.json the first time the game starts with the mod. */
    static class Config {
        String supabaseUrl = "https://yqcjkcyregkldgsdkycj.supabase.co";
        String serviceKey = "";          // your Supabase SECRET key. Keep this file private.
        String payRegex = "^(\\w{3,16}) (?:has )?paid you \\$?([\\d,.]+\\s?[kmbKMB]?)";
        long maxAutoAmount = 5_000_000L; // bigger payments are NOT auto-credited
        boolean debug = true;            // log every server message to latest.log so you can see the format
    }

    private Config cfg;
    private Pattern pattern;
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    @Override
    public void onInitializeClient() {
        cfg = loadConfig();
        pattern = Pattern.compile(cfg.payRegex, Pattern.CASE_INSENSITIVE);
        // GAME = messages sent by the server itself (not typed by players), which is what we trust.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) handle(message.getString());
        });
        LOG.info("Live Schematics payment watcher loaded. Key set: {}", !cfg.serviceKey.isBlank());
    }

    private Config loadConfig() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve("live-schematics.json");
        try {
            if (Files.exists(p)) {
                Config c = GSON.fromJson(Files.readString(p), Config.class);
                if (c != null) return c;
            }
            Config c = new Config();
            Files.writeString(p, GSON.toJson(c));
            return c;
        } catch (Exception e) {
            LOG.error("Could not read config", e);
            return new Config();
        }
    }

    private void handle(String text) {
        if (cfg.debug) LOG.info("[server message] {}", text);
        Matcher m = pattern.matcher(text);
        if (!m.find()) return;
        String name = m.group(1);
        Long amount = parseAmount(m.group(2));
        if (amount == null || !name.matches("\\w{1,32}")) return;

        String key = name.toLowerCase() + "|" + amount;
        long now = System.currentTimeMillis();
        Long last = seen.put(key, now);
        if (last != null && now - last < 1500) return; // same message delivered twice

        if (amount > cfg.maxAutoAmount) {
            say("Large payment from " + name + " ($" + amount + "). Confirm it by hand in the admin panel.");
            return;
        }
        if (cfg.serviceKey.isBlank()) {
            say("Payment from " + name + " seen, but no Supabase key is set in the config.");
            return;
        }
        credit(name, amount, 1);
    }

    /** "100,000" -> 100000, "1.5K" -> 1500, "2M" -> 2000000 */
    static Long parseAmount(String t) {
        Matcher m = Pattern.compile("^([\\d.]+)([kmbKMB])?$").matcher(t.replaceAll("[,\\s]", ""));
        if (!m.matches()) return null;
        double mult = 1;
        if (m.group(2) != null) {
            switch (Character.toLowerCase(m.group(2).charAt(0))) {
                case 'k' -> mult = 1e3;
                case 'm' -> mult = 1e6;
                case 'b' -> mult = 1e9;
            }
        }
        try {
            long n = Math.round(Double.parseDouble(m.group(1)) * mult);
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void credit(String name, long amount, int attempt) {
        String body = "{\"p_username\":\"" + name + "\",\"p_amount\":" + amount + "}";
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(cfg.supabaseUrl + "/rest/v1/rpc/bot_credit_deposit"))
                .header("Content-Type", "application/json")
                .header("apikey", cfg.serviceKey)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cfg.serviceKey.startsWith("eyJ")) req.header("Authorization", "Bearer " + cfg.serviceKey); // legacy JWT keys

        HTTP.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
            if (err == null && res.statusCode() == 200) {
                String result = res.body().replace("\"", "").trim();
                LOG.info("{} paid {} -> {}", name, amount, result);
                say(name + " paid $" + amount + ": " + result);
                return;
            }
            LOG.warn("Credit failed (try {}): {}", attempt, err != null ? err.getMessage() : res.statusCode() + " " + res.body());
            if (attempt < 4) {
                CompletableFuture.delayedExecutor(2L * attempt, TimeUnit.SECONDS).execute(() -> credit(name, amount, attempt + 1));
            } else {
                try {
                    Path f = FabricLoader.getInstance().getConfigDir().resolve("live-schematics-failed.log");
                    Files.writeString(f, Instant.now() + " " + name + " " + amount + "\n",
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (Exception ignored) { }
                say("Could not credit " + name + " $" + amount + ". Saved to live-schematics-failed.log, confirm it by hand.");
            }
        });
    }

    /** Shows a message only you can see in your chat. */
    private void say(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendMessage(Text.literal("[Live Schematics] " + msg), false);
        });
    }
}
