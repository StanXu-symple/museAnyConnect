package main

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"sync"

	"github.com/gotd/td/session"
)

const sessionStringPrefix = "tg_sess_v1:"

// StringSessionStorage 将 gotd/td 的原始会话数据编解码为单行字符串（无状态持久化，无需挂载磁盘文件）。
type StringSessionStorage struct {
	mu   sync.RWMutex
	data []byte
}

// NewStringSessionStorage 从已有会话字符串构造内存存储；空字符串表示全新未登录会话。
func NewStringSessionStorage(encoded string) (*StringSessionStorage, error) {
	trimmed := strings.TrimSpace(encoded)
	if trimmed == "" {
		return &StringSessionStorage{}, nil
	}
	raw, err := decodeSessionString(trimmed)
	if err != nil {
		return nil, err
	}
	return &StringSessionStorage{data: raw}, nil
}

// LoadSession 实现 gotd/td 的 session.Storage 接口，读取当前会话字节数据。
func (s *StringSessionStorage) LoadSession(_ context.Context) ([]byte, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if len(s.data) == 0 {
		return nil, session.ErrNotFound
	}
	cp := make([]byte, len(s.data))
	copy(cp, s.data)
	return cp, nil
}

// StoreSession 实现 gotd/td 的 session.Storage 接口，在登录成功或 DC 迁移后刷新内存中的会话数据。
func (s *StringSessionStorage) StoreSession(_ context.Context, data []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	cp := make([]byte, len(data))
	copy(cp, data)
	s.data = cp
	return nil
}

// String 将当前会话数据编码为带版本前缀的单行字符串，便于写入连接器配置与 JSON 导入导出。
func (s *StringSessionStorage) String() string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if len(s.data) == 0 {
		return ""
	}
	return sessionStringPrefix + base64.RawURLEncoding.EncodeToString(s.data)
}

// decodeSessionString 解析带前缀或裸 Base64 的会话字符串。
func decodeSessionString(encoded string) ([]byte, error) {
	payload := strings.TrimPrefix(strings.TrimSpace(encoded), sessionStringPrefix)
	if payload == "" {
		return nil, errors.New("Telegram 会话字符串内容为空")
	}
	if raw, err := base64.RawURLEncoding.DecodeString(payload); err == nil && len(raw) > 0 {
		return raw, nil
	}
	if raw, err := base64.StdEncoding.DecodeString(payload); err == nil && len(raw) > 0 {
		return raw, nil
	}
	return nil, fmt.Errorf("无效的 Telegram Session 字符串格式，请使用本服务生成的 %s... 凭据", sessionStringPrefix)
}
