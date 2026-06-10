package libcore

import (
	"encoding/json"
	"errors"
	"fmt"
	"libcore/procfs"
	"log"
	"net/netip"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/experimental/libbox"
	sblog "github.com/sagernet/sing-box/log"
)

type stringIterator []string
type networkInterfaceIterator []*libbox.NetworkInterface

type platformNetworkInterface struct {
	Index     int32    `json:"index"`
	MTU       int32    `json:"mtu"`
	Name      string   `json:"name"`
	Addresses []string `json:"addresses"`
	Flags     int32    `json:"flags"`
	Type      int32    `json:"type"`
	DNSServer []string `json:"dns_server"`
	Metered   bool     `json:"metered"`
	Default   bool     `json:"default"`
}

var boxPlatformInterfaceInstance libbox.PlatformInterface = &boxPlatformInterfaceWrapper{}

type boxPlatformInterfaceWrapper struct{}

func (w *boxPlatformInterfaceWrapper) LocalDNSTransport() libbox.LocalDNSTransport {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformAutoDetectInterfaceControl() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) AutoDetectInterfaceControl(fd int32) error {
	// call protect_path
	if !isBgProcess {
		_ = sendFdToProtect(int(fd), "protect_path")
		return nil
	}
	// bg process call VPNService
	return intfBox.AutoDetectInterfaceControl(fd)
}

func (w *boxPlatformInterfaceWrapper) OpenTun(options libbox.TunOptions) (int32, error) {
	a, _ := json.Marshal(options)
	tunFd, err := intfBox.OpenTun(string(a), "")
	if err != nil {
		return 0, fmt.Errorf("intfBox.OpenTun: %v", err)
	}
	return int32(tunFd), nil
}

func (w *boxPlatformInterfaceWrapper) UseProcFS() bool {
	return useProcfs
}

func (w *boxPlatformInterfaceWrapper) FindConnectionOwner(ipProtocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) (*libbox.ConnectionOwner, error) {
	var uid int32
	if useProcfs {
		var network string
		if ipProtocol == syscall.IPPROTO_TCP {
			network = "tcp"
		} else {
			network = "udp"
		}
		source, _ := netip.ParseAddrPort(fmt.Sprintf("%s:%d", sourceAddress, sourcePort))
		destination, _ := netip.ParseAddrPort(fmt.Sprintf("%s:%d", destinationAddress, destinationPort))
		uid = procfs.ResolveSocketByProcSearch(network, source, destination)
		if uid == -1 {
			return nil, errors.New("procfs: not found")
		}
	} else {
		var err error
		uid, err = intfBox.FindConnectionOwner(ipProtocol, sourceAddress, sourcePort, destinationAddress, destinationPort)
		if err != nil {
			return nil, err
		}
	}
	packageName, _ := intfBox.PackageNameByUid(uid)
	owner := &libbox.ConnectionOwner{
		UserId: uid,
	}
	var iter stringIterator = []string{packageName}
	owner.SetAndroidPackageNames(&iter)
	return owner, nil
}

func (w *boxPlatformInterfaceWrapper) StartDefaultInterfaceMonitor(listener libbox.InterfaceUpdateListener) error {
	defaultInterfaceMonitorAccess.Lock()
	if defaultInterfaceMonitorCancel != nil {
		close(defaultInterfaceMonitorCancel)
	}
	stop := make(chan struct{})
	defaultInterfaceMonitorCancel = stop
	defaultInterfaceMonitorAccess.Unlock()

	w.updateDefaultInterface(listener)
	go func() {
		ticker := time.NewTicker(10 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				w.updateDefaultInterface(listener)
			case <-stop:
				return
			}
		}
	}()
	return nil
}

func (w *boxPlatformInterfaceWrapper) CloseDefaultInterfaceMonitor(listener libbox.InterfaceUpdateListener) error {
	defaultInterfaceMonitorAccess.Lock()
	if defaultInterfaceMonitorCancel != nil {
		close(defaultInterfaceMonitorCancel)
		defaultInterfaceMonitorCancel = nil
	}
	defaultInterfaceMonitorAccess.Unlock()
	return nil
}

func (w *boxPlatformInterfaceWrapper) platformInterfaces() ([]platformNetworkInterface, error) {
	rawInterfaces, err := intfBox.NetworkInterfaces()
	if err != nil {
		return nil, fmt.Errorf("intfBox.NetworkInterfaces: %w", err)
	}
	if strings.TrimSpace(rawInterfaces) == "" {
		updateTailscaleDefaultRoute("")
		return nil, nil
	}

	var platformInterfaces []platformNetworkInterface
	if err := json.Unmarshal([]byte(rawInterfaces), &platformInterfaces); err != nil {
		return nil, fmt.Errorf("decode platform network interfaces: %w", err)
	}
	return platformInterfaces, nil
}

