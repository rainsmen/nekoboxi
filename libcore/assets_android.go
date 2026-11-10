//go:build android

package libcore

import (
	"io"
	"log"

	"golang.org/x/mobile/asset"
)

func extractAssets() {
	installer := assetInstaller{
		open:        func(name string) (io.ReadCloser, error) { return asset.Open(name) },
		internalDir: internalAssetsPath,
		externalDir: externalAssetsPath,
	}
	for _, name := range []string{geoipDat, geositeDat, yacdDstFolder} {
		if err := installer.install(name, intfNB4A.UseOfficialAssets()); err != nil {
			log.Println("Extract", name, "failed:", err)
		}
	}
}
