package jop.ddr3

case class CacheConfig(
    addrWidth: Int = 28,
    dataWidth: Int = 128,
    setCount: Int = 16,
    wayCount: Int = 1
) {
  require(dataWidth % 8 == 0, "dataWidth must be byte-aligned")
  require(setCount > 0 && ((setCount & (setCount - 1)) == 0), "setCount must be a power of two")
  require(wayCount == 1, "current implementation supports wayCount = 1 only")
}
