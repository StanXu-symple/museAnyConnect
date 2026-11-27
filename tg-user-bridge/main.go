package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
)

const (
	bridgeTokenHeader = "X-Bridge-Token"
	maxRequestBytes   = 1 << 20 // 限制单次请求体最大 1MB，防止内存放大
)

// BridgeServer 封装基于 gotd/td 的 Telegram 个人账号 HTTP 桥接服务。
type BridgeServer struct {
	token    string
	sessions *SessionManager
	logins   *LoginManager
}

func NewBridgeServer(token string) *BridgeServer {
	return &BridgeServer{
		token:    strings.TrimSpace(token),
		sessions: NewSessionManager(),
		logins:   NewLoginManager(),
	}
}

func (s *BridgeServer) Close() {
	s.logins.CloseAll()
	s.sessions.CloseAll()
}

func (s *BridgeServer) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.handleHealth)

	// 交互式登录接口（获取 StringSession）
	mux.HandleFunc("/bridge/auth/start", s.withAuth(jsonEndpoint(s.logins.StartLogin)))
	mux.HandleFunc("/bridge/auth/verify", s.withAuth(jsonEndpoint(s.logins.VerifyLogin)))

	// 个人账号 MTProto 业务能力接口
	mux.HandleFunc("/bridge/me", s.withAuth(jsonEndpoint(s.handleMe)))
	mux.HandleFunc("/bridge/dialogs", s.withAuth(jsonEndpoint(s.handleDialogs)))
	mux.HandleFunc("/bridge/messages/history", s.withAuth(jsonEndpoint(s.handleHistory)))
	mux.HandleFunc("/bridge/messages/search", s.withAuth(jsonEndpoint(s.handleSearch)))
	mux.HandleFunc("/bridge/messages/send", s.withAuth(jsonEndpoint(s.handleSendMessage)))
	mux.HandleFunc("/bridge/messages/edit", s.withAuth(jsonEndpoint(s.handleEditMessage)))
	mux.HandleFunc("/bridge/messages/delete", s.withAuth(jsonEndpoint(s.handleDeleteMessages)))
	mux.HandleFunc("/bridge/messages/forward", s.withAuth(jsonEndpoint(s.handleForwardMessages)))
	mux.HandleFunc("/bridge/messages/read", s.withAuth(jsonEndpoint(s.handleMarkRead)))
	mux.HandleFunc("/bridge/chat/resolve", s.withAuth(jsonEndpoint(s.handleResolveChat)))

	return mux
}

func (s *BridgeServer) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "error": "仅支持 GET 方法"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "status": "UP"})
}

func (s *BridgeServer) withAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "error": "仅支持 POST 方法"})
			return
		}
		if s.token != "" {
			provided := strings.TrimSpace(r.Header.Get(bridgeTokenHeader))
			if subtle.ConstantTimeCompare([]byte(s.token), []byte(provided)) != 1 {
				writeJSON(w, http.StatusUnauthorized, map[string]any{"ok": false, "error": "无效或缺失 X-Bridge-Token 请求头"})
				return
			}
		}
		next(w, r)
	}
}

// jsonEndpoint 统一处理 JSON 解码、上下文超时控制与标准响应封包。
func jsonEndpoint[Req any, Resp any](fn func(context.Context, Req) (Resp, error)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req Req
		body := io.LimitReader(r.Body, maxRequestBytes)
		defer r.Body.Close()

		dec := json.NewDecoder(body)
		if err := dec.Decode(&req); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "请求 JSON 解析失败: " + err.Error()})
			return
		}

		ctx, cancel := context.WithTimeout(r.Context(), 45*time.Second)
		defer cancel()

		result, err := fn(ctx, req)
		if err != nil {
			status := http.StatusBadGateway
			if errors.Is(err, context.DeadlineExceeded) {
				status = http.StatusGatewayTimeout
			}
			writeJSON(w, status, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "result": result})
	}
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func main() {
	port := strings.TrimSpace(os.Getenv("TG_BRIDGE_PORT"))
	if port == "" {
		port = "8081"
	}
	token := strings.TrimSpace(os.Getenv("TG_BRIDGE_TOKEN"))

	bridge := NewBridgeServer(token)
	defer bridge.Close()

	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           bridge.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	stopCtx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	go func() {
		log.Printf("Muse Telegram 个人账号桥接服务 (gotd/td) 已启动，监听端口 :%s（鉴权令牌启用=%v）", port, token != "")
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("HTTP 服务异常退出: %v", err)
		}
	}()

	<-stopCtx.Done()
	log.Println("正在优雅关闭 Telegram 桥接服务...")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
}
