package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/dlclark/regexp2"

	"cfa/native/common"

	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	RC "github.com/metacubex/mihomo/rules/common"
)

var processors = []processor{
	patchExternalController, // must before patchOverride, so we only apply ExternalController in Override settings
	patchOverride,
	patchGeneral,
	patchProfile,
	patchDns,
	patchTailscale,
	patchTun,
	patchListeners,
	patchProviders,
	validConfig,
}

type processor func(cfg *config.RawConfig, profileDir string) error

func patchOverride(cfg *config.RawConfig, _ string) error {
	// 手动规则必须单独读出来：它们放在 prepend-rules 键里，RawConfig 没有对应字段，
	// decode 时会被忽略，这里再取出做前置合并。
	prepend := readPrependRules(OverrideSlotPersist)
	prepend = append(prepend, readPrependRules(OverrideSlotSession)...)

	if err := json.NewDecoder(strings.NewReader(ReadOverride(OverrideSlotPersist))).Decode(cfg); err != nil {
		log.Warnln("Apply persist override: %s", err.Error())
	}
	if err := json.NewDecoder(strings.NewReader(ReadOverride(OverrideSlotSession))).Decode(cfg); err != nil {
		log.Warnln("Apply session override: %s", err.Error())
	}

	if len(prepend) == 0 {
		return nil
	}

	merged := sanitizePrependRules(prepend, cfg)
	if len(merged) == 0 {
		return nil
	}

	// 注意：cfg.Rule 此刻是 override 之后的结果（override 没写 rule 时就是 profile 自己的规则），
	// 所以这里 append 到它前面，两种情形都符合预期。
	cfg.Rule = append(merged, cfg.Rule...)

	log.Infoln("[Override] prepended %d manual rule(s)", len(merged))

	return nil
}

// readPrependRules 从单个 override 文档里取出 prepend-rules。
func readPrependRules(slot OverrideSlot) []string {
	raw := strings.TrimSpace(ReadOverride(slot))
	if raw == "" || raw == "{}" {
		return nil
	}

	var doc struct {
		PrependRules []string `json:"prepend-rules"`
	}
	if err := json.Unmarshal([]byte(raw), &doc); err != nil {
		return nil
	}

	return doc.PrependRules
}

// sanitizePrependRules 过滤格式非法的规则，并把当前 profile 里不存在的目标降级到 GLOBAL。
//
// 必须做这件事：config.parseRules 遇到 `proxy [x] not found` 是返回 error 而不是跳过，
// 会让整个 profile 加载失败、代理起不来。GLOBAL 由 parseProxies 保证一定存在。
func sanitizePrependRules(rules []string, cfg *config.RawConfig) []string {
	available := map[string]struct{}{
		"DIRECT":      {},
		"REJECT":      {},
		"REJECT-DROP": {},
		"PASS":        {},
		"GLOBAL":      {},
		"COMPATIBLE":  {},
	}

	for _, group := range cfg.ProxyGroup {
		if name, ok := group["name"].(string); ok && name != "" {
			available[name] = struct{}{}
		}
	}
	for _, proxy := range cfg.Proxy {
		if name, ok := proxy["name"].(string); ok && name != "" {
			available[name] = struct{}{}
		}
	}

	out := make([]string, 0, len(rules))

	// AUTO 目标：规则里不写死订阅私有的组名，加载时换算成当前 profile 的可用出口，
	// 这样切换订阅后规则不会因为组名消失而失效。
	var autoResolved bool
	var autoTarget string

	resolveAuto := func() string {
		if !autoResolved {
			autoResolved = true
			autoTarget = resolveAutoTarget(cfg)

			log.Infoln("[Override] auto target resolved to [%s]", autoTarget)
		}

		return autoTarget
	}

	for idx, line := range rules {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") || strings.HasPrefix(trimmed, "//") {
			continue
		}

		tp, payload, target, params := RC.ParseRulePayload(trimmed, true)
		if tp == "" || target == "" {
			log.Warnln("[Override] prepend-rules[%d] [%s] error: format invalid, skipped", idx, line)
			continue
		}

		if strings.EqualFold(target, autoTargetName) {
			trimmed = rebuildRuleLine(tp, payload, resolveAuto(), params)

			out = append(out, trimmed)

			continue
		}

		if _, ok := available[target]; !ok {
			fallback := resolveAuto()

			log.Warnln(
				"[Override] prepend-rules[%d] [%s] error: target [%s] not found in current profile, fallback to [%s]",
				idx, line, target, fallback,
			)

			trimmed = rebuildRuleLine(tp, payload, fallback, params)
		}

		out = append(out, trimmed)
	}

	return out
}

// autoTargetName 是规则里可以写的占位目标，表示「跟随当前订阅」。
const autoTargetName = "AUTO"

// rebuildRuleLine 用新的目标重建规则行。MATCH 没有 payload，需要单独处理。
func rebuildRuleLine(tp, payload, target string, params []string) string {
	if tp == "MATCH" {
		return "MATCH," + target
	}

	parts := append([]string{tp, payload, target}, params...)

	return strings.Join(parts, ",")
}

