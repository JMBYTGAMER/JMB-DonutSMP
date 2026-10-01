package com.jmb.donut;

public final class MarketItem {
    public final String id;
    public final double basePrice;
    public double price;
    public double demand;
    public double supply;

    public MarketItem(String id, double basePrice) {
        this.id = id;
        this.basePrice = basePrice;
        this.price = basePrice;
    }

    public void update(double floor, double ceiling) {
        double pressure = (demand - supply) / Math.max(1.0, demand + supply);
        double drift = (Math.random() - 0.5) * 0.04;
        price *= 1.0 + pressure * 0.18 + drift;
        price = Math.max(basePrice * floor, Math.min(basePrice * ceiling, price));
        demand *= 0.82;
        supply *= 0.82;
    }
}