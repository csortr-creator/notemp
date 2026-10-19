# Single-core subscription compatibility

SFAxtnd uses its pinned sing-box core. Import translates equivalent settings;
it does not make an unsupported wire protocol work by dropping its options.
The table describes implemented paths, not a guarantee that a remote server is reachable.

| Input | Native representation | Scope / limit |
| --- | --- | --- |
| SNI / ALPN | `tls.server_name` / `tls.alpn` | Preserve provider values and certificate verification |
| Xray certificate pins / names | Native verification options | Existing mapping; no conversion into `insecure` |
| Reality public key / short ID / fingerprint | Native Reality TLS options | Existing core compatibility patches; fingerprint support still matters |
| Mihomo Reality `support-x25519mlkem768` | `tls.reality.support_x25519mlkem768` | URI/Base64 and Clash JSON/YAML; explicit false removes hybrid shares for legacy servers, absent/true preserves browser shares |
| XHTTP padding range | `transport.x_padding_bytes` | Existing parser/core support; conflicting aliases rejected |
| VLESS Encryption + Vision | `encryption` + `flow` | Native single-key `mlkem768x25519plus.native.0rtt/1rtt`; Vision can use the encryption connection without removing either layer |
| FinalMask UDP Salamander | Hysteria2 `obfs.type=salamander` | One mask, password-only settings, at least four UTF-8 bytes; proxy authentication remains separate |
| FinalMask TCP header-custom | VLESS `tcp_header.clients/servers` | One mask; literal `str` items; plain TCP without TLS/Reality, other transport, or Vision |
| FinalMask QUIC tuning | Native Hysteria2 BBR/MTU/hopping options | Existing supported subset; other QUIC fields are rejected |
| Hysteria2 URI / Xray JSON fingerprint | Source-aware QUIC conversion | Xray uses standard TLS here and does not apply its TCP fingerprint; preserve the independent QUIC Chrome parrot option |
| Native / Clash QUIC uTLS | Hysteria2 Chrome parrot for exact `chrome` | Explicit native/Clash uTLS remains strict; the Xray rule does not apply to these formats |
| Other FinalMask masks/chains | Import issue | Unsupported settings are not ignored or collapsed into a different mask |

## Shared mapping

`FinalMaskCompatibility` is used by URI and Xray JSON adapters through
`ProxyLinkParser.applyFinalMask`. It validates a copy before updating the node,
so a rejected mapping leaves no partially applied native settings behind.
Existing native obfuscation plus another FinalMask UDP layer is rejected even
when the passwords match: collapsing a two-layer mask would change the wire.

Trojan URI recognizes `fm` and rejects unsupported combinations instead of
silently claiming that the setting was applied. Legacy VMess JSON remains strict
about unsupported fields. This change does not convert all input formats or all
future Xray extensions automatically.

## Runtime extension

The small pinned-core TCP header patch implements literal sequence exchange:
write each client sequence, read the corresponding server sequence, then read
any remaining server sequences. Responses are compared exactly. Exchange happens
before VLESS Encryption and VLESS traffic, on both TCP and packet dialing paths.

The parser and core bound headers to 32 sequences per direction and 64 KiB total;
the parser additionally bounds item count. Exchange has a 15-second maximum,
honors a shorter dialing deadline, and closes the socket on cancellation.
Errors do not include literal header contents, and cancellation stays cancellation.
Unmasked outbounds do not enter this path.

## Verification

JVM fixtures exercise URI/Xray JSON conversion, password/TLS preservation,
unsupported masks/settings/chains, conflicting obfuscation, transactional mapping,
and no fingerprint bypass. Generated full configurations are checked by the
patched core in CI.

Official checksum-pinned Xray v26.9.9 is a **test fixture**, not a second runtime
in the APK. The wire tests cover:

- TCP header with plain VLESS, Encryption 0rtt and 1rtt, and fresh connections;
- complete HTTPS exchange with a trusted test certificate;
- wrong header rejection and cancelled header exchange;
- native Hysteria2 Salamander against Xray FinalMask, including wrong proxy
  authentication and wrong mask password rejection.

Loopback permission exists only in fixture configs. Android device and provider
tests are separate from successful import, config checks, and loopback tests.

