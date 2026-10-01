package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"log"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// 生成自签名 TLS 证书（供本地开发测试使用）
func generateTLSCert(dataDir string) (certFile, keyFile string, err error) {
	certFile = filepath.Join(dataDir, "cert.pem")
	keyFile = filepath.Join(dataDir, "key.pem")

	// 如果已存在，直接返回
	if _, err := os.Stat(certFile); err == nil {
		if _, err := os.Stat(keyFile); err == nil {
			return certFile, keyFile, nil
		}
	}

	log.Println("正在生成自签名 TLS 证书...")

	// 生成私钥
	privateKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return "", "", fmt.Errorf("生成私钥失败: %w", err)
	}

	// DNS 名称：包含 localhost 以及用户配置的域名
	dnsNames := []string{"localhost", "aurora.local"}
	if d := os.Getenv("SERVER_DOMAIN"); d != "" {
		d = strings.TrimSpace(d)
		if d != "" {
			dnsNames = append(dnsNames, d)
		}
	}
	if d := os.Getenv("SERVER_BASE_URL"); d != "" {
		// 去掉协议前缀和端口，提取纯域名
		d = strings.TrimSpace(d)
		d = strings.TrimPrefix(d, "http://")
		d = strings.TrimPrefix(d, "https://")
		d = strings.Split(d, ":")[0]
		if d != "" && d != "localhost" {
			found := false
			for _, existing := range dnsNames {
				if existing == d {
					found = true
					break
				}
			}
			if !found {
				dnsNames = append(dnsNames, d)
			}
		}
	}

	// 证书模板
	serialNumber, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	template := &x509.Certificate{
		SerialNumber: serialNumber,
		Subject: pkix.Name{
			Organization: []string{"Aurora Chat Development"},
			CommonName:   "Aurora Chat Server",
		},
		NotBefore:             time.Now(),
		NotAfter:              time.Now().Add(365 * 24 * time.Hour),
		KeyUsage:              x509.KeyUsageKeyEncipherment | x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IsCA:                  true,
		IPAddresses:           []net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("0.0.0.0")},
		DNSNames:              dnsNames,
	}

	// 自签名
	certDER, err := x509.CreateCertificate(rand.Reader, template, template, &privateKey.PublicKey, privateKey)
	if err != nil {
		return "", "", fmt.Errorf("创建证书失败: %w", err)
	}

	// 写入证书文件
	certOut, err := os.Create(certFile)
	if err != nil {
		return "", "", fmt.Errorf("创建证书文件失败: %w", err)
	}
	defer certOut.Close()
	if err := pem.Encode(certOut, &pem.Block{Type: "CERTIFICATE", Bytes: certDER}); err != nil {
		return "", "", err
	}

	// 写入私钥文件
	keyOut, err := os.Create(keyFile)
	if err != nil {
		return "", "", fmt.Errorf("创建私钥文件失败: %w", err)
	}
	defer keyOut.Close()
	privBytes, _ := x509.MarshalECPrivateKey(privateKey)
	if err := pem.Encode(keyOut, &pem.Block{Type: "EC PRIVATE KEY", Bytes: privBytes}); err != nil {
		return "", "", err
	}

	log.Printf("TLS 证书已生成: %s, %s", certFile, keyFile)
	return certFile, keyFile, nil
}
