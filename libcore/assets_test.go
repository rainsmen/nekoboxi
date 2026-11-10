package libcore

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"

	geosites "github.com/sagernet/sing-box/common/geosite"
	"github.com/sagernet/sing-box/common/srs"
	"github.com/sagernet/sing-box/option"
)

func TestAssetValidation(t *testing.T) {
	dir := t.TempDir()
	testFile := filepath.Join(dir, "import.tmp")
	for _, name := range []string{"geoip.db", "geosite.db", "rules.json", "rules.srs"} {
		for _, content := range [][]byte{nil, []byte("not an asset")} {
			if err := os.WriteFile(testFile, content, 0600); err != nil {
				t.Fatal(err)
			}
			if err := ValidateAsset(testFile, name); err == nil {
				t.Fatalf("accepted invalid %s", name)
			}
		}
	}
	source := []byte(`{"version":3,"rules":[{"domain_suffix":["example.com"]}]}`)
	if err := os.WriteFile(testFile, source, 0600); err != nil {
		t.Fatal(err)
	}
	if err := ValidateAsset(testFile, "rules.JSON"); err != nil {
		t.Fatal(err)
	}
	var binary bytes.Buffer
	if err := srs.Write(&binary, option.PlainRuleSet{Rules: []option.HeadlessRule{}}, 3); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(testFile, binary.Bytes(), 0600); err != nil {
		t.Fatal(err)
	}
	if err := ValidateAsset(testFile, "rules.srs"); err != nil {
		t.Fatal(err)
	}
	var database bytes.Buffer
	if err := geosites.Write(&database, map[string][]geosites.Item{"test": {{Type: 0, Value: "example.com"}}}); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(testFile, database.Bytes(), 0600); err != nil {
		t.Fatal(err)
	}
	if err := ValidateAsset(testFile, "geosite.db"); err != nil {
		t.Fatal(err)
	}
	if err := ValidateAsset(testFile, "geoip.db"); err == nil {
		t.Fatal("accepted a geosite database as geoip")
	}
}

func TestAssetValidationRejectsRuntimeRuleErrors(t *testing.T) {
	path := filepath.Join(t.TempDir(), "import.tmp")
	for _, source := range []string{
		`{"version":3,"rules":[{"domain_regex":["["]}]}`,
		`{"version":3,"rules":[{"port_range":["bad"]}]}`,
		`{"version":3,"rules":[{"type":"logical","mode":"or","rules":[{"domain_regex":["["]}]}]}`,
		`{"version":3,"rules":[{"ip_cidr":["invalid"]}]}`,
	} {
		if err := os.WriteFile(path, []byte(source), 0600); err != nil {
			t.Fatal(err)
		}
		if err := ValidateAsset(path, "rules.json"); err == nil {
			t.Fatalf("accepted runtime-invalid rules: %s", source)
		}
	}
}
