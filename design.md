# LabProbe Android UI material contract

- Scope is limited to Home, Devices, Ping, Device detail, and representative Sheet/Dialog reference screens.
- Preserve all typography sizes, weights, line heights, spacing, alignment, content order, device artwork, routes, and interactions.
- Android uses static restrained frosted glass only on navigation, the Home primary status surface, Sheet, and Dialog. No Liquid Glass, refraction, morphing, glow, or glass animation.
- Ordinary lists, forms, charts, and technical data remain opaque surfaces. Never place glass inside glass.
- Visual priority is spacing, tonal surface contrast, one glass layer, a weak rounded shadow, then a border only when needed.
- Phase one uses a mist-grey light palette rather than bright white. Dark mode is intentionally out of scope. Purple, pink, neon, and saturated gradients are prohibited.
- Every elevated surface owns exactly one rounded shape and one shadow source. Square shadow backplates and duplicate icon backplates fail review.
- October 5 scoped refinement: router hero session counts occupy a full-width row below the artwork; rates and units remain readable. Child report status and local removal dialog use content-sized spacing. Preserve artwork, colors, routes and all other page layouts.
- Unsupported blur falls back directly to a static frosted translucent surface with the same shape and contrast.
- October 12 ShinyaHub additions: match official account-page proportions with modest body text, compact card spacing, and content-sized centered dialogs. Additional HUB dialogs use a pale blue-to-cyan gradient, a centered medium title, readable body copy, and equal-width outlined pill actions. Long forms scroll independently of their visible action footer; contextual menus remain anchored to the tapped control. These rules apply only to the added ShinyaHub screens.
- October 16 LabProbeApp refinement: centered dialogs use a compact, content-sized pale blue-to-cyan gradient card, a modest centered title, readable body text, and a visible action row. Keep a long dialog's content scrollable without hiding its actions. This is an explicit exception to the earlier gradient restriction and applies to centered LabProbeApp dialogs; bottom sheets and anchored menus retain their own layout.
- Centered dialog action containers provide alignment only. The action button itself owns its outline or fill; never draw an additional pill border around an existing button. Dialog shells have no added fine-line outline; their gradient and soft shadow provide separation.
- October 16 button refinement: use 13sp semibold action text and restrained vertical padding. Shared primary buttons use a 40dp visual minimum while retaining Material touch targets; allow text to increase height when accessibility font scaling needs it. Do not reduce fields or unrelated content to achieve thinner buttons. Service-card titles take priority over secondary badges; avoid repeating the same protocol name in title, subtitle, and badges.
- Confirmation dialogs use a 16sp semibold title, 13sp regular body with 20sp line height, 18dp between title and body, and 16dp between body and actions. Keep content-sized height and readable separation; compact does not mean squeezing all three sections together.
- October 16 child internet refinement: compact summary and schedule cards; online devices first with stable ordering within each group. Known offline devices settle their last usage read without a persistent syncing label. Usage-session ranges and durations share one row in a scrollable dialog. Detail headers use 16sp medium titles and rounded-square device artwork without a circular backplate.
- All deletion and clear-history icons use the same fine-line trash-can vector (lid, handle, two ribs), inspired by the official app. No decorative circle behind delete icons. Preserve destructive-action confirmations, labels, colors and click behavior.

- Child internet top cards retain their existing laptop/child graphic and pale blue-to-cyan gradient while tightening spacing; do not flatten them into plain white text cards.

## October 18 — Favorites and editing dialogs (explicit user contract)

