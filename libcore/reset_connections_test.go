package libcore

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

func TestResetConnectionsClosesTrackedTCPAndUDP(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ctx = box.Context(ctx, nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(),
		nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil),
		nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateProviderRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, []byte(`{"log":{"disabled":true},"outbounds":[{"type":"direct","tag":"direct"}]}`)); err != nil {
		t.Fatal(err)
	}
	core, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		t.Fatal(err)
	}
	instance := &BoxInstance{Box: core, ctx: ctx, cancel: cancel}
	defer instance.Close()
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	manager := service.FromContext[adapter.ConnectionManager](ctx)
	if manager == nil {
		t.Fatal("missing core connection manager")
	}
	left, right := net.Pipe()
	defer right.Close()
	tracked := manager.TrackConn(left)
	defer tracked.Close()
	udp, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	packet := manager.TrackPacketConn(udp)
	defer packet.Close()
	// An automatic task keeps its original instance even after main is replaced.
	mainInstance.Store(&BoxInstance{state: 2})
	if err := instance.ResetConnections(); err != nil {
		t.Fatal(err)
	}
	if _, err := tracked.Write([]byte("fixture")); err == nil {
		t.Fatal("TCP survived reset")
	}
	packet.SetReadDeadline(time.Now().Add(time.Second))
	if _, _, err := packet.ReadFrom(make([]byte, 1)); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("UDP survived reset: %v", err)
	}
	mainInstance.Store(instance)
	if err := ResetAllConnections(true); err != nil {
		t.Fatal(err)
	}
	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}
	if err := ResetAllConnections(true); err == nil {
		t.Fatal("stopped core reported successful reset")
	}
}
