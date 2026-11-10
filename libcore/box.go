package libcore

import (
	"context"
	"errors"
	"fmt"
	M "github.com/sagernet/sing/common/metadata"
	"io"
	"libcore/device"
	"log"
	"net"
	"net/http"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/matsuridayo/libneko/protect_server"
	"github.com/matsuridayo/libneko/speedtest"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/experimental/libbox"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"

	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

func init() {
	dialer.DoNotSelectInterface = true
}

var mainInstance atomic.Pointer[BoxInstance]

func VersionBox() string {
	version := []string{
		"sing-box: " + constant.Version,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func ResetAllConnections(system bool) {
	if system {
		// conntrack.Close()
		log.Println("Reset system connections done")
	} else {
		log.Println("TODO: Reset user connections")
	}
}

type BoxInstance struct {
	closeError error
	access     sync.Mutex

	*box.Box
	cancel context.CancelFunc
	ctx    context.Context
	state  int

	v2api        *boxapi.SbV2rayServer
	stats        *resolvedStats
	selector     *group.Selector
	pauseManager pause.Manager
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
		nekoboxAndroidCertificateProviderRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	// Register the platform interface as adapter.PlatformInterface (not just the
	// gomobile-facing libbox.PlatformInterface) so the sing-box network manager uses
	// the platform interface monitor instead of a netlink monitor, which Android bans.
	libbox.RegisterPlatformInterface(ctx, boxPlatformInterfaceInstance)

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		return nil, fmt.Errorf("decode config: %v", err)
	}

	// create box
	instance, err := box.New(box.Options{
		Options:           options,
		Context:           ctx,
		PlatformLogWriter: boxPlatformLogWriter,
	})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %v", err)
	}

	b = &BoxInstance{
		Box:          instance,
		cancel:       cancel,
		ctx:          ctx,
		pauseManager: service.FromContext[pause.Manager](ctx),
	}

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		}
	}

	return b, nil
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.state == 0 {
		b.state = 1
		return b.Box.Start()
	}
	return errors.New("already started")
}

func (b *BoxInstance) Close() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	// no double close
	if b.state == 2 {
		if b.stats != nil {
			return errors.Join(b.closeError, b.stats.Close())
		}
		return b.closeError
	}
	b.state = 2

	// clear main instance
	if mainInstance.CompareAndSwap(b, nil) {
		goServeProtect(false)
	}

	// close box
	if b.cancel != nil {
		b.cancel()
	}
	if b.Box != nil {
		b.closeError = b.Box.Close()
	}
	err = b.closeError
	if b.stats != nil {
		err = errors.Join(err, b.stats.Close())
	}
	return err
}

func (b *BoxInstance) Sleep() {
	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) Wake() {
	if b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	mainInstance.Store(b)
	goServeProtect(true)
}

func (b *BoxInstance) SetV2rayStats(outbounds string) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.v2api != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	b.v2api = boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	b.stats = &resolvedStats{delegate: b.v2api.StatsService()}
	b.Box.Router().AppendTracker(b.stats)
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if b.v2api == nil {
		return 0
	}
	return b.v2api.QueryStats(fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct))
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	if b.selector != nil {
		return b.selector.SelectOutbound(tag)
	}
	return false
}

// URL tests dial outbounds directly, so they must explicitly join both the
// lifetime of the box and its counter barrier (the router is not involved).
func (b *BoxInstance) testHTTPClient() *http.Client {
	client := boxapi.CreateProxyHttpClient(nil, nil)
	client.Transport.(*http.Transport).DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
		b.access.Lock()
		if b.state != 1 {
			b.access.Unlock()
			return nil, net.ErrClosed
		}
		outbound := b.Box.Outbound().Default()
		stats, api, boxContext := b.stats, b.v2api, b.ctx
		b.access.Unlock()
		ctx, cancel := context.WithCancel(ctx)
		defer cancel()
		stop := context.AfterFunc(boxContext, cancel)
		defer stop()
		for depth := 0; ; depth++ {
			group, ok := outbound.(adapter.OutboundGroup)
			if !ok {
				break
			}
			if depth >= 128 {
				return nil, errors.New("circular outbound group")
			}
			outbound = group.Selected(network)
			if outbound == nil {
				return nil, errors.New("outbound does not support test network")
			}
		}
		conn, err := outbound.DialContext(ctx, network, M.ParseSocksaddr(address))
		if err != nil {
			return nil, err
		}
		if stats != nil {
			conn = api.StatsService().(*boxapi.SbStatsService).RoutedConnectionInternal("", outbound.Tag(), "", conn, false)
			tracked := &statsConn{Conn: conn, owner: stats}
			stats.register(tracked)
			conn = tracked
		}
		return conn, nil
	}
	return client
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	if i == nil {
		i = mainInstance.Load()
	}
	client := boxapi.CreateProxyHttpClient(nil, nil)
	if i != nil {
		client = i.testHTTPClient()
	}
	return speedtest.UrlTest(client, link, timeout, speedtest.UrlTestStandard_RTT)
}

var protectCloser io.Closer

func goServeProtect(start bool) {
	if protectCloser != nil {
		protectCloser.Close()
		protectCloser = nil
	}
	if start {
		protectCloser = protect_server.ServeProtect("protect_path", false, 0, func(fd int) {
			intfBox.AutoDetectInterfaceControl(int32(fd))
		})
	}
}