- Favorites are user-owned cards. STUN, webhook, IPv6 mapping and Home VPN/penetration sources must never create cards automatically. Additions require an explicit user action. Except for the explicitly requested one-time cleanup below, synchronization updates only bound addresses and retains user metadata.
- Favorite editors give name and description separate full-width rows. Address fields support manual input and a cascading source selector: category, then original remark/name plus the actual host and port. LAN and WAN bindings are independent and scoped to the owning Hub/router workspace. Preserve custom path/query/fragment suffixes on address changes. A missing source must retain the card and last address, with a clear unavailable state; never substitute another device or source by matching IP alone.
- Favorite cards show name and current LAN/WAN address (LAN by default). Remove the redundant protocol footer; show an extra status line only for unavailable, stopped or pending sources. The left rounded-square icon opens editing. Tapping the right address area directly copies the external/WAN address and navigates, without any popup or extra choice. Copy always uses the external/WAN endpoint regardless of the displayed LAN/WAN mode; if absent, explain that no external address is configured and never silently copy LAN. Navigate retains the existing service detection, reachability checks, LAN/WAN selection and launch/fallback behavior; only the entry gesture changes. Remove the three-dot control. Long press enters arrangement mode with a small top-right remove cross and drag reordering; Done or Back exits. Removal, replacement and gap filling use gentle 240–320ms fades/placement transitions, without shakes or square shadows. These gesture changes apply only to Favorites; other pages retain their existing long-press actions and menus.
- All card long-press feedback is a faint cool-blue rounded perimeter halo, without elevation changes, grey/black rectangular plates or heavy shadows. This narrow interaction-feedback exception replaces the earlier prohibition on all glow; decorative page glow remains prohibited.
- All anchored menus/selectors must fit the visible safe area. Prefer below the anchor when it fits; otherwise open above it. Bound height and scroll internally for long lists, preserving all final items and actions. Account for navigation bars and the keyboard; never place an inaccessible menu below the screen. Compact contextual actions use 30dp visual rows and 12dp horizontal padding, sized to the longest label rather than a wide empty panel. Multi-line source rows may grow to keep addresses readable.
- Editing dialogs follow the compact child-internet reference: one pale blue-to-cyan-to-white rounded card, no outer fine-line outline or nested button outlines. Use 16sp semibold centered titles, 13sp regular body/field text with 20sp line height, 12sp field labels, 18dp side padding, 10–12dp form-row gaps, 18dp title-to-body gap and 16dp body-to-action gap. Buttons use 13sp semibold text and a 40dp visual minimum with accessible touch handling. Long content scrolls within the available safe height; actions must remain reachable. Size from content and available space, never a fixed oversized blank panel.
- Favorite icons retain the supplied brand artwork and aspect ratio. Render-time source bounds trim empty margins; use Fit rather than cropping the artwork. No circular backplate or duplicate decorative border. Specific brands/services take precedence over generic name/protocol matching; short aliases match complete tokens. Explicitly chosen/uploaded icons are locked through name changes and synchronization, with a Restore automatic matching action.
- Confirmed asset identities: teal triangle A = Alist; purple square with white ring = iStoreOS; blue square with white interlaced mark = NodeBaby/节点小宝; UGREEN uses the supplied metallic NAS icon. Presets also include fnOS, Lucky, DSM, OpenWrt, NETGEAR, TP-Link, Ruijie, Docker, update repository, SSH, Windows/RDP, generic NAS, Home Assistant, WireGuard and OpenVPN.

- Refresh buttons animate by rotating the refresh glyph around its own fixed center with linear timing. Keep the icon container fixed; no vertical translation, bobbing, scale pulses or moving transform origin.

## October 18 — Second refinement

