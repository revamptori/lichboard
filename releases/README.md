# Lichboard APK downloads

## Recommended: Standard

**`Lichboard-1.1.0-standard.apk`** — package `dev.lichboard`, launcher/IME name **Lichboard**.

Includes the normal Android keyboard, floating keyboard and bubble, all key panels, clipboard draft, emoji, themes and controls. **No AccessibilityService declaration, implementation class or service metadata is included.**

## Optional: Assist

**`Lichboard-1.1.0-assist.apk`** — package `dev.lichboard.assist`, launcher/IME name **Lichboard Assist**.

Adds explicitly opted-in accessibility editing for supported focused non-password text fields. Install only if you need this sensitive capability. It can coexist with Standard, but normally enable the floating button and select the IME of only the edition you intend to use.

Both APKs appear here only after both editions pass all CI verification stages. Check [`../verification/latest.json`](../verification/latest.json) and `SHA256SUMS`. Superseded 1.0.0 is removed from current downloads after successful 1.1.0 publication; it remains in Git history.

**No Play Protect acceptance is claimed.** A previous combined-capability build was blocked on a user's phone. Keep Play Protect enabled; [read the installation-block guidance](../docs/INSTALLATION-BLOCK.md). Standard minimizes capabilities but cannot guarantee that any installer/security product will accept it.

Both releases are non-debuggable **evaluation builds signed with an Android development certificate**. Production distribution and stable updates require your own private release keystore. Rebuilding on a fresh CI runner may change the evaluation certificate.
