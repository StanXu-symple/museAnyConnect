package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"strings"
	"sync"
	"time"

	"github.com/gotd/td/telegram"
	"github.com/gotd/td/telegram/auth/qrlogin"
	"github.com/gotd/td/tg"
	"github.com/gotd/td/tgerr"
)

const (
	maxPendingQrLogins    = 8
	pendingQrLoginTimeout = 5 * time.Minute
)

// StartQrLoginRequest 表示发起 Telegram 二维码扫码登录的请求参数。
type StartQrLoginRequest struct {
	APIID     int    `json:"apiId"`
	APIHash   string `json:"apiHash"`
	ProxyHost string `json:"proxyHost,omitempty"`
	ProxyPort int    `json:"proxyPort,omitempty"`
}

// PollQrLoginRequest 表示轮询扫码状态或提交 2FA 云密码的请求参数。
type PollQrLoginRequest struct {
	QrLoginID string `json:"qrLoginId"`
	Password  string `json:"password,omitempty"`
}

type qrPasswordCmd struct {
	password string
	replyCh  chan qrPasswordResp
}

type qrPasswordResp struct {
	sessionString string
	user          map[string]any
	err           error
}

type pendingQrLogin struct {
	id        string
	createdAt time.Time
	cancel    context.CancelFunc
	pwCh      chan qrPasswordCmd

	mu            sync.RWMutex
	status        string
	qrURL         string
	expiresAt     int64
	sessionString string
	user          map[string]any
	errMsg        string
}

func (p *pendingQrLogin) setToken(qrURL string, expiresAt int64) {
	p.mu.Lock()
	p.status = "WAIT_SCAN"
	p.qrURL = qrURL
	p.expiresAt = expiresAt
	p.mu.Unlock()
}

func (p *pendingQrLogin) setWaitPassword() {
	p.mu.Lock()
	p.status = "WAIT_PASSWORD"
	p.mu.Unlock()
}

func (p *pendingQrLogin) setAuthorized(sessionString string, user map[string]any) {
	p.mu.Lock()
	p.status = "AUTHORIZED"
	p.sessionString = sessionString
	p.user = user
	p.errMsg = ""
	p.mu.Unlock()
}

func (p *pendingQrLogin) setFailed(err error) {
	p.mu.Lock()
	if p.status != "AUTHORIZED" {
		p.status = "FAILED"
		if err != nil {
			p.errMsg = err.Error()
		} else {
			p.errMsg = "二维码登录会话已终止"
		}
	}
	p.mu.Unlock()
}

// QrLoginManager 管理交互式二维码扫码登录的临时 gotd/td 会话。
type QrLoginManager struct {
	mu      sync.Mutex
	pending map[string]*pendingQrLogin
}

func NewQrLoginManager() *QrLoginManager {
	return &QrLoginManager{
		pending: make(map[string]*pendingQrLogin),
	}
}

