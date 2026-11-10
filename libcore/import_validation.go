package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/filemanager"
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
	ctx = service.ContextWith[filemanager.Manager](ctx, importFiles{})
	if len(config) > 16*1024*1024 {
		return fmt.Errorf("import-field:size")
	}
	var raw struct {
		Outbounds []map[string]json.RawMessage `json:"outbounds"`
		Endpoints []map[string]json.RawMessage `json:"endpoints"`
	}
	if json.Unmarshal([]byte(config), &raw) == nil {
		for _, node := range append(raw.Outbounds, raw.Endpoints...) {
			var kind string
			if json.Unmarshal(node["type"], &kind) != nil || kind == "" {
				return fmt.Errorf("import-field:type")
			}
			if value, ok := node["server_port"]; ok {
				var port int
				if json.Unmarshal(value, &port) != nil || port < 1 || port > 65535 {
					return fmt.Errorf("import-field:server_port")
				}
			}
		}
	}
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
				return fmt.Errorf("import-field:server")
			}
			if needsPort && server.ServerPort == 0 {
				return fmt.Errorf("import-field:server_port")
			}
		}
	}
	instance, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		if errors.Is(err, errImportedFile) {
			return errImportedFile
		}
		// Constructor errors can include credentials or paths from imported input.
		return fmt.Errorf("invalid imported protocol configuration")
	}
	return instance.Close()
}
