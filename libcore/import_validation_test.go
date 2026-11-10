package libcore

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"testing"
)

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

func TestImportedConfigurationNeverReadsLocalFiles(t *testing.T) {
	for _, node := range []string{
		`{"type":"ssh","server":"example.org","private_key_path":"/dev/zero"}`,
		`{"type":"http","server":"example.org","server_port":443,"tls":{"enabled":true,"certificate_path":"/dev/zero"}}`,
		`{"type":"http","server":"example.org","server_port":443,"tls":{"enabled":true,"client_certificate_path":"/dev/zero","client_key_path":"/dev/zero"}}`,
		`{"type":"http","server":"example.org","server_port":443,"tls":{"enabled":true,"ech":{"enabled":true,"config_path":"/dev/zero"}}}`,
	} {
		if err := ValidateImportedConfig(importContainer("["+node+"]", "[]")); !errors.Is(err, errImportedFile) {
			t.Fatalf("file input should fail without being opened: %v", err)
		}
	}
}

func TestImportedInlineKeysRemainValid(t *testing.T) {
	_, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	node, err := json.Marshal(map[string]any{"type": "ssh", "server": "example.org", "private_key": []string{
		string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: encoded})),
	}})
	if err != nil {
		t.Fatal(err)
	}
	if err := ValidateImportedConfig(importContainer("["+string(node)+"]", "[]")); err != nil {
		t.Fatal(err)
	}
}
