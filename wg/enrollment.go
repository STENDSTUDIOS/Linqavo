package linqavowg

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"math/big"
	"time"

	"golang.org/x/crypto/curve25519"
)

// NewEnrollmentIdentity returns an ephemeral platform-TLS credential in memory.
// The private PKCS8 key MUST never be logged or persisted by the caller.
func NewEnrollmentIdentity() (string, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return "", err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return "", err
	}
	now := time.Now()
	template := &x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "Linqavo session"},
		NotBefore: now.Add(-time.Minute), NotAfter: now.Add(24 * time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		return "", err
	}
	private, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return "", err
	}
	fingerprint := sha256.Sum256(der)
	out, err := json.Marshal(struct {
		Certificate string `json:"certificate"`
		PrivateKey  string `json:"privateKey"`
		Fingerprint string `json:"fingerprint"`
	}{
		base64.StdEncoding.EncodeToString(der), base64.StdEncoding.EncodeToString(private), hex.EncodeToString(fingerprint[:])})
	return string(out), err
}

// NewClientIdentity generates a client-owned key using the existing X25519 implementation.
func NewClientIdentity() (string, error) {
	private := make([]byte, 32)
	if _, err := rand.Read(private); err != nil {
		return "", err
	}
	private[0] &= 248
	private[31] &= 127
	private[31] |= 64
	public, err := curve25519.X25519(private, curve25519.Basepoint)
	if err != nil {
		return "", err
	}
	out, err := json.Marshal(struct {
		PrivateKey string `json:"privateKey"`
		PublicKey  string `json:"publicKey"`
	}{
		base64.StdEncoding.EncodeToString(private), base64.StdEncoding.EncodeToString(public)})
	clear(private)
	return string(out), err
}