// StartQrLogin 发起二维码扫码登录流程并返回首个 tg://login?token=... 链接。
func (qm *QrLoginManager) StartQrLogin(ctx context.Context, req StartQrLoginRequest) (map[string]any, error) {
	if req.APIID <= 0 || strings.TrimSpace(req.APIHash) == "" {
		return nil, errors.New("apiId 与 apiHash 为必填项")
	}

	resolver, err := buildResolver(req.ProxyHost, req.ProxyPort)
	if err != nil {
		return nil, err
	}

	apiHash := strings.TrimSpace(req.APIHash)
	storage := &StringSessionStorage{}
	dispatcher := tg.NewUpdateDispatcher()
	loggedIn := qrlogin.OnLoginToken(&dispatcher)

	opts := telegram.Options{
		SessionStorage: storage,
		UpdateHandler:  dispatcher,
		Device: telegram.DeviceConfig{
			DeviceModel:   "MuseAnyConnect",
			SystemVersion: "Linux",
			AppVersion:    "1.0.0",
			LangCode:      "en",
		},
	}
	if resolver != nil {
		opts.Resolver = resolver
	}

	client := telegram.NewClient(req.APIID, apiHash, opts)
	qrLoginID := randomHexID(16)
	flowCtx, cancel := context.WithTimeout(context.Background(), pendingQrLoginTimeout)

	entry := &pendingQrLogin{
		id:        qrLoginID,
		createdAt: time.Now(),
		cancel:    cancel,
		pwCh:      make(chan qrPasswordCmd, 1),
		status:    "WAIT_SCAN",
	}

	type firstTokenResult struct {
		qrURL     string
		expiresAt int64
		err       error
	}
	firstCh := make(chan firstTokenResult, 1)
	var firstOnce sync.Once

	go func() {
		defer cancel()

		runErr := client.Run(flowCtx, func(c context.Context) error {
			qr := qrlogin.NewQR(client.API(), req.APIID, apiHash, qrlogin.Options{
				Migrate: client.MigrateTo,
			})

			show := func(_ context.Context, token qrlogin.Token) error {
				urlStr := token.URL()
				exp := token.Expires().Unix()
				entry.setToken(urlStr, exp)
				firstOnce.Do(func() {
					firstCh <- firstTokenResult{qrURL: urlStr, expiresAt: exp}
				})
				return nil
			}

			_, authErr := qr.Auth(c, loggedIn, show)
			if authErr != nil {
				if tgerr.Is(authErr, "SESSION_PASSWORD_NEEDED") {
					entry.setWaitPassword()
					for {
						select {
						case <-c.Done():
							return c.Err()
						case cmd := <-entry.pwCh:
							pw := cmd.password
							if strings.TrimSpace(pw) == "" {
								cmd.replyCh <- qrPasswordResp{err: errors.New("当前账号已开启两步验证，请输入 2FA 云密码")}
								continue
							}
							if _, pwErr := client.Auth().Password(c, pw); pwErr != nil {
								cmd.replyCh <- qrPasswordResp{err: fmt.Errorf("两步验证密码校验失败: %w", pwErr)}
								continue
							}
							self, selfErr := client.Self(c)
							if selfErr != nil {
								wrapErr := fmt.Errorf("扫码登录成功但读取账号信息失败: %w", selfErr)
								entry.setFailed(wrapErr)
								cmd.replyCh <- qrPasswordResp{err: wrapErr}
								return wrapErr
							}
							userInfo := formatUser(self)
							sessStr := storage.String()
							entry.setAuthorized(sessStr, userInfo)
							cmd.replyCh <- qrPasswordResp{sessionString: sessStr, user: userInfo}
							return nil
						}
					}
				}
				return authErr
			}

			self, selfErr := client.Self(c)
			if selfErr != nil {
				return fmt.Errorf("扫码登录成功但读取账号信息失败: %w", selfErr)
			}
			entry.setAuthorized(storage.String(), formatUser(self))
			log.Printf("Telegram 二维码扫码登录成功: qrLoginId=%s", qrLoginID)
			return nil
		})

		if runErr != nil {
			firstOnce.Do(func() {
				firstCh <- firstTokenResult{err: runErr}
			})
			entry.setFailed(runErr)
			if !errors.Is(runErr, context.Canceled) && !errors.Is(runErr, context.DeadlineExceeded) {
				log.Printf("Telegram 二维码登录会话 %s 结束: %v", qrLoginID, runErr)
			}
		}
	}()

	select {
	case res := <-firstCh:
		if res.err != nil {
			cancel()
			return nil, fmt.Errorf("生成 Telegram 登录二维码失败: %w", res.err)
		}
		qm.mu.Lock()
		qm.evictExpiredLocked()
		qm.pending[qrLoginID] = entry
		qm.mu.Unlock()

		return map[string]any{
			"qrLoginId":    qrLoginID,
			"status":       "WAIT_SCAN",
			"qrUrl":        res.qrURL,
			"expiresAt":    res.expiresAt,
			"expiresInSec": int(pendingQrLoginTimeout.Seconds()),
		}, nil
	case <-ctx.Done():
		cancel()
		return nil, fmt.Errorf("生成 Telegram 登录二维码超时: %w", ctx.Err())
	}
}

