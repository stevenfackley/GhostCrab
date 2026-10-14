# ADR-006: iOS App Transport Security: Local Networking Only, No Arbitrary Loads

**Status:** Accepted  
**Date:** 2026-10-14

> ADR-005 is taken by `0005-additive-repository-methods.md` (`docs/IMPLEMENTATION_PLAN.md` §7 had also reserved that number for an onboarding decision), so this decision is 006.

## Context

GhostCrab talks to self-hosted OpenClaw gateways, often over plain HTTP on a LAN (`http://192.168.x.y:18789`, `http://gateway.local:18789`). Android permits cleartext everywhere (`network_security_config.xml` base-config) and gates only *public IP literals* with `CleartextPublicIpInterceptor` plus a user toggle. iOS ports that as `CleartextPublicIPGuard`, a `URLProtocol`.

On iOS, App Transport Security (ATS) runs before any app networking code. With no exceptions configured, ATS:

- allows plain HTTP to IP literals, unqualified host names (`http://nas:18789`) and `.local` names;
- blocks plain HTTP to every qualified domain name: public DNS names, Tailscale MagicDNS (`*.ts.net`), split-horizon and `home.arpa` names. It fails with `NSURLErrorAppTransportSecurityRequiresSecureConnection` (-1022), and `OpenClawAPIClient` surfaces that as `GatewayError.unreachable`.

## Decision

1. `Info.plist` declares `NSAppTransportSecurity = { NSAllowsLocalNetworking = YES }` and nothing else. On iOS 10+ the OS already exempts local hosts, so the key changes no behaviour today. It records the intent in the binary and keeps the local exemption explicit if other ATS keys are ever added.
2. `NSAllowsArbitraryLoads` and per-domain `NSExceptionAllowsInsecureHTTPLoads` are rejected. They turn ATS off for every host, need an App Review justification, and are unnecessary: a gateway can always be fronted with TLS (Cloudflare tunnel per `docs/TUNNEL_SETUP.md`, or Tailscale HTTPS certificates).
3. Plain HTTP to a qualified hostname is unsupported on iOS. The Settings toggle "Allow HTTP to public IPs" affects only `CleartextPublicIPGuard` (IP literals) and cannot override ATS. Its footer copy says so.
4. Manual Entry warns before connecting when `ATSCleartextPolicy.blocksCleartext(host:)` predicts ATS will refuse the load.

## Consequences

- Platform divergence: Android can reach `http://box.tailnet.ts.net:18789`; iOS cannot. iOS users must use HTTPS, the IP literal, or the unqualified / `.local` name.
- `CleartextPublicIPGuard` stays the only app-level cleartext policy, and it only classifies IP literals (`IPLiteralTests`).
- ATS exceptions are static `Info.plist` keys, so there is no way to add a user-chosen per-host exception at runtime. Revisit only if Apple changes that or App Review guidance changes.
