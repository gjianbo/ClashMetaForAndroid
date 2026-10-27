package main

//#include "bridge.h"
import "C"

import (
	"context"
	"net/netip"
	"strings"
	"time"

	"github.com/metacubex/mihomo/adapter/outbound"
	"github.com/metacubex/tailscale/ipn/ipnstate"
)

type tsPeerJSON struct {
	ID       string   `json:"id"`
	HostName string   `json:"hostname"`
	DNSName  string   `json:"dnsName"`
	OS       string   `json:"os"`
	IPs      []string `json:"ips"`
	Online   bool     `json:"online"`
	Relay    string   `json:"relay"`
	CurAddr  string   `json:"curAddr"`
	LastSeen string   `json:"lastSeen"`
	RxBytes  int64    `json:"rxBytes"`
	TxBytes  int64    `json:"txBytes"`
	Active   bool     `json:"active"`
	ExitNode bool     `json:"exitNode"`
}

type tsStatusJSON struct {
	Found   bool         `json:"found"`
	State   string       `json:"state"`
	Version string       `json:"version"`
	TUN     bool         `json:"tun"`
	AuthURL string       `json:"authUrl"`
	Suffix  string       `json:"suffix"`
	Self    *tsPeerJSON  `json:"self"`
	Peers   []tsPeerJSON `json:"peers"`
	Health  []string     `json:"health"`
	Error   string       `json:"error"`
}

type tsPingJSON struct {
	IP       string  `json:"ip"`
	NodeName string  `json:"nodeName"`
	Err      string  `json:"err"`
	Latency  float64 `json:"latency"`
	Endpoint string  `json:"endpoint"`
}

func findTailscaleProxy() *outbound.Tailscale {
	return outbound.LookupTailscale()
}

func peerToJSON(peer *ipnstate.PeerStatus) tsPeerJSON {
	ips := make([]string, 0, len(peer.TailscaleIPs))
	for _, ip := range peer.TailscaleIPs {
		ips = append(ips, ip.String())
	}

	var lastSeen string
	if !peer.LastSeen.IsZero() {
		lastSeen = peer.LastSeen.Format(time.RFC3339)
	}

	return tsPeerJSON{
		ID:       string(peer.ID),
		HostName: peer.HostName,
		DNSName:  strings.TrimSuffix(peer.DNSName, "."),
		OS:       peer.OS,
		IPs:      ips,
		Online:   peer.Online,
		Relay:    peer.Relay,
		CurAddr:  peer.CurAddr,
		LastSeen: lastSeen,
		RxBytes:  peer.RxBytes,
		TxBytes:  peer.TxBytes,
		Active:   peer.Active,
		ExitNode: peer.ExitNode,
	}
}

//export queryTailscaleStatus
func queryTailscaleStatus() *C.char {
	status := tsStatusJSON{}

	ts := findTailscaleProxy()
	if ts == nil {
		status.Error = "tailscale outbound not found"
		return marshalJson(&status)
	}
	status.Found = true

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	st, err := ts.Status(ctx)
	if err != nil {
		status.Error = err.Error()
		return marshalJson(&status)
	}

	status.State = st.BackendState
	status.Version = st.Version
	status.TUN = st.TUN
	status.AuthURL = st.AuthURL
	status.Health = st.Health
	status.Suffix = st.MagicDNSSuffix
	if st.CurrentTailnet != nil && st.CurrentTailnet.MagicDNSSuffix != "" {
		status.Suffix = st.CurrentTailnet.MagicDNSSuffix
	}

	if st.Self != nil {
		self := peerToJSON(st.Self)
		status.Self = &self
	}

	for _, peer := range st.Peer {
		status.Peers = append(status.Peers, peerToJSON(peer))
	}

	return marshalJson(&status)
}

//export pingTailscale
func pingTailscale(address C.c_string) *C.char {
	result := tsPingJSON{IP: C.GoString(address)}

	ts := findTailscaleProxy()
	if ts == nil {
		result.Err = "tailscale outbound not found"
		return marshalJson(&result)
	}

	ip, err := netip.ParseAddr(result.IP)
	if err != nil {
		result.Err = "invalid address: " + err.Error()
		return marshalJson(&result)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	ping, err := ts.Ping(ctx, ip)
	if err != nil {
		result.Err = err.Error()
		return marshalJson(&result)
	}

	result.NodeName = ping.NodeName
	result.Err = ping.Err
	result.Latency = ping.LatencySeconds
	result.Endpoint = ping.Endpoint

	return marshalJson(&result)
}
