package linqavowg

import (
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"golang.org/x/crypto/curve25519"
	"testing"
)

func TestEnrollmentIdentityUsesStandardCertificateAndIndependentClientKeys(t *testing.T) {
	raw, err := NewEnrollmentIdentity()
	if err != nil {
		t.Fatal(err)
	}
	var identity struct{ Certificate, PrivateKey, Fingerprint string }
	if err = json.Unmarshal([]byte(raw), &identity); err != nil {
		t.Fatal(err)
	}
	der, _ := base64.StdEncoding.DecodeString(identity.Certificate)
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, _ := base64.StdEncoding.DecodeString(identity.PrivateKey)
	key, err := x509.ParsePKCS8PrivateKey(keyDER)
	if err != nil {
		t.Fatal(err)
	}
	if !key.(*ecdsa.PrivateKey).PublicKey.Equal(cert.PublicKey) {
		t.Fatal("certificate/key mismatch")
	}
	hash := sha256.Sum256(der)
	if hex.EncodeToString(hash[:]) != identity.Fingerprint {
		t.Fatal("fingerprint mismatch")
	}
	previous := ""
	for range 3 {
		raw, err := NewClientIdentity()
		if err != nil {
			t.Fatal(err)
		}
		var pair struct{ PrivateKey, PublicKey string }
		json.Unmarshal([]byte(raw), &pair)
		private, _ := base64.StdEncoding.DecodeString(pair.PrivateKey)
		public, err := curve25519.X25519(private, curve25519.Basepoint)
		if err != nil {
			t.Fatal(err)
		}
		if base64.StdEncoding.EncodeToString(public) != pair.PublicKey || pair.PublicKey == previous {
			t.Fatal("invalid or reused client key")
		}
		previous = pair.PublicKey
	}
}
