package com.jmb.donut;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

public final class JmbCommands {
    private JmbCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d, JmbMarket m) {
        d.register(Commands.literal("balance").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            m.register(p, c.getSource().getServer());
            double b = m.account(p).balance;
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§6JMB Balance: §a$" + String.format("%.2f", b)));
            return 1;
        }));

        d.register(Commands.literal("bal").executes(c -> d.getRoot().getChild("balance").getCommand().run(c)));

        d.register(Commands.literal("pay")
            .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
            .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
            .executes(c -> {
                ServerPlayer from = c.getSource().getPlayerOrException();
                ServerPlayer to = net.minecraft.commands.arguments.EntityArgument.getPlayer(c, "player");
                try {
                    m.pay(from, to, DoubleArgumentType.getDouble(c, "amount"));
                    from.sendSystemMessage(net.minecraft.network.chat.Component.literal("§aPayment sent."));
                    to.sendSystemMessage(net.minecraft.network.chat.Component.literal("§aYou received a JMB payment."));
                    return 1;
                } catch (Exception e) {
                    from.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c" + e.getMessage()));
                    return 0;
                }
            }))));

        d.register(Commands.literal("sell")
            .then(Commands.argument("item", StringArgumentType.word())
            .then(Commands.argument("amount", IntegerArgumentType.integer(1))
            .executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                try {
                    m.register(p, c.getSource().getServer());
                    String item = StringArgumentType.getString(c, "item");
                    int amount = IntegerArgumentType.getInteger(c, "amount");
                    double before = m.account(p).balance;
                    m.sell(p, item, amount);
                    p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§aSold for §e$" + String.format("%.2f", m.account(p).balance - before)));
                    return 1;
                } catch (Exception e) {
                    p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c" + e.getMessage()));
                    return 0;
                }
            }))));

        d.register(Commands.literal("shop")
            .executes(c -> {
                c.getSource().sendSystemMessage(net.minecraft.network.chat.Component.literal(m.marketText()));
                return 1;
            })
            .then(Commands.literal("buy")
                .then(Commands.argument("item", StringArgumentType.word())
                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                .executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    try {
                        m.register(p, c.getSource().getServer());
                        String itemId = StringArgumentType.getString(c, "item");
                        int amount = IntegerArgumentType.getInteger(c, "amount");
                        double price = m.shopBuy(p, itemId, amount);
                        p.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "§aBought §e" + amount + " §f" + itemId + " §afor §e$" + String.format("%.2f", price)));
                        return 1;
                    } catch (Exception e) {
                        p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c" + e.getMessage()));
                        return 0;
                    }
                }))));

        d.register(Commands.literal("market")
            .executes(c -> {
                c.getSource().sendSystemMessage(net.minecraft.network.chat.Component.literal(m.marketText()));
                return 1;
            }));

        d.register(Commands.literal("ah")
            .executes(c -> {
                c.getSource().sendSystemMessage(net.minecraft.network.chat.Component.literal(m.ahText()));
                return 1;
            })
            .then(Commands.literal("list")
                .then(Commands.argument("item", StringArgumentType.word())
                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.01))
                .executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    try {
                        m.register(p, c.getSource().getServer());
                        long id = m.list(p, StringArgumentType.getString(c, "item"),
                                IntegerArgumentType.getInteger(c, "amount"),
                                DoubleArgumentType.getDouble(c, "price"));
                        p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§aAH listing created: §e#" + id));
                        return 1;
                    } catch (Exception e) {
                        p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c" + e.getMessage()));
                        return 0;
                    }
                })))))
            .then(Commands.literal("buy")
                .then(Commands.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg(1))
                .executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    m.register(p, c.getSource().getServer());
                    boolean ok = m.buyListing(p, com.mojang.brigadier.arguments.LongArgumentType.getLong(c, "id"));
                    p.sendSystemMessage(net.minecraft.network.chat.Component.literal(ok ? "§aPurchase complete." : "§cListing unavailable or you cannot afford it."));
                    return ok ? 1 : 0;
                })));

        d.register(Commands.literal("order")
            .then(Commands.argument("item", StringArgumentType.word())
            .then(Commands.argument("amount", IntegerArgumentType.integer(1))
            .then(Commands.argument("maxPrice", DoubleArgumentType.doubleArg(0.01))
            .executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                try {
                    m.register(p, c.getSource().getServer());
                    long id = m.createOrder(p, StringArgumentType.getString(c, "item"),
                            IntegerArgumentType.getInteger(c, "amount"),
                            DoubleArgumentType.getDouble(c, "maxPrice"));
                    p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§aBuy order created: §e#" + id));
                    return 1;
                } catch (Exception e) {
                    p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c" + e.getMessage()));
                    return 0;
                }
            }))));

        d.register(Commands.literal("orders").executes(c -> {
            c.getSource().sendSystemMessage(net.minecraft.network.chat.Component.literal(m.orderText()));
            return 1;
        }));

        d.register(Commands.literal("baltop").executes(c -> {
            var list = m.balances();
            c.getSource().sendSystemMessage(net.minecraft.network.chat.Component.literal("§6§lJMB BALTOP"));
            for (int i = 0; i < Math.min(10, list.size()); i++) {
                var a = list.get(i);
                c.getSource().sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§e#" + (i + 1) + " §f" + a.name + " §a$" + String.format("%.2f", a.balance)));
            }
            return 1;
        }));

        d.register(Commands.literal("jmbadmin")
            .requires(s -> s.hasPermission(2))
            .then(Commands.literal("give")
                .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                .then(Commands.argument("amount", DoubleArgumentType.doubleArg())
                .executes(c -> {
                    ServerPlayer p = net.minecraft.commands.arguments.EntityArgument.getPlayer(c, "player");
                    m.give(p, DoubleArgumentType.getDouble(c, "amount"));
                    c.getSource().sendSystemMessage(net.minecraft.network.chat.Component.literal("§aBalance updated."));
                    return 1;
                }))))
            .then(Commands.literal("price")
                .then(Commands.argument("item", StringArgumentType.word())
                .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.01))
                .executes(c -> {
                    m.setPrice(StringArgumentType.getString(c, "item"), DoubleArgumentType.getDouble(c, "price"));
                    c.getSource().sendSystemMessage(net.minecraft.network.chat.Component.literal("§aMarket price updated."));
                    return 1;
                }))));
    }
}