## Subscription compatibility matrix

The same Encryption + Vision + Reality connection is tested through nine input
containers: URI, Base64 URI, Xray JSON, Xray JSON array, Clash JSON, Clash YAML,
Base64 YAML, sing-box JSON and sing-box outbound array. The matrix preserves
encryption, flow, UUID, public key, short ID, SNI and the chosen fingerprint,
and exports every generated configuration for native core validation.

The runtime uses the existing sing-vmess Vision implementation with the
`CommonConn` registry adapter from sing-box-lx. Its buffer layout and registry
entry are tested so a dependency change cannot silently invalidate the adapter.
The browser preset dependency is pinned to utls-lx; Firefox 148 and Safari 26.3
provide their own hybrid key shares, with Chrome Auto remaining Chrome 133.

The Xray interoperability matrix covers Chrome, Firefox and Safari with 0rtt
and 1rtt, fresh and resumed connections, TLS 1.3, and a 1.4 MiB upload/download.
Wrong Reality key/short ID and wrong Encryption key must fail. Vision tests run
without race/checkptr because sing-vmess uses unsafe reflection; ordinary
Reality, encryption and Hysteria tests retain race instrumentation.

Hysteria2 source conversion follows Xray's actual dialer: `GetTLSConfig`
returns `crypto/tls.Config`; `tlsSettings.fingerprint` is not used by that path.
`finalmask.quicParams.disableChromeParrot` is independent and is preserved.
The same rule applies to the Xray-style `fp` extension in Hysteria2 share links,
with a report note explaining it. SNI, certificate verification/pins, proxy
authentication and Salamander credentials are not changed. Native/Clash uTLS
settings are not reinterpreted as Xray hints. Hysteria2 wire tests cover both
parrot settings and reject wrong certificate names, authentication and masks.

Pinned implementation references:

- [Vision adapter, sing-box-lx 6b1cfa4](https://github.com/Leadaxe/sing-box-lx/blob/6b1cfa43d931ba5f7e79acaa64841290f25557de/protocol/vless/encryption/lx_vision.go)
- [utls-lx ad69b19](https://github.com/Leadaxe/utls-lx/tree/ad69b191e2f83367ec0ac5f3a1a61310732c43b9), locked by Go module checksums
- [Xray v26.9.9 Hysteria dialer](https://github.com/XTLS/Xray-core/blob/v26.9.9/transport/internet/hysteria/dialer.go)
- [Xray v26.9.9 TLS configuration](https://github.com/XTLS/Xray-core/blob/v26.9.9/transport/internet/tls/config.go)

## Remaining work

Audit unsupported combinations against native capabilities before implementing
new protocol code. Current limits include dynamic header items, arbitrary mask
chains, Salamander packet-size/gecko options, explicit native QUIC fingerprints,
multi-key Encryption profiles and Reality ML-DSA signatures. Keep offline probe/Stop lifecycle work separate
from protocol compatibility; this change does not claim to fix those behaviors.

No importer can guarantee arbitrary future protocol extensions or a remote
server's availability. Unsupported wire requirements must stay visible and must
not become a successful import by removing encryption or certificate checks.

References: pinned Xray `infra/conf/transport_finalmask.go` and
`transport/internet/finalmask/header/custom/tcp.go`; pinned sing-quic
`hysteria2/salamander.go`; local interoperability fixtures.

## Android network startup and remote rules

The initial default-interface update completes synchronously before the native
monitor registration returns. Android may deliver `onAvailable` before link
properties or an interface index exist; registration waits up to five seconds
for a usable snapshot instead of starting DNS/rule-set downloads against an
empty native interface list. Later link-property updates trigger a refresh.
Cancelled updates cannot publish into a replaced listener. A missing network
fails startup explicitly; routing rules are never skipped to make startup pass.

Hybrid-option regression tests capture the actual outgoing ClientHello for
Chrome/Firefox/Safari, including cloned configurations, and check both supported
groups and key shares. Legacy Xray with explicit false and current Xray with
explicit true must complete HTTPS and reject wrong Reality keys/short IDs.
The source flag is validated as a boolean and is rejected without Reality.
