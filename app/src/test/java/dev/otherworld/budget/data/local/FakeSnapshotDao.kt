package dev.otherworld.budget.data.local

/**
 * Hand-rolled in-memory fake -- same pattern as the other `Fake*` test doubles in this suite
 * (e.g. [dev.otherworld.budget.data.theme.FakeThemePalette]). Lets a test build a working
 * [dev.otherworld.budget.data.repo.SnapshotStore] without Room, so a
 * [dev.otherworld.budget.data.repo.CatalogRepository] test that has no interest in persistence
 * itself doesn't need a Robolectric context just to satisfy the constructor.
 */
class FakeSnapshotDao : SnapshotDao {
    private val rows = mutableMapOf<String, SnapshotEntity>()

    override suspend fun get(kind: String): SnapshotEntity? = rows[kind]
    override suspend fun put(entity: SnapshotEntity) { rows[entity.kind] = entity }
    override suspend fun clear() { rows.clear() }
}
