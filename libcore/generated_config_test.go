package libcore

import (
	"encoding/json"
	"errors"
	"net"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

type fixturePlatform struct {
	iface           net.Interface
	mu              sync.Mutex
	tokens          map[int64]bool
	started, closed int
}

func (*fixturePlatform) AutoDetectInterfaceControl(int32) error { return nil }
func (*fixturePlatform) OpenTun(string, string) (int, error) {
	return -1, errors.New("proxy fixture must not open a TUN")
}
func (*fixturePlatform) UseProcFS() bool { return false }
func (p *fixturePlatform) NetworkInterfaces() (string, error) {
	addresses, err := p.iface.Addrs()
	if err != nil {
		return "", err
	}
	items := make([]string, len(addresses))
	for i, address := range addresses {
		items[i] = address.String()
	}
	data, err := json.Marshal([]platformNetworkInterface{{Index: int32(p.iface.Index), MTU: int32(p.iface.MTU), Name: p.iface.Name,
		Addresses: items, Flags: int32(p.iface.Flags), Default: true}})
	return string(data), err
}
func (p *fixturePlatform) StartDefaultInterfaceMonitor(token int64) error {
	p.mu.Lock()
	p.tokens[token] = true
	p.started++
	p.mu.Unlock()
	UpdateDefaultInterface(token, p.iface.Name, int32(p.iface.Index), false)
	return nil
}
func (p *fixturePlatform) CloseDefaultInterfaceMonitor(token int64) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	delete(p.tokens, token)
	p.closed++
	return nil
}
func (*fixturePlatform) FindConnectionOwner(int32, string, int32, string, int32) (int32, error) {
	return 10001, nil
}
func (*fixturePlatform) PackageNameByUid(int32) (string, error) { return "fixture.app", nil }
func (*fixturePlatform) UIDByPackageName(string) (int32, error) { return 10001, nil }
func (*fixturePlatform) WIFIState() string                      { return "" }

type quietPlatformLog struct{}

func (quietPlatformLog) DisableColors() bool        { return true }
func (quietPlatformLog) WriteMessage(uint8, string) {}

func TestKotlinGeneratedConfigurationStarts(t *testing.T) {
	root := os.Getenv("THBOX_CONFIG_FIXTURES")
	if root == "" {
		t.Skip("integration step supplies fresh Kotlin ConfigBuilder output")
	}
	files, err := filepath.Glob(filepath.Join(root, "*.json"))
	if err != nil || len(files) != 9 {
		t.Fatalf("missing Kotlin fixtures: %s (%v)", root, err)
	}
	for _, expected := range []string{"proxy-fake-dns-false.json", "proxy-fake-dns-true.json", "shared-chain-selector-false.json", "shared-chain-selector-true.json", "three-hop-reserved-selector.json", "group-front-landing-chains.json", "protocol-only-dns-block.json", "imported-detour-selector-false.json", "imported-detour-selector-true.json"} {
		if _, err := os.Stat(filepath.Join(root, expected)); err != nil {
			t.Fatalf("missing fixture %s: %v", expected, err)
		}
	}
	interfaces, err := net.Interfaces()
	if err != nil {
		t.Fatal(err)
	}
	var iface net.Interface
	for _, item := range interfaces {
		if item.Flags&net.FlagLoopback != 0 {
			iface = item
			break
		}
	}
	if iface.Index == 0 {
		t.Fatal("loopback interface unavailable")
	}
	platform := &fixturePlatform{iface: iface, tokens: map[int64]bool{}}
	oldPlatform, oldBG, oldWriter := intfBox, isBgProcess, boxPlatformLogWriter
	intfBox, isBgProcess, boxPlatformLogWriter = platform, true, quietPlatformLog{}
	defer func() { intfBox, isBgProcess, boxPlatformLogWriter = oldPlatform, oldBG, oldWriter }()
	t.Chdir(t.TempDir())
	for _, path := range files {
		t.Run(filepath.Base(path), func(t *testing.T) {
			data, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			instance, err := NewSingBoxInstance(string(data), &lookupResolver{})
			if err != nil {
				t.Fatal(err)
			}
			defer instance.Close()
			if err = instance.Start(); err != nil {
				t.Fatal(err)
			}
			if instance.pauseManager.IsNetworkPaused() {
				t.Fatal("initial interface did not wake the network")
			}
			platform.mu.Lock()
			tokens := make([]int64, 0, len(platform.tokens))
			for token := range platform.tokens {
				tokens = append(tokens, token)
			}
			platform.mu.Unlock()
			if len(tokens) == 0 {
				t.Fatal("core did not register an interface monitor")
			}
			for _, token := range tokens {
				UpdateDefaultInterface(token, "", -1, false)
			}
			if !instance.pauseManager.IsNetworkPaused() {
				t.Fatal("network loss did not pause core")
			}
			for _, token := range tokens {
				UpdateDefaultInterface(token, iface.Name, int32(iface.Index), false)
			}
			if instance.pauseManager.IsNetworkPaused() {
				t.Fatal("network recovery did not wake core")
			}
			if err = instance.Close(); err != nil {
				t.Fatal(err)
			}
		})
	}
	if platform.started != platform.closed || len(platform.tokens) != 0 {
		t.Fatalf("monitor lifecycle: started=%d closed=%d remaining=%d", platform.started, platform.closed, len(platform.tokens))
	}
}
