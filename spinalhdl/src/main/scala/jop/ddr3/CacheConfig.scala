package jop.ddr3

case class CacheConfig(
    addrWidth: Int = 28,
    dataWidth: Int = 128,
    setCount: Int = 256,
    wayCount: Int = 4
) {
  require(dataWidth % 8 == 0, "dataWidth must be byte-aligned")
  require(setCount > 0 && ((setCount & (setCount - 1)) == 0), "setCount must be a power of two")
  require(wayCount == 1 || wayCount == 2 || wayCount == 4, "wayCount must be 1, 2, or 4")
}
