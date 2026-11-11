# Vendored compression dependency

`fflate.js` is the unmodified UMD build of [fflate 0.8.2](https://github.com/101arrowz/fflate/tree/v0.8.2), distributed under the accompanying MIT license. It supplies ZIP, GZIP and zlib decompression for local map imports. The file is bundled so the existing editor works offline without a CDN or Node.js installation.

Source: `https://cdn.jsdelivr.net/npm/fflate@0.8.2/umd/index.js`. The editor's own bounded NBT and map readers live in `editor/map/`. No imported scripts are evaluated.