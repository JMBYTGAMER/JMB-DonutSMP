package com.jmb.donut;

import java.util.UUID;

public final class Account {
    public final UUID uuid;
    public String name;
    public double balance;

    public Account(UUID uuid, String name, double balance) {
        this.uuid = uuid;
        this.name = name;
        this.balance = balance;
    }
}