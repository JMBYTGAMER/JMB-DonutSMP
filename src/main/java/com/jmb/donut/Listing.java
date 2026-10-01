package com.jmb.donut;

public final class Listing {
    public long id;
    public String seller;
    public String item;
    public int amount;
    public double unitPrice;

    public Listing(long id, String seller, String item, int amount, double unitPrice) {
        this.id = id;
        this.seller = seller;
        this.item = item;
        this.amount = amount;
        this.unitPrice = unitPrice;
    }
}