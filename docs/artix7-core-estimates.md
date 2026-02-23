# JOP Core Count Estimates for Artix-7 FPGAs

Based on measured dual-core DDR3 SMP utilization on XC7A35T-2 (2026-02-23).
Both cores verified running NCoreHelloWorld on real DDR3 hardware (Alchitry Au V2).

## Measured Data Points

| Config | LUT | FF | BRAM (tiles) | WNS | WHS |
|--------|-----|-----|------|-----|-----|
| 1-core DDR3 | 12,021 (57.8%) | 10,279 (24.7%) | 12.5 (25%) | +0.115 ns | +0.025 ns |
| 2-core DDR3 SMP | 16,454 (79.1%) | 13,215 (31.8%) | 15.0 (30%) | +0.228 ns | +0.043 ns |

### Per-Core Cost (delta from 1-core to 2-core)

| Resource | Per additional core |
|----------|-------------------|
| LUT | ~4,400 |
| FF | ~2,900 |
| BRAM | ~2.5 tiles |

### Base Infrastructure (shared, independent of core count)

Includes: MIG IP, ClkWiz, BmbCacheBridge, LruCacheCore (16KB 4-way), CacheToMigAdapter,
DiagUart, hang detector, LED/heartbeat logic. Approximately 7,600 LUT, 7,400 FF, 10 BRAM.

## Artix-7 Family Resources

| Device | Logic Cells | 6-input LUTs | Flip-Flops | Block RAM (36Kb tiles) | Block RAM (Kb) | DSP48E1 |
|--------|------------|--------------|-----------|----------------------|---------------|---------|
| XC7A12T | 12,800 | 8,000 | 16,000 | 20 | 720 | 40 |
| XC7A15T | 16,640 | 10,400 | 20,800 | 25 | 900 | 45 |
| XC7A25T | 23,360 | 14,600 | 29,200 | 45 | 1,620 | 80 |
| XC7A35T | 33,280 | 20,800 | 41,600 | 50 | 1,800 | 90 |
| XC7A50T | 52,160 | 32,600 | 65,200 | 75 | 2,700 | 120 |
| XC7A75T | 75,520 | 47,200 | 94,400 | 105 | 3,780 | 180 |
| XC7A100T | 101,440 | 63,400 | 126,800 | 135 | 4,860 | 240 |
| XC7A200T | 215,360 | 134,600 | 269,200 | 365 | 13,140 | 740 |

Source: Xilinx 7 Series FPGAs Data Sheet: Overview (DS180)

## Estimated Core Counts

Model: `LUT(N) = 7,600 + N * 4,400 + arbiter_overhead(N)`

Arbiter overhead is small (round-robin BmbArbiter, CmpSync) — estimated ~200 LUT per
additional core beyond 2. Target utilization: 85% LUT (leaves room for routing/timing closure).

| Device | LUTs | 85% LUT budget | Est. max cores | Limiting factor | Notes |
|--------|------|---------------|----------------|-----------------|-------|
| XC7A12T | 8,000 | 6,800 | 0 | LUT | Base alone needs ~12K LUT |
| XC7A15T | 10,400 | 8,840 | 0 | LUT | Base alone needs ~12K LUT |
| XC7A25T | 14,600 | 12,410 | 1 | LUT | Tight; single-core only |
| **XC7A35T** | **20,800** | **17,680** | **2** | **LUT** | **Measured: 79% at 2 cores** |
| XC7A50T | 32,600 | 27,710 | 4 | LUT | Comfortable headroom |
| XC7A75T | 47,200 | 40,120 | 7 | LUT | Good for multi-core |
| XC7A100T | 63,400 | 53,890 | 10 | LUT | SMP scaling sweet spot |
| XC7A200T | 134,600 | 114,410 | 23 | LUT | May hit arbiter/bus limits first |

### Assumptions and Caveats

1. **MIG IP**: All estimates assume Xilinx MIG 7 Series DDR3 controller. Devices without
   DDR3 support (XC7A12T, XC7A15T) or without suitable I/O banks would need a different
   memory controller (SRAM, HyperRAM, etc.) with different base cost.

2. **BRAM is not limiting**: Even at 23 cores on XC7A200T, BRAM usage would be
   ~10 + 23*2.5 = 67.5 tiles out of 365 (18%). The 16KB shared L2 cache and per-core
   microcode ROMs/stack RAMs fit comfortably.

3. **Bus contention**: Beyond ~8 cores, the single shared DDR3 cache port becomes a
   bottleneck. Possible mitigations: multi-bank cache, split L1/L2, or NoC topology.

4. **Timing closure**: The XC7A35T achieved WNS=+0.228ns at 79% LUT. Higher utilization
   (>85%) makes timing closure harder. Larger devices have more routing resources per LUT,
   so the 85% target is conservative for XC7A100T/XC7A200T.

5. **Package constraints**: Not all devices are available in all packages. DDR3 requires
   sufficient I/O pins in the right banks. Check package/pinout compatibility.

6. **burstLen=0 SMP limitation**: SMP requires `burstLen >= 4` due to a BC_FILL pipelining
   issue with single-word mode and the BMB arbiter. This adds ~200 LUT vs burstLen=0 per
   core (included in the estimates above).

### Cost Reduction Options (if LUT-limited)

If a target device is tight on LUTs:

| Optimization | LUT savings per core | Trade-off |
|-------------|---------------------|-----------|
| Reduce A$ entries (`acacheWayBits=3`, 8 from 16) | ~500 | More A$ misses |
| Reduce O$ entries (`ocacheWayBits=3`, 8 from 16) | ~400 | More O$ misses |
| Reduce shared cache to 128 sets (from 256) | ~200 (shared) | 8KB instead of 16KB |
| Reduce shared cache to 2-way (from 4-way) | ~400 + 2 BRAM (shared) | More evictions |
| Disable O$ (`useOcache=false`) | ~800 | Object field access always goes to main memory |
