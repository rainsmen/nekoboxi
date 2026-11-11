package bridgeprobe

import (
	"fmt"
	"runtime"
)

type Platform interface {
	OldWIFIState() string
	SafeWIFIState() (string, error)
	Owner() (int, error)
}

var retained Platform

func Run(p Platform, safe bool) (string, error) {
	retained = p
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	failures := 0
	for i := 0; i < 1000; i++ {
		if safe {
			_, e := p.SafeWIFIState()
			if e != nil {
				failures++
			}
		} else {
			p.OldWIFIState()
		}
		owner, e := p.Owner()
		if e != nil {
			return "", e
		}
		if owner != 123 {
			return "", fmt.Errorf("bad owner %d", owner)
		}
		if i%10 == 0 {
			runtime.GC()
		}
	}
	return fmt.Sprintf("completed=1000 caught=%d", failures), nil
}
