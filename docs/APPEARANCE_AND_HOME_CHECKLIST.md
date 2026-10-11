# Appearance and Home verification checklist

Elsewhere offers five per-device palettes: **Purple** (the default), **Ocean Blue**, **Forest Green**, **Rose**, and **Slate**. Each palette has complete light and dark semantic color schemes. Theme mode remains **System**, **Light**, or **Dark**.

The palette is a device appearance preference. Changing it does not modify a chat, restart a conversation, clear a draft, regenerate content, or change another phone's co-op appearance.

## Automated coverage

- Purple is used when no preference exists.
- All five palettes persist through DataStore and reload.
- Legacy or type-incompatible preference values fall back safely.
- Core text/background and text/surface pairs meet a 4.5:1 contrast target in light and dark variants.
- Home navigation remains reachable with the new responsive layout.

## Phone verification

1. Launch with no saved appearance preference. Confirm Purple is selected and no white/default-Material flash replaces the branded loading surface.
2. Visit **Settings → Appearance** and select each palette in System, Light, and Dark mode.
3. For every combination, inspect Home, Solo chat, Group chat, Settings, destructive dialogs, text fields, disabled controls, error text, and the co-op status/approval cards.
4. Check narrow portrait, landscape, and the largest practical Android font/display-size settings. Labels may wrap but must not clip controls, hide confirmation actions, or force horizontal scrolling.
5. On Home, verify the Elsewhere doorway mark, recent-chat area, separate **Start Solo** and **Start Group** actions, empty state, and management links are readable and do not imply unavailable cloud/social behavior.
6. Type an unsent draft, switch palette and theme mode, navigate away and back, and restart the activity. Confirm the draft and current chat remain intact.
7. Restart the app and confirm the chosen palette/theme reloads without changing any chat or model setting.
8. If using two phones in local co-op, choose different palettes on each phone and confirm neither device changes the other's preference.

Automated contrast checks do not replace visual inspection for clipping, system-bar integration, image appearance, OLED behavior, or subjective reading comfort.
