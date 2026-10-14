// Стенд жизненного цикла ядра так, как его ведёт Zen (vpn/BoxCore.kt):
// на каждый подъём — свой CommandServer и клиент журнала; закрытие —
// detach (клиент + сервер) сразу, closeService — в фоне; следующий подъём
// стартует, не дожидаясь, пока старое ядро закроется.
package main

import (
	"fmt"
	"net"
	"os/exec"
	"os"
	"strings"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/experimental/libbox"
)

type plat struct{}

func (plat) LocalDNSTransport() libbox.LocalDNSTransport        { return nil }
func (plat) UsePlatformAutoDetectInterfaceControl() bool         { return true }
func (plat) AutoDetectInterfaceControl(fd int32) error           { return nil }
func (plat) OpenTun(o libbox.TunOptions) (int32, error)          { return -1, fmt.Errorf("no tun") }
func (plat) UseProcFS() bool                                     { return false }
func (plat) FindConnectionOwner(int32, string, int32, string, int32) (*libbox.ConnectionOwner, error) {
	return nil, fmt.Errorf("no")
}
func (plat) StartDefaultInterfaceMonitor(l libbox.InterfaceUpdateListener) error {
	name := defIface()
	ifc, err := net.InterfaceByName(name)
	if err != nil {
		return err
	}
	l.UpdateDefaultInterface(name, int32(ifc.Index), false, false)
	return nil
}

func defIface() string {
	b, _ := os.ReadFile("/proc/net/route")
	for _, ln := range strings.Split(string(b), "\n")[1:] {
		f := strings.Fields(ln)
		if len(f) > 2 && f[1] == "00000000" {
			return f[0]
		}
	}
	return "lo"
}

type ifIter struct {
	l []*libbox.NetworkInterface
	i int
}

func (it *ifIter) HasNext() bool                 { return it.i < len(it.l) }
func (it *ifIter) Next() *libbox.NetworkInterface { it.i++; return it.l[it.i-1] }

type strIter struct {
	l []string
	i int
}

func (it *strIter) HasNext() bool  { return it.i < len(it.l) }
func (it *strIter) Next() string   { it.i++; return it.l[it.i-1] }
func (it *strIter) Len() int32     { return int32(len(it.l)) }
func (plat) CloseDefaultInterfaceMonitor(l libbox.InterfaceUpdateListener) error { return nil }
func (plat) GetInterfaces() (libbox.NetworkInterfaceIterator, error) {
	ifs, _ := net.Interfaces()
	var out []*libbox.NetworkInterface
	for _, f := range ifs {
		addrs, _ := f.Addrs()
		var a []string
		for _, x := range addrs {
			a = append(a, x.String())
		}
		out = append(out, &libbox.NetworkInterface{Index: int32(f.Index), MTU: int32(f.MTU), Name: f.Name,
			Addresses: &strIter{l: a}, Flags: int32(f.Flags), DNSServer: &strIter{}})
	}
	return &ifIter{l: out}, nil
}
func (plat) UnderNetworkExtension() bool                                         { return false }
func (plat) IncludeAllNetworks() bool                                            { return false }
func (plat) ReadWIFIState() *libbox.WIFIState                                    { return libbox.NewWIFIState("", "") }
func (plat) ClearDNSCache()                                                      {}
func (plat) SendNotification(n *libbox.Notification) error                       { return nil }
func (plat) CancelNotification(string, int32) error                              { return nil }
func (plat) StartNeighborMonitor(l libbox.NeighborUpdateListener) error          { return nil }
func (plat) CloseNeighborMonitor(l libbox.NeighborUpdateListener) error          { return nil }
func (plat) RegisterMyInterface(string)                                          {}
func (plat) UsePlatformShell() bool                                              { return false }
func (plat) CheckPlatformShell() error                                           { return fmt.Errorf("no") }
func (plat) OpenShellSession(*libbox.PlatformUser, string, libbox.StringIterator, string, int32, int32) (libbox.ShellSession, error) {
	return nil, fmt.Errorf("no")
}
func (plat) LookupUser(string) (*libbox.PlatformUser, error)                  { return nil, fmt.Errorf("no") }
func (plat) LookupSFTPServer() (string, error)                                { return "", fmt.Errorf("no") }
func (plat) ReadSystemSSHHostKey() (string, error)                            { return "", fmt.Errorf("no") }
func (plat) TailscaleHostname() string                                        { return "" }
func (plat) UsePlatformBridge() bool                                          { return false }
func (plat) CreateBridge(*libbox.BridgeOptions) (libbox.BridgeSession, error) { return nil, fmt.Errorf("no") }

type srvh struct{}

