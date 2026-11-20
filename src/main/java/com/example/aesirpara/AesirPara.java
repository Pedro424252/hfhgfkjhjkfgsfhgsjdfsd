package com.example.aesirpara;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aesir Para - Gaks_v310
 *
 * Yakindaki oyuncular icin sunucuya arka planda "/bal <isim>" komutunu gonderir,
 * sohbetteki cevabi ("Gaks_v310 bakiyesi 460m") yakalar, sohbetten gizler ve
 * parayi oyuncunun kafasinin ustundeki isim etiketine yazar.
 *
 * - Her turda en fazla 10 oyuncuya sorgu gider (tick basina 1 komut), sonra 3.5 sn beklenir.
 * - Yeni gorulen / henuz yuklenmemis oyuncular once sorgulanir (en yakin olan ilk).
 * - Yuklenmis oyuncularin parasi her 10 sn'de bir yenilenir.
 * - Cevap gelene kadar etikette "Yukleniyor..." yazar.
 */
public class AesirPara implements ClientModInitializer {

    /** Cevap mesaji: 1. grup = oyuncu adi, 2. grup = para (460m, 1.5k, 12,345 ...) */
    private static final String DEFAULT_REGEX =
            "([A-Za-z0-9_]{3,16})\\s+bakiyesi\\s*[:=]?\\s*\\$?\\s*(\\d[\\d.,]*[kKmMbBtT]?)";
    private static final String DEFAULT_COMMAND = "bal {isim}";

    public static volatile boolean enabled = true;
    public static volatile String symbol = "$";
    public static volatile String regex = DEFAULT_REGEX;
    public static volatile String command = DEFAULT_COMMAND;
    public static volatile int batchSize = 10;
    public static volatile int batchDelayMs = 3500;
    public static volatile int refreshMs = 10000;

    private static volatile Pattern pattern = Pattern.compile(DEFAULT_REGEX);

    private static final class Entry {
        volatile String name;
        volatile String money;      // null = henuz yuklenmedi
        volatile long lastQuery;    // en son sorgu gonderilme zamani (0 = hic)
        volatile long lastUpdate;   // en son cevap gelme zamani
        volatile long lastSeen;
        volatile double dist;
        Entry(String name) { this.name = name; }
    }

    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();
    /** cevabi beklenen oyuncular: kucuk harfli isim -> gonderilme zamani */
    private static final Map<String, Long> PENDING = new ConcurrentHashMap<>();
    private static final Queue<String> SEND_QUEUE = new ConcurrentLinkedQueue<>();
    private static long nextBatchAt = 0;

    private static KeyBinding openKey;
    private static KeyBinding toggleKey;

    // ---------------------------------------------------------------- ayar dosyasi