// resolveAutoTarget 在当前 profile 里挑一个最像「主出口」的策略组。
//
// 顺序：自动测速/负载均衡类（用户不用手动挑节点）→ 手动选择类 → GLOBAL 兜底。
// GLOBAL 由内核保证存在，所以最坏情况也能保证配置加载成功。
func resolveAutoTarget(cfg *config.RawConfig) string {
	type group struct {
		name string
		typ  string
	}

	groups := make([]group, 0, len(cfg.ProxyGroup))

	for _, raw := range cfg.ProxyGroup {
		name, _ := raw["name"].(string)
		if name == "" {
			continue
		}

		typ, _ := raw["type"].(string)

		groups = append(groups, group{name: name, typ: typ})
	}

	autoTypes := []string{"url-test", "fallback", "load-balance"}

	for _, want := range autoTypes {
		for _, g := range groups {
			if g.typ == want && looksLikeMainEntry(g.name) {
				return g.name
			}
		}
	}

	for _, want := range autoTypes {
		for _, g := range groups {
			if g.typ == want {
				return g.name
			}
		}
	}

	for _, g := range groups {
		if g.typ == "select" && looksLikeMainEntry(g.name) {
			return g.name
		}
	}

	for _, g := range groups {
		if g.typ == "select" {
			return g.name
		}
	}

	return "GLOBAL"
}

// looksLikeMainEntry 用命名习惯猜测这个组是不是「总入口」。
func looksLikeMainEntry(name string) bool {
	lower := strings.ToLower(name)

	for _, keyword := range []string{"select", "proxy", "auto", "global", "选择", "节点", "自动", "代理"} {
		if strings.Contains(lower, keyword) {
			return true
		}
	}

	return false
}

func patchExternalController(cfg *config.RawConfig, _ string) error {
	cfg.ExternalController = ""
	cfg.ExternalControllerTLS = ""

	return nil
}

func patchGeneral(cfg *config.RawConfig, profileDir string) error {
	cfg.Interface = ""
	cfg.RoutingMark = 0
	if cfg.ExternalController != "" || cfg.ExternalControllerTLS != "" {
		cfg.ExternalUI = profileDir + "/ui"
	}

	return nil
}

func patchProfile(cfg *config.RawConfig, _ string) error {
	cfg.Profile.StoreSelected = false
	cfg.Profile.StoreFakeIP = true

	return nil
}

func patchDns(cfg *config.RawConfig, _ string) error {
	if !cfg.DNS.Enable {
		cfg.DNS = config.DefaultRawConfig().DNS
		cfg.DNS.Enable = true
		cfg.DNS.NameServer = defaultNameServers
		cfg.DNS.EnhancedMode = C.DNSFakeIP
		cfg.DNS.FakeIPRange = defaultFakeIPRange
		cfg.DNS.FakeIPFilter = defaultFakeIPFilter

		cfg.ClashForAndroid.AppendSystemDNS = true
	}

	if cfg.ClashForAndroid.AppendSystemDNS {
		cfg.DNS.NameServer = append(cfg.DNS.NameServer, "system://")
	}

	return nil
}

func patchTun(cfg *config.RawConfig, _ string) error {
	cfg.Tun.Enable = false
	cfg.Tun.AutoRoute = false
	cfg.Tun.AutoDetectInterface = false
	return nil
}

func patchListeners(cfg *config.RawConfig, _ string) error {
	newListeners := make([]map[string]any, 0, len(cfg.Listeners))
	for _, mapping := range cfg.Listeners {
		if proxyType, existType := mapping["type"].(string); existType {
			switch proxyType {
			case "tproxy", "redir", "tun":
				continue // remove those listeners which is not supported
			}
		}
		newListeners = append(newListeners, mapping)
	}
	cfg.Listeners = newListeners
	return nil
}

func patchProviders(cfg *config.RawConfig, profileDir string) error {
	forEachProviders(cfg, func(index int, total int, key string, provider map[string]any, prefix string) {
		path, _ := provider["path"].(string)
		if len(path) > 0 {
			path = common.ResolveAsRoot(path)
		} else if url, ok := provider["url"].(string); ok {
			path = prefix + "/" + utils.MakeHash([]byte(url)).String() // same as C.GetPathByHash
		} else {
			return // both path and url are empty, maybe inline provider
		}
		provider["path"] = profileDir + "/providers/" + path
	})

	return nil
}

func validConfig(cfg *config.RawConfig, _ string) error {
	if len(cfg.Proxy) == 0 && len(cfg.ProxyProvider) == 0 {
		return errors.New("profile does not contain `proxies` or `proxy-providers`")
	}

	if _, err := regexp2.Compile(cfg.ClashForAndroid.UiSubtitlePattern, 0); err != nil {
		return fmt.Errorf("compile ui-subtitle-pattern: %s", err.Error())
	}

	return nil
}

func process(cfg *config.RawConfig, profileDir string) error {
	for _, p := range processors {
		if err := p(cfg, profileDir); err != nil {
			return err
		}
	}

	return nil
}
