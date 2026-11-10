package libcore

import (
	"github.com/sagernet/sing-box/experimental/libbox"
	"sync"
)

type interfaceSubscription struct {
	mu       sync.Mutex
	listener libbox.InterfaceUpdateListener
	closed   bool
	ready    chan struct{}
	token    int64
}

type interfaceMonitorRegistry struct {
	mu            sync.Mutex
	next          int64
	entries       map[int64]*interfaceSubscription
	startPlatform func(int64) error
	stopPlatform  func(int64) error
}

var defaultInterfaceMonitors = &interfaceMonitorRegistry{
	startPlatform: func(token int64) error { return intfBox.StartDefaultInterfaceMonitor(token) },
	stopPlatform:  func(token int64) error { return intfBox.CloseDefaultInterfaceMonitor(token) },
}

func (r *interfaceMonitorRegistry) start(listener libbox.InterfaceUpdateListener) error {
	r.mu.Lock()
	if r.entries == nil {
		r.entries = make(map[int64]*interfaceSubscription)
	}
	for _, entry := range r.entries {
		if entry.listener == listener {
			r.mu.Unlock()
			return nil
		}
	}
	r.next++
	entry := &interfaceSubscription{listener: listener, token: r.next, ready: make(chan struct{})}
	r.entries[entry.token] = entry
	r.mu.Unlock()
	err := r.startPlatform(entry.token)
	close(entry.ready)
	if err != nil {
		_ = r.close(listener)
	}
	return err
}

func (r *interfaceMonitorRegistry) close(listener libbox.InterfaceUpdateListener) error {
	r.mu.Lock()
	var entry *interfaceSubscription
	for token, candidate := range r.entries {
		if candidate.listener == listener {
			entry = candidate
			delete(r.entries, token)
			break
		}
	}
	r.mu.Unlock()
	if entry == nil {
		return nil
	}
	<-entry.ready
	entry.mu.Lock()
	entry.closed = true
	entry.mu.Unlock()
	return r.stopPlatform(entry.token)
}

func (r *interfaceMonitorRegistry) update(token int64, name string, index int32, expensive bool) {
	r.mu.Lock()
	entry := r.entries[token]
	r.mu.Unlock()
	if entry == nil {
		return
	}
	// Serialize delivery with close; callbacks may enumerate interfaces, so do not hold r.mu.
	entry.mu.Lock()
	defer entry.mu.Unlock()
	if entry.closed {
		return
	}
	entry.listener.UpdateDefaultInterface(name, index, expensive, false)
}

// Called by Android for the initial state, capabilities/link changes, and network loss.
func UpdateDefaultInterface(token int64, name string, index int32, expensive bool) {
	defaultInterfaceMonitors.update(token, name, index, expensive)
}
