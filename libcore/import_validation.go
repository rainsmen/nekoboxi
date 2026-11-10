package libcore

import (
	"context"
	"fmt"
	"strings"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

// ValidateImportedConfig validates a node and its embedded dependencies with the
// Android registries. It constructs and closes the core but never starts it.
// The caller supplies only a generated validation container, not a full user config.
func ValidateImportedConfig(config string) (err error) {
	defer func() {
		if recover() != nil {
			err = fmt.Errorf("invalid imported configuration")
		}
	}()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateProviderRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	var options option.Options
	if err = options.UnmarshalJSONContext(ctx, []byte(config)); err != nil {
		return fmt.Errorf("invalid imported configuration fields")
	}
	for _, outbound := range options.Outbounds {
		if remote, ok := outbound.Options.(option.ServerOptionsWrapper); ok {
			server := remote.TakeServerOptions()
			needsServer, needsPort := true, true
			switch value := outbound.Options.(type) {
			case *option.HysteriaOutboundOptions:
				needsPort = len(value.ServerPorts) == 0
			case *option.Hysteria2OutboundOptions:
				needsServer = value.Realm == nil
				needsPort = needsServer && len(value.ServerPorts) == 0
			case *option.SSHOutboundOptions:
				needsPort = false // The core supplies the SSH default port.
			}
			if needsServer && strings.TrimSpace(server.Server) == "" {
				return fmt.Errorf("missing server")
			}
			if needsPort && server.ServerPort == 0 {
				return fmt.Errorf("missing server_port")
			}
		}
	}
	instance, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		// Constructor errors can include credentials or paths from imported input.
		return fmt.Errorf("invalid imported protocol configuration")
	}
	return instance.Close()
}
