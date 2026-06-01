package libcore

import (
	"encoding/json"
	"fmt"
	"libcore/procfs"
	"log"
	"net"
	"net/netip"
	"strings"
	"syscall"
	"errors"

	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/experimental/libbox"
	sblog "github.com/sagernet/sing-box/log"
)

type stringIterator []string
type networkInterfaceIterator []*libbox.NetworkInterface

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
    return nil
}

func (w *boxPlatformInterfaceWrapper) CloseDefaultInterfaceMonitor(listener libbox.InterfaceUpdateListener) error {
    return nil
}

func (w *boxPlatformInterfaceWrapper) GetInterfaces() (libbox.NetworkInterfaceIterator, error) {
	interfaces, err := net.Interfaces()
	if err != nil {
		items := make(networkInterfaceIterator, 0)
		return &items, nil
	}
	items := make(networkInterfaceIterator, 0, len(interfaces))
	for _, netInterface := range interfaces {
		addrs, _ := netInterface.Addrs()
		addressStrings := make(stringIterator, 0, len(addrs))
		for _, addr := range addrs {
			addressStrings = append(addressStrings, addr.String())
		}
		items = append(items, &libbox.NetworkInterface{
			Index:     int32(netInterface.Index),
			MTU:       int32(netInterface.MTU),
			Name:      netInterface.Name,
			Addresses: &addressStrings,
			Flags:     int32(netInterface.Flags),
			Type:      libbox.InterfaceTypeOther,
		})
	}
	return &items, nil
}

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

func (i *stringIterator) Len() int32 { return int32(len(*i)) }
func (i *networkInterfaceIterator) HasNext() bool { return len(*i) > 0 }
func (i *networkInterfaceIterator) Next() *libbox.NetworkInterface {
	if len(*i) == 0 { return nil }
	nextValue := (*i)[0]
	*i = (*i)[1:]
	return nextValue
}
func (i *stringIterator) HasNext() bool { return len(*i) > 0 }
func (i *stringIterator) Next() string {
	if len(*i) == 0 { return "" }
	nextValue := (*i)[0]
	*i = (*i)[1:]
	return nextValue
}
