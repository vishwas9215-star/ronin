package downloadguard

import (
	"os"
	"runtime"
)

// MarkOfTheWeb attaches the Zone.Identifier stream to a downloaded file so
// Windows SmartScreen and Defender treat it as coming from the internet.
// On other systems it does nothing.
func MarkOfTheWeb(path, hostURL, referrerURL string) error {
	if runtime.GOOS != "windows" {
		return nil
	}
	return os.WriteFile(path+":Zone.Identifier", []byte(ZoneIdentifier(hostURL, referrerURL)), 0o644)
}
