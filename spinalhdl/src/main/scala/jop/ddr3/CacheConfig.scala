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
    fillAddrWidth: Int = 0,  // MemFill word-address width; required when hasFill
    // Width of the frontend request tag. 0 means the frontend carries no id and
    // the master must keep one request outstanding at a time; anything larger
    // lets responses be matched to requests by tag instead of by arrival order.
    // Must cover the id space of whoever drives the frontend (BmbCacheBridge).
    idWidth: Int = 0,
    // How many misses may be in flight at once. The miss FSM never waits on
    // memory whatever this is set to, so even 1 lets a hit be served while a
    // miss is outstanding; larger values let the misses themselves overlap.
    // Only reachable when idWidth > 0 — an id-less master gets in-order
    // responses, and that means one request at a time. See LruCacheCore.
    mshrCount: Int = 1
) {
  require(dataWidth % 8 == 0, "dataWidth must be byte-aligned")
  require(setCount > 0 && ((setCount & (setCount - 1)) == 0), "setCount must be a power of two")
  require(wayCount == 1 || wayCount == 2 || wayCount == 4, "wayCount must be 1, 2, or 4")
  require(!hasFill || fillAddrWidth > 0, "hasFill requires fillAddrWidth > 0")
  require(idWidth >= 0, "idWidth must not be negative")
  require(mshrCount >= 1, "mshrCount must be at least 1")
  require(mshrCount == 1 || idWidth > 0,
    "mshrCount > 1 needs idWidth > 0: overlapping misses complete out of order, " +
    "which only a tagged frontend can make sense of")
}
