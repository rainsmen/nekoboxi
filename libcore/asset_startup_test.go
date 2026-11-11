package libcore

import (
	"path/filepath"
	"testing"
	"time"
)

func TestDatabaseOpenWaitsForInitialAssetInstallation(t *testing.T) {
	previous := initialAssets.Load()
	defer initialAssets.Store(previous)
	data := fixtureGeoIP()
	installer := fixtureInstaller(t, map[string][]byte{"sing-box/geoip.db.xz": fixtureXZ(t, data), "sing-box/geoip.version.txt": []byte("1")})
	entered, release := make(chan struct{}), make(chan struct{})
	defer func() {
		select {
		case <-release:
		default:
			close(release)
		}
	}()
	installed := make(chan error, 1)
	startAssetInitialization(func() { close(entered); <-release; installed <- installer.install(geoipDat, true) })
	<-entered
	database := new(geoip)
	done := make(chan error, 1)
	opening := make(chan struct{})
	go func() { close(opening); done <- database.Open(filepath.Join(installer.externalDir, geoipDat)) }()
	<-opening
	select {
	case err := <-done:
		t.Fatalf("read escaped installation barrier: %v", err)
	case <-time.After(100 * time.Millisecond):
	}
	close(release)
	for _, result := range []<-chan error{installed, done} {
		select {
		case err := <-result:
			if err != nil {
				t.Fatal(err)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("asset initialization did not release reader")
		}
	}
	defer database.geoipReader.Close()
	if _, err := database.Rules("cn"); err != nil {
		t.Fatal(err)
	}
}
