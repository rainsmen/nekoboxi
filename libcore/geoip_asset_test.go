package libcore

import (
	"archive/zip"
	"bytes"
	"github.com/oschwald/maxminddb-golang"
	"github.com/sagernet/sing/common"
	"io"
	"os"
	"path/filepath"
	"testing"
)

// Minimal valid sing-geoip MMDB: one IPv4 search node, two references to "cn",
// and the empty description emitted by the real bundled database producer.
func fixtureGeoIP() []byte {
	text := func(s string) []byte { return append([]byte{byte(0x40 + len(s))}, []byte(s)...) }
	data := []byte{0, 0, 17, 0, 0, 17} // node count 1 + 16-byte separator + data offset 0
	data = append(data, make([]byte, 16)...)
	data = append(data, text("cn")...)
	data = append(data, []byte("\xab\xcd\xefMaxMind.com")...)
	data = append(data, 0xe7) // seven metadata entries
	for _, entry := range []struct {
		key   string
		value []byte
	}{
		{"database_type", text("sing-geoip")}, {"description", []byte{0xe0}},
		{"binary_format_major_version", []byte{0xa1, 2}}, {"binary_format_minor_version", []byte{0xa1, 0}},
		{"ip_version", []byte{0xa1, 4}}, {"node_count", []byte{0xc1, 1}}, {"record_size", []byte{0xa1, 24}},
	} {
		data = append(data, text(entry.key)...)
		data = append(data, entry.value...)
	}
	return data
}

func TestSingGeoIPEmptyDescriptionInstallsAndRejectsCorruptTree(t *testing.T) {
	data := fixtureGeoIP()
	r, err := maxminddb.FromBytes(data)
	if err != nil {
		t.Fatal(err)
	}
	if len(r.Metadata.Description) != 0 || r.Verify() == nil {
		t.Fatal("fixture must exercise strict verifier incompatibility")
	}
	r.Close()
	installer := fixtureInstaller(t, map[string][]byte{
		"sing-box/geoip.db.xz": fixtureXZ(t, data), "sing-box/geoip.version.txt": []byte("1"),
	})
	if err = installer.install(geoipDat, true); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(installer.externalDir, geoipDat)
	assertBytes(t, path, data)
	database := new(geoip)
	if err = database.Open(path); err != nil {
		t.Fatal(err)
	}
	defer database.geoipReader.Close()
	if _, err = database.Rules("cn"); err != nil {
		t.Fatal(err)
	}
	broken := bytes.Clone(data)
	broken[0], broken[1], broken[2] = 0xff, 0xff, 0xff
	mustWrite(t, path, broken)
	if err = ValidateAsset(path, geoipDat); err == nil {
		t.Fatal("accepted corrupt search tree with empty description")
	}
}

func TestGeoIPCompatibilityDoesNotAcceptWrongMetadataOrRecords(t *testing.T) {
	path := filepath.Join(t.TempDir(), "geoip.db")
	data := fixtureGeoIP()
	wrongType := bytes.Replace(data, []byte("sing-geoip"), []byte("other-type"), 1)
	emptyCode := bytes.Clone(data)
	emptyCode[23], emptyCode[24] = ' ', ' '
	wrongRecord := bytes.Clone(data)
	wrongRecord[22] = 0xa2 // integer instead of country string
	for _, broken := range [][]byte{wrongType, emptyCode, wrongRecord} {
		mustWrite(t, path, broken)
		if err := ValidateAsset(path, geoipDat); err == nil {
			t.Fatal("accepted incompatible GeoIP")
		}
	}
}

// This gate reads the actual built APK, using the same installer and readers as
// Android. It must run after assembly, before the APK can be uploaded.
func TestBundledAPKDatabasesInstallAndLoad(t *testing.T) {
	pattern := os.Getenv("THBOX_APK")
	if pattern == "" {
		t.Skip("APK integration step provides THBOX_APK")
	}
	matches, err := filepath.Glob(pattern)
	if err != nil || len(matches) != 1 {
		t.Fatalf("expected one APK: %v %v", matches, err)
	}
	apk, err := zip.OpenReader(matches[0])
	if err != nil {
		t.Fatal(err)
	}
	defer apk.Close()
	installer := assetInstaller{internalDir: t.TempDir(), externalDir: t.TempDir(), open: func(name string) (io.ReadCloser, error) {
		for _, file := range apk.File {
			if file.Name == "assets/"+name {
				return file.Open()
			}
		}
		return nil, os.ErrNotExist
	}}
	for _, name := range []string{geoipDat, geositeDat} {
		if err = installer.install(name, true); err != nil {
			t.Fatal(err)
		}
		if err = ValidateAsset(filepath.Join(installer.externalDir, name), name); err != nil {
			t.Fatal(err)
		}
	}
	ip := new(geoip)
	if err = ip.Open(filepath.Join(installer.externalDir, geoipDat)); err != nil {
		t.Fatal(err)
	}
	defer ip.geoipReader.Close()
	if _, err = ip.Rules("cn"); err != nil {
		t.Fatal(err)
	}
	site := new(geosite)
	if err = site.Open(filepath.Join(installer.externalDir, geositeDat)); err != nil {
		t.Fatal(err)
	}
	defer common.Close(site.geositeReader)
	for _, code := range []string{"cn", "private", "category-ads-all"} {
		if _, err = site.Rules(code); err != nil {
			t.Fatal(err)
		}
	}
}
