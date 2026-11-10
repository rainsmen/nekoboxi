package libcore

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/oschwald/maxminddb-golang"
	geosites "github.com/sagernet/sing-box/common/geosite"
	"github.com/sagernet/sing-box/common/srs"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
)

// ValidateAsset checks the temporary file before Android replaces a working asset.
func ValidateAsset(path, name string) (err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			err = fmt.Errorf("invalid asset: %v", recovered)
		}
	}()
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return err
	}
	if info.Size() == 0 || info.Size() > 256*1024*1024 {
		return fmt.Errorf("asset must contain between 1 byte and 256 MiB")
	}
	switch strings.ToLower(filepath.Ext(name)) {
	case ".json":
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return readErr
		}
		var rules option.PlainRuleSetCompat
		return json.Unmarshal(content, &rules)
	case ".srs":
		_, err = srs.Read(file, false)
		return err
	case ".db":
		checkGeoIP := func() error {
			reader, openErr := maxminddb.Open(path)
			if openErr != nil {
				return openErr
			}
			defer reader.Close()
			return reader.Verify()
		}
		checkGeoSite := func() error {
			if _, seekErr := file.Seek(0, 0); seekErr != nil {
				return seekErr
			}
			reader, codes, openErr := geosites.NewReader(file)
			if openErr != nil {
				return openErr
			}
			if len(codes) == 0 {
				return fmt.Errorf("geosite database has no categories")
			}
			for _, code := range codes {
				if _, readErr := reader.Read(code); readErr != nil {
					return readErr
				}
			}
			return nil
		}
		switch strings.ToLower(name) {
		case "geoip.db":
			return checkGeoIP()
		case "geosite.db":
			return checkGeoSite()
		default:
			if checkGeoIP() == nil {
				return nil
			}
			return checkGeoSite()
		}
	default:
		return fmt.Errorf("unsupported asset format")
	}
}
