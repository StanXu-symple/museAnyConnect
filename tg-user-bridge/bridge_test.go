package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gotd/td/session"
	"github.com/gotd/td/tg"
)

func TestStringSessionStorageRoundTrip(t *testing.T) {
	ctx := context.Background()

	// 空字符串初始化应返回 ErrNotFound
	empty, err := NewStringSessionStorage("")
	if err != nil {
		t.Fatalf("空字符串初始化失败: %v", err)
	}
	if _, err := empty.LoadSession(ctx); err != session.ErrNotFound {
		t.Fatalf("预期返回 session.ErrNotFound，实际: %v", err)
	}

	// 写入数据后导出单行字符串并重新恢复
	sample := []byte(`{"Version":1,"Data":{"DC":5,"Addr":"149.154.171.5:443"}}`)
	if err := empty.StoreSession(ctx, sample); err != nil {
		t.Fatalf("写入会话失败: %v", err)
	}
	encoded := empty.String()
	if !strings.HasPrefix(encoded, sessionStringPrefix) {
		t.Fatalf("编码前缀不匹配: %s", encoded)
	}

	restored, err := NewStringSessionStorage(encoded)
	if err != nil {
		t.Fatalf("反序列化会话字符串失败: %v", err)
	}
	loaded, err := restored.LoadSession(ctx)
	if err != nil {
		t.Fatalf("读取恢复后的会话失败: %v", err)
	}
	if !bytes.Equal(loaded, sample) {
		t.Fatalf("恢复数据不一致: got=%q want=%q", string(loaded), string(sample))
	}

	// 非法格式应报错
	if _, err := NewStringSessionStorage("tg_sess_v1:!!!not-base64!!!"); err == nil {
		t.Fatalf("非法 Base64 字符串应当返回错误")
	}

	// 兼容 Python Telethon v1 StringSession（'1' + Base64URL(dc(1B) + ipv4(4B) + port(2B) + authKey(256B))）
	rawTelethon := make([]byte, 1+4+2+256)
	rawTelethon[0] = 2
	copy(rawTelethon[1:5], []byte{149, 154, 167, 50})
	binary.BigEndian.PutUint16(rawTelethon[5:7], 443)
	for i := 7; i < len(rawTelethon); i++ {
		rawTelethon[i] = byte(i)
	}
	telethonStr := "1" + base64.URLEncoding.EncodeToString(rawTelethon)
	fromTelethon, err := NewStringSessionStorage(telethonStr)
	if err != nil {
		t.Fatalf("Telethon StringSession 解析失败: %v", err)
	}
	if !strings.HasPrefix(fromTelethon.String(), sessionStringPrefix) {
		t.Fatalf("Telethon 会话转换后应输出 %s 前缀，实际: %s", sessionStringPrefix, fromTelethon.String())
	}
	if loadedTelethon, err := fromTelethon.LoadSession(ctx); err != nil || !strings.Contains(string(loadedTelethon), "149.154.167.50:443") {
		t.Fatalf("Telethon 会话加载内容异常: err=%v data=%s", err, string(loadedTelethon))
	}
}

func TestPeerCacheAndHelperParsing(t *testing.T) {
	cache := newPeerCache()
	cache.RememberUsers([]tg.UserClass{
		&tg.User{ID: 1001, AccessHash: 99999, Username: "MuseUser"},
	})
	cache.RememberChats([]tg.ChatClass{
		&tg.Channel{ID: 2002, AccessHash: 88888, Username: "MuseGroup", Megagroup: true},
	})

	if h, ok := cache.LookupUserHash(1001); !ok || h != 99999 {
		t.Fatalf("用户 access_hash 缓存命中异常: ok=%v hash=%d", ok, h)
	}
	if h, ok := cache.LookupChannelHash(2002); !ok || h != 88888 {
		t.Fatalf("频道 access_hash 缓存命中异常: ok=%v hash=%d", ok, h)
	}
	if peer, ok := cache.LookupUsername("@museuser"); !ok || peer == nil {
		t.Fatalf("用户名大小写不敏感缓存未命中")
	}

	if !isSignedDigits("-100123456") || !isSignedDigits("42") || isSignedDigits("@muse") || isSignedDigits("-") {
		t.Fatalf("isSignedDigits 校验逻辑异常")
	}

	if dt, desc := describeSentCodeType(&tg.AuthSentCodeTypeApp{Length: 5}); dt != "app" || !strings.Contains(desc, "5") {
		t.Fatalf("App 渠道解析异常: type=%s desc=%s", dt, desc)
	}
	if dt, _ := describeSentCodeType(&tg.AuthSentCodeTypeSMS{Length: 5}); dt != "sms" {
		t.Fatalf("SMS 渠道解析异常: type=%s", dt)
	}
	if dt, desc := describeSentCodeType(&tg.AuthSentCodeTypeEmailCode{EmailPattern: "s***@example.com", Length: 6}); dt != "email" || !strings.Contains(desc, "s***@example.com") {
		t.Fatalf("Email 渠道解析异常: type=%s desc=%s", dt, desc)
	}
}

func TestBridgeHTTPAuthAndHealth(t *testing.T) {
	bridge := NewBridgeServer("bridge-secret-123")
	defer bridge.Close()
	handler := bridge.Handler()

	// 健康检查无需鉴权
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("预期 /health 返回 200，实际 %d", rec.Code)
	}

	// 缺少 X-Bridge-Token 应被拒绝（401）
	req2 := httptest.NewRequest(http.MethodPost, "/bridge/me", strings.NewReader(`{}`))
	rec2 := httptest.NewRecorder()
	handler.ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusUnauthorized {
		t.Fatalf("缺少鉴权头预期 401，实际 %d", rec2.Code)
	}

	// 携带正确令牌但参数缺失时应返回结构化 JSON 错误
	req3 := httptest.NewRequest(http.MethodPost, "/bridge/me", strings.NewReader(`{"apiId":0}`))
	req3.Header.Set(bridgeTokenHeader, "bridge-secret-123")
	rec3 := httptest.NewRecorder()
	handler.ServeHTTP(rec3, req3)
	if rec3.Code != http.StatusBadGateway {
		t.Fatalf("预期参数校验失败返回 502，实际 %d", rec3.Code)
	}
	var resp map[string]any
	if err := json.Unmarshal(rec3.Body.Bytes(), &resp); err != nil {
		t.Fatalf("响应非合法 JSON: %v", err)
	}
	if okVal, _ := resp["ok"].(bool); okVal {
		t.Fatalf("预期 ok=false，实际 true")
	}
}