- Favorite feedback is only a thin, faint line at the full card perimeter. Nested icon/text click regions have no independent halo, ripple, scale or elevation change.
- Favorites refresh as one coherent page snapshot with one header refresh indicator; no individual card loading/expansion animation. Preserve user edits/reorder/removal while data requests are in flight.
- All displayed favorite cards use the largest natural content height in the collection, including wrapped IPv6/URLs. Do not truncate addresses to force smaller cards.
- This release performs one workspace-scoped cleanup of existing source-linked favorites (STUN/mapping/webhook/home/bindings). Keep manual favorites and manual domain-only associations. Never delete underlying rules. Newly selected bindings survive future reads/refreshes; sources cannot create cards automatically.
- Address selectors show `name - IP:port`, grouped by source, then individual rule. HTTP/SSH/plain host:port and bracketed IPv6 are valid choices. Selecting a source fills the address and follows future endpoint changes without discarding custom suffixes.
- Domain association stays a normal full-width dropdown field, separate from the address source cascade.
- Dropdown popups are text-only: centered 13sp labels with 18sp line height, 30dp minimum single-line rows, 12dp side padding, 8dp corners, including source cascades. Long options may wrap/grow. No decorative or selection icons inside popup rows. Keep opening arrows in fields. Preserve functional history removal as a text action.
- Popup placement remains anchored and flips upward near the bottom; long menus scroll within available safe space. Router switching floats with an additional 20dp gap above its former bottom edge and 12dp card vertical padding.
- Small-copy changes are limited to Favorites, Home DDNS, Home Child Internet and STUN. Keep operational errors and timing that affects decisions; remove repeated explanations and duplicate counts. Other pages' copy stays unchanged.
- Navigation A was approved: 32dp pale-blue circle, 20dp chevron for internal navigation / 16dp outward arrow for existing external-open actions, 48dp parent hit region. Replace only places that already had a navigation indicator; do not add arrows to other cards, change routes, or restyle dropdown/expand/back/data-flow arrows. No duplicate circular shells.

- Favorites editor: keep icon preview, preset selector and upload on one compact row. Automatic matching remains a separate compact action on the right of the icon label; show the image URL field only for URL icons. Domain remains a full-width field. Dropdown popup corners are 8dp, including source cascades, preserving compact centered text rows and upward placement. Existing STUN external-open icons use the approved pale-blue circle.

## October 18 — Floating-surface corner refinement

- Scope: popup menus, cascading dropdowns, dropdown fields, centered editor/confirmation cards and bottom popup sheets only. Keep all page-level large/compact cards, hero cards, device artwork, badges, pill controls and circular navigation indicators unchanged.
- Use LabPopupShapes: Menu 8dp, popup Card 12dp, selector/editor Field 10dp, docked Sheet top corners 12dp. Floating router picker uses popup Card with its existing bottom gap. Use the same shape for clipping and shadow; no second shell.
- Explicit per-page menu overrides must use the shared Menu shape. Keep 30dp single-line menu rows, 12dp horizontal padding, wrapping for long addresses, and upward placement when space below is insufficient.

## October 18 — Post-336 preview adjustments

- Keep page-level cards unchanged. Router picker bottom gap is 14dp, moving it down 6dp.
- Popup menus retain 8dp corners; short menus have at least 80dp width and 16dp horizontal text padding, with 28dp minimum single-line rows. Long values can wrap. Keep upward placement near the bottom.
- Source-follow status and detach action use a compact 22–24dp row without Material TextButton minimum-height whitespace.
- Selecting a LAN source fills its paired WAN source only when WAN has not been explicitly configured by the user. Match source kind and stable rule ID, never name/IP. Explicit WAN selection/manual host-port editing takes precedence. Do not erase an existing WAN value if no live paired address exists.
- Editing only a bound WAN suffix keeps its binding. Synchronization replaces only host and port, retaining scheme, raw path/query/fragment and user info. Editing host/port detaches following and preserves the manual endpoint.
- Generic favorite router and camera use the two supplied PNG assets, aspect-fit and no circular wrapper; manual icon selection remains available.

- The supplied generic router favorite preset is labeled “路由器” in all user-facing selectors and previews (no “通用” prefix).

- Short popup menu refinement: 64dp minimum width (replacing the overly wide 80dp preview), 14dp horizontal text padding and 24dp minimum single-line rows. Keep 13sp text and existing wrapping; long options expand naturally. Page cards/fields are unaffected.

## October 18 — Lucky basic management

