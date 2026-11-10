package libcore

import "testing"

type wifiFixture struct {
	fixturePlatform
	value string
}

func (f *wifiFixture) WIFIState() string { return f.value }

func TestWIFIStatePreservesSeparatorsUnicodeAndQuotes(t *testing.T) {
	previous := intfBox
	defer func() { intfBox = previous }()
	for _, value := range []struct{ raw, ssid, bssid string }{
		{`{"ssid":"ACME,Guest","bssid":"00:11:22:33:44:55"}`, "ACME,Guest", "00:11:22:33:44:55"},
		{`{"ssid":"家,\\\"网","bssid":"00:11:22:33:44:55"}`, "家,\\\"网", "00:11:22:33:44:55"},
		{`{"ssid":"","bssid":""}`, "", ""}, {`invalid`, "", ""},
	} {
		intfBox = &wifiFixture{value: value.raw}
		actual := (&boxPlatformInterfaceWrapper{}).ReadWIFIState()
		if actual.SSID != value.ssid || actual.BSSID != value.bssid {
			t.Fatalf("unexpected wifi state: %#v", actual)
		}
	}
}
