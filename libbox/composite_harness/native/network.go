package composite

import (
	"context"
	"errors"
	"fmt"
	"net/netip"
	"strings"
	"sync"
)

// NetworkProvider belongs to one immutable underlying network. Implementations
// must protect AND bind each fd before returning. Lookup must enqueue work and
// return promptly; completion and cancellation cross JNI through LookupRequest.
// Neither callback may call lifecycle methods on its owning ExecutorFacade.
type NetworkProvider interface {
	ProtectAndBind(fd int64) error
	Lookup(domain string, request *LookupRequest) error
}

// LookupCancellation cancels platform DNS work, without blocking or reentering
// the facade. It may be called from any thread, including after registration.
type LookupCancellation interface{ CancelLookup() }

type lookupResult struct {
	addresses []netip.Addr
	err       error
}

// LookupRequest is a single-use, owner-scoped DNS completion, safe for JNI
// callbacks from arbitrary threads. Constructed only by the protected adapter.
type LookupRequest struct {
	mu           sync.Mutex
	ctx          context.Context
	done         bool
	cancelled    bool
	registered   bool
	cancellation LookupCancellation
	result       chan lookupResult
}

func (r *LookupRequest) OnCancel(callback LookupCancellation) error {
	if r.ctx == nil {
		return errors.New("uninitialized DNS request")
	}
	if callback == nil {
		return errors.New("DNS cancellation callback required")
	}
	r.mu.Lock()
	if r.registered {
		r.mu.Unlock()
		return errors.New("DNS cancellation already registered")
	}
	r.registered = true
	cancelled := r.cancelled || (!r.done && r.ctx.Err() != nil)
	if !r.done && !cancelled {
		r.cancellation = callback
	}
	r.mu.Unlock()
	if cancelled {
		callback.CancelLookup()
	}
	return nil
}

func (r *LookupRequest) cancel() {
	r.mu.Lock()
	if r.done {
		r.mu.Unlock()
		return
	}
	r.done, r.cancelled = true, true
	callback := r.cancellation
	r.cancellation = nil
	r.mu.Unlock()
	if callback != nil {
		callback.CancelLookup()
	}
}

func (r *LookupRequest) complete(result lookupResult) error {
	if r.ctx == nil {
		return errors.New("uninitialized DNS request")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.done || r.ctx.Err() != nil {
		return errors.New("DNS request already completed or cancelled")
	}
	r.done = true
	r.cancellation = nil
	r.result <- result
	return nil
}

// Success accepts newline-separated numeric IPs only. Invalid/empty/oversized
// results fail the whole lookup; never substitute the process default resolver.
func (r *LookupRequest) Success(addresses string) error {
	var parsed []netip.Addr
	var parseErr error
	if len(addresses) > 16384 {
		parseErr = errors.New("DNS response too large")
	} else {
		lines := strings.Split(strings.TrimSpace(addresses), "\n")
		if len(lines) > 256 {
			parseErr = errors.New("too many DNS addresses")
		} else {
			for _, line := range lines {
				addr, err := netip.ParseAddr(strings.TrimSpace(line))
				if err != nil || addr.Zone() != "" {
					parseErr = errors.New("DNS response contains invalid IP")
					break
				}
				parsed = append(parsed, addr.Unmap())
			}
		}
	}
	if parseErr != nil {
		parsed = nil
	}
	if err := r.complete(lookupResult{parsed, parseErr}); err != nil {
		return err
	}
	return parseErr
}

func (r *LookupRequest) Fail(reason string) error {
	if len(reason) > 1024 {
		reason = reason[:1024]
	}
	return r.complete(lookupResult{err: fmt.Errorf("platform DNS failed: %s", reason)})
}

type providerAdapter struct{ provider NetworkProvider }

func (a providerAdapter) protect(ctx context.Context, _ string, fd uintptr) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if fd > 0x7fffffff {
		return errors.New("invalid Android socket descriptor")
	}
	if err := a.provider.ProtectAndBind(int64(fd)); err != nil {
		return err
	}
	return ctx.Err()
}

func (a providerAdapter) resolve(ctx context.Context, domain string) ([]netip.Addr, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	request := &LookupRequest{ctx: ctx, result: make(chan lookupResult, 1)}
	stop := context.AfterFunc(ctx, request.cancel)
	defer stop()
	if err := a.provider.Lookup(domain, request); err != nil {
		request.cancel()
		return nil, err
	}
	select {
	case <-ctx.Done():
		request.cancel()
		return nil, context.Cause(ctx)
	case result := <-request.result:
		if err := ctx.Err(); err != nil {
			return nil, context.Cause(ctx)
		}
		return result.addresses, result.err
	}
}
