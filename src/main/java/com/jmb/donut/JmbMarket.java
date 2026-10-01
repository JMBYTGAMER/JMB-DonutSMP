package com.jmb.donut;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.WorldSavePath;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class JmbMarket {
    private final Map<UUID, Account> accounts = new HashMap<>();
    private final Map<String, MarketItem> market = new HashMap<>();
    private final List<Listing> listings = new ArrayList<>();
    private final List<Order> orders = new ArrayList<>();
    private final Map<String, Integer> botStock = new HashMap<>();
    private final Map<String, Double> botMoney = new HashMap<>();
    private final List<String> bots = List.of("JMB_MarketBot", "DiamondDealer", "BlockBroker", "SMPTrader", "NightTrader");
    private long nextId = 1000;
    private long lastMarketUpdate = System.currentTimeMillis();
    private long lastPriceUpdate = System.currentTimeMillis();
    private Path saveFile;

    public void start(MinecraftServer server) {
        setServer(server);
        try {
            saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("jmb_donutsmp.properties");
            load();
            long elapsed = Math.max(0, System.currentTimeMillis() - lastMarketUpdate);
            int cycles = (int)Math.min(1440, elapsed / 60_000L);
            for (int i = 0; i < cycles; i++) botCycle(null, true);
            lastMarketUpdate = System.currentTimeMillis();
            save();
        } catch (Exception e) {
            System.err.println("[JMB DonutSMP] Load error: " + e);
        }
    }

    public void stop(MinecraftServer server) {
        save();
    }

    public void tick(MinecraftServer server) {
        setServer(server);
        long now = System.currentTimeMillis();
        if (now - lastMarketUpdate >= 60_000L) {
            botCycle(server, false);
            lastMarketUpdate = now;
            save();
        }
        if (now - lastPriceUpdate >= 300_000L) {
            market.values().forEach(x -> x.update(0.35, 3.0));
            lastPriceUpdate = now;
            save();
        }
    }

    private void botCycle(MinecraftServer server, boolean catchup) {
        seedMarket();
        ThreadLocalRandom r = ThreadLocalRandom.current();

        for (int i = 0; i < 2; i++) {
            MarketItem mi = randomItem();
            if (mi == null) continue;
            String bot = bots.get(r.nextInt(bots.size()));
            int amount = Math.max(1, r.nextInt(1, 17));
            double price = mi.price * (0.92 + r.nextDouble(0.18));
            listings.add(new Listing(nextId++, bot, mi.id, amount, round(price)));
            botStock.merge(mi.id, -amount, Integer::sum);
            mi.supply += amount;
        }

        for (int i = 0; i < 2; i++) {
            MarketItem mi = randomItem();
            if (mi == null) continue;
            String bot = bots.get(r.nextInt(bots.size()));
            int amount = Math.max(1, r.nextInt(1, 13));
            double max = mi.price * (0.95 + r.nextDouble(0.15));
            orders.add(new Order(nextId++, bot, mi.id, amount, round(max)));
            mi.demand += amount;
        }

        matchOrders();
        matchHumanFriendlyOrders();
    }

    private void matchHumanFriendlyOrders() {
        for (Order o : new ArrayList<>(orders)) {
            if (o.amount <= 0) continue;
            Listing best = null;
            for (Listing l : listings) {
                if (!l.item.equals(o.item) || l.amount <= 0 || l.unitPrice > o.maxUnitPrice) continue;
                if (best == null || l.unitPrice < best.unitPrice) best = l;
            }
            if (best == null) continue;

            int qty = Math.min(o.amount, best.amount);
            double total = qty * best.unitPrice;
            double reservedTotal = qty * o.maxUnitPrice;

            Account buyer = accountByName(o.buyer);
            if (buyer != null && buyer.balance >= total && !isBot(o.buyer)) {
                buyer.balance += reservedTotal - total;
                deliverToOnlinePlayer(o.buyer, o.item, qty);
                best.amount -= qty;
                o.amount -= qty;
                mi(o.item).demand += qty;
            } else if (isBot(o.buyer)) {
                double money = botMoney.getOrDefault(o.buyer, 1_000_000.0);
                if (money >= total) {
                    botMoney.put(o.buyer, money - total);
                    best.amount -= qty;
                    o.amount -= qty;
                }
            }
            if (best.amount <= 0) listings.remove(best);
        }
        orders.removeIf(o -> o.amount <= 0);
        listings.removeIf(l -> l.amount <= 0);
    }

    private void matchOrders() {
        for (Listing l : new ArrayList<>(listings)) {
            if (!isBot(l.seller)) continue;
            for (Order o : new ArrayList<>(orders)) {
                if (!isBot(o.buyer) || !o.item.equals(l.item) || o.amount <= 0 || l.amount <= 0) continue;
                if (l.unitPrice > o.maxUnitPrice) continue;
                int qty = Math.min(l.amount, o.amount);
                double total = qty * l.unitPrice;
                double money = botMoney.getOrDefault(o.buyer, 1_000_000.0);
                if (money < total) continue;
                botMoney.put(o.buyer, money - total);
                botMoney.put(l.seller, botMoney.getOrDefault(l.seller, 1_000_000.0) + total);
                l.amount -= qty;
                o.amount -= qty;
                mi(l.item).demand += qty;
                if (l.amount <= 0) break;
            }
        }
        orders.removeIf(o -> o.amount <= 0);
        listings.removeIf(l -> l.amount <= 0);
    }

    public void registerPlayer(ServerPlayer p) {
        Account a = accounts.get(p.getUUID());
        if (a == null) accounts.put(p.getUUID(), new Account(p.getUUID(), p.getGameProfile().getName(), 1000.0));
        else a.name = p.getGameProfile().getName();
        save();
    }

    public Account account(ServerPlayer p) {
        registerPlayer(p);
        return accounts.get(p.getUUID());
    }

    public void pay(ServerPlayer from, ServerPlayer to, double amount) {
        Account a = account(from), b = account(to);
        if (amount <= 0 || a.balance < amount) throw new IllegalArgumentException("Invalid amount or insufficient funds.");
        a.balance -= amount;
        b.balance += amount;
        save();
    }

    public void sell(ServerPlayer p, String itemId, int amount) {
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive.");
        Item item = item(itemId);
        if (item == null) throw new IllegalArgumentException("Unknown item: " + itemId);
        ItemStack held = p.getMainHandItem();
        if (held.isEmpty() || !BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(itemId)) {
            throw new IllegalArgumentException("Hold the item you want to sell in your main hand.");
        }
        int qty = Math.min(amount, held.getCount());
        MarketItem mi = mi(itemId);
        double total = qty * mi.price;
        held.shrink(qty);
        account(p).balance += total;
        mi.supply += qty;
        save();
    }

    public double shopBuy(ServerPlayer p, String itemId, int amount) {
        Item item = item(itemId);
        if (item == null || amount <= 0) throw new IllegalArgumentException("Invalid shop purchase.");
        MarketItem mi = mi(itemId);
        double total = mi.price * amount;
        Account a = account(p);
        if (a.balance < total) throw new IllegalArgumentException("Insufficient balance.");
        a.balance -= total;
        int remaining = amount;
        while (remaining > 0) {
            int n = Math.min(remaining, item.getDefaultMaxStackSize());
            if (!p.getInventory().add(new ItemStack(item, n))) p.drop(new ItemStack(item, n), false);
            remaining -= n;
        }
        mi.demand += amount;
        save();
        return total;
    }

    public long list(ServerPlayer p, String itemId, int amount, double price) {
        Item item = item(itemId);
        if (item == null || amount <= 0 || price <= 0) throw new IllegalArgumentException("Invalid listing.");
        ItemStack held = p.getMainHandItem();
        if (held.isEmpty() || !BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(itemId) || held.getCount() < amount) {
            throw new IllegalArgumentException("Hold enough of that item in your main hand.");
        }
        held.shrink(amount);
        long id = nextId++;
        listings.add(new Listing(id, p.getGameProfile().getName(), itemId, amount, price));
        mi(itemId).supply += amount;
        save();
        return id;
    }

    public boolean buyListing(ServerPlayer p, long id) {
        Listing l = listings.stream().filter(x -> x.id == id).findFirst().orElse(null);
        if (l == null || l.amount <= 0) return false;
        double total = l.amount * l.unitPrice;
        Account a = account(p);
        if (a.balance < total) return false;
        a.balance -= total;
        if (isBot(l.seller)) botMoney.put(l.seller, botMoney.getOrDefault(l.seller, 1_000_000.0) + total);
        Account seller = accountByName(l.seller);
        if (seller != null) seller.balance += total;
        deliverToOnlinePlayer(p.getGameProfile().getName(), l.item, l.amount);
        mi(l.item).demand += l.amount;
        listings.remove(l);
        save();
        return true;
    }

    public long createOrder(ServerPlayer p, String itemId, int amount, double maxPrice) {
        if (item(itemId) == null || amount <= 0 || maxPrice <= 0) throw new IllegalArgumentException("Invalid order.");
        double reserved = amount * maxPrice;
        Account a = account(p);
        if (a.balance < reserved) throw new IllegalArgumentException("You need enough money to reserve the order.");
        a.balance -= reserved;
        long id = nextId++;
        orders.add(new Order(id, p.getGameProfile().getName(), itemId, amount, maxPrice));
        mi(itemId).demand += amount;
        save();
        return id;
    }

    public String marketText() {
        StringBuilder b = new StringBuilder("§6§lJMB MARKET§r\n");
        market.values().stream().sorted(Comparator.comparing(x -> x.id)).limit(18).forEach(x ->
                b.append("§e").append(x.id).append(" §7→ §a$").append(round(x.price))
                 .append(" §8(base $").append(round(x.basePrice)).append(")\n"));
        return b.toString();
    }

    public String ahText() {
        StringBuilder b = new StringBuilder("§6§lJMB AUCTION HOUSE§r\n");
        if (listings.isEmpty()) return b.append("§7No listings right now.").toString();
        listings.stream().limit(18).forEach(l ->
                b.append("§e#").append(l.id).append(" §f").append(l.item)
                 .append(" §7x").append(l.amount).append(" §a$").append(round(l.unitPrice))
                 .append(" §8by ").append(l.seller).append("\n"));
        return b.toString();
    }

    public String orderText() {
        StringBuilder b = new StringBuilder("§6§lJMB BUY ORDERS§r\n");
        if (orders.isEmpty()) return b.append("§7No active orders.").toString();
        orders.stream().limit(18).forEach(o ->
                b.append("§e#").append(o.id).append(" §f").append(o.item)
                 .append(" §7x").append(o.amount).append(" §a≤$").append(round(o.maxUnitPrice))
                 .append(" §8by ").append(o.buyer).append("\n"));
        return b.toString();
    }

    private void deliverToOnlinePlayer(String name, String itemId, int amount) {
        if (CURRENT_SERVER == null) return;
        for (ServerPlayer p : CURRENT_SERVER.getPlayerList().getPlayers()) {
            if (p.getGameProfile().getName().equalsIgnoreCase(name)) {
                Item item = item(itemId);
                while (amount > 0) {
                    int n = Math.min(amount, item.getDefaultMaxStackSize());
                    if (!p.getInventory().add(new ItemStack(item, n))) p.drop(new ItemStack(item, n), false);
                    amount -= n;
                }
                return;
            }
        }
    }

    private static MinecraftServer CURRENT_SERVER;

    public void setServer(MinecraftServer server) {
        CURRENT_SERVER = server;
    }

    private boolean isBot(String name) { return bots.stream().anyMatch(x -> x.equalsIgnoreCase(name)); }
    private Account accountByName(String name) {
        return accounts.values().stream().filter(x -> x.name.equalsIgnoreCase(name)).findFirst().orElse(null);
    }
    private MarketItem mi(String id) {
        seedMarket();
        return market.computeIfAbsent(id, k -> new MarketItem(k, 100.0));
    }
    private MarketItem randomItem() {
        seedMarket();
        if (market.isEmpty()) return null;
        return new ArrayList<>(market.values()).get(ThreadLocalRandom.current().nextInt(market.size()));
    }
    private Item item(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    private void seedMarket() {
        put("minecraft:cobblestone", 1.0);
        put("minecraft:coal", 12.0);
        put("minecraft:iron_ingot", 45.0);
        put("minecraft:gold_ingot", 90.0);
        put("minecraft:diamond", 450.0);
        put("minecraft:emerald", 300.0);
        put("minecraft:obsidian", 180.0);
        put("minecraft:ender_pearl", 650.0);
        put("minecraft:blaze_rod", 800.0);
        put("minecraft:quartz", 35.0);
        put("minecraft:ancient_debris", 6500.0);
        put("minecraft:netherite_scrap", 3000.0);
        put("minecraft:gunpowder", 25.0);
        put("minecraft:slime_ball", 40.0);
        put("minecraft:leather", 20.0);
        put("minecraft:book", 15.0);
    }

    private void put(String id, double base) {
        market.putIfAbsent(id, new MarketItem(id, base));
    }

    private static double round(double x) { return Math.round(x * 100.0) / 100.0; }

    private void load() {
        if (saveFile == null || !Files.exists(saveFile)) {
            bots.forEach(x -> botMoney.put(x, 1_000_000.0));
            seedMarket();
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(saveFile)) {
            p.load(in);
            nextId = Long.parseLong(p.getProperty("nextId", "1000"));
            lastMarketUpdate = Long.parseLong(p.getProperty("lastMarketUpdate", String.valueOf(System.currentTimeMillis())));
            seedMarket();

            for (String k : market.keySet()) {
                MarketItem m = market.get(k);
                m.price = Double.parseDouble(p.getProperty("price." + k, String.valueOf(m.basePrice)));
            }

            bots.forEach(x -> botMoney.put(x, Double.parseDouble(p.getProperty("botmoney." + x, "1000000"))));

            int ac = Integer.parseInt(p.getProperty("accounts.count", "0"));
            for (int i = 0; i < ac; i++) {
                String base = "account." + i + ".";
                UUID uuid = UUID.fromString(p.getProperty(base + "uuid"));
                accounts.put(uuid, new Account(uuid,
                        p.getProperty(base + "name", "Player"),
                        Double.parseDouble(p.getProperty(base + "balance", "1000"))));
            }

            int lc = Integer.parseInt(p.getProperty("listings.count", "0"));
            for (int i = 0; i < lc; i++) {
                String base = "listing." + i + ".";
                listings.add(new Listing(
                        Long.parseLong(p.getProperty(base + "id")),
                        p.getProperty(base + "seller"),
                        p.getProperty(base + "item"),
                        Integer.parseInt(p.getProperty(base + "amount")),
                        Double.parseDouble(p.getProperty(base + "price"))
                ));
            }

            int oc = Integer.parseInt(p.getProperty("orders.count", "0"));
            for (int i = 0; i < oc; i++) {
                String base = "order." + i + ".";
                orders.add(new Order(
                        Long.parseLong(p.getProperty(base + "id")),
                        p.getProperty(base + "buyer"),
                        p.getProperty(base + "item"),
                        Integer.parseInt(p.getProperty(base + "amount")),
                        Double.parseDouble(p.getProperty(base + "maxPrice"))
                ));
            }
        } catch (Exception e) {
            System.err.println("[JMB DonutSMP] Save parse error: " + e);
        }
    }

    private void save() {
        if (saveFile == null) return;
        Properties p = new Properties();
        p.setProperty("nextId", String.valueOf(nextId));
        p.setProperty("lastMarketUpdate", String.valueOf(lastMarketUpdate));

        for (MarketItem m : market.values()) p.setProperty("price." + m.id, String.valueOf(m.price));
        for (String bot : bots) p.setProperty("botmoney." + bot, String.valueOf(botMoney.getOrDefault(bot, 1_000_000.0)));

        p.setProperty("accounts.count", String.valueOf(accounts.size()));
        int ai = 0;
        for (Account a : accounts.values()) {
            String base = "account." + ai++ + ".";
            p.setProperty(base + "uuid", a.uuid.toString());
            p.setProperty(base + "name", a.name);
            p.setProperty(base + "balance", String.valueOf(a.balance));
        }

        p.setProperty("listings.count", String.valueOf(listings.size()));
        int li = 0;
        for (Listing l : listings) {
            String base = "listing." + li++ + ".";
            p.setProperty(base + "id", String.valueOf(l.id));
            p.setProperty(base + "seller", l.seller);
            p.setProperty(base + "item", l.item);
            p.setProperty(base + "amount", String.valueOf(l.amount));
            p.setProperty(base + "price", String.valueOf(l.unitPrice));
        }

        p.setProperty("orders.count", String.valueOf(orders.size()));
        int oi = 0;
        for (Order o : orders) {
            String base = "order." + oi++ + ".";
            p.setProperty(base + "id", String.valueOf(o.id));
            p.setProperty(base + "buyer", o.buyer);
            p.setProperty(base + "item", o.item);
            p.setProperty(base + "amount", String.valueOf(o.amount));
            p.setProperty(base + "maxPrice", String.valueOf(o.maxUnitPrice));
        }

        try {
            Files.createDirectories(saveFile.getParent());
            try (OutputStream out = Files.newOutputStream(saveFile)) {
                p.store(out, "JMB DonutSMP Offline");
            }
        } catch (IOException e) {
            System.err.println("[JMB DonutSMP] Save error: " + e);
        }
    }

    public List<Account> balances() {
        return accounts.values().stream().sorted((a,b) -> Double.compare(b.balance, a.balance)).toList();
    }

    public void give(ServerPlayer p, double amount) {
        account(p).balance += amount;
        save();
    }

    public void setPrice(String itemId, double price) {
        MarketItem m = mi(itemId);
        m.price = Math.max(0.01, price);
        save();
    }

    public void register(ServerPlayer p, MinecraftServer server) {
        setServer(server);
        registerPlayer(p);
    }
}