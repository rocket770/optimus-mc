# Scripts

- `run-core-tests.ps1` / `.sh`: core Java checks without Forge dependencies.
- `test-*.js`: editor loader, graph/UI and map-import tests.
- `export-pit-world.py`: rebuild the example world and schematic from the test capture.
- `build-parkour-view.py`: make a local replay page from navigation CSVs.
- `verify-parkour-view.cjs` / `render-parkour-qa.py`: inspect that report's geometry and images.

Generated reports belong in `diagnostics/` or `build/`, not the source tree.