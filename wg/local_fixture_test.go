package linqavowg

// Local fixture destinations are deliberately private addresses on this host.
// Production isolation is tested separately; these helpers never ship in AARs.
func startLocalFixture(config string) (*Endpoint, error) {
	endpoint, err := Start(config)
	if err == nil {
		endpoint.tun.allowPrivateForTest = true
	}
	return endpoint, err
}
