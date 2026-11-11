package libcore

import (
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
)

type snapshotFixture struct {
	fixturePlatform
	name  string
	calls atomic.Int32
}

func (p *snapshotFixture) AutoDetectInterfaceControl(int32) error { p.calls.Add(1); return nil }
func (p *snapshotFixture) NetworkInterfaces() (string, error)     { return `[]`, nil }
func (p *snapshotFixture) WIFIState() (string, error)             { return fmt.Sprintf(`{"ssid":%q}`, p.name), nil }
func (p *snapshotFixture) PackageNameByUid(int32) (string, error) { return p.name, nil }
func (p *snapshotFixture) OpenTun(string, string) (int, error)    { return 123, nil }

func TestPlatformSnapshotSurvivesGlobalReplacement(t *testing.T) {
	old, bg, proc := intfBox, isBgProcess, useProcfs
	defer func() { intfBox, isBgProcess, useProcfs = old, bg, proc }()
	a, b := &snapshotFixture{name: "a"}, &snapshotFixture{name: "b"}
	intfBox, isBgProcess, useProcfs = a, true, false
	wrapper := snapshotBoxPlatform()
	var wg sync.WaitGroup
	wg.Add(1)
	start := make(chan struct{})
	go func() {
		defer wg.Done()
		<-start
		for range 1000 {
			if err := wrapper.AutoDetectInterfaceControl(1); err != nil {
				t.Error(err)
			}
			if wrapper.ReadWIFIState().SSID != "a" {
				t.Error("platform changed")
			}
			if _, err := wrapper.platformInterfaces(); err != nil {
				t.Error(err)
			}
			if _, err := wrapper.FindConnectionOwner(6, "127.0.0.1", 1, "127.0.0.1", 2); err != nil {
				t.Error(err)
			}
		}
	}()
	close(start)
	for range 1000 {
		intfBox, isBgProcess, useProcfs = b, false, true
	}
	wg.Wait()
	if a.calls.Load() != 1000 || b.calls.Load() != 0 {
		t.Fatal("old callback used new platform")
	}
}

func TestMonitorCloseUsesRegisteringPlatform(t *testing.T) {
	r := &interfaceMonitorRegistry{}
	a, b := &testInterfaceListener{}, &testInterfaceListener{}
	var stoppedA, stoppedB int
	var tokenA, tokenB int64
	if err := r.startBound(a, func(id int64) error { tokenA = id; return nil }, func(int64) error { stoppedA++; return nil }); err != nil {
		t.Fatal(err)
	}
	if err := r.startBound(b, func(id int64) error { tokenB = id; return nil }, func(int64) error { stoppedB++; return nil }); err != nil {
		t.Fatal(err)
	}
	if tokenA == tokenB {
		t.Fatal("duplicate token")
	}
	r.close(a)
	r.update(tokenA, "", 1, false)
	r.update(tokenB, "", 1, false)
	if stoppedA != 1 || stoppedB != 0 || a.count() != 0 || b.count() != 1 {
		t.Fatal("monitor ownership changed")
	}
	r.close(b)
	if stoppedB != 1 {
		t.Fatal("second monitor leaked")
	}
}
