package jop.ddr3

case class CacheConfig(
    addrWidth: Int = 28,
    dataWidth: Int = 128,
    setCount: Int = 256,
    wayCount: Int = 4,
    // Optional block-fill (GC zeroing) sideband. When true the cache exposes a
    // MemFill slave and streams write-through zero writes straight to memory
    // (invalidating any cached copy) — no per-line eviction cascade. See the
    // FILL_* states in LruCacheCore.
    hasFill: Boolean = false,
    fillAddrWidth: Int = 0   // MemFill word-address width; required when hasFill
) {
  require(dataWidth % 8 == 0, "dataWidth must be byte-aligned")
  require(setCount > 0 && ((setCount & (setCount - 1)) == 0), "setCount must be a power of two")
  require(wayCount == 1 || wayCount == 2 || wayCount == 4, "wayCount must be 1, 2, or 4")
  require(!hasFill || fillAddrWidth > 0, "hasFill requires fillAddrWidth > 0")
}
