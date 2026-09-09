# Multi-file modules + UI widgets

> 组件属性、事件 payload 与重渲染契约的权威定义在 [ENGINE.md](ENGINE.md);
> 本页只保留 require 解析规则的速览。

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
