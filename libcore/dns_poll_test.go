//go:build linux || android

package libcore

import (
	"context"
	"errors"
	"golang.org/x/sys/unix"
	"testing"
	"time"
)

func TestDNSPollHonorsCancellation(t *testing.T) {
	var fds [2]int
	if err := unix.Pipe(fds[:]); err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fds[0])
	defer unix.Close(fds[1])
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	start := time.Now()
	if err := waitDNSResponse(ctx, fds[0]); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal(err)
	}
	if time.Since(start) > time.Second {
		t.Fatal("cancellation waited for the native 5s timeout")
	}
	if _, err := unix.Write(fds[1], []byte{1}); err != nil {
		t.Fatal(err)
	}
	if err := waitDNSResponse(context.Background(), fds[0]); err != nil {
		t.Fatal(err)
	}
}
