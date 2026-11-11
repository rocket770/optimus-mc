# Maps

Export the terrain already loaded around the player:

```text
/o map export MyMap 32
/o map status
/o map cancel
```

Radius defaults to 32 blocks (8-64 supported), rounded to loaded chunks. Exports go to `<game directory>/optimus/maps/`: a world ZIP containing an Anvil save, schematic and editor JSON. Only the selected loaded terrain is included; outside is empty. Capture spans several ticks, not an atomic server backup.

In the editor, **Open map** accepts Optimus ZIP/JSON, Minecraft 1.8.x world ZIPs, `.mca` regions and classic `.schematic` files. Set a center and radius for world/schematic imports. Use one dimension at a time. **Download map** saves terrain; **Export hints** saves the separate route draft.

Native exports retain exact collision routes. Other imports use conservative walking previews; unresolved shapes are obstacles. Modern palette worlds and `.schem` are not supported. Files stay local; large maps may need a smaller import radius.