//go:build android

package libcore

import "github.com/sagernet/tailscale/net/netmon"

func updateTailscaleDefaultRoute(interfaceName string) {
	netmon.UpdateLastKnownDefaultRouteInterface(interfaceName)
}
