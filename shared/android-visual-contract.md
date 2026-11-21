# Android visual presentation — Relay Fork

UI-only continuation of the original Liquid Glass language in design-tokens.json.
No wire format, state machine, permission, enrollment, update or routing policy
changes are part of this pass. Windows presentation is outside its scope.

- Retain the original neutral gradient, teal accent, system typography, glass
  panels, 12/16/24 dp radii and 8/12/16/24 dp spacing. Do not introduce a second
  palette for fork controls. Material controls inherit these same semantic colors.
- Always give text a readable theme foreground, including implicit content and
  Material control labels. System theme resolves through the same dark/light
  palette as explicit overrides. Primary/secondary text must meet 4.5:1 on the
  intended glass surfaces; do not fade active labels for visual hierarchy.
- Sharing state, pairing code and Start/Stop remain the principal content/actions.
  Authorized devices use matching glass cards with clear status and quiet actions.
- Maximum clients, appearance and diagnostics/update actions
  belong in the existing Advanced disclosure. Preserve all callbacks and their
  enablement rules; moving a control never changes a setting.
- The complete server/client fingerprint stays available for comparison. Use a
  separate explanation and selectable, left-to-right monospace value with natural
  wrapping, no truncation and no change to the actual identity string.
- Preserve EN/FA, RTL, 48 dp touch targets, selected/disabled/toggle semantics,
  scalable text, reduced-motion behavior and explicit security warning content.

Host-rendered Compose previews are visual review artifacts, not physical-device
verification. Final visual acceptance requires a real device in light/dark/system
themes, EN/FA, larger fonts, sharing states and expanded Advanced/approval views.
