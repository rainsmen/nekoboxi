package libcore

import (
	"errors"
	"sync"
	"testing"
)

type testInterfaceListener struct {
	mu      sync.Mutex
	indices []int32
}

func (l *testInterfaceListener) UpdateDefaultInterface(_ string, index int32, _, _ bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.indices = append(l.indices, index)
}
func (l *testInterfaceListener) UpdateNetworkPath(string) {}
func (l *testInterfaceListener) count() int               { l.mu.Lock(); defer l.mu.Unlock(); return len(l.indices) }
func TestInterfaceMonitorLifecycle(t *testing.T) {
	r := &interfaceMonitorRegistry{}
	var tokens, stopped []int64
	r.startPlatform = func(token int64) error { tokens = append(tokens, token); r.update(token, "", -1, false); return nil }
	r.stopPlatform = func(token int64) error { stopped = append(stopped, token); return nil }
	a, b := &testInterfaceListener{}, &testInterfaceListener{}
	if err := r.start(a); err != nil {
		t.Fatal(err)
	}
	if err := r.start(b); err != nil {
		t.Fatal(err)
	}
	r.update(tokens[0], "wlan0", 4, false)
	r.update(tokens[1], "wlan0", 4, false)
	if err := r.close(a); err != nil {
		t.Fatal(err)
	}
	r.update(tokens[0], "rmnet0", 5, true)
	r.update(tokens[1], "rmnet0", 5, true)
	r.update(tokens[1], "rmnet0", 5, true)
	if a.count() != 2 || b.count() != 4 {
		t.Fatalf("unexpected callbacks: %d, %d", a.count(), b.count())
	}
	r.close(a)
	r.close(b)
	if len(stopped) != 2 || len(r.entries) != 0 {
		t.Fatal("listener leaked")
	}
}
func TestInterfaceMonitorFailureAndConcurrentClose(t *testing.T) {
	r := &interfaceMonitorRegistry{startPlatform: func(int64) error { return errors.New("registration failed") }, stopPlatform: func(int64) error { return nil }}
	listener := &testInterfaceListener{}
	if r.start(listener) == nil || len(r.entries) != 0 {
		t.Fatal("failed start not rolled back")
	}
	var token int64
	r.startPlatform = func(id int64) error { token = id; return nil }
	if err := r.start(listener); err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for range 10 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for range 100 {
				r.update(token, "wlan0", 2, false)
			}
		}()
	}
	r.close(listener)
	wg.Wait()
	before := listener.count()
	r.update(token, "wlan0", 2, false)
	if before != listener.count() {
		t.Fatal("callback after close")
	}
}
func TestSocketAddressIPv6(t *testing.T) {
	address, err := socketAddress("2001:db8::1", 443)
	if err != nil || address.String() != "[2001:db8::1]:443" {
		t.Fatalf("%v %v", address, err)
	}
	if _, err := socketAddress("invalid", 443); err == nil {
		t.Fatal("accepted invalid IP")
	}
	if _, err := socketAddress("127.0.0.1", 65536); err == nil {
		t.Fatal("accepted invalid port")
	}
}
