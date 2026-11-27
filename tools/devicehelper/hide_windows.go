//go:build windows

package main

import (
	"os/exec"
	"syscall"
)

// adb runs without flashing a black window each time.
func hideWindow(c *exec.Cmd) { c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true} }
