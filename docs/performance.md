# Rendering performance

## Purpose and acceptance

The rendering benchmarks provide repeatable local evidence about retained-frame cost, rasterization cost, and allocation behavior.
Their timing results are diagnostic measurements rather than a release promise or a hard continuous-integration threshold.
Performance changes are accepted through deterministic cache-identity, bounded-retention, lifecycle-release, and rendering-parity tests, with JMH used to confirm the practical effect.

## Benchmark methodology

Run the complete suite from the repository root with `./gradlew :quality:benchmarks:jmh`.
The benchmark module uses JMH 1.37 in average-time mode with one worker thread, three one-second warmup iterations, five one-second measurement iterations, and one fork.
The built-in `gc` profiler records normalized allocation in bytes per operation alongside elapsed time in microseconds per operation.
The three logical viewports are `Compact` at 320 by 180, `Windowed` at 854 by 480, and `FullHd` at 1920 by 1080.
Every case uses the same public API-built scene containing a full-viewport background and 54 keyed paint-and-semantics leaves in a centered nine-column grid.

`cleanUiSessionFrame` primes one retained session before measurement and then requests another frame without invalidation.
`dirtyUiSessionFrame` invalidates every representative leaf with all retained phases before requesting the measured frame.
`headlessRasterization` obtains a detached display list from a real retained frame, closes the temporary session, and measures creation of fresh headless pixel storage.

JMH writes structured output to `quality/benchmarks/build/reports/jmh/results.json`.
The report and every other file under `quality/benchmarks/build/` are temporary, untracked build outputs and must not be committed.

## Baseline before fixes

The baseline below was measured from commit `729da15` before the frame-reuse, bounded raster-texture cache, and player-skin lifecycle fixes.
Values are the JMH average-time score and `gc.alloc.rate.norm`, rounded to three decimal places and the nearest byte respectively.

| Benchmark | Viewport | Average time (µs/op) | Allocation (B/op) |
| --- | --- | ---: | ---: |
| Clean session frame | Compact | 7.795 | 20,056 |
| Clean session frame | Windowed | 6.762 | 20,056 |
| Clean session frame | FullHd | 7.487 | 20,056 |
| Dirty session frame | Compact | 14.288 | 58,648 |
| Dirty session frame | Windowed | 13.965 | 58,648 |
| Dirty session frame | FullHd | 14.086 | 58,648 |
| Headless rasterization | Compact | 514.090 | 230,796 |
| Headless rasterization | Windowed | 3,244.911 | 1,643,615 |
| Headless rasterization | FullHd | 17,392.278 | 8,298,495 |

The equal clean-frame allocation across viewport sizes showed that retained node phase caches were working while complete frame and bridge snapshots were still recreated.
The raster allocation scales primarily with the fresh physical pixel array required by the headless facade and is therefore expected to grow with viewport area.

## Why wall-clock time is not a hard CI gate

Microbenchmark time changes with processor model, power management, thermal state, operating-system scheduling, background load, JVM compilation decisions, and virtualized CI contention.
The single-fork configuration keeps a local run practical but does not make a fixed microsecond threshold portable across machines.
CI must therefore not fail solely because an average-time score crosses a fixed wall-clock boundary.
Reviewers should compare runs made on the same controlled host and investigate sustained regressions together with allocation and deterministic structural evidence.

## Deterministic structural gates

The following gates encode the intended ownership and reuse behavior without depending on machine speed.
Existing exact headless-to-Fabric rendering parity tests remain required so caching cannot change pixels, command order, or native presentation.

### Clean frame reuse

An unchanged session with equal constraints and an unchanged whole-tree revision must return the same immutable core frame instance.
The public runtime bridge must also return the same immutable bridge snapshot, draw-command list, and semantics list for that clean frame.
A content rebuild, changed constraints, retained invalidation, or invalidation raised during a frame must prevent stale reuse and produce a fresh snapshot before the next clean frame can be retained.
Failure and close paths must clear cached references so a session cannot keep a released tree or content graph alive.

### Bounded raster texture cache

The Fabric presenter reuses the complete partitioned frame when the immutable draw-command list has referential identity and the logical viewport is equal.
When a mixed portable-and-platform display list changes, a portable layer may also reuse its texture at the same portable-layer index when its immutable command values and viewport are equal; platform layers are still extracted natively every time.
It retains only the currently prepared frame layers and their corresponding dynamic textures rather than accumulating historical frames.
Replacing a prepared frame must trim surplus textures, while detachment, a zero-sized viewport, and terminal screen cleanup must release every retained texture and prepared-layer reference.
Pointer dispatch and inventory or skin refresh coalescing must still invalidate the frame path when observable presentation state changes.

### Player-skin lifecycle

The asynchronous skin completion path must retain only its detached lifecycle target and must not capture the screen, platform bridge, or binding owner after close.
Close must atomically reject late publication, drop a queued completion, clear a committed ready-image snapshot, clear its observer, and remain idempotent.
Owner-thread draining must transfer an accepted completion at most once, and a closed lifecycle must never accept another snapshot commit.

## Post-fix results

The first verified post-fix run was measured from commit `01d0705` after all three fixes and their deterministic tests landed together.
The clean retained-frame path now rounds to zero bytes per operation at every viewport and takes 0.004 microseconds per operation, removing the former 20,056-byte snapshot allocation and reducing measured time by more than 99.9% on this host.
Dirty-frame allocation is unchanged, and its measured time ranges from 1.3% faster to 0.1% slower than the baseline.
Headless rasterization retains the expected viewport-sized pixel allocation and measured between 1.7% and 4.4% faster than the baseline.
These comparisons confirm the intended clean-frame improvement without moving work into the dirty or raster paths; they remain diagnostic measurements subject to the environmental limits described above.

| Benchmark | Viewport | Average time (µs/op) | Allocation (B/op) |
| --- | --- | ---: | ---: |
| Clean session frame | Compact | 0.004 | 0 |
| Clean session frame | Windowed | 0.004 | 0 |
| Clean session frame | FullHd | 0.004 | 0 |
| Dirty session frame | Compact | 14.309 | 58,648 |
| Dirty session frame | Windowed | 13.669 | 58,648 |
| Dirty session frame | FullHd | 13.905 | 58,648 |
| Headless rasterization | Compact | 505.134 | 230,796 |
| Headless rasterization | Windowed | 3,208.000 | 1,643,616 |
| Headless rasterization | FullHd | 16,631.944 | 8,298,491 |
