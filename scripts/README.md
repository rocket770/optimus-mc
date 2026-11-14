# Scripts

- `run-core-tests.ps1` / `.sh`: core Java checks without Forge dependencies.
- `test-*.js`: editor loader, graph/UI and map-import tests.
- `export-pit-world.py`: rebuild the example world and schematic from the test capture.
- `build-parkour-view.py`: make a local replay page from navigation CSVs.
- `verify-parkour-view.cjs` / `render-parkour-qa.py`: inspect that report's geometry and images.

Generated reports belong in `diagnostics/` or `build/`, not the source tree.

- `prepare-fight-data.ps1`: prepare completed recorded lives and aim/timing diagnostics.
- `train-fight-model.ps1`: fit a movement candidate from the latest prepared dataset without installing it.
- `find-python.ps1`: shared Python 3.9+ discovery for the training wrappers.

See [ML collection and training](../docs/ML.md).

Headless provisioning and launch code lives in [headless/](../headless/README.md).