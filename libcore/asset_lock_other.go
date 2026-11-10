//go:build !linux && !android

package libcore

import "fmt"

func lockAssetDirectory(directory string) (func(), error) {
	return nil, fmt.Errorf("Android asset installation requires Linux file locking")
}
