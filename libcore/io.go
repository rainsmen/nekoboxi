package libcore

import (
	"archive/zip"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/ulikunitz/xz"
)

func Unxz(archive string, path string) error {
	input, err := os.Open(archive)
	if err != nil {
		return err
	}
	reader, err := xz.NewReader(input)
	if err == nil {
		err = replaceAssetFile(path, func(output io.Writer) error { return copyAsset(output, reader) }, nil)
	}
	return errors.Join(err, input.Close())
}

func Unzip(archive string, path string) error {
	r, err := zip.OpenReader(archive)
	if err != nil {
		return err
	}
	defer r.Close()

	err = os.MkdirAll(path, os.ModePerm)
	if err != nil {
		return err
	}

	var expanded int64
	for _, file := range r.File {
		filePath := filepath.Join(path, file.Name)
		relative, err := filepath.Rel(path, filePath)
		if err != nil || filepath.IsAbs(file.Name) || relative == ".." || strings.HasPrefix(relative, ".."+string(os.PathSeparator)) ||
			file.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("invalid archive path %q", file.Name)
		}

		if file.FileInfo().IsDir() {
			err = os.MkdirAll(filePath, os.ModePerm)
			if err != nil {
				return err
			}
			continue
		}

		if err := os.MkdirAll(filepath.Dir(filePath), 0755); err != nil {
			return err
		}
		newFile, err := os.Create(filePath)
		if err != nil {
			return err
		}

		zipFile, err := file.Open()
		if err != nil {
			newFile.Close()
			return err
		}

		var errs error
		n, copyErr := io.Copy(newFile, io.LimitReader(zipFile, maxAssetBytes-expanded+1))
		expanded += n
		err = copyErr
		if expanded > maxAssetBytes {
			err = fmt.Errorf("archive exceeds 256 MiB")
		}
		errs = E.Errors(errs, err)
		errs = E.Errors(errs, common.Close(zipFile, newFile))
		if errs != nil {
			return errs
		}
	}

	return nil
}
