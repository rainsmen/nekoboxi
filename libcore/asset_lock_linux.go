//go:build linux || android

package libcore

import (
	"errors"
	"golang.org/x/sys/unix"
	"os"
	"path/filepath"
)

// OFD locks also conflict with Java FileChannel's process locks in the same process.
// Both use byte 0 of .assets.lock; retain the inode between installations.
func lockAssetDirectory(directory string) (func(), error) {
	if err := os.MkdirAll(directory, 0755); err != nil {
		return nil, err
	}
	file, err := os.OpenFile(filepath.Join(directory, ".assets.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, err
	}
	lock := unix.Flock_t{Type: unix.F_WRLCK, Whence: 0, Start: 0, Len: 1}
	for {
		err = unix.FcntlFlock(file.Fd(), unix.F_OFD_SETLKW, &lock)
		if !errors.Is(err, unix.EINTR) {
			break
		}
	}
	if err != nil {
		file.Close()
		return nil, err
	}
	return func() { file.Close() }, nil
}
