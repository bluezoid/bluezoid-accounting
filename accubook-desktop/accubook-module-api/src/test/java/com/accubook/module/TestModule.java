package com.accubook.module;

import java.util.List;
import java.util.Set;

/** A configurable {@link FeatureModule} for registry tests. */
final class TestModule implements FeatureModule {

    private final ModuleId id;
    private Set<ModuleId> dependsOn = Set.of();
    private Set<String> ownedTables = Set.of();
    private String displayName;
    private String version = "1.0.0";
    private int navigationOrder = 100;
    private RuntimeException idFailure;
    private RuntimeException metadataFailure;

    TestModule(String id) {
        this.id = ModuleId.of(id);
        this.displayName = id;
    }

    static TestModule module(String id) {
        return new TestModule(id);
    }

    TestModule dependsOn(String... ids) {
        this.dependsOn = java.util.Arrays.stream(ids).map(ModuleId::of)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return this;
    }

    TestModule owning(String... tables) {
        this.ownedTables = Set.of(tables);
        return this;
    }

    TestModule displayName(String name) {
        this.displayName = name;
        return this;
    }

    TestModule version(String v) {
        this.version = v;
        return this;
    }

    TestModule navigationOrder(int order) {
        this.navigationOrder = order;
        return this;
    }

    TestModule failingId(RuntimeException e) {
        this.idFailure = e;
        return this;
    }

    TestModule failingMetadata(RuntimeException e) {
        this.metadataFailure = e;
        return this;
    }

    @Override
    public ModuleId id() {
        if (idFailure != null) {
            throw idFailure;
        }
        return id;
    }

    @Override
    public String displayName() {
        if (metadataFailure != null) {
            throw metadataFailure;
        }
        return displayName;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public int navigationOrder() {
        return navigationOrder;
    }

    @Override
    public Set<ModuleId> dependsOn() {
        return dependsOn;
    }

    @Override
    public Set<String> ownedTables() {
        return ownedTables;
    }

    @Override
    public List<HistoricalView> historicalViews() {
        return List.of();
    }
}
