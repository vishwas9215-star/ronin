package main

import (
	"strings"
	"testing"
)

func TestOnlyAllowListedJobsRun(t *testing.T) {
	bad := []Job{
		{ID: "a", Do: "shell", Device: "phone"},
		{ID: "a", Do: "uninstall", Device: "phone"},
		{ID: "a", Do: "info", Device: "tv"},
		{ID: "../x", Do: "info", Device: "phone"},
		{ID: "a", Do: "install", Device: "phone", APK: "..\\evil.apk"},
		{ID: "a", Do: "install", Device: "phone", APK: "x.exe"},
		{ID: "a", Do: "test", Device: "phone", Pkg: "com.android.settings", Class: "com.ronin.X"},
		{ID: "a", Do: "test", Device: "phone", Pkg: "com.ronin.browser", Class: "com.ronin.X; rm -rf /"},
		{ID: "a", Do: "launch", Device: "phone", Pkg: "com.whatsapp"},
		{ID: "a", Do: "uninstall", Device: "phone", Pkg: "com.ronin.browser"},
		{ID: "a", Do: "uninstall", Device: "phone", Pkg: "com.ronin.phone"},
	}
	for _, j := range bad {
		if j.Validate() == nil {
			t.Fatalf("should refuse %+v", j)
		}
	}
	good := []Job{
		{ID: "t1", Do: "info", Device: "tablet"},
		{ID: "t2", Do: "install", Device: "phone", APK: "Ronin-agent-test.apk"},
		{ID: "t3", Do: "test", Device: "tablet", Pkg: "com.ronin.browser", Class: "com.ronin.browser.AgentDeviceTest#hostilePages"},
	}
	for _, j := range good {
		if err := j.Validate(); err != nil {
			t.Fatalf("should allow %+v: %v", j, err)
		}
	}
}

func TestArgsAreFixed(t *testing.T) {
	a := Job{ID: "t3", Do: "test", Device: "tablet", Pkg: "com.ronin.browser", Class: "com.ronin.browser.AgentDeviceTest"}.Args("1.2.3.4:5555", `C:\q\apks`)
	got := strings.Join(a[0], " ")
	want := "-s 1.2.3.4:5555 shell am instrument -w -r -e class com.ronin.browser.AgentDeviceTest com.ronin.browser.test/androidx.test.runner.AndroidJUnitRunner"
	if got != want {
		t.Fatalf("got %q", got)
	}
	l := strings.Join(Job{ID: "l", Do: "log", Device: "phone"}.Args("s", "")[0], " ")
	if !strings.Contains(l, "RoninAgent:*") || !strings.Contains(l, "-s RoninAgent") {
		t.Fatalf("log must be Ronin's own lines only: %q", l)
	}
}

func TestParsing(t *testing.T) {
	d := ParseDevices("List of devices attached\nZY22 device\n192.168.1.5:5555 offline\n\n")
	if d["ZY22"] != "device" || d["192.168.1.5:5555"] != "offline" {
		t.Fatal(d)
	}
	c, p := ParseMdns("List of discovered mdns services\nadb-ZY22-abc\t_adb-tls-connect._tcp\t192.168.1.7:37211\nadb-OP-x\t_adb-tls-pairing._tcp\t192.168.1.9:40001\n")
	if len(c) != 1 || c[0] != "192.168.1.7:37211" || len(p) != 1 || p[0] != "192.168.1.9:40001" {
		t.Fatal(c, p)
	}
	if Kind("OPD2415\n", "tablet") != "tablet" || Kind("motorola edge", "phone") != "phone" || Kind("TCL", "tv,nosdcard") != "tv" {
		t.Fatal("kind")
	}
	if WlanIP("3: wlan0: <UP>\n    inet 192.168.29.44/24 brd 192.168.29.255 scope global wlan0") != "192.168.29.44" {
		t.Fatal("ip")
	}
	if s := trimInfo("  versionName=131.0.6778.39\n  userId=10123\n  com.whatsapp\n  level: 87\n  secretThing: x\n"); strings.Contains(s, "userId") || strings.Contains(s, "whatsapp") || !strings.Contains(s, "versionName=131") {
		t.Fatal(s)
	}
}
