# FastScroller third-party notice

This directory contains the source portions of `fastscroller-compose-multiplatform` used by Finance Tracker.

- Upstream: [oikvpqya/fastscroller-compose-multiplatform](https://github.com/oikvpqya/fastscroller-compose-multiplatform)
- Upstream version: `0.3.2`
- Source revision: `63d51ef083321eb88fb08068627f70408355f9d6`
- License: Apache License 2.0; see [`LICENSE`](LICENSE).

The upstream copyright and license headers are retained in the copied source files. Finance Tracker's local changes are:

- `ScrollbarAdapter.kt` uses a regular common Kotlin interface instead of the unused platform `expect`/`actual` adapter implementations. The ledger provides its own ordinal adapter.
- `Scrollbar.kt` draws a 4 dp track and thumb centered inside the 40 dp pointer target. Upstream applied the hit-target width and minimum constraints to the visual nodes as well, which widened the visible rail.
- `material3/DefaultScrollbarStyle.kt` resolves `MaterialTheme` in the application module and passes Compose colors directly. The original separately compiled Material 3 artifact failed at runtime in this project's Wasm build while accessing the theme's backing field. Its scrollbar thickness is set to 4 dp to match the ledger rail.

The remaining files are copied from the upstream common sources and retain their upstream headers.
