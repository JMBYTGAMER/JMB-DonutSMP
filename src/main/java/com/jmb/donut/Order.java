package com.jmb.donut;

public final class Order {
    public long id;
    public String buyer;
    public String item;
    public int amount;
    public double maxUnitPrice;

    public Order(long id, String buyer, String item, int amount, double maxUnitPrice) {
        this.id = id;
        this.buyer = buyer;
        this.item = item;
        this.amount = amount;
        this.maxUnitPrice = maxUnitPrice;
    }
}