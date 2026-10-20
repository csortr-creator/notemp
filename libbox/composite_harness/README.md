# Composite host proof

This harness imports the real patched `experimental/libbox` and the Xray bridge
into one Go dependency graph. It starts an authenticated Xray SOCKS listener,
constructs and closes a sing-box configuration through `libbox.CheckConfig`,
checks that the Xray listener remains accessible, and stops Xray.

Prepare `sing-box-core/` in the repository root from upstream sing-box v1.14.2,
commit `af6e64c3b69e6132ebaee0e1a3d24e93903f6709`, then run
`python3 scripts/apply_core_patches.py sing-box-core`. No checked-in legacy
`libbox/go.mod` is used. Its old dependency versions are not the production pin.
The replacements in this harness deliberately target the prepared core and the
sibling bridge module. CI checks the exact core commit before applying patches.

From this directory, with Go 1.27:

```sh
go test -mod=readonly -race -timeout 2m -ldflags=-checklinkname=0 -tags=with_quic,with_utls -count=3 ./...
```

This is a Linux host initialization/dependency proof. It does not create an
Android AAR, exercise JNI, protect Android sockets, run a TUN, or establish a
shared runtime session across the cores. Those remain explicit integration gates.
