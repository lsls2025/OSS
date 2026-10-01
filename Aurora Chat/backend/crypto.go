package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"io"
)

// DeriveSessionKey ECDH 共享密钥 → AES-256 会话密钥
func DeriveSessionKey(sharedSecret []byte) []byte {
	h := sha256.Sum256(append([]byte("aurora_e2e_v2"), sharedSecret...))
	return h[:]
}

// EncryptMessageAES AES-GCM 加密（内部使用，密钥由客户端 ECDH 生成后传入）
func EncryptMessageAES(plaintext string, aesKey []byte) (string, error) {
	block, err := aes.NewCipher(aesKey)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return "", err
	}
	ciphertext := gcm.Seal(nonce, nonce, []byte(plaintext), nil)
	return base64.StdEncoding.EncodeToString(ciphertext), nil
}

// DecryptMessageAES AES-GCM 解密
func DecryptMessageAES(encryptedBase64 string, aesKey []byte) (string, error) {
	ciphertext, err := base64.StdEncoding.DecodeString(encryptedBase64)
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(aesKey)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	nonceSize := gcm.NonceSize()
	if len(ciphertext) < nonceSize {
		return "", fmt.Errorf("密文太短")
	}
	nonce, ciphertext := ciphertext[:nonceSize], ciphertext[nonceSize:]
	plaintext, err := gcm.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return "", err
	}
	return string(plaintext), nil
}

// GenerateEncryptedPayload 生成客户端格式的加密负载
// 客户端发送: base64(ephemeral_pubkey_x|ephemeral_pubkey_y|nonce|ciphertext)
func GenerateEncryptedPayload(ephemeralPubX, ephemeralPubY []byte, nonce, ciphertext []byte) string {
	data := append(ephemeralPubX, ephemeralPubY...)
	data = append(data, nonce...)
	data = append(data, ciphertext...)
	return base64.StdEncoding.EncodeToString(data)
}

// ParseEncryptedPayload 解析客户端加密负载
func ParseEncryptedPayload(payload string) (ephemeralPubX, ephemeralPubY, nonce, ciphertext []byte, err error) {
	data, err := base64.StdEncoding.DecodeString(payload)
	if err != nil {
		return nil, nil, nil, nil, err
	}
	if len(data) < 64+12 {
		return nil, nil, nil, nil, fmt.Errorf("负载太短")
	}
	// 前 32 字节为 X，32-64 字节为 Y，之后 12 字节 nonce，剩余为密文
	return data[:32], data[32:64], data[64:76], data[76:], nil
}