    private static Path cfgPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("aesirpara.properties");
    }

    public static void save() {
        try (OutputStream out = Files.newOutputStream(cfgPath())) {
            Properties p = new Properties();
            p.setProperty("enabled", String.valueOf(enabled));
            p.setProperty("symbol", symbol);
            p.setProperty("regex", regex);
            p.setProperty("command", command);
            p.setProperty("batchSize", String.valueOf(batchSize));
            p.setProperty("batchDelayMs", String.valueOf(batchDelayMs));
            p.setProperty("refreshMs", String.valueOf(refreshMs));
            p.store(out, "Aesir Para");
        } catch (Throwable ignored) {
        }
    }

    private static int parseInt(String s, int def, int min) {
        try { return Math.max(min, Integer.parseInt(s.trim())); } catch (Throwable t) { return def; }
    }

    private static void load() {
        Path path = cfgPath();
        if (!Files.exists(path)) return;
        try (InputStream in = Files.newInputStream(path)) {
            Properties p = new Properties();
            p.load(in);
            enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
            symbol = p.getProperty("symbol", "$");
            command = p.getProperty("command", DEFAULT_COMMAND);
            batchSize = parseInt(p.getProperty("batchSize", "10"), 10, 1);
            batchDelayMs = parseInt(p.getProperty("batchDelayMs", "3500"), 3500, 0);
            refreshMs = parseInt(p.getProperty("refreshMs", "10000"), 10000, 1000);
            setRegex(p.getProperty("regex", DEFAULT_REGEX));
        } catch (Throwable ignored) {
        }
    }

    private static boolean setRegex(String r) {
        try {
            pattern = Pattern.compile(r);
            regex = r;
            return true;
        } catch (Throwable t) {
            pattern = Pattern.compile(DEFAULT_REGEX);
            regex = DEFAULT_REGEX;
            return false;
        }
    }

    // ---------------------------------------------------------------- isim etiketi

    /** Mixin'den cagrilir: isim etiketinin yanina parayi ekler. Eklenecek bir sey yoksa null. */
    public static Text decorate(Entity e, Text original) {
        if (!enabled || original == null) return null;
        Entry en = ENTRIES.get(e.getUuid());
        if (en == null) return null;
        Text label = en.money == null
                ? Text.literal("Y\u00FCkleniyor...").formatted(Formatting.GRAY)
                : Text.literal(symbol + en.money).formatted(Formatting.GOLD);
        return Text.empty().append(original).append(Text.literal(" ")).append(label);
    }

    // ---------------------------------------------------------------- sohbetten cevap yakalama

    /** false dondurursek mesaj sohbette gorunmez. */
    private static boolean onGameMessage(Text message, boolean overlay) {
        if (!enabled || overlay) return true;
        try {
            Matcher m = pattern.matcher(message.getString());
            if (!m.find()) return true;
            String name = m.group(1);
            String amount = m.group(2).trim();
            if (name == null || amount == null || amount.isEmpty()) return true;

            long now = System.currentTimeMillis();
            boolean ours = PENDING.remove(name.toLowerCase(Locale.ROOT)) != null;
            for (Entry en : ENTRIES.values()) {
                if (en.name.equalsIgnoreCase(name)) {
                    en.money = amount;
                    en.lastUpdate = now;
                }
            }
            // sadece bizim gonderdigimiz sorgunun cevabini gizle; elle yazilan /bal gorunsun
            return !ours;
        } catch (Throwable t) {
            return true;
        }
    }

    // ---------------------------------------------------------------- sorgu zamanlayici

    private static void tick(MinecraftClient mc) {
        ClientPlayNetworkHandler nh = mc.getNetworkHandler();
        if (nh == null) return;
        long now = System.currentTimeMillis();

        // yakindaki oyunculari guncelle
        List<Entry> present = new ArrayList<>();
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (nh.getPlayerListEntry(p.getUuid()) == null) continue;   // NPC / sahte oyuncu
            Entry en = ENTRIES.computeIfAbsent(p.getUuid(), k -> new Entry(p.getName().getString()));
            en.name = p.getName().getString();
            en.lastSeen = now;
            en.dist = mc.player.squaredDistanceTo(p);
            present.add(en);
        }
        // 30 sn'dir gorulmeyenleri sil, cevap gelmeyen bekleyenleri 5 sn sonra birak
        ENTRIES.values().removeIf(en -> now - en.lastSeen > 30000);
        PENDING.values().removeIf(t -> now - t > 5000);

        // sirada bekleyen komutlari tick basina 1 tane gonder
        String cmd = SEND_QUEUE.poll();
        if (cmd != null) {
            try { nh.sendChatCommand(cmd); } catch (Throwable ignored) { }
        }
        if (!SEND_QUEUE.isEmpty() || now < nextBatchAt) return;

        // yeni tur: sorgulanmasi gerekenleri sec
        List<Entry> due = new ArrayList<>();
        for (Entry en : present) {
            if (PENDING.containsKey(en.name.toLowerCase(Locale.ROOT))) continue;
            if (en.lastQuery == 0 || now - en.lastQuery >= refreshMs) due.add(en);
        }
        if (due.isEmpty()) return;

        // once yuklenmemisler (en yakin ilk), sonra en eski guncellenenler
        due.sort(Comparator
                .comparing((Entry en) -> en.money != null)
                .thenComparingDouble(en -> en.money == null ? en.dist : en.lastQuery));

        int n = Math.min(batchSize, due.size());
        for (int i = 0; i < n; i++) {
            Entry en = due.get(i);
            en.lastQuery = now;
            PENDING.put(en.name.toLowerCase(Locale.ROOT), now);
            SEND_QUEUE.add(command.replace("{isim}", en.name));
        }
        nextBatchAt = now + (long) n * 50L + batchDelayMs;
    }

    private static void reset() {
        ENTRIES.clear();
        PENDING.clear();
        SEND_QUEUE.clear();
        nextBatchAt = 0;
    }

    // ---------------------------------------------------------------- komutlar

    private static void say(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.inGameHud.getChatHud().addMessage(Text.literal(msg));
    }

    private static void debug() {
        say("\u00A7e== Aesir Para debug ==");
        say("\u00A77Komut: /" + command + "  |  parti: " + batchSize + "  |  bekleme: " + batchDelayMs
                + "ms  |  yenileme: " + refreshMs + "ms");
        say("\u00A77Takip edilen: " + ENTRIES.size() + "  |  cevap bekleyen: " + PENDING.size()
                + "  |  gonderilmeyi bekleyen: " + SEND_QUEUE.size());
        for (Entry en : ENTRIES.values()) {
            say(" - " + en.name + " -> " + (en.money == null ? "yukleniyor" : en.money));
        }
        say("\u00A77Para gelmiyorsa elle /bal <isim> yazip cevabi bana at; regex: /aesirpara regex <desen>");
    }

    @Override
    public void onInitializeClient() {
        load();
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("aesirpara", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aesirpara.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_CONTROL, category));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aesirpara.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

        ClientReceiveMessageEvents.ALLOW_GAME.register(AesirPara::onGameMessage);

        // Oyun menusune ayar butonu (LootGuard butonuyla ust uste binmesin diye biraz asagida)
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof GameMenuScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Aesir Para"),
                        b -> MinecraftClient.getInstance().setScreen(new AesirParaScreen()))
                        .dimensions(5, 30, 110, 20).build());
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                client.setScreen(new AesirParaScreen());
            }
            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                save();
                if (client.player != null) {
                    client.inGameHud.setOverlayMessage(Text.literal("Aesir Para: " + (enabled ? "A\u00E7\u0131k" : "Kapal\u0131")), false);
                }
            }
            if (client.player == null || client.world == null || !enabled) {
                reset();
                return;
            }
            try {
                tick(client);
            } catch (Throwable ignored) {
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("aesirpara")
                        .then(ClientCommandManager.literal("debug").executes(ctx -> {
                            debug();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("komut")
                                .then(ClientCommandManager.argument("sablon", StringArgumentType.greedyString()).executes(ctx -> {
                                    String c = StringArgumentType.getString(ctx, "sablon").trim();
                                    if (c.startsWith("/")) c = c.substring(1);
                                    command = c;
                                    save();
                                    say("\u00A7aKomut: /" + command + "  ({isim} = oyuncu adi)");
                                    return 1;
                                })))
                        .then(ClientCommandManager.literal("regex")
                                .then(ClientCommandManager.argument("desen", StringArgumentType.greedyString()).executes(ctx -> {
                                    boolean ok = setRegex(StringArgumentType.getString(ctx, "desen"));
                                    save();
                                    say(ok ? "\u00A7aRegex ayarlandi (1. grup = oyuncu adi, 2. grup = para)"
                                            : "\u00A7cGecersiz regex, varsayilana donuldu");
                                    return 1;
                                })))
                        .then(ClientCommandManager.literal("sembol")
                                .then(ClientCommandManager.argument("s", StringArgumentType.greedyString()).executes(ctx -> {
                                    symbol = StringArgumentType.getString(ctx, "s");
                                    save();
                                    say("\u00A7aSembol: " + symbol);
                                    return 1;
                                })))
                        .then(ClientCommandManager.literal("sifirla").executes(ctx -> {
                            symbol = "$";
                            command = DEFAULT_COMMAND;
                            batchSize = 10;
                            batchDelayMs = 3500;
                            refreshMs = 10000;
                            setRegex(DEFAULT_REGEX);
                            save();
                            say("\u00A7aAyarlar sifirlandi");
                            return 1;
                        }))));
    }
}