// PollQrLogin 查询当前扫码会话状态；若账号开启了两步验证且传入 password，则直接完成 2FA 校验。
func (qm *QrLoginManager) PollQrLogin(ctx context.Context, req PollQrLoginRequest) (map[string]any, error) {
	qrLoginID := strings.TrimSpace(req.QrLoginID)
	if qrLoginID == "" {
		return nil, errors.New("qrLoginId 不能为空")
	}

	qm.mu.Lock()
	entry, ok := qm.pending[qrLoginID]
	qm.mu.Unlock()
	if !ok {
		return nil, errors.New("二维码登录会话不存在或已过期，请重新生成二维码")
	}

	entry.mu.RLock()
	status := entry.status
	qrURL := entry.qrURL
	expiresAt := entry.expiresAt
	sessStr := entry.sessionString
	userInfo := entry.user
	errMsg := entry.errMsg
	entry.mu.RUnlock()

	if status == "WAIT_PASSWORD" && strings.TrimSpace(req.Password) != "" {
		replyCh := make(chan qrPasswordResp, 1)
		select {
		case entry.pwCh <- qrPasswordCmd{password: req.Password, replyCh: replyCh}:
		case <-ctx.Done():
			return nil, fmt.Errorf("提交两步验证密码超时: %w", ctx.Err())
		}

		select {
		case resp := <-replyCh:
			if resp.err != nil {
				return nil, resp.err
			}
			qm.deletePending(qrLoginID)
			return map[string]any{
				"qrLoginId":     qrLoginID,
				"status":        "AUTHORIZED",
				"sessionString": resp.sessionString,
				"user":          resp.user,
			}, nil
		case <-ctx.Done():
			return nil, fmt.Errorf("等待两步验证响应超时: %w", ctx.Err())
		}
	}

	switch status {
	case "AUTHORIZED":
		qm.deletePending(qrLoginID)
		return map[string]any{
			"qrLoginId":     qrLoginID,
			"status":        "AUTHORIZED",
			"sessionString": sessStr,
			"user":          userInfo,
		}, nil
	case "WAIT_PASSWORD":
		return map[string]any{
			"qrLoginId": qrLoginID,
			"status":    "WAIT_PASSWORD",
			"message":   "已扫码确认，当前账号开启了两步验证，请输入 2FA 云密码完成登录",
		}, nil
	case "FAILED":
		qm.deletePending(qrLoginID)
		return nil, errors.New(errMsg)
	default:
		return map[string]any{
			"qrLoginId": qrLoginID,
			"status":    "WAIT_SCAN",
			"qrUrl":     qrURL,
			"expiresAt": expiresAt,
		}, nil
	}
}

func (qm *QrLoginManager) deletePending(qrLoginID string) {
	qm.mu.Lock()
	if entry, ok := qm.pending[qrLoginID]; ok {
		entry.cancel()
		delete(qm.pending, qrLoginID)
	}
	qm.mu.Unlock()
}

func (qm *QrLoginManager) evictExpiredLocked() {
	now := time.Now()
	for k, v := range qm.pending {
		if now.Sub(v.createdAt) > pendingQrLoginTimeout {
			v.cancel()
			delete(qm.pending, k)
		}
	}
	for len(qm.pending) >= maxPendingQrLogins {
		var oldestKey string
		var oldestTime time.Time
		for k, v := range qm.pending {
			if oldestKey == "" || v.createdAt.Before(oldestTime) {
				oldestKey = k
				oldestTime = v.createdAt
			}
		}
		if oldestKey == "" {
			break
		}
		qm.pending[oldestKey].cancel()
		delete(qm.pending, oldestKey)
	}
}

func (qm *QrLoginManager) CloseAll() {
	qm.mu.Lock()
	for k, v := range qm.pending {
		v.cancel()
		delete(qm.pending, k)
	}
	qm.mu.Unlock()
}
