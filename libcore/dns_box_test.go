package libcore

import (
	"context"
	"github.com/miekg/dns"
	"github.com/sagernet/sing-box/option"
	"testing"
	"time"
)

type lookupResolver struct{ fail bool }

func (*lookupResolver) Raw() bool                               { return false }
func (*lookupResolver) NetworkHandle() int64                    { return 0 }
func (*lookupResolver) Exchange(*ExchangeContext, []byte) error { panic("unexpected raw lookup") }
func (r *lookupResolver) Lookup(ctx *ExchangeContext, _, _ string) error {
	if r.fail {
		ctx.ErrorCode(3)
	} else {
		ctx.Success("192.0.2.1\n2001:db8::1")
	}
	return nil
}
func TestLocalDNSLookupCompletes(t *testing.T) {
	for _, fail := range []bool{false, true} {
		resolver := newPlatformTransport(&lookupResolver{fail}, "local", option.LocalDNSServerOptions{})
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		request := new(dns.Msg).SetQuestion("example.com.", dns.TypeA)
		response, err := resolver.Exchange(ctx, request)
		if ctx.Err() != nil {
			t.Fatal("lookup timed out")
		}
		if fail && err == nil {
			t.Fatal("expected DNS error")
		}
		if !fail && (err != nil || len(response.Answer) == 0) {
			t.Fatalf("%v %v", response, err)
		}
		cancel()
	}
}
