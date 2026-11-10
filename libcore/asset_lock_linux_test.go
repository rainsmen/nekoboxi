//go:build linux

package libcore

import (
	"golang.org/x/sys/unix"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestNativeAssetInstallWaitsForJavaStyleVersionedImport(t *testing.T) {
	files := map[string][]byte{apkAssetPrefixSingBox + geositeVersion: []byte("2")}
	installer := fixtureInstaller(t, files)
	file, err := os.OpenFile(filepath.Join(installer.externalDir, ".assets.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	lock := unix.Flock_t{Type: unix.F_WRLCK, Whence: 0, Start: 0, Len: 1}
	if err = unix.FcntlFlock(file.Fd(), unix.F_SETLK, &lock); err != nil {
		t.Fatal(err)
	}
	release := func() { lock.Type = unix.F_UNLCK; unix.FcntlFlock(file.Fd(), unix.F_SETLK, &lock) }
	defer release()
	done := make(chan error, 1)
	go func() { done <- installer.install(geositeDat, true) }()
	select {
	case err := <-done:
		t.Fatal("native installation bypassed Java lock", err)
	case <-time.After(50 * time.Millisecond):
	}
	data := fixtureGeoSite(t, "imported.example")
	mustWrite(t, filepath.Join(installer.externalDir, geositeDat), data)
	mustWrite(t, filepath.Join(installer.externalDir, geositeVersion), []byte("Custom"))
	release()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("installation did not resume after import")
	}
	assertBytes(t, filepath.Join(installer.externalDir, geositeDat), data)
	assertBytes(t, filepath.Join(installer.externalDir, geositeVersion), []byte("Custom"))
}
