package libcore

import (
	"context"
	"errors"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/option"
)

type statsOutbound struct {
	adapter.Outbound
	tag string
}

func (o statsOutbound) Tag() string { return o.tag }

func TestResolvedStatsKeepExistingConnectionsOnTheirOriginalSelectorMember(t *testing.T) {
	server := boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{Enabled: true, Outbounds: []string{"a", "b", "proxy"}})
	tracker := &resolvedStats{delegate: server.StatsService()}
	selector := statsOutbound{tag: "proxy"}
	connection := func(tag string) net.Conn {
		client, remote := net.Pipe()
		t.Cleanup(func() { client.Close(); remote.Close() })
		go io.Copy(io.Discard, remote)
		return tracker.RoutedConnection(context.Background(), client,
			adapter.InboundContext{OutboundChain: []adapter.Outbound{selector, statsOutbound{tag: tag}}}, nil, selector)
	}
	a := connection("a")
	b := connection("b") // the selector changed; a remains open
	for _, item := range []struct {
		conn net.Conn
		data string
	}{{a, "123"}, {b, "12345"}, {a, "4567"}} {
		if _, err := item.conn.Write([]byte(item.data)); err != nil {
			t.Fatal(err)
		}
	}
	if got := server.QueryStats("outbound>>>a>>>traffic>>>downlink"); got != 7 {
		t.Fatalf("a = %d, want 7", got)
	}
	if got := server.QueryStats("outbound>>>b>>>traffic>>>downlink"); got != 5 {
		t.Fatalf("b = %d, want 5", got)
	}
	if got := server.QueryStats("outbound>>>proxy>>>traffic>>>downlink"); got != 0 {
		t.Fatalf("selector = %d", got)
	}
}

// Emulates a write that has reached the socket but whose counter is updated only
// after Close unblocks it. Final counter draining must wait for this write.
type delayedStatsConn struct {
	net.Conn
	entered, release chan struct{}
	once             sync.Once
}

func (c *delayedStatsConn) Write(p []byte) (int, error) {
	close(c.entered)
	<-c.release
	return len(p), nil
}
func (c *delayedStatsConn) Close() error { c.once.Do(func() { close(c.release) }); return nil }

func TestResolvedStatsCloseWaitsForFinalInFlightCounter(t *testing.T) {
	server := boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{Enabled: true, Outbounds: []string{"a"}})
	tracker := &resolvedStats{delegate: server.StatsService()}
	raw := &delayedStatsConn{entered: make(chan struct{}), release: make(chan struct{})}
	conn := tracker.RoutedConnection(context.Background(), raw, adapter.InboundContext{}, nil, statsOutbound{tag: "a"})
	done := make(chan struct{})
	go func() { defer close(done); conn.Write([]byte("final bytes")) }()
	<-raw.entered
	if err := tracker.Close(); err != nil {
		t.Fatal(err)
	}
	<-done
	if got := server.QueryStats("outbound>>>a>>>traffic>>>downlink"); got != 11 {
		t.Fatalf("final count = %d", got)
	}
	if _, err := conn.Write([]byte("late")); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("late write: %v", err)
	}
}

type testStatsPacket struct{ N.PacketConn }

func (p *testStatsPacket) ReadPacket(b *buf.Buffer) (M.Socksaddr, error) {
	b.WriteString("udp")
	return M.Socksaddr{}, nil
}
func (p *testStatsPacket) WritePacket(b *buf.Buffer, _ M.Socksaddr) error { b.Release(); return nil }
func (p *testStatsPacket) Close() error                                   { return nil }

type testFlowHandle struct{ closed bool }

func (f *testFlowHandle) CloseFlow() { f.closed = true }

