package libcore

import "sync/atomic"

type assetInitialization struct{ done chan struct{} }

var initialAssets atomic.Pointer[assetInitialization]

func startAssetInitialization(install func()) {
	state := &assetInitialization{done: make(chan struct{})}
	initialAssets.Store(state)
	go func() {
		defer close(state.done)
		install()
	}()
}

// WaitForAssets prevents first-run readers from racing APK database installation.
// Failures remain available through extraction logs and the subsequent file open.
func WaitForAssets() {
	if state := initialAssets.Load(); state != nil {
		<-state.done
	}
}

const (
	geoipDat       = "geoip.db"
	geositeDat     = "geosite.db"
	geoipVersion   = "geoip.version.txt"
	geositeVersion = "geosite.version.txt"

	yacdDstFolder = "yacd"
	yacdVersion   = "yacd.version.txt"
)

var apkAssetPrefixSingBox = "sing-box/"
var internalAssetsPath string
var externalAssetsPath string