func (w *boxPlatformInterfaceWrapper) updateDefaultInterface(listener libbox.InterfaceUpdateListener) {
	platformInterfaces, err := w.platformInterfaces()
	if err != nil {
		return
	}
	defaultInterface := selectDefaultInterface(platformInterfaces)
	if defaultInterface == nil {
		if listener != nil {
			listener.UpdateDefaultInterface("", -1, false, false)
		}
		updateTailscaleDefaultRoute("")
		return
	}
	updateTailscaleDefaultRoute(defaultInterface.Name)
	if listener != nil {
		listener.UpdateDefaultInterface(defaultInterface.Name, defaultInterface.Index, defaultInterface.Metered, false)
	}
}

func (w *boxPlatformInterfaceWrapper) GetInterfaces() (libbox.NetworkInterfaceIterator, error) {
	platformInterfaces, err := w.platformInterfaces()
	if err != nil {
		return nil, err
	}
	if len(platformInterfaces) == 0 {
		items := make(networkInterfaceIterator, 0)
		return &items, nil
	}
	if defaultInterface := selectDefaultInterface(platformInterfaces); defaultInterface != nil {
		updateTailscaleDefaultRoute(defaultInterface.Name)
	}

	items := make(networkInterfaceIterator, 0, len(platformInterfaces))
	for _, platformInterface := range platformInterfaces {
		if platformInterface.Name == "" {
			continue
		}
		addressStrings := stringIterator(platformInterface.Addresses)
		dnsServers := stringIterator(platformInterface.DNSServer)
		items = append(items, &libbox.NetworkInterface{
			Index:     platformInterface.Index,
			MTU:       platformInterface.MTU,
			Name:      platformInterface.Name,
			Addresses: &addressStrings,
			Flags:     platformInterface.Flags,
			Type:      platformInterface.Type,
			DNSServer: &dnsServers,
			Metered:   platformInterface.Metered,
		})
	}
	return &items, nil
}

func selectDefaultInterface(platformInterfaces []platformNetworkInterface) *platformNetworkInterface {
	for index := range platformInterfaces {
		platformInterface := &platformInterfaces[index]
		if platformInterface.Name != "" && platformInterface.Default {
			return platformInterface
		}
	}
	for index := range platformInterfaces {
		platformInterface := &platformInterfaces[index]
		if platformInterface.Name != "" {
			return platformInterface
		}
	}
	return nil
}

var (
	defaultInterfaceMonitorAccess sync.Mutex
	defaultInterfaceMonitorCancel chan struct{}
)

func (w *boxPlatformInterfaceWrapper) UnderNetworkExtension() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) IncludeAllNetworks() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) ReadWIFIState() *libbox.WIFIState {
	state := strings.Split(intfBox.WIFIState(), ",")
	if len(state) >= 2 {
		return libbox.NewWIFIState(state[0], state[1])
	}
	return libbox.NewWIFIState("", "")
}

func (s *boxPlatformInterfaceWrapper) SystemCertificates() libbox.StringIterator {
	return nil
}

func (w *boxPlatformInterfaceWrapper) ClearDNSCache() {
}

func (w *boxPlatformInterfaceWrapper) SendNotification(notification *libbox.Notification) error {
	return nil
}

// io.Writer

var disableSingBoxLog = false

func (w *boxPlatformInterfaceWrapper) Write(p []byte) (n int, err error) {
	// use neko_log
	if !disableSingBoxLog {
		log.Print(string(p))
	}
	return len(p), nil
}

// 日志

type boxPlatformLogWriterWrapper struct {
}

var boxPlatformLogWriter sblog.PlatformWriter = &boxPlatformLogWriterWrapper{}

func (w *boxPlatformLogWriterWrapper) DisableColors() bool { return true }

func (w *boxPlatformLogWriterWrapper) WriteMessage(level uint8, message string) {
	if !strings.HasSuffix(message, "\n") {
		message += "\n"
	}
	neko_log.LogWriter.Write([]byte(message))
}

func (i *stringIterator) Len() int32              { return int32(len(*i)) }
func (i *networkInterfaceIterator) HasNext() bool { return len(*i) > 0 }
func (i *networkInterfaceIterator) Next() *libbox.NetworkInterface {
	if len(*i) == 0 {
		return nil
	}
	nextValue := (*i)[0]
	*i = (*i)[1:]
	return nextValue
}
func (i *stringIterator) HasNext() bool { return len(*i) > 0 }
func (i *stringIterator) Next() string {
	if len(*i) == 0 {
		return ""
	}
	nextValue := (*i)[0]
	*i = (*i)[1:]
	return nextValue
}
