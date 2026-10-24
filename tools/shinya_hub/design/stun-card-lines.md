# STUN card presentation — hub.21

The user selected line elements for the card backdrop on 2026-10-10.

- Keep the same blue STUN icon on every rule; display it at 36 dp.
- Rule names use 16 sp sans-serif medium and a 10 dp icon-to-title gap.
- Cards keep the existing 32 dp corners, 20 dp padding and 16 dp gap.
- Use thin pale blue connection paths with rounded turns and two small outline nodes. Restrict them to the right-hand negative space, behind the content.
- The illustration experiment was discarded. No new illustration bitmap is packaged.
- Match the official tools page's low contrast contours, airy white surfaces and restrained color; remove the previous clipped concentric circles.
- The public address remains directly tappable for copying, with a minimum 44 dp hit area.
- The top-right menu retains its 44 dp hit area and 24 dp popup corners. Address history uses the same 28 dp rounded dialog as the editor and confirmation.
- This is a local presentation change in `HubToolsView`; polling, encrypted snapshots, tenant/project scope and router write callbacks keep their established behavior.

References inspected: the supplied official tools screenshot, the decoded `tools_child_bg`, `tools_toolsdiagnosebg` and helper card assets, and the installed hub.20 STUN screenshot.

Final background is drawn by native Android paths in `NetworkSurface`, without image generation. The existing blue primary icon's generation record remains in `stun-icon/generation.json`.