func TestResolvedStatsCountsPacketAndFlowUnderResolvedEndpoint(t *testing.T) {
	server := boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{Enabled: true, Outbounds: []string{"endpoint", "proxy"}})
	tracker := &resolvedStats{delegate: server.StatsService()}
	selector := statsOutbound{tag: "proxy"}
	metadata := adapter.InboundContext{OutboundChain: []adapter.Outbound{selector, statsOutbound{tag: "endpoint"}}}
	packet := tracker.RoutedPacketConnection(context.Background(), &testStatsPacket{}, metadata, nil, selector)
	b := buf.NewSize(32)
	if _, err := packet.ReadPacket(b); err != nil {
		t.Fatal(err)
	}
	b.Release()
	b = buf.As([]byte("reply"))
	if err := packet.WritePacket(b, M.Socksaddr{}); err != nil {
		t.Fatal(err)
	}
	flow := tracker.RoutedFlow(context.Background(), metadata, nil, selector)
	handle := &testFlowHandle{}
	flow.AttachFlow(handle)
	flow.CountForward(7)
	flow.CountReverse(9)
	if err := tracker.Close(); err != nil {
		t.Fatal(err)
	}
	if !handle.closed {
		t.Fatal("flow was not closed")
	}
	if got := server.QueryStats("outbound>>>endpoint>>>traffic>>>uplink"); got != 10 {
		t.Fatalf("uplink = %d", got)
	}
	if got := server.QueryStats("outbound>>>endpoint>>>traffic>>>downlink"); got != 14 {
		t.Fatalf("downlink = %d", got)
	}
	if got := server.QueryStats("outbound>>>proxy>>>traffic>>>uplink"); got != 0 {
		t.Fatalf("selector = %d", got)
	}
}

func TestURLTestJoinsBoxShutdownAndUsesResolvedCounters(t *testing.T) {
	interfaces, err := net.Interfaces()
	if err != nil {
		t.Fatal(err)
	}
	var loopback net.Interface
	for _, iface := range interfaces {
		if iface.Flags&net.FlagLoopback != 0 {
			loopback = iface
			break
		}
	}
	platform := &fixturePlatform{iface: loopback, tokens: map[int64]bool{}}
	oldPlatform, oldBG, oldWriter := intfBox, isBgProcess, boxPlatformLogWriter
	intfBox, isBgProcess, boxPlatformLogWriter = platform, true, quietPlatformLog{}
	defer func() { intfBox, isBgProcess, boxPlatformLogWriter = oldPlatform, oldBG, oldWriter }()
	entered := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(entered)
		<-r.Context().Done() // don't return headers before the box is stopped
	}))
	defer server.Close()
	instance, err := NewSingBoxInstance(`{"route":{"auto_detect_interface":true,"final":"proxy"},"outbounds":[{"type":"selector","tag":"proxy","outbounds":["member"]},{"type":"direct","tag":"member"}]}`, &lookupResolver{})
	if err != nil {
		t.Fatal(err)
	}
	instance.SetV2rayStats("proxy\nmember")
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	done := make(chan error, 1)
	go func() { _, err := UrlTest(instance, server.URL, 60000); done <- err }()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("test request did not arrive")
	}
	if err = instance.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case err = <-done:
		if err == nil {
			t.Fatal("closed URL test unexpectedly succeeded")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("URL test outlived box shutdown")
	}
	if instance.QueryStats("member", "uplink") <= 0 {
		t.Fatal("request was not counted under resolved member")
	}
	if instance.QueryStats("proxy", "uplink") != 0 {
		t.Fatal("request counted under selector")
	}
	if instance.QueryStats("member", "uplink") != 0 {
		t.Fatal("late request bytes after final drain")
	}
}

func TestClosedBoxPreservesCoreFailureOnRetry(t *testing.T) {
	failure := errors.New("core cleanup failed")
	instance := &BoxInstance{state: 2, closeError: failure, stats: &resolvedStats{}}
	if !errors.Is(instance.Close(), failure) || !errors.Is(instance.Close(), failure) {
		t.Fatal("a repeated close must not turn failed core cleanup into success")
	}
}

func TestClosedBoxPreservesPanicBeforeCleanupReturns(t *testing.T) {
	instance := &BoxInstance{cancel: func() { panic("cleanup panic") }, stats: &resolvedStats{}}
	first := instance.Close()
	if first == nil {
		t.Fatal("cleanup panic was lost")
	}
	for range 2 {
		if retry := instance.Close(); !errors.Is(retry, first) {
			t.Fatalf("retry lost the original panic: %v", retry)
		}
	}
}
