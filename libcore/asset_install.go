package libcore

import (
	"crypto/sha256"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/ulikunitz/xz"
)

const maxAssetBytes int64 = 256 * 1024 * 1024

// Shared with host fault-injection tests; Android only supplies the APK opener.
type assetInstaller struct {
	open                     func(string) (io.ReadCloser, error)
	internalDir, externalDir string
}

func copyAsset(output io.Writer, input io.Reader) error {
	n, err := io.Copy(output, io.LimitReader(input, maxAssetBytes+1))
	if err == nil && n > maxAssetBytes {
		return fmt.Errorf("asset exceeds 256 MiB")
	}
	return err
}

func replaceAssetFile(destination string, write func(io.Writer) error, validate func(string) error) error {
	if err := os.MkdirAll(filepath.Dir(destination), 0755); err != nil {
		return err
	}
	file, err := os.CreateTemp(filepath.Dir(destination), ".asset-*")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	err = write(file)
	if err == nil {
		err = file.Sync()
	}
	err = errors.Join(err, file.Close())
	if err != nil {
		return err
	}
	if validate != nil {
		if err = validate(file.Name()); err != nil {
			return err
		}
	}
	return os.Rename(file.Name(), destination)
}

func (a assetInstaller) install(name string, official bool) error {
	var versionName, prefix string
	dir := a.externalDir
	switch name {
	case geoipDat:
		versionName, prefix = geoipVersion, apkAssetPrefixSingBox
	case geositeDat:
		versionName, prefix = geositeVersion, apkAssetPrefixSingBox
	case yacdDstFolder:
		versionName, dir = yacdVersion, a.internalDir
	default:
		return fmt.Errorf("unknown bundled asset %q", name)
	}
	release, err := lockAssetDirectory(dir)
	if err != nil {
		return err
	}
	defer release()
	destination, versionPath := filepath.Join(dir, name), filepath.Join(dir, versionName)
	if name == yacdDstFolder {
		if err := recoverDashboard(destination); err != nil {
			return err
		}
	} else {
		if err := recoverAssetVersion(destination, versionPath); err != nil {
			return err
		}
	}
	versionSource, err := a.open(prefix + versionName)
	if err != nil {
		return err
	}
	versionBytes, err := io.ReadAll(io.LimitReader(versionSource, 4097))
	err = errors.Join(err, versionSource.Close())
	if err != nil {
		return err
	}
	version := strings.TrimSpace(string(versionBytes))
	if version == "" || len(versionBytes) > 4096 {
		return fmt.Errorf("invalid bundled asset version")
	}
	if _, err = os.Stat(destination); err == nil {
		if !official && name != yacdDstFolder {
			return nil
		}
		localBytes, readErr := os.ReadFile(versionPath)
		local := strings.TrimSpace(string(localBytes))
		if local == "Custom" {
			return nil
		}
		newer := version != local
		if av, e := strconv.ParseUint(version, 10, 64); e == nil {
			lv, e := strconv.ParseUint(local, 10, 64)
			newer = e != nil || av > lv
		}
		if readErr == nil && !newer {
			if name == yacdDstFolder || ValidateAsset(destination, name) == nil {
				return nil
			}
			// Repair a damaged official database even if a previous build marked it current.
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}

	if name == yacdDstFolder {
		err = a.installDashboard(destination)
	} else {
		err = replaceAssetFile(destination, func(output io.Writer) error {
			source, err := a.open(prefix + name + ".xz")
			if err != nil {
				return err
			}
			reader, err := xz.NewReader(source)
			if err == nil {
				err = copyAsset(output, reader)
			}
			return errors.Join(err, source.Close())
		}, func(path string) error { return ValidateAsset(path, name) })
	}
	if err != nil {
		return fmt.Errorf("install %s: %w", name, err)
	}
	if err = replaceAssetFile(versionPath, func(output io.Writer) error {
		_, err := io.WriteString(output, version)
		return err
	}, nil); err != nil {
		return fmt.Errorf("%s installed, but version metadata could not be saved: %w", name, err)
	}
	return nil
}

// Shared journal format with Android AssetImporter: SHA-256, newline, intended version.
func recoverAssetVersion(destination, versionPath string) error {
	journal := versionPath + ".pending"
	raw, err := os.ReadFile(journal)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	expected, version, found := strings.Cut(string(raw), "\n")
	if !found || len(raw) > 4096 || len(expected) != 64 || strings.TrimSpace(version) == "" || strings.ContainsAny(version, "\r\n") {
		return fmt.Errorf("invalid asset version journal")
	}
	file, err := os.Open(destination)
	if errors.Is(err, os.ErrNotExist) {
		return os.Remove(journal)
	}
	if err != nil {
		return err
	}
	hash := sha256.New()
	err = errors.Join(copyAsset(hash, file), file.Close())
	if err != nil {
		return err
	}
	if fmt.Sprintf("%x", hash.Sum(nil)) != expected {
		return os.Remove(journal)
	}
	if err = replaceAssetFile(versionPath, func(output io.Writer) error {
		_, err := io.WriteString(output, version)
		return err
	}, nil); err != nil {
		return err
	}
	return os.Remove(journal)
}

func recoverDashboard(destination string) error {
	backup := destination + ".previous"
	if _, err := os.Stat(destination); errors.Is(err, os.ErrNotExist) {
		// Recover an interrupted directory swap before beginning a new one.
		if _, e := os.Stat(backup); e == nil {
			if err = os.Rename(backup, destination); err != nil {
				return err
			}
		}
	}
	return nil
}

func (a assetInstaller) installDashboard(destination string) error {
	if err := os.MkdirAll(filepath.Dir(destination), 0755); err != nil {
		return err
	}
	backup := destination + ".previous"
	staging, err := os.MkdirTemp(filepath.Dir(destination), ".dashboard-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(staging)
	archive := filepath.Join(staging, "dashboard.zip")
	err = replaceAssetFile(archive, func(output io.Writer) error {
		input, err := a.open("yacd.zip")
		if err != nil {
			return err
		}
		return errors.Join(copyAsset(output, input), input.Close())
	}, nil)
	if err != nil {
		return err
	}
	unpacked := filepath.Join(staging, "unpacked")
	if err = Unzip(archive, unpacked); err != nil {
		return err
	}
	matches, err := filepath.Glob(filepath.Join(unpacked, "Yacd-*"))
	if err != nil {
		return err
	}
	if len(matches) != 1 {
		return fmt.Errorf("expected one dashboard directory, got %d", len(matches))
	}
	info, err := os.Stat(filepath.Join(matches[0], "index.html"))
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() || info.Size() == 0 {
		return fmt.Errorf("dashboard has no index page")
	}
	moved := false
	if _, err = os.Stat(destination); err == nil {
		if err = os.RemoveAll(backup); err != nil {
			return err
		}
		if err = os.Rename(destination, backup); err != nil {
			return err
		}
		moved = true
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err = os.Rename(matches[0], destination); err != nil {
		if moved {
			err = errors.Join(err, os.Rename(backup, destination))
		}
		return err
	}
	return os.RemoveAll(backup)
}