func (srvh) ServiceStop() error                                        { return nil }
func (srvh) ServiceReload() error                                      { return nil }
func (srvh) GetSystemProxyStatus() (*libbox.SystemProxyStatus, error)  { return &libbox.SystemProxyStatus{}, nil }
func (srvh) SetSystemProxyEnabled(bool) error                          { return nil }
func (srvh) TriggerNativeCrash() error                                 { return nil }
func (srvh) WriteDebugMessage(string)                                  {}
func (srvh) ConnectSSHAgent() (int32, error)                           { return 0, fmt.Errorf("no") }

type logh struct {
	n     *atomic.Int64
	first *atomic.Value
	disc  *atomic.Value
}

func (logh) Connected()                                                   {}
func (h logh) Disconnected(m string)                                      { h.disc.Store(m) }
func (logh) SetDefaultLogLevel(int32)                                     {}
func (logh) ClearLogs()                                                   {}
func (h logh) WriteLogs(it libbox.LogIterator) {
	for it.HasNext() {
		m := it.Next().Message
		if os.Getenv("ZL_ALL") != "" || strings.Contains(m, "ERROR") || strings.Contains(m, "WARN") {
			fmt.Printf("   журнал: %.200s\n", m)
		}
		if h.first.Load() == nil {
			h.first.Store(m)
		}
		h.n.Add(1)
	}
}
func (logh) WriteStatus(*libbox.StatusMessage)                            {}
func (logh) WriteGroups(libbox.OutboundGroupIterator)                     {}
func (logh) WriteOutbounds(libbox.OutboundGroupItemIterator)              {}
func (logh) InitializeClashMode(libbox.StringIterator, string)            {}
func (logh) UpdateClashMode(string)                                       {}
func (logh) WriteConnectionEvents(*libbox.ConnectionEvents)               {}

type core struct {
	s *libbox.CommandServer
	c *libbox.CommandClient
}

func main() {
	cfgTpl, _ := os.ReadFile(os.Args[1])
	base := os.Args[2]
	os.MkdirAll(base, 0o755)
	wd, _ := os.Getwd()
	must(libbox.Setup(&libbox.SetupOptions{BasePath: base, WorkingPath: wd + "/" + base + "/work", TempPath: wd + "/" + base + "/tmp",
		FixAndroidStack: true, LogMaxLines: 300, CrashReportSource: "zen"}))
	var prev *core
	for i := 0; i < loops(); i++ {
		port := 39200 + i
		cfg := strings.ReplaceAll(string(cfgTpl), "39123", fmt.Sprint(port))
		if os.Getenv("ZL_UNIQUE") != "" {
			cfg = strings.Replace(cfg, `"experimental": {}`, fmt.Sprintf(`"experimental":{"cache_file":{"enabled":true,"path":"%s/%s/work/cache-%d.db"}}`, wdir(), base, time.Now().UnixNano()), 1)
		}
		var n atomic.Int64
		var first atomic.Value
		var disc atomic.Value
		t0 := time.Now()
		// старое — как closeBox: detach сразу, closeService в фоне
		if prev != nil && os.Getenv("ZL_HANG") != "" {
			// прошлое ядро «зависло»: не закрываем вовсе, держит cache.db
		} else if prev != nil {
			p := prev
			p.c.Disconnect()
			p.s.Close()
			go func() { t := time.Now(); p.s.CloseService(); fmt.Printf("   [старое ядро закрылось за %v]\n", time.Since(t).Round(time.Millisecond)) }()
		}
		s, err := libbox.NewCommandServer(srvh{}, plat{})
		must(err)
		must(s.Start())
		opts := &libbox.CommandClientOptions{}
		opts.AddCommand(libbox.CommandLog)
		c := libbox.NewCommandClient(logh{&n, &first, &disc}, opts)
		serr := s.StartOrReloadService(cfg, &libbox.OverrideOptions{})
		cerr := c.Connect()
		up := time.Since(t0)
		code := 0
		if out, err := exec.Command("curl", "--noproxy", "", "-s", "-o", "/dev/null", "-m", "8", "-w", "%{http_code}",
			"-x", fmt.Sprintf("socks5h://127.0.0.1:%d", port), "https://cp.cloudflare.com/generate_204").Output(); err == nil {
			fmt.Sscan(string(out), &code)
		}
		n1 := n.Load()
		time.Sleep(2 * time.Second)
		f, _ := first.Load().(string)
		d, _ := disc.Load().(string)
		fmt.Printf("#%d порт %d: подъём %v, connect=%v start=%v, ответ %d, строк журнала %d→%d, обрыв журнала: %q, первая: %.70s\n",
			i, port, up.Round(time.Millisecond), cerr, serr, code, n1, n.Load(), d, f)
		prev = &core{s, c}
	}
	prev.c.Disconnect()
	prev.s.Close()
	prev.s.CloseService()
	time.Sleep(500 * time.Millisecond)
	fmt.Println("готово")
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func loops() int {
	if os.Getenv("ZL_ALL") != "" {
		return 1
	}
	return 6
}

func wdir() string { w, _ := os.Getwd(); return w }
