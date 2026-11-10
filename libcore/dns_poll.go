//go:build linux || android

package libcore

import (
	"context"
	"errors"
	"golang.org/x/sys/unix"
	"time"
)

// Keep ownership of the fd on this goroutine. Closing it from a cancellation
// goroutine can race descriptor reuse or android_res_nresult's own close.
func waitDNSResponse(ctx context.Context, fd int) error {
	fds := []unix.PollFd{{Fd: int32(fd), Events: unix.POLLIN | unix.POLLERR}}
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		timeout := 50
		if deadline, ok := ctx.Deadline(); ok {
			remaining := int(time.Until(deadline).Milliseconds())
			if remaining < timeout {
				timeout = max(1, remaining)
			}
		}
		n, err := unix.Poll(fds, timeout)
		if errors.Is(err, unix.EINTR) {
			continue
		}
		if err != nil {
			return err
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if n > 0 {
			if fds[0].Revents&unix.POLLNVAL != 0 {
				return unix.EBADF
			}
			return nil
		}
	}
}