- Favorites exposes Lucky as an independent service connection, never as router configuration. Preserve its complete URL/security entry; do not apply router channel normalization. Hub owns credentials and connection IDs shared across router workers.
- Basic proxy editing includes name, domain, backend URL/source selector, HTTPS and port. Keep source selection in the backend field; preserve custom URL suffixes. Preserve advanced upstream fields; multi-domain/multi-backend rules direct editing to Lucky's own backend.
- Proxy cards have compact name/status, public URL and backend rows. Actions remain in the shared text-only Edit/Enable-or-disable/Delete menu. Use one page refresh, fixed-center rotation and retain the last snapshot on errors.
- Certificate application asks for domains and a DNS account; Lucky performs issuance and scheduled renewal. Label a manual request “检查续签”; do not promise unconditional renewal. Show pending/failure/expiry honestly. Secrets/private keys never enter list DTOs.
- Use existing dialog, field and menu shapes/typography. Do not change unrelated page cards or router navigation. Saving a connection verifies actual authenticated module reads, not just the version endpoint.
- Lucky connection dialog refinement: use 40dp natural-minimum text fields (grow with font scaling), 8dp between form groups, and one compact row for Test connection plus its wrapping result. Avoid Material TextButton minimum-height whitespace in this row. This exception is local to Lucky connection settings; other editors retain their existing sizing.
- Lucky disconnected page: omit the empty connection selector; show one centered, content-sized onboarding surface with the supplied Lucky artwork, two concise capability tiles and one Add connection action. Use an 18sp semibold page title and 20dp outlined toolbar settings icon with explicit blue tint. Connected instances use a full-width connection selector and a compact segmented tab row. No duplicate connection prompts or loose explanatory paragraphs.
- Lucky rules preserve subrule identity: show each public domain immediately with its own backend(s). Never flatten domains and backends into unrelated lists or infer pairs by index. Keep multi-backend groups explicit; file/other services have meaningful labels instead of an empty arrow.
- Lucky cards permit an opaque, very light white-to-blue gradient. Long-press text uses selection handles and a native Copy selected/Copy all toolbar; the overflow menu also exposes Copy all. Apply this only to Lucky cards.
- All Lucky controls explicitly use blue/cyan primary, container and selected colors; no purple or pink default Material controls. OpenToken starts masked and has a visibility toggle; showing it never changes or persists the input.
- Lucky opens from its last successful scoped display snapshot, then refreshes quietly. Persist only whitelisted display fields; scope by Hub/auth identity and stable Lucky instance ID, preserve selection/tab, retain content on errors and evict removed instances.
- Home Child Internet keeps the guarded total as its value and shows `X台在线` below using actual presence, independently of blocked/allowed schedule. Home DDNS keeps its record total and shows `X台异常`, or `正常` when no record is abnormal; unknown/failed reads retain their diagnostic labels.
- Lucky rule groups and individual subrules have explicit blue on/off switches. Their compact text-only menus contain only Edit, Copy and Delete, without a name header or duplicated enable action. Child Copy includes only that child's domain/backend group. Child edits preserve sibling rules and advanced fields with versioned snapshots. Shared listener port and TLS remain parent settings. Preserve large card shapes and the existing compact popup corner/spacing contract.
- Lucky subrule mappings use a continuous left-hand domain/backend stack with 2dp between text rows, 6dp between multiple domain pairs, and compact 2dp divider padding. Keep the switch and overflow together on the right; place the disabled caption directly below its switch, never between domain and backend. URLs wrap naturally without truncation; popup menu spacing remains unchanged.

## October 18 — Lucky proxy form and menu refinement

- The basic Lucky reverse-proxy editor uses compact 44dp single-line fields; preserve existing labels, selection behavior, and saved values. Other Lucky dialogs retain their current field sizing.
- Lucky dropdowns use a 68dp minimum width, a small increase from the shared 64dp minimum. Long options continue to size naturally; row height, text padding, corners, and every non-Lucky menu stay unchanged.

## October 18 — Star Home Hub STUN refinement

