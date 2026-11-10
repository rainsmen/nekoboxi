package libcore

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"

	geosites "github.com/sagernet/sing-box/common/geosite"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/ulikunitz/xz"
)

func fixtureGeoSite(t *testing.T, domain string) []byte {
	t.Helper()
	var data bytes.Buffer
	if err := geosites.Write(&data, map[string][]geosites.Item{"test": {{Type: 0, Value: domain}}}); err != nil {
		t.Fatal(err)
	}
	return data.Bytes()
}
func fixtureXZ(t *testing.T, data []byte) []byte {
	t.Helper()
	var compressed bytes.Buffer
	writer, err := xz.NewWriter(&compressed)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = writer.Write(data); err != nil {
		t.Fatal(err)
	}
	if err = writer.Close(); err != nil {
		t.Fatal(err)
	}
	return compressed.Bytes()
}
func fixtureInstaller(t *testing.T, files map[string][]byte) assetInstaller {
	t.Helper()
	return assetInstaller{internalDir: t.TempDir(), externalDir: t.TempDir(), open: func(name string) (io.ReadCloser, error) {
		data, ok := files[name]
		if !ok {
			return nil, os.ErrNotExist
		}
		return io.NopCloser(bytes.NewReader(data)), nil
	}}
}
func mustWrite(t *testing.T, path string, data []byte) {
	t.Helper()
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
}
func assertBytes(t *testing.T, path string, expected []byte) {
	t.Helper()
	actual, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(expected, actual) {
		t.Fatalf("unexpected contents in %s", filepath.Base(path))
	}
}

func TestBundledDatabaseFailurePreservesFileAndVersion(t *testing.T) {
	for _, failure := range []string{"missing archive", "corrupt archive", "invalid database"} {
		t.Run(failure, func(t *testing.T) {
			files := map[string][]byte{"sing-box/geosite.version.txt": []byte("2")}
			switch failure {
			case "corrupt archive":
				files["sing-box/geosite.db.xz"] = []byte("broken")
			case "invalid database":
				files["sing-box/geosite.db.xz"] = fixtureXZ(t, []byte("not a database"))
			}
			installer := fixtureInstaller(t, files)
			old := fixtureGeoSite(t, "old.example")
			destination := filepath.Join(installer.externalDir, geositeDat)
			version := filepath.Join(installer.externalDir, geositeVersion)
			mustWrite(t, destination, old)
			mustWrite(t, version, []byte("1"))
			if err := installer.install(geositeDat, true); err == nil {
				t.Fatal("accepted failed install")
			}
			assertBytes(t, destination, old)
			assertBytes(t, version, []byte("1"))
			entries, _ := os.ReadDir(installer.externalDir)
			for _, entry := range entries {
				if entry.Name() != ".assets.lock" && entry.Name() != geositeDat && entry.Name() != geositeVersion {
					t.Fatalf("temporary files leaked: %v", entries)
				}
			}
		})
	}
}

func TestBundledAssetRepairsDamagedCurrentVersionAndRespectsCustom(t *testing.T) {
	current := fixtureGeoSite(t, "current.example")
	installer := fixtureInstaller(t, map[string][]byte{
		"sing-box/geosite.version.txt": []byte("2"), "sing-box/geosite.db.xz": fixtureXZ(t, current),
	})
	destination, version := filepath.Join(installer.externalDir, geositeDat), filepath.Join(installer.externalDir, geositeVersion)
	mustWrite(t, destination, []byte("partial database"))
	mustWrite(t, version, []byte("2"))
	if err := installer.install(geositeDat, true); err != nil {
		t.Fatal(err)
	}
	assertBytes(t, destination, current)
	custom := fixtureGeoSite(t, "custom.example")
	mustWrite(t, destination, custom)
	mustWrite(t, version, []byte("Custom"))
	if err := installer.install(geositeDat, true); err != nil {
		t.Fatal(err)
	}
	assertBytes(t, destination, custom)
	assertBytes(t, version, []byte("Custom"))
}

