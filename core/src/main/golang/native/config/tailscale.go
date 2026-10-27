package config

import (
	"encoding/json"
	"strings"

	"github.com/metacubex/mihomo/common/orderedmap"
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/log"
)

const (
	defaultTailscaleName     = "TS"
	defaultTailscaleHostname = "cmfa-phone"
	defaultTailscaleStateDir = "tailscale"
)

type tailscaleSettings struct {
	Enable       bool     `json:"enable"`
	Name         string   `json:"name"`
	Hostname     string   `json:"hostname"`
	AuthKey      string   `json:"auth-key"`
	StateDir     string   `json:"state-dir"`
	UDP          *bool    `json:"udp"`
	AcceptRoutes bool     `json:"accept-routes"`
	Routes       []string `json:"routes"`
}

func readTailscaleSettings() *tailscaleSettings {
	for _, slot := range []OverrideSlot{OverrideSlotPersist, OverrideSlotSession} {
		raw := strings.TrimSpace(ReadOverride(slot))
		if raw == "" || raw == "{}" {
			continue
		}

		var wrapper struct {
			Tailscale *tailscaleSettings `json:"tailscale"`
		}
		if err := json.Unmarshal([]byte(raw), &wrapper); err != nil {
			continue
		}

		if wrapper.Tailscale != nil {
			return wrapper.Tailscale
		}
	}

	return nil
}

// patchTailscale appends a Tailscale outbound and its routing rules to every profile,
// so the tailnet stays reachable regardless of which profile or subscription is active.
func patchTailscale(cfg *config.RawConfig, _ string) error {
	settings := readTailscaleSettings()
	if settings == nil || !settings.Enable || strings.TrimSpace(settings.AuthKey) == "" {
		return nil
	}

	name := strings.TrimSpace(settings.Name)
	if name == "" {
		name = defaultTailscaleName
	}

	hostname := strings.TrimSpace(settings.Hostname)
	if hostname == "" {
		hostname = defaultTailscaleHostname
	}

	stateDir := strings.TrimSpace(settings.StateDir)
	if stateDir == "" {
		stateDir = defaultTailscaleStateDir
	}

	for _, proxy := range cfg.Proxy {
		if n, ok := proxy["name"].(string); ok && n == name {
			return nil
		}
	}

	udp := true
	if settings.UDP != nil {
		udp = *settings.UDP
	}

	outbound := map[string]any{
		"name":      name,
		"type":      "tailscale",
		"hostname":  hostname,
		"auth-key":  settings.AuthKey,
		"state-dir": stateDir,
		"udp":       udp,
	}
	if settings.AcceptRoutes {
		outbound["accept-routes"] = true
	}
	cfg.Proxy = append(cfg.Proxy, outbound)

	rules := []string{
		"DOMAIN-SUFFIX,ts.net," + name,
		"IP-CIDR,100.64.0.0/10," + name + ",no-resolve",
	}
	for _, route := range settings.Routes {
		if route = strings.TrimSpace(route); route != "" {
			rules = append(rules, "IP-CIDR,"+route+","+name+",no-resolve")
		}
	}
	cfg.Rule = append(rules, cfg.Rule...)

	if cfg.DNS.NameServerPolicy == nil {
		cfg.DNS.NameServerPolicy = orderedmap.New[string, any]()
	}
	cfg.DNS.NameServerPolicy.Set("+.ts.net", "tailscale://"+name)

	log.Infoln("[Tailscale] injected outbound %q with %d rule(s)", name, len(rules))

	return nil
}
