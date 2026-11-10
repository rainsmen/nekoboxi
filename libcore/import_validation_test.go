package libcore

import "testing"

func importContainer(outbounds, endpoints string) string {
	return `{"log":{"disabled":true},"dns":{"servers":[{"type":"local","tag":"import-dns"}]},` +
		`"route":{"default_domain_resolver":"import-dns"},"outbounds":` + outbounds + `,"endpoints":` + endpoints + `}`
}

func TestImportedConfigurationRejectsInvalidNodesBeforeCommit(t *testing.T) {
	for _, node := range []string{
		`{"type":"socks","server":"example.org","server_port":"oops"}`,
		`{"type":"socks","server_port":1080}`,
		`{"type":"socks","server":"","server_port":1080}`,
		`{"type":"socks","server":"example.org","server_port":0}`,
		`{"type":"socks","server":"example.org","server_port":65536}`,
		`{"type":"socks","server":"example.org","server_port":1080,"version":"99"}`,
		`{"type":"vmess","server":"example.org","server_port":443,"uuid":123}`,
		`{"type":"unknown","server":"example.org","server_port":443}`,
	} {
		if err := ValidateImportedConfig(importContainer("["+node+"]", "[]")); err == nil {
			t.Fatalf("accepted invalid node: %s", node)
		}
	}
}

func TestImportedConfigurationAcceptsLinearDependenciesAndEndpoints(t *testing.T) {
	const wireguard = `{"type":"wireguard","tag":"wg","address":["10.0.0.2/32"],"private_key":"eCtXsJZ27+4PbhDkHnB923tkUn2Gj59wZw5wFA75MnU=","peers":[{"address":"127.0.0.1","port":51820,"public_key":"Z1XXLsKYkYxuiYjJIkRvtIKFepCYHTgON+GwPq7SOV4=","allowed_ips":["0.0.0.0/0"]}]}`
	for _, fixture := range []struct{ outbounds, endpoints string }{
		{`[{"type":"socks","tag":"a","server":"example.org","server_port":1080}]`, `[]`},
		{`[{"type":"socks","tag":"a","server":"example.org","server_port":1080,"detour":"b"},{"type":"socks","tag":"b","server":"hop.example","server_port":1080}]`, `[]`},
		{`[{"type":"socks","tag":"a","server":"example.org","server_port":1080,"detour":"wg"}]`, `[` + wireguard + `]`},
		{`[]`, `[` + wireguard + `]`},
		{`[]`, `[{"type":"tailscale","tag":"tail","control_url":"http://127.0.0.1:1"}]`},
		{`[{"type":"ssh","tag":"ssh","server":"example.org","user":"fixture","password":"fixture"}]`, `[]`},
		{`[{"type":"hysteria","tag":"hy","server":"example.org","server_ports":["443:445"],"up_mbps":10,"down_mbps":50,"tls":{"enabled":true}}]`, `[]`},
		{`[{"type":"hysteria2","tag":"hy2","server":"example.org","server_ports":["443:445"],"password":"fixture","tls":{"enabled":true}}]`, `[]`},
		{`[{"type":"hysteria2","tag":"realm","realm":{"server_url":"https://realm.example","realm_id":"fixture","token":"fixture","stun_servers":["stun.example:3478"]},"password":"fixture","tls":{"enabled":true,"server_name":"example.org"}}]`, `[]`},
	} {
		if err := ValidateImportedConfig(importContainer(fixture.outbounds, fixture.endpoints)); err != nil {
			t.Fatalf("valid fixture rejected: %v", err)
		}
	}
}
