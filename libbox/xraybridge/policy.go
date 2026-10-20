package xraybridge

import (
	"encoding/json"
	"errors"
	"net/netip"
	"strings"
)

var ErrInboundPolicy = errors.New("XRAY_BRIDGE_INBOUND_POLICY")

// The caller owns CSPRNG credential generation. Never accept a public listener,
// unauthenticated SOCKS, or an ancillary listener in this experimental bridge.
func validateBridgePolicy(data []byte) error {
	var root struct {
		Inbounds []struct {
			Listen   string `json:"listen"`
			Port     int    `json:"port"`
			Protocol string `json:"protocol"`
			Settings struct {
				Auth     string `json:"auth"`
				Accounts []struct {
					User string `json:"user"`
					Pass string `json:"pass"`
				} `json:"accounts"`
			} `json:"settings"`
		} `json:"inbounds"`
		API     json.RawMessage `json:"api"`
		Metrics json.RawMessage `json:"metrics"`
		Reverse json.RawMessage `json:"reverse"`
	}
	if json.Unmarshal(data, &root) != nil || len(root.Inbounds) == 0 ||
		len(root.API) > 0 || len(root.Metrics) > 0 || len(root.Reverse) > 0 {
		return ErrInboundPolicy
	}
	for _, inbound := range root.Inbounds {
		ip, err := netip.ParseAddr(inbound.Listen)
		if err != nil || !ip.Unmap().IsLoopback() || inbound.Port < 1 || inbound.Port > 65535 ||
			inbound.Protocol != "socks" || inbound.Settings.Auth != "password" || len(inbound.Settings.Accounts) == 0 {
			return ErrInboundPolicy
		}
		for _, account := range inbound.Settings.Accounts {
			if len(account.User) == 0 || len(account.User) > 255 || len(account.Pass) < 32 || len(account.Pass) > 255 {
				return ErrInboundPolicy
			}
		}
	}
	return nil
}

// Only wire-reviewed mask chains may run in the experimental protected bridge.
// Other masks can create auxiliary sockets/resolvers (e.g. realm/STUN); importing
// and retaining their source config is separate from protected execution.
func validateProtectedMasks(data []byte) error {
	var root any
	if err := json.Unmarshal(data, &root); err != nil {
		return err
	}
	var visit func(any, bool) error
	visit = func(value any, inMasks bool) error {
		switch node := value.(type) {
		case map[string]any:
			for key, child := range node {
				if inMasks && strings.EqualFold(key, "type") {
					kind, ok := child.(string)
					if !ok || (kind != "header-custom" && kind != "salamander") {
						return errors.New("XRAY_PROTECTED_MASK_NOT_REVIEWED")
					}
				}
				next := inMasks || strings.EqualFold(key, "finalmask")
				if inMasks && strings.EqualFold(key, "settings") {
					next = false
				}
				if err := visit(child, next); err != nil {
					return err
				}
			}
		case []any:
			for _, child := range node {
				if err := visit(child, inMasks); err != nil {
					return err
				}
			}
		}
		return nil
	}
	return visit(root, false)
}
