# LabProbe Android UI material contract

- Scope is limited to Home, Devices, Ping, Device detail, and representative Sheet/Dialog reference screens.
- Preserve all typography sizes, weights, line heights, spacing, alignment, content order, device artwork, routes, and interactions.
- Android uses static restrained frosted glass only on navigation, the Home primary status surface, Sheet, and Dialog. No Liquid Glass, refraction, morphing, glow, or glass animation.
- Ordinary lists, forms, charts, and technical data remain opaque surfaces. Never place glass inside glass.
- Visual priority is spacing, tonal surface contrast, one glass layer, a weak rounded shadow, then a border only when needed.
- Phase one uses a mist-grey light palette rather than bright white. Dark mode is intentionally out of scope. Purple, pink, neon, and saturated gradients are prohibited.
- Every elevated surface owns exactly one rounded shape and one shadow source. Square shadow backplates and duplicate icon backplates fail review.
- Unsupported blur falls back directly to a static frosted translucent surface with the same shape and contrast.
- Router settings entry and return use paired, opaque horizontal slides. Determine direction from the source and destination; do not blend whole-page opacity.
- Device detail keeps existing artwork and its rounded backing without extra orbit rings. Its hero owns connection type, “协商速率”, signal and continuous online duration; the connection card owns SSID and today's traffic, and presence statistics own daily totals and history. Do not repeat these metrics across adjacent cards.
- Device detail and its presence records share one clock, event snapshot and duration formatter. Rate denominators include only elapsed local-calendar time; the displayed peak is the actual observed maximum, independent of the chart axis minimum. Missing records remain explicitly unknown.
- Page subtitles describe their purpose; do not expose caching or rendering implementation details.
