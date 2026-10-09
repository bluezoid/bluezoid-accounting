package com.accubook.inventory;

public record Item(
        String id,
        String name,
        String sku,
        ItemKind kind,
        String unitId,
        String categoryId,
        String hsnSac,
        String description,
        boolean active) {
}
