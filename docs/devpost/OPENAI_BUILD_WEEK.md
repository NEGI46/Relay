# OpenAI Build Week submission checklist

Target: OpenAI Build Week on Devpost. Official submission deadline is July
21, 2026 at 5:00 PM Pacific Time. Confirm the live Devpost page before
submitting because dates and required fields are external state.

## Relay submission package

- [ ] Use the Fuchu Town v1 scope in `docs/V1_FUCHU_PILOT.md` as the demo source of truth; do not claim next-version items as complete.

- [ ] Choose one track; Relay is most naturally an app for your life or
  education project depending on the final story.
- [ ] Public repository or a reviewer-accessible build.
- [ ] README with problem, disaster scenario, architecture, setup, and known
  hardware limitations.
- [ ] Short public demo video showing offline map, encrypted rescue report,
  multi-hop/QR fallback, and receipt or delivery state.
- [ ] No music-only screencast; narrate the product behavior and show the
  working result.
- [ ] List the exact Codex/GPT-5.6 contribution and dates. Preserve commit
  history and this repository's test/implementation commits as evidence.
- [ ] Explain that real-device BLE/Nearby validation remains an explicit
  hardware-lab boundary if it is not available in the demo environment.

## Final verification

Run the local host checks, `:shared:jvmTest`, distribution verification, and a
clean demo build. Record command output and the commit hash in the submission
notes. Never include private keys, Keystore material, user data, or plaintext
rescue payloads in screenshots or the video.
