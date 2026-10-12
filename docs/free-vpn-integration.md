# Free VPN integration — engineering plan

## Non-negotiable constraints
- Zero subscription cost; no claim of guaranteed uptime, access, anonymity or permanent availability.
- Never bundle shared private keys or silently route user traffic through volunteer relays.
- Android VPN permission must be requested through VpnService.prepare and user must explicitly approve it.
- One VPN tunnel at a time; disconnect cleanly when the user disables VPN.
- Do not automatically bypass channel access restrictions or claim all streams will play.
- Respect the upstream provider's API terms and licensing; no proprietary Monster VPN integration.

## Mode A: VPN Gate volunteer relays (experimental)
- VPN Gate publicly advertises volunteer OpenVPN endpoints, not WireGuard endpoints.
- Requires a maintained Android OpenVPN tunnel engine, audited dependency licensing, safe configuration parsing and Android VpnService integration.
- Retrieve the public relay list via HTTPS; limit response size and parse defensively.
- Do not trust a relay based solely on its published throughput/uptime; probe and verify before ranking.
- Default to opt-in, with a privacy notice explaining that volunteer operators can observe traffic metadata and may log.
- No VPN Gate tunneling code is included yet.

## Mode B: user-provided WireGuard config (preferred privacy fallback)
- Use the official WireGuard Android tunnel library after verifying compatible versions and licensing.
- Import a local .conf via Android document picker; do not request storage permissions.
- Encrypt stored private configuration with Android Keystore-backed storage; never log private keys.
- Validate keys, endpoint, allowed IPs and DNS before connecting.
- Android VPN permission and foreground lifecycle are mandatory for reliable tunnel management.
- Proton's free tier may restrict location choice and configuration availability; don't assume automatic server selection is supported.
- No WireGuard tunneling code is included yet.

## Server selection
- First implementation: pure ranking logic in VpnServerSelector, tested without Android.
- Only trusted, verified endpoints are eligible; 2-minute cooldown after recent failure.
- Future: handshake and health probes, latency scoring, max retry count, user consent for country switches.
- Do not rotate VPN locations endlessly when a channel URL is broken or returns an HTTP authorization error.

## Release gates
1. Unit tests: endpoint filtering, sorting, cooldown and invalid input.
2. Android instrumentation: VPN permission denied/granted, reconnect, disconnect, lifecycle, network changes.
3. Integration tests using authorized test endpoints and a controlled streaming test server.
4. Security: configuration handling, no credential logging, DNS leak and IPv6 behavior.
5. Device playback QA: HLS, M3U, resume, country filter, import, backup and restore.
6. Do not publish as a working VPN release until real tunnel connectivity is verified.
