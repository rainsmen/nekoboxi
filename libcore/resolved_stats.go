package libcore

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// The router resolves groups per connection. Counters keep that owner for the
// whole connection, including connections surviving a selector change.
// Stop also establishes an I/O completion barrier before Kotlin drains counters.
type resolvedStats struct {
	delegate    adapter.ConnectionTracker
	mu          sync.Mutex
	closed      bool
	pending     sync.WaitGroup
	connections map[io.Closer]struct{}
}

func resolvedStatsOutbound(metadata adapter.InboundContext, fallback adapter.Outbound) adapter.Outbound {
	if len(metadata.OutboundChain) > 0 {
		return metadata.OutboundChain[len(metadata.OutboundChain)-1]
	}
	return fallback
}
func (s *resolvedStats) begin() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return false
	}
	s.pending.Add(1)
	return true
}
func (s *resolvedStats) register(c io.Closer) {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		_ = c.Close()
		return
	}
	if s.connections == nil {
		s.connections = make(map[io.Closer]struct{})
	}
	s.connections[c] = struct{}{}
	s.mu.Unlock()
}
func (s *resolvedStats) remove(c io.Closer) {
	s.mu.Lock()
	delete(s.connections, c)
	s.mu.Unlock()
}
func (s *resolvedStats) Close() error {
	s.mu.Lock()
	s.closed = true
	connections := make([]io.Closer, 0, len(s.connections))
	for c := range s.connections {
		connections = append(connections, c)
	}
	s.mu.Unlock()
	for _, c := range connections {
		_ = c.Close()
	}
	done := make(chan struct{})
	go func() { s.pending.Wait(); close(done) }()
	select {
	case <-done:
		return nil
	case <-time.After(5 * time.Second):
		return errors.New("traffic accounting: I/O did not finish during shutdown")
	}
}
func (s *resolvedStats) RoutedConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, rule adapter.Rule, outbound adapter.Outbound) net.Conn {
	wrapped := &statsConn{Conn: s.delegate.RoutedConnection(ctx, conn, metadata, rule, resolvedStatsOutbound(metadata, outbound)), owner: s}
	s.register(wrapped)
	return wrapped
}
func (s *resolvedStats) RoutedPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, rule adapter.Rule, outbound adapter.Outbound) N.PacketConn {
	wrapped := &statsPacketConn{PacketConn: s.delegate.RoutedPacketConnection(ctx, conn, metadata, rule, resolvedStatsOutbound(metadata, outbound)), owner: s}
	s.register(wrapped)
	return wrapped
}
func (s *resolvedStats) RoutedFlow(ctx context.Context, metadata adapter.InboundContext, rule adapter.Rule, outbound adapter.Outbound) tun.FlowTracker {
	delegate := s.delegate.RoutedFlow(ctx, metadata, rule, resolvedStatsOutbound(metadata, outbound))
	if delegate == nil {
		return nil
	}
	return &statsFlow{FlowTracker: delegate, owner: s}
}

type statsConn struct {
	net.Conn
	owner *resolvedStats
}

func (c *statsConn) Read(p []byte) (int, error) {
	if !c.owner.begin() {
		return 0, net.ErrClosed
	}
	defer c.owner.pending.Done()
	return c.Conn.Read(p)
}
func (c *statsConn) Write(p []byte) (int, error) {
	if !c.owner.begin() {
		return 0, net.ErrClosed
	}
	defer c.owner.pending.Done()
	return c.Conn.Write(p)
}
func (c *statsConn) Close() error { defer c.owner.remove(c); return c.Conn.Close() }

type statsPacketConn struct {
	N.PacketConn
	owner *resolvedStats
}

func (c *statsPacketConn) ReadPacket(b *buf.Buffer) (M.Socksaddr, error) {
	if !c.owner.begin() {
		return M.Socksaddr{}, net.ErrClosed
	}
	defer c.owner.pending.Done()
	return c.PacketConn.ReadPacket(b)
}
func (c *statsPacketConn) WritePacket(b *buf.Buffer, destination M.Socksaddr) error {
	if !c.owner.begin() {
		b.Release()
		return net.ErrClosed
	}
	defer c.owner.pending.Done()
	return c.PacketConn.WritePacket(b, destination)
}
func (c *statsPacketConn) Close() error { defer c.owner.remove(c); return c.PacketConn.Close() }

type statsFlow struct {
	tun.FlowTracker
	owner  *resolvedStats
	handle tun.FlowHandle
}

func (f *statsFlow) AttachFlow(handle tun.FlowHandle) {
	f.handle = handle
	f.FlowTracker.AttachFlow(handle)
	f.owner.register(f)
}
func (f *statsFlow) CountForward(n int) {
	if !f.owner.begin() {
		return
	}
	defer f.owner.pending.Done()
	f.FlowTracker.CountForward(n)
}
func (f *statsFlow) CountReverse(n int) {
	if !f.owner.begin() {
		return
	}
	defer f.owner.pending.Done()
	f.FlowTracker.CountReverse(n)
}
func (f *statsFlow) CloseFlow(reason tun.FlowCloseReason) {
	defer f.owner.remove(f)
	f.FlowTracker.CloseFlow(reason)
}
func (f *statsFlow) Close() error { f.handle.CloseFlow(); f.owner.remove(f); return nil }
