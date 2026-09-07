package dev.scathiard.feedmepackages.storage;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.domain.*;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;

/** One world authority. Corrupt data produces a locked ledger, never an empty replacement. */
public final class CacheLedger extends SavedData {
    public static final String NAME = "create_feed_me_packages_ledger";
    public static final int SCHEMA = 7; // Per-item-cell residuals; personal pendants are owner-locked (no capacity-grant entitlement flow).
    public static final int MAX_REDIRECT_VARIANTS = 4096;
    private static final Factory<CacheLedger> FACTORY = new Factory<>(CacheLedger::new, CacheLedger::load);
    private Map<UUID, CacheRecord> caches = Map.of();
    private Map<UUID, UUID> personal = Map.of();
    private Map<UUID, Boolean> cacheFirst = Map.of();
    private Map<UUID, ParcelRedirect> redirects = Map.of();
    private String problem = "";
    private CompoundTag preserved;
    private byte[] integrityKey = createIntegrityKey();

    private static byte[] createIntegrityKey() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return bytes;
    }

    /** Internal world data; key material never leaves this class or enters a packet/log. */
    public String authenticate(String material) {
        writable();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(integrityKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Required JDK integrity algorithm unavailable", failure);
        }
    }

    public boolean authentic(String material, String signature) {
        if (!problem.isEmpty() || signature.length() != 64) return false;
        return MessageDigest.isEqual(authenticate(material).getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
    }

    public static CacheLedger get(MinecraftServer server) {
        var storage = server.overworld().getDataStorage();
        CacheLedger ledger = storage.get(FACTORY, NAME);
        if (ledger == null) {
            ledger = new CacheLedger();
            if (Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat")))
                ledger.problem = "Cache file exists but could not be read; refusing to replace it";
            storage.set(NAME, ledger);
        }
        return ledger;
    }

    public String problem() { return problem; }
    public CacheRecord find(UUID id) { return caches.get(id); }
    public UUID personal(UUID player) { return personal.get(player); }
    // The existing schema field is inert; it cannot override the fixed backpack-first policy.
    public boolean cacheFirst(UUID player) { return false; }
    public boolean isOwner(UUID cacheId, UUID player) {
        var record = caches.get(cacheId);
        return record != null && record.owner() != null && record.owner().equals(player);
    }

    public record ParcelTarget(UUID cacheId, long revision) {}
    /** Caller must authenticate the original parcel before resolving a manufacturing redirect. */
    public ParcelTarget parcelTarget(UUID original, ItemVariantKey variant, long revision) {
        UUID id = original; long currentRevision = revision;
        if (!caches.containsKey(id)) {
            var route = redirects.get(id); if (route == null) return null;
            Long mapped = route.variants().get(new CacheMerge.Route<>(variant, revision));
            if (mapped == null) return null;
            id = route.target(); currentRevision = mapped;
        }
        var record = caches.get(id); if (record == null) return null;
        int slot = record.state().find(variant);
        return slot >= 0 && record.state().cells().get(slot).filterRevision() == currentRevision ? new ParcelTarget(id, currentRevision) : null;
    }

    /** Fresh unregistered source for manufacturing preview; no ledger entry is created here. */
    public CacheRecord ordinaryForManufacturing(UUID id) {
        writable();
        if (id == null) return new CacheRecord(CacheState.empty(UUID.randomUUID()), null, Map.of());
        var record = caches.get(id);
        if (record == null || record.owner() != null) throw new IllegalStateException("Invalid ordinary manufacturing source");
        return record;
    }

    /** Immutable proposed maps; neither construction nor inspection publishes any state. */
    public final class Mutation {
        private final Map<UUID, CacheRecord> beforeCaches = caches;
        private final Map<UUID, UUID> beforePersonal = personal;
        private final Map<UUID, ParcelRedirect> beforeRedirects = redirects;
        private final Map<UUID, CacheRecord> afterCaches;
        private final Map<UUID, UUID> afterPersonal;
        private final Map<UUID, ParcelRedirect> afterRedirects;
        private final CacheRecord result;
        private boolean committed;
        private Mutation(Map<UUID, CacheRecord> records, Map<UUID, UUID> owners, Map<UUID, ParcelRedirect> routes, CacheRecord result) {
            afterCaches = Map.copyOf(records); afterPersonal = Map.copyOf(owners); afterRedirects = Map.copyOf(routes);
            this.result = result;
        }
        public CacheRecord result() { return result; }
        public boolean current() {
            return !committed && beforeCaches == caches && beforePersonal == personal && beforeRedirects == redirects;
        }
        /** Final caller must also prove actual input/output ownership; there are no callbacks here. */
        public void commit() {
            writable(); if (!current()) throw new IllegalStateException("Stale or repeated world transaction");
            caches = afterCaches; personal = afterPersonal; redirects = afterRedirects;
            committed = true; setDirty();
        }
    }

    private void sourceCurrent(CacheRecord source) {
        var actual = caches.get(source.state().id());
        if (actual == source) return;
        if (actual != null || redirects.containsKey(source.state().id()) || source.owner() != null || source.anyResidual()
                || !source.state().equals(CacheState.<ItemVariantKey>empty(source.state().id())))
            throw new IllegalStateException("Stale or unregistered nonempty source");
    }

    public Mutation prepareOrdinaryUpgrade(CacheRecord source, int targetLevel) {
        writable(); sourceCurrent(source);
        if (source.owner() != null || source.state().level() + 1 != targetLevel) throw new IllegalArgumentException("Upgrade must advance one level");
        var edit = source.state().edit(); edit.upgrade(); var result = source.withState(edit.finish());
        var records = new HashMap<>(caches); records.put(result.state().id(), result);
        return new Mutation(records, personal, redirects, result);
    }

    public Mutation prepareOwnership(CacheRecord source, CacheRecord target, CacheRecord result, Map<CacheMerge.Route<ItemVariantKey>, Long> routes) {
        writable(); sourceCurrent(source);
        if (source.owner() != null || result.owner() == null || !Objects.equals(personal.get(result.owner()), target == null ? null : target.state().id())
                || (target != null && (caches.get(target.state().id()) != target || !result.owner().equals(target.owner())))
                || !result.state().id().equals(target == null ? source.state().id() : target.state().id())
                || result.state().revision() != Math.incrementExact(target == null ? source.state().revision() : target.state().revision())
                || result.state().level() != Math.max(source.state().level(), target == null ? 1 : target.state().level()))
            throw new IllegalStateException("Invalid ownership proposal");
        var records = new HashMap<>(caches); records.remove(source.state().id()); records.put(result.state().id(), result);
        var owners = new HashMap<>(personal); owners.put(result.owner(), result.state().id());
        var nextRoutes = new HashMap<>(redirects);
        // A revoked filter can never accept its old parcel again, so its routing metadata is no longer needed.
        for (var entry : redirects.entrySet()) if (entry.getValue().target().equals(result.state().id())) {
            var live = new HashMap<CacheMerge.Route<ItemVariantKey>, Long>();
            entry.getValue().variants().forEach((old, revision) -> {
                int slot = result.state().find(old.variant());
                if (slot >= 0 && result.state().cells().get(slot).filterRevision() == revision) live.put(old, revision);
            });
            if (live.isEmpty()) nextRoutes.remove(entry.getKey());
            else nextRoutes.put(entry.getKey(), new ParcelRedirect(result.state().id(), live));
        }
        if (!source.state().id().equals(result.state().id()) && !routes.isEmpty()) {
            for (var entry : routes.entrySet()) {
                int sourceSlot = source.state().find(entry.getKey().variant()), targetSlot = result.state().find(entry.getKey().variant());
                if (sourceSlot < 0 || targetSlot < 0 || source.state().cells().get(sourceSlot).filterRevision() != entry.getKey().revision()
                        || result.state().cells().get(targetSlot).filterRevision() != entry.getValue()) throw new IllegalStateException("Invalid manufacturing parcel route");
            }
            nextRoutes.put(source.state().id(), new ParcelRedirect(result.state().id(), routes));
        }
        long count = nextRoutes.values().stream().filter(route -> route.target().equals(result.state().id())).mapToLong(route -> route.variants().size()).sum();
        if (count > MAX_REDIRECT_VARIANTS) throw new IllegalStateException("Personal parcel route table is full");
        return new Mutation(records, owners, nextRoutes, result);
    }

    /** Owner-locked personal cache upgrade; no capacity grant is issued. */
    public Mutation preparePersonalUpgrade(CacheHandle handle, int targetLevel) {
        writable(); var source = caches.get(handle.cacheId());
        if (source == null || source.owner() == null || !source.owner().equals(handle.playerId())
                || source.state().level() + 1 != targetLevel) throw new IllegalArgumentException("Personal upgrade must advance one level for its owner");
        var edit = source.state().edit(); edit.upgrade(); var result = source.withState(edit.finish());
        var records = new HashMap<>(caches); records.put(handle.cacheId(), result);
        return new Mutation(records, personal, redirects, result);
    }

    public void setCacheFirst(UUID player, boolean first) {
        writable(); var next = new HashMap<>(cacheFirst); next.put(player, first); cacheFirst = Map.copyOf(next); setDirty();
    }

    public UUID createOrdinary() {
        writable(); UUID id = UUID.randomUUID();
        var next = new HashMap<>(caches); next.put(id, new CacheRecord(CacheState.empty(id), null, Map.of()));
        caches = Map.copyOf(next); setDirty(); return id;
    }

    public UUID personalOrCreate(UUID player) {
        writable(); UUID current = personal.get(player); if (current != null) return current;
        UUID id = UUID.randomUUID();
        var nextCaches = new HashMap<>(caches); nextCaches.put(id, new CacheRecord(CacheState.empty(id), player, Map.of()));
        var nextPersonal = new HashMap<>(personal); nextPersonal.put(player, id);
        caches = Map.copyOf(nextCaches); personal = Map.copyOf(nextPersonal); setDirty(); return id;
    }

    /** No callbacks inside commit. The operation's effective access must be re-resolved first. */
    public void replace(CacheHandle handle, long expectedRevision, CacheRecord replacement) {
        writable(); CacheRecord old = caches.get(handle.cacheId());
        if (old == null || old.state().revision() != expectedRevision || !replacement.state().id().equals(handle.cacheId())
                || !Objects.equals(old.owner(), replacement.owner()) || (old.owner() != null && !old.owner().equals(handle.playerId())))
            throw new IllegalStateException("Stale or unauthorized cache transaction");
        long nextRevision = Math.incrementExact(expectedRevision);
        if (replacement.state().revision() < expectedRevision || replacement.state().revision() > nextRevision)
            throw new IllegalStateException("Invalid cache revision advance");
        // Residual-only changes belong to the same optimistic transaction boundary as inventory.
        if (replacement.state().revision() == expectedRevision) {
            var state = replacement.state();
            replacement = replacement.withState(new CacheState<>(state.id(), state.level(), nextRevision, state.cells(), state.orders()));
        }
        var next = new HashMap<>(caches); next.put(handle.cacheId(), replacement); caches = Map.copyOf(next); setDirty();
    }

    private void writable() { if (!problem.isEmpty()) throw new IllegalStateException(problem); }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (preserved != null) return preserved.copy();
        writable(); tag.putInt("schema", SCHEMA); tag.putByteArray("integrity_key", integrityKey);
        ListTag records = new ListTag();
        for (var entry : caches.entrySet()) {
            CacheRecord record = entry.getValue(); var state = record.state(); CompoundTag row = new CompoundTag();
            row.putUUID("id", state.id()); row.putInt("level", state.level()); row.putLong("revision", state.revision());
            if (record.owner() != null) row.putUUID("owner", record.owner());
            ListTag cells = new ListTag();
            for (Cell<ItemVariantKey> cell : state.cells()) {
                CompoundTag data = new CompoundTag();
                if (cell.filter() != null) data.putString("filter", cell.filter().encoded());
                data.putInt("amount", cell.amount()); data.putInt("min", cell.minimum()); data.putInt("max", cell.maximum()); data.putLong("revision", cell.filterRevision());
                cells.add(data);
            }
            row.put("cells", cells); ListTag orders = new ListTag();
            for (var order : state.orders().values()) {
                CompoundTag data = new CompoundTag(); data.putUUID("id", order.id()); data.putUUID("player", order.player());
                data.putString("variant", order.variant().encoded()); data.putLong("revision", order.filterRevision());
                data.putInt("remaining", order.remaining()); data.putBoolean("uncertain", order.uncertain()); orders.add(data);
            }
            row.put("orders", orders);
            if (!record.residuals().isEmpty()) {
                ListTag residualRows = new ListTag();
                for (var resid : record.residuals().entrySet()) {
                    var data = new CompoundTag(); data.putString("variant", resid.getKey().encoded());
                    data.put("package", resid.getValue().save(registries)); residualRows.add(data);
                }
                row.put("residuals", residualRows);
            }
            records.add(row);
        }
        tag.put("caches", records);
        ListTag preferences = new ListTag();
        cacheFirst.forEach((player, first) -> { var data = new CompoundTag(); data.putUUID("player", player); data.putBoolean("cache_first", first); preferences.add(data); });
        tag.put("preferences", preferences);
        ListTag routeRows = new ListTag();
        redirects.forEach((source, route) -> {
            var row = new CompoundTag(); row.putUUID("source", source); row.putUUID("target", route.target()); var variants = new ListTag();
            route.variants().forEach((old, revision) -> {
                var mapping = new CompoundTag(); mapping.putString("variant", old.variant().encoded()); mapping.putLong("before", old.revision()); mapping.putLong("after", revision); variants.add(mapping);
            }); row.put("variants", variants); routeRows.add(row);
        }); tag.put("redirects", routeRows);
        return tag;
    }

    public static CacheLedger load(CompoundTag tag, HolderLookup.Provider registries) {
        var ledger = new CacheLedger();
        try {
            fields(tag, Set.of("schema", "integrity_key", "caches", "preferences", "redirects", "grants"));
            require(tag, "schema", Tag.TAG_INT);
            int sourceSchema = tag.getInt("schema");
            if (sourceSchema != SCHEMA && sourceSchema != 2 && sourceSchema != 4 && sourceSchema != 5 && sourceSchema != 6)
                throw new IllegalArgumentException("Unsupported cache schema");
            require(tag, "integrity_key", Tag.TAG_BYTE_ARRAY);
            if (tag.getByteArray("integrity_key").length != 32) throw new IllegalArgumentException("Invalid world integrity state");
            ledger.integrityKey = tag.getByteArray("integrity_key").clone();
            var caches = new HashMap<UUID, CacheRecord>(); var personal = new HashMap<UUID, UUID>();
            ListTag rows = compounds(tag, "caches");
            for (Tag element : rows) {
                CompoundTag row = (CompoundTag)element;
                fields(row, Set.of("id", "level", "revision", "owner", "cells", "orders", "residual", "residuals"));
                require(row, "id", Tag.TAG_INT_ARRAY); require(row, "level", Tag.TAG_INT); require(row, "revision", Tag.TAG_LONG);
                if (row.contains("owner")) require(row, "owner", Tag.TAG_INT_ARRAY);
                UUID id = row.getUUID("id"); int level = row.getInt("level"); UUID owner = row.contains("owner") ? row.getUUID("owner") : null;
                List<Cell<ItemVariantKey>> cells = new ArrayList<>(); var cellRows = compounds(row, "cells");
                if (cellRows.size() != CacheLevel.of(level).slots()) throw new IllegalArgumentException("Invalid cell count");
                for (Tag cellTag : cellRows) {
                    var cell = (CompoundTag)cellTag;
                    fields(cell, Set.of("filter", "amount", "min", "max", "revision"));
                    require(cell, "amount", Tag.TAG_INT); require(cell, "min", Tag.TAG_INT); require(cell, "max", Tag.TAG_INT); require(cell, "revision", Tag.TAG_LONG);
                    if (cell.contains("filter")) require(cell, "filter", Tag.TAG_STRING);
                    ItemVariantKey filter = cell.contains("filter") ? ItemVariantKey.decode(cell.getString("filter"), registries) : null;
                    int min = cell.getInt("min"), max = cell.getInt("max");
                    if (sourceSchema == 2 && filter != null) { min = min < 0 ? -1 : min / filter.stackSize(); max = max < 0 ? -1 : max / filter.stackSize(); }
                    int amount = cell.getInt("amount");
                    // Schema < 5 used fixed item capacity; clamp amounts that exceed the new per-item capacity.
                    if (sourceSchema < 5 && filter != null) {
                        int itemCap = CacheLevel.of(level).groupCapacity() * filter.stackSize();
                        if (amount > itemCap) amount = itemCap;
                        if (min > CacheLevel.of(level).groupCapacity()) min = CacheLevel.of(level).groupCapacity();
                        if (max > CacheLevel.of(level).groupCapacity()) max = CacheLevel.of(level).groupCapacity();
                    }
                    cells.add(new Cell<>(filter, amount, min, max, cell.getLong("revision")));
                }
                var orders = new HashMap<UUID, SupplyOrder<ItemVariantKey>>(); var orderRows = compounds(row, "orders");
                if (orderRows.size() > CacheState.MAX_ORDERS) throw new IllegalArgumentException("Too many orders");
                for (Tag orderTag : orderRows) {
                    var data = (CompoundTag)orderTag;
                    fields(data, Set.of("id", "player", "variant", "revision", "remaining", "uncertain"));
                    require(data, "id", Tag.TAG_INT_ARRAY); require(data, "player", Tag.TAG_INT_ARRAY); require(data, "variant", Tag.TAG_STRING);
                    require(data, "remaining", Tag.TAG_INT); require(data, "revision", Tag.TAG_LONG); bool(data, "uncertain");
                    UUID orderId = data.getUUID("id"); var order = new SupplyOrder<>(orderId, data.getUUID("player"), ItemVariantKey.decode(data.getString("variant"), registries), data.getLong("revision"), data.getInt("remaining"), data.getBoolean("uncertain"));
                    if (orders.put(orderId, order) != null) throw new IllegalArgumentException("Duplicate order");
                }
                if (row.contains("residual")) require(row, "residual", Tag.TAG_COMPOUND);
                Map<ItemVariantKey, ItemStack> residuals = new HashMap<>();
                if (row.contains("residuals")) {
                    for (Tag residualTag : compounds(row, "residuals")) {
                        var data = (CompoundTag)residualTag;
                        fields(data, Set.of("variant", "package"));
                        require(data, "variant", Tag.TAG_STRING); require(data, "package", Tag.TAG_COMPOUND);
                        ItemVariantKey variant = ItemVariantKey.decode(data.getString("variant"), registries);
                        ItemStack box = ItemStack.parse(registries, data.getCompound("package")).orElseThrow();
                        if (box.getCount() != 1 || !com.simibubi.create.content.logistics.box.PackageItem.isPackage(box))
                            throw new IllegalArgumentException("Residual slot contains a non-package");
                        if (residuals.put(variant, box) != null) throw new IllegalArgumentException("Duplicate residual variant");
                    }
                } else if (row.contains("residual")) {
                    ItemStack box = ItemStack.parse(registries, row.getCompound("residual")).orElseThrow();
                    if (!box.isEmpty()) {
                        if (box.getCount() != 1 || !com.simibubi.create.content.logistics.box.PackageItem.isPackage(box))
                            throw new IllegalArgumentException("Residual slot contains a non-package");
                        var seal = box.get(dev.scathiard.feedmepackages.registry.FmpRegistries.PARCEL_SEAL.get());
                        if (seal == null) throw new IllegalArgumentException("Residual package has no seal");
                        residuals.put(ItemVariantKey.decode(seal.variant(), registries), box);
                    }
                }
                var state = new CacheState<>(id, level, row.getLong("revision"), cells, orders);
                if (caches.put(id, new CacheRecord(state, owner, residuals)) != null) throw new IllegalArgumentException("Duplicate cache identity");
                if (owner != null && personal.put(owner, id) != null) throw new IllegalArgumentException("Duplicate personal cache");
            }
            var preferences = new HashMap<UUID, Boolean>();
            for (Tag element : compounds(tag, "preferences")) {
                var row = (CompoundTag)element;
                fields(row, Set.of("player", "cache_first")); require(row, "player", Tag.TAG_INT_ARRAY); bool(row, "cache_first");
                if (preferences.put(row.getUUID("player"), row.getBoolean("cache_first")) != null) throw new IllegalArgumentException("Duplicate player preference");
            }
            var redirects = new HashMap<UUID, ParcelRedirect>(); var routeCounts = new HashMap<UUID, Integer>();
            for (Tag element : compounds(tag, "redirects")) {
                var row = (CompoundTag)element; fields(row, Set.of("source", "target", "variants"));
                require(row, "source", Tag.TAG_INT_ARRAY); require(row, "target", Tag.TAG_INT_ARRAY);
                UUID source = row.getUUID("source"), target = row.getUUID("target");
                if (caches.containsKey(source) || !caches.containsKey(target) || caches.get(target).owner() == null)
                    throw new IllegalArgumentException("Invalid redirect ownership");
                var mappings = new HashMap<CacheMerge.Route<ItemVariantKey>, Long>();
                for (Tag mappingTag : compounds(row, "variants")) {
                    var mapping = (CompoundTag)mappingTag; fields(mapping, Set.of("variant", "before", "after"));
                    require(mapping, "variant", Tag.TAG_STRING); require(mapping, "before", Tag.TAG_LONG); require(mapping, "after", Tag.TAG_LONG);
                    var key = new CacheMerge.Route<>(ItemVariantKey.decode(mapping.getString("variant"), registries), mapping.getLong("before"));
                    if (mappings.put(key, mapping.getLong("after")) != null) throw new IllegalArgumentException("Duplicate redirect variant");
                }
                if (redirects.put(source, new ParcelRedirect(target, mappings)) != null) throw new IllegalArgumentException("Duplicate redirect identity");
                if (routeCounts.merge(target, mappings.size(), Math::addExact) > MAX_REDIRECT_VARIANTS) throw new IllegalArgumentException("Too many personal parcel routes");
            }
            // Schema <=6 persisted a vestigial capacity-grant table; the redesign removed that entitlement flow.
            // Tolerate the field so old worlds still migrate their caches, but treat the grants as obsolete.
            ledger.caches = Map.copyOf(caches); ledger.personal = Map.copyOf(personal); ledger.cacheFirst = Map.copyOf(preferences);
            ledger.redirects = Map.copyOf(redirects);
        } catch (RuntimeException failure) {
            ledger.problem = "Cache data rejected: " + failure.getClass().getSimpleName();
            ledger.preserved = tag.copy();
            FeedMePackages.LOGGER.error("FMP cache ledger locked; original data retained", failure);
        }
        return ledger;
    }

    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("Missing or mistyped ledger field: " + key);
    }

    private static ListTag compounds(CompoundTag tag, String key) {
        require(tag, key, Tag.TAG_LIST); var list = (ListTag)tag.get(key);
        if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Wrong ledger list element type: " + key);
        return list;
    }

    private static void bool(CompoundTag tag, String key) {
        require(tag, key, Tag.TAG_BYTE);
        if (tag.getByte(key) != 0 && tag.getByte(key) != 1) throw new IllegalArgumentException("Invalid boolean: " + key);
    }

    private static void fields(CompoundTag tag, Set<String> allowed) {
        if (!allowed.containsAll(tag.getAllKeys())) throw new IllegalArgumentException("Unknown ledger fields");
    }
}
