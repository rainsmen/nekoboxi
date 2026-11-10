package libcore

import (
	"errors"
	"os"
)

var errImportedFile = errors.New("import-field:local_file")

// Subscription validation must not open paths supplied by an untrusted node.
// Inline certificates/keys remain valid; explicit local paths belong in a full,
// user-managed configuration. System certificate loading is independent of this manager.
type importFiles struct{}

func (importFiles) BasePath(name string) string                         { return name }
func (importFiles) TempPath() string                                    { return "" }
func (importFiles) OpenFile(string, int, os.FileMode) (*os.File, error) { return nil, errImportedFile }
func (importFiles) Create(string) (*os.File, error)                     { return nil, errImportedFile }
func (importFiles) CreateTemp(string) (*os.File, error)                 { return nil, errImportedFile }
func (importFiles) Chown(string) error                                  { return errImportedFile }
func (importFiles) Mkdir(string, os.FileMode) error                     { return errImportedFile }
func (importFiles) MkdirAll(string, os.FileMode) error                  { return errImportedFile }
func (importFiles) Remove(string) error                                 { return errImportedFile }
func (importFiles) RemoveAll(string) error                              { return errImportedFile }
func (importFiles) Rename(string, string) error                         { return errImportedFile }