func TestBundledAssetVersionFailureIsReportedAfterValidInstall(t *testing.T) {
	current := fixtureGeoSite(t, "new.example")
	installer := fixtureInstaller(t, map[string][]byte{
		"sing-box/geosite.version.txt": []byte("2"), "sing-box/geosite.db.xz": fixtureXZ(t, current),
	})
	version := filepath.Join(installer.externalDir, geositeVersion)
	if err := os.Mkdir(version, 0700); err != nil {
		t.Fatal(err)
	}
	if err := installer.install(geositeDat, true); err == nil || !strings.Contains(err.Error(), "version metadata") {
		t.Fatalf("missing metadata failure: %v", err)
	}
	assertBytes(t, filepath.Join(installer.externalDir, geositeDat), current)
	if err := os.Remove(version); err != nil {
		t.Fatal(err)
	}
	if err := installer.install(geositeDat, true); err != nil {
		t.Fatal(err)
	}
	assertBytes(t, version, []byte("2"))
}

func TestBundledAssetPartialWriteNeverTruncatesExistingFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "existing.db")
	mustWrite(t, path, []byte("existing"))
	err := replaceAssetFile(path, func(output io.Writer) error {
		_, err := output.Write([]byte("partial"))
		return errors.Join(err, syscall.ENOSPC)
	}, nil)
	if !errors.Is(err, syscall.ENOSPC) {
		t.Fatal(err)
	}
	assertBytes(t, path, []byte("existing"))
}

func fixtureZIP(t *testing.T, path string) []byte {
	t.Helper()
	var data bytes.Buffer
	writer := zip.NewWriter(&data)
	file, err := writer.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = file.Write([]byte("new dashboard")); err != nil {
		t.Fatal(err)
	}
	if err = writer.Close(); err != nil {
		t.Fatal(err)
	}
	return data.Bytes()
}
func TestBundledDashboardValidatesBeforeReplacingDirectory(t *testing.T) {
	files := map[string][]byte{"yacd.version.txt": []byte("2"), "yacd.zip": fixtureZIP(t, "../escape")}
	installer := fixtureInstaller(t, files)
	oldDir := filepath.Join(installer.internalDir, yacdDstFolder)
	if err := os.Mkdir(oldDir, 0700); err != nil {
		t.Fatal(err)
	}
	mustWrite(t, filepath.Join(oldDir, "index.html"), []byte("old dashboard"))
	mustWrite(t, filepath.Join(installer.internalDir, yacdVersion), []byte("1"))
	if err := installer.install(yacdDstFolder, true); err == nil {
		t.Fatal("accepted traversal")
	}
	assertBytes(t, filepath.Join(oldDir, "index.html"), []byte("old dashboard"))
	assertBytes(t, filepath.Join(installer.internalDir, yacdVersion), []byte("1"))
	files["yacd.zip"] = fixtureZIP(t, "Yacd-build/index.html")
	if err := installer.install(yacdDstFolder, true); err != nil {
		t.Fatal(err)
	}
	assertBytes(t, filepath.Join(oldDir, "index.html"), []byte("new dashboard"))
	assertBytes(t, filepath.Join(installer.internalDir, yacdVersion), []byte("2"))
}

func TestDashboardRecoversInterruptedSwapBeforeReadingNewArchive(t *testing.T) {
	for _, broken := range []bool{false, true} {
		t.Run(fmt.Sprint("broken-", broken), func(t *testing.T) {
			files := map[string][]byte{"yacd.version.txt": []byte("2")}
			if broken {
				files["yacd.zip"] = []byte("broken archive")
			}
			installer := fixtureInstaller(t, files)
			destination := filepath.Join(installer.internalDir, yacdDstFolder)
			if err := os.Mkdir(destination+".previous", 0700); err != nil {
				t.Fatal(err)
			}
			mustWrite(t, filepath.Join(destination+".previous", "index.html"), []byte("recoverable dashboard"))
			mustWrite(t, filepath.Join(installer.internalDir, yacdVersion), []byte("1"))
			if err := installer.install(yacdDstFolder, true); err == nil {
				t.Fatal("accepted invalid archive")
			}
			assertBytes(t, filepath.Join(destination, "index.html"), []byte("recoverable dashboard"))
			assertBytes(t, filepath.Join(installer.internalDir, yacdVersion), []byte("1"))
		})
	}
}