- This exception applies only to the Star Home Hub native STUN action popup: 92dp width, 12dp corners, 8dp outer horizontal inset, 10dp text horizontal padding, 34dp rows and 6dp outer vertical inset. Preserve centered text, subtle separators, red Delete, anchored upward placement and existing large cards. Other app menus retain their own contracts.
- Star Home Hub STUN refresh uses two rounded circular arcs in a fixed 44dp control, rotating around the view's exact center with linear timing. Each arc connects continuously to one exterior arrow wing, matching the user's paired-arrow reference; omit inner wings. Never rotate a text glyph with font-baseline offsets. DDNS/firewall SVG refresh uses the SVG view box center explicitly.

## October 19 — Star Home Hub STUN refresh refinement

- Follow the user's paired circular-arrow reference: two balanced arcs, each with only its outer wing. Calculate each wing from the exact arc endpoint so it cannot detach. Use round stroke caps and joins, omit inner wings, and do not widen the arrowhead. Inspect a raster preview at icon size before packaging. Preserve fixed-center rotation and existing refresh behavior.


## October 20 — LabProbe action menus aligned with Star Home HUB

- Edit/copy/enable-disable/delete and child-control action popups use the Star Home HUB proportions with the user's narrower width refinement: 88dp minimum width, 34dp minimum row height, 14sp text with 20sp line height, 12dp outer corners, 8dp outer horizontal inset and 10dp row horizontal padding. Keep centered labels, white surfaces, restrained shadows and red destructive text. Long labels may grow the menu naturally; font scaling may grow rows.
- This supersedes the earlier compact action-menu sizing and Lucky action-menu width. Field selectors, address histories, source cascades and instance/account pickers retain their existing sizing and placement. Preserve all callbacks and safe-area flipping/scrolling.

## November 24 — Mapping and STUN density refinement

- IPv6 mapping overview uses a 72dp surface with compact 12sp stats and Agent status; a fine vertical divider separates the statistics segment from Agent status. Filter chips use 32dp height and tighter horizontal spacing.
- Mapping rule cards show only connection count, cumulative upload and cumulative download. Packet counters remain available in details, not on the card. Endpoint labels and addresses share one row; the port remains inside the flowing address string instead of pinning to the card edge.
- STUN cards remove the repeated dynamic-address badge, duplicated native-mechanism caption and the refresh/add divider. Keep the public endpoint, one concise target line, traffic for Relay rules, a short router-direct footer for native rules, history and quick actions. Initial sync shows skeleton cards instead of a blank page.
- UPnP default route uses a compact two-button AUTO/WAN selector instead of a dropdown. Preserve the existing mutation callbacks and disabled-service behavior.

## November 25 — STUN list card balance refinement

- STUN cards reuse the mapping/favorite application recognition and icon presets. The header shows the application icon, rule title, service/protocol subtitle, a single-line status pill and the existing overflow menu.
- The public endpoint is bold green text directly on the white card, followed by one forwarding route line. Do not repeat the forwarding-mode caption or add an endpoint background panel.
- LabRelay proxy cards use a thin divider and one compact footer row with cumulative download/upload chips at left and history, quick-access and copy icons at right. Traffic values keep their units on one line and must not be labeled as realtime speed.
- Router-direct cards place the endpoint/route block beside the same action icons, with no traffic chips, divider or empty footer. Card height follows content.
- Action icons use uniform 32dp light rounded backgrounds. Preserve all existing callbacks, conditional quick-access behavior, real cumulative counters and long-address wrapping at 360dp and 393dp.

## November 25 — NAT diagnostic density refinement

- NAT parameters use a 2×2 grid with the left column slightly wider. Labels sit above the fields; fields contain only the blue icon, value and required arrow. The start action remains full-width with the existing blue gradient.
- Diagnostic process and analysis results share a 42:58 row. The process column uses a shallow blue-gray timeline with real task/log timestamps only; the result column uses compact wrapped value tiles and a real status pill.
- Detection logs use a shallow blue-gray monospace surface with content-sized height up to the existing 260dp limit and internal scrolling.
- History remains five real records with result/date on the first line and external address, protocol, actual WAN and STUN port on the second. Delete stays at the far right; rows use thin separators rather than nested cards.
