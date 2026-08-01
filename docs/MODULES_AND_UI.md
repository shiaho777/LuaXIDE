# Multi-file modules + UI widgets

## require()
- `require("ui")` → built-in UI library
- `require("lib.util")` → `src/lib/util.lua` or `src/lib/util/init.lua`
- Dots become path separators under the project `src/` root
- Results are cached in `package.loaded`
- Packaged APKs extract `assets/lua/**` to app private storage and set the same module root

## New UI nodes
- `ui.list` / `ui.listitem` — vertical lists with optional onClick
- `ui.stack` / `ui.page` — page switching via `selected = "key"`
- `ui.switch` — toggle with label / checked / onChange

## Build / install
- Debug APK signs with on-device generated PKCS12 key
- Install button requests unknown-app permission when needed
- Packaging errors map to readable messages