func TestDashboardRecoveryDoesNotDependOnBundledVersion(t *testing.T) {
	for _, contents := range [][]byte{nil, {}, bytes.Repeat([]byte("x"), 4097)} {
		files := map[string][]byte{}
		if contents != nil {
			files[yacdVersion] = contents
		}
		installer := fixtureInstaller(t, files)
		destination := filepath.Join(installer.internalDir, yacdDstFolder)
		if err := os.Mkdir(destination+".previous", 0700); err != nil {
			t.Fatal(err)
		}
		mustWrite(t, filepath.Join(destination+".previous", "index.html"), []byte("old dashboard"))
		if err := installer.install(yacdDstFolder, true); err == nil {
			t.Fatal("accepted missing version")
		}
		assertBytes(t, filepath.Join(destination, "index.html"), []byte("old dashboard"))
	}
}

func TestBundledInstallerRecoversCustomVersionJournalBeforeAutomaticUpdate(t *testing.T) {
	files := map[string][]byte{apkAssetPrefixSingBox + geositeVersion: []byte("2")}
	installer := fixtureInstaller(t, files)
	destination := filepath.Join(installer.externalDir, geositeDat)
	version := filepath.Join(installer.externalDir, geositeVersion)
	data := fixtureGeoSite(t, "custom.example")
	mustWrite(t, destination, data)
	mustWrite(t, version, []byte("1"))
	mustWrite(t, version+".pending", []byte(fmt.Sprintf("%x\nCustom", sha256.Sum256(data))))
	if err := installer.install(geositeDat, true); err != nil {
		t.Fatal(err)
	}
	assertBytes(t, destination, data)
	assertBytes(t, version, []byte("Custom"))
	if _, err := os.Stat(version + ".pending"); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("journal not cleared", err)
	}
}

func assertNoOpenAssetFD(t *testing.T, directory string) {
	t.Helper()
	entries, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		t.Skip("fd observation unavailable on this host")
	}
	for _, entry := range entries {
		target, err := os.Readlink(filepath.Join("/proc/self/fd", entry.Name()))
		if err == nil && strings.HasPrefix(target, directory+string(os.PathSeparator)) {
			t.Fatalf("asset fd still open: %s", target)
		}
	}
}
func TestUnxzClosesFilesAndKeepsOriginalOnFailure(t *testing.T) {
	dir := t.TempDir()
	archive, output := filepath.Join(dir, "input.xz"), filepath.Join(dir, "output")
	mustWrite(t, archive, fixtureXZ(t, []byte("complete")))
	if err := Unxz(archive, output); err != nil {
		t.Fatal(err)
	}
	assertBytes(t, output, []byte("complete"))
	assertNoOpenAssetFD(t, dir)
	mustWrite(t, archive, []byte("broken"))
	if err := Unxz(archive, output); err == nil {
		t.Fatal("accepted broken xz")
	}
	assertBytes(t, output, []byte("complete"))
	assertNoOpenAssetFD(t, dir)
}
func TestGeositeLoadClosesDatabaseOnSuccessAndFailure(t *testing.T) {
	previous := externalAssetsPath
	externalAssetsPath = t.TempDir()
	defer func() { externalAssetsPath = previous }()
	mustWrite(t, filepath.Join(externalAssetsPath, geositeDat), fixtureGeoSite(t, "example.org"))
	if _, err := nekoutils.GetGeoSiteHeadlessRules("test"); err != nil {
		t.Fatal(err)
	}
	assertNoOpenAssetFD(t, externalAssetsPath)
	if _, err := nekoutils.GetGeoSiteHeadlessRules("missing"); err == nil {
		t.Fatal("expected missing category")
	}
	assertNoOpenAssetFD(t, externalAssetsPath)
}
