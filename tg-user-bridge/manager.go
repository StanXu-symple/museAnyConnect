package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"log"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gotd/td/telegram"
	"github.com/gotd/td/telegram/auth"
	"github.com/gotd/td/tg"
)

const (
	maxActiveSessions   = 16
	sessionIdleTimeout  = 30 * time.Minute
	maxPendingLogins    = 8
	pendingLoginTimeout = 5 * time.Minute
)

// ClientCredentials 表示调用桥接接口时传入的 Telegram 个人账号 MTProto 凭据。
type ClientCredentials struct {
	APIID     int    `json:"apiId"`
	APIHash   string `json:"apiHash"`
	Session   string `json:"sessionString"`
	ProxyHost string `json:"proxyHost,omitempty"`
	ProxyPort int    `json:"proxyPort,omitempty"`
}

func (c ClientCredentials) cacheKey() string {
	raw := fmt.Sprintf("%d|%s|%s|%s|%d",
		c.APIID,
		strings.TrimSpace(c.APIHash),
		strings.TrimSpace(c.Session),
		strings.TrimSpace(c.ProxyHost),
		c.ProxyPort,
	)
	sum := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(sum[:])
}

// PeerCache 在内存中维护当前账号的实体访问哈希缓存（用户、频道、用户名），避免每次请求重复查全量对话。
type PeerCache struct {
	mu        sync.RWMutex
	users     map[int64]int64
	channels  map[int64]int64
	usernames map[string]tg.InputPeerClass
	warmedAt  time.Time
}

func newPeerCache() *PeerCache {
	return &PeerCache{
		users:     make(map[int64]int64),
		channels:  make(map[int64]int64),
		usernames: make(map[string]tg.InputPeerClass),
	}
}

func (p *PeerCache) RememberUsers(users []tg.UserClass) {
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, uc := range users {
		u, ok := uc.(*tg.User)
		if !ok || u.ID == 0 {
			continue
		}
		if u.AccessHash != 0 {
			p.users[u.ID] = u.AccessHash
		}
		if u.Username != "" {
			p.usernames[strings.ToLower(u.Username)] = &tg.InputPeerUser{
				UserID:     u.ID,
				AccessHash: u.AccessHash,
			}
		}
	}
}

func (p *PeerCache) RememberChats(chats []tg.ChatClass) {
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, cc := range chats {
		switch ch := cc.(type) {
		case *tg.Channel:
			if ch.ID == 0 {
				continue
			}
			if ch.AccessHash != 0 {
				p.channels[ch.ID] = ch.AccessHash
			}
			if ch.Username != "" {
				p.usernames[strings.ToLower(ch.Username)] = &tg.InputPeerChannel{
					ChannelID:  ch.ID,
					AccessHash: ch.AccessHash,
				}
			}
		case *tg.Chat:
			// 普通群组无需 access_hash，直接使用 ChatID 构造 InputPeerChat
		}
	}
}

func (p *PeerCache) LookupUserHash(userID int64) (int64, bool) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	h, ok := p.users[userID]
	return h, ok
}

func (p *PeerCache) LookupChannelHash(channelID int64) (int64, bool) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	h, ok := p.channels[channelID]
	return h, ok
}

func (p *PeerCache) LookupUsername(username string) (tg.InputPeerClass, bool) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	peer, ok := p.usernames[strings.ToLower(strings.TrimPrefix(strings.TrimSpace(username), "@"))]
	return peer, ok
}

func (p *PeerCache) MarkWarmed() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.warmedAt = time.Now()
}

func (p *PeerCache) NeedsWarm() bool {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.warmedAt.IsZero() || time.Since(p.warmedAt) > 5*time.Minute
}

// ManagedClient 封装单个长连接 gotd/td 客户端及其内存 Peer 缓存。
type ManagedClient struct {
	key      string
	client   *telegram.Client
	storage  *StringSessionStorage
	peers    *PeerCache
	cancel   context.CancelFunc
	closedCh chan struct{}

	mu       sync.Mutex
	closed   bool
	lastUsed time.Time
}

func (m *ManagedClient) touch() {
	m.mu.Lock()
	m.lastUsed = time.Now()
	m.mu.Unlock()
}

func (m *ManagedClient) isClosed() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.closed
}

func (m *ManagedClient) markClosed() {
	m.mu.Lock()
	if !m.closed {
		m.closed = true
		close(m.closedCh)
	}
	m.mu.Unlock()
}

func (m *ManagedClient) close() {
	m.cancel()
	m.markClosed()
}

// WarmPeers 拉取当前账号最近对话列表，自动填充内存中的用户/超级群/频道 access_hash 缓存。
func (m *ManagedClient) WarmPeers(ctx context.Context) error {
	res, err := m.client.API().MessagesGetDialogs(ctx, &tg.MessagesGetDialogsRequest{
		OffsetDate: 0,
		OffsetID:   0,
		OffsetPeer: &tg.InputPeerEmpty{},
		Limit:      100,
	})
	if err != nil {
		return err
	}
	switch d := res.(type) {
	case *tg.MessagesDialogs:
		m.peers.RememberUsers(d.Users)
		m.peers.RememberChats(d.Chats)
	case *tg.MessagesDialogsSlice:
		m.peers.RememberUsers(d.Users)
		m.peers.RememberChats(d.Chats)
	}
	m.peers.MarkWarmed()
	return nil
}

// ResolvePeer 将统一的 chatId 标识（me/self、@username、-100频道/超级群ID、-普通群ID、正数用户ID）解析为 MTProto InputPeer。
func (m *ManagedClient) ResolvePeer(ctx context.Context, rawChatID string) (tg.InputPeerClass, error) {
	target := strings.TrimSpace(rawChatID)
	if target == "" {
		return nil, errors.New("chatId 不能为空")
	}

	lower := strings.ToLower(target)
	if lower == "me" || lower == "self" {
		return &tg.InputPeerSelf{}, nil
	}

	// 支持 @username 或纯用户名解析
	if strings.HasPrefix(target, "@") || !isSignedDigits(target) {
		uname := strings.TrimPrefix(target, "@")
		if cached, ok := m.peers.LookupUsername(uname); ok {
			return cached, nil
		}
		resolved, err := m.client.API().ContactsResolveUsername(ctx, &tg.ContactsResolveUsernameRequest{
			Username: uname,
		})
		if err != nil {
			return nil, fmt.Errorf("解析用户名 @%s 失败: %w", uname, err)
		}
		m.peers.RememberUsers(resolved.Users)
		m.peers.RememberChats(resolved.Chats)
		return peerClassToInputPeer(resolved.Peer, m.peers)
	}

	// 兼容 Bot API 风格的 -100<channelId> 超级群组 / 频道 ID
	if strings.HasPrefix(target, "-100") && len(target) > 4 {
		channelID, err := strconv.ParseInt(target[4:], 10, 64)
		if err != nil {
			return nil, fmt.Errorf("频道/超级群 chatId 非法: %s", target)
		}
		if hash, ok := m.peers.LookupChannelHash(channelID); ok {
			return &tg.InputPeerChannel{ChannelID: channelID, AccessHash: hash}, nil
		}
		if m.peers.NeedsWarm() {
			_ = m.WarmPeers(ctx)
			if hash, ok := m.peers.LookupChannelHash(channelID); ok {
				return &tg.InputPeerChannel{ChannelID: channelID, AccessHash: hash}, nil
			}
		}
		return nil, fmt.Errorf("未找到超级群/频道 %s 的 access_hash，请先调用 GET /dialogs 确认当前账号已加入该群组", target)
	}

	numID, err := strconv.ParseInt(target, 10, 64)
	if err != nil {
		return nil, fmt.Errorf("无法解析 chatId: %s", target)
	}

	// 普通群组（Basic Group）使用负数 ID
	if numID < 0 {
		return &tg.InputPeerChat{ChatID: -numID}, nil
	}

	// 正数 ID 优先按用户解析，若未命中则尝试按原始频道 ID 查找
	if hash, ok := m.peers.LookupUserHash(numID); ok {
		return &tg.InputPeerUser{UserID: numID, AccessHash: hash}, nil
	}
	if m.peers.NeedsWarm() {
		_ = m.WarmPeers(ctx)
		if hash, ok := m.peers.LookupUserHash(numID); ok {
			return &tg.InputPeerUser{UserID: numID, AccessHash: hash}, nil
		}
		if hash, ok := m.peers.LookupChannelHash(numID); ok {
			return &tg.InputPeerChannel{ChannelID: numID, AccessHash: hash}, nil
		}
	}
	return nil, fmt.Errorf("未找到目标用户 %s 的 access_hash，请使用 @username 或先调用 GET /dialogs 预热对话列表", target)
}

func peerClassToInputPeer(peer tg.PeerClass, cache *PeerCache) (tg.InputPeerClass, error) {
	switch p := peer.(type) {
	case *tg.PeerUser:
		hash, _ := cache.LookupUserHash(p.UserID)
		return &tg.InputPeerUser{UserID: p.UserID, AccessHash: hash}, nil
	case *tg.PeerChat:
		return &tg.InputPeerChat{ChatID: p.ChatID}, nil
	case *tg.PeerChannel:
		hash, _ := cache.LookupChannelHash(p.ChannelID)
		return &tg.InputPeerChannel{ChannelID: p.ChannelID, AccessHash: hash}, nil
	default:
		return nil, fmt.Errorf("无法转换未知 Peer 类型: %T", peer)
	}
}

func isSignedDigits(s string) bool {
	if s == "" {
		return false
	}
	start := 0
	if s[0] == '-' {
		if len(s) == 1 {
			return false
		}
		start = 1
	}
	for i := start; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return false
		}
	}
	return true
}

// SessionManager 池化管理活跃的 MTProto 个人账号长连接，支持空闲回收与上限控制。
type SessionManager struct {
	mu      sync.Mutex
	clients map[string]*ManagedClient
	stopCh  chan struct{}
}

func NewSessionManager() *SessionManager {
	sm := &SessionManager{
		clients: make(map[string]*ManagedClient),
		stopCh:  make(chan struct{}),
	}
	go sm.reapIdleLoop()
	return sm
}

// GetOrConnect 获取或建立指定凭据对应的已认证 gotd/td 长连接。
func (sm *SessionManager) GetOrConnect(ctx context.Context, creds ClientCredentials) (*ManagedClient, error) {
	if creds.APIID <= 0 || strings.TrimSpace(creds.APIHash) == "" {
		return nil, errors.New("telegramApiId 与 telegramApiHash 为必填项")
	}
	if strings.TrimSpace(creds.Session) == "" {
		return nil, errors.New("telegramSession 不能为空，请先登录获取 Session 字符串")
	}

	key := creds.cacheKey()
	sm.mu.Lock()
	if existing, ok := sm.clients[key]; ok && !existing.isClosed() {
		existing.touch()
		sm.mu.Unlock()
		return existing, nil
	}
	sm.mu.Unlock()

	storage, err := NewStringSessionStorage(creds.Session)
	if err != nil {
		return nil, err
	}
	resolver, err := buildResolver(creds.ProxyHost, creds.ProxyPort)
	if err != nil {
		return nil, err
	}

	opts := telegram.Options{
		SessionStorage: storage,
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

	client := telegram.NewClient(creds.APIID, strings.TrimSpace(creds.APIHash), opts)
	runCtx, cancel := context.WithCancel(context.Background())

	mc := &ManagedClient{
		key:      key,
		client:   client,
		storage:  storage,
		peers:    newPeerCache(),
		cancel:   cancel,
		closedCh: make(chan struct{}),
		lastUsed: time.Now(),
	}

	ready := make(chan error, 1)
	go func() {
		runErr := client.Run(runCtx, func(c context.Context) error {
			status, err := client.Auth().Status(c)
			if err != nil {
				ready <- fmt.Errorf("检查 Telegram 登录态失败: %w", err)
				return err
			}
			if !status.Authorized {
				authErr := errors.New("Telegram 个人账号 Session 无效或已失效，请重新登录生成")
				ready <- authErr
				return authErr
			}
			ready <- nil
			<-c.Done()
			return c.Err()
		})
		select {
		case ready <- runErr:
		default:
		}
		mc.markClosed()
		sm.removeIfSame(key, mc)
	}()

	select {
	case err := <-ready:
		if err != nil {
			mc.close()
			return nil, err
		}
	case <-ctx.Done():
		mc.close()
		return nil, fmt.Errorf("连接 Telegram MTProto 超时: %w", ctx.Err())
	}

	sm.mu.Lock()
	if existing, ok := sm.clients[key]; ok && !existing.isClosed() {
		// 并发建连时保留先就绪的实例，关闭多余连接
		sm.mu.Unlock()
		mc.close()
		existing.touch()
		return existing, nil
	}
	sm.evictOldestLocked()
	sm.clients[key] = mc
	sm.mu.Unlock()

	return mc, nil
}

func (sm *SessionManager) removeIfSame(key string, target *ManagedClient) {
	sm.mu.Lock()
	if cur, ok := sm.clients[key]; ok && cur == target {
		delete(sm.clients, key)
	}
	sm.mu.Unlock()
}

func (sm *SessionManager) evictOldestLocked() {
	for len(sm.clients) >= maxActiveSessions {
		var oldestKey string
		var oldestTime time.Time
		for k, v := range sm.clients {
			v.mu.Lock()
			lu := v.lastUsed
			v.mu.Unlock()
			if oldestKey == "" || lu.Before(oldestTime) {
				oldestKey = k
				oldestTime = lu
			}
		}
		if oldestKey == "" {
			break
		}
		if victim := sm.clients[oldestKey]; victim != nil {
			victim.close()
		}
		delete(sm.clients, oldestKey)
	}
}

func (sm *SessionManager) reapIdleLoop() {
	ticker := time.NewTicker(2 * time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-sm.stopCh:
			return
		case <-ticker.C:
			now := time.Now()
			sm.mu.Lock()
			for k, mc := range sm.clients {
				mc.mu.Lock()
				idle := now.Sub(mc.lastUsed)
				closed := mc.closed
				mc.mu.Unlock()
				if closed || idle > sessionIdleTimeout {
					mc.close()
					delete(sm.clients, k)
				}
			}
			sm.mu.Unlock()
		}
	}
}

func (sm *SessionManager) CloseAll() {
	close(sm.stopCh)
	sm.mu.Lock()
	for k, mc := range sm.clients {
		mc.close()
		delete(sm.clients, k)
	}
	sm.mu.Unlock()
}

// =========================================================================
// 交互式登录会话管理器（用于控制台或 API 在线完成手机号 + 验证码 + 可选 2FA 登录）
// =========================================================================

type loginVerifyCmd struct {
	code     string
	password string
	replyCh  chan loginVerifyResp
}

type loginVerifyResp struct {
	status        string
	sessionString string
	user          map[string]any
	err           error
}

type pendingLogin struct {
	id        string
	phone     string
	createdAt time.Time
	cmdCh     chan loginVerifyCmd
	cancel    context.CancelFunc
}

type LoginManager struct {
	mu      sync.Mutex
	pending map[string]*pendingLogin
}

func NewLoginManager() *LoginManager {
	return &LoginManager{
		pending: make(map[string]*pendingLogin),
	}
}

type StartLoginRequest struct {
	APIID     int    `json:"apiId"`
	APIHash   string `json:"apiHash"`
	Phone     string `json:"phone"`
	ProxyHost string `json:"proxyHost,omitempty"`
	ProxyPort int    `json:"proxyPort,omitempty"`
}

type VerifyLoginRequest struct {
	LoginID  string `json:"loginId"`
	Code     string `json:"code"`
	Password string `json:"password,omitempty"`
}

// StartLogin 发起验证码登录流程：启动临时 gotd/td 客户端并向目标手机号发送登录验证码。
func (lm *LoginManager) StartLogin(ctx context.Context, req StartLoginRequest) (map[string]any, error) {
	if req.APIID <= 0 || strings.TrimSpace(req.APIHash) == "" {
		return nil, errors.New("apiId 与 apiHash 为必填项")
	}
	phone := strings.TrimSpace(req.Phone)
	if phone == "" {
		return nil, errors.New("phone（带国际区号的手机号，如 +8613800138000）为必填项")
	}

	resolver, err := buildResolver(req.ProxyHost, req.ProxyPort)
	if err != nil {
		return nil, err
	}

	storage := &StringSessionStorage{}
	opts := telegram.Options{
		SessionStorage: storage,
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

	client := telegram.NewClient(req.APIID, strings.TrimSpace(req.APIHash), opts)
	loginID := randomHexID(16)
	flowCtx, cancel := context.WithTimeout(context.Background(), pendingLoginTimeout)
	cmdCh := make(chan loginVerifyCmd, 1)

	type initResult struct {
		phoneCodeHash string
		err           error
	}
	initCh := make(chan initResult, 1)

	go func() {
		defer lm.deletePending(loginID)
		defer cancel()

		runErr := client.Run(flowCtx, func(c context.Context) error {
			sentClass, err := client.Auth().SendCode(c, phone, auth.SendCodeOptions{})
			if err != nil {
				initCh <- initResult{err: fmt.Errorf("发送 Telegram 验证码失败: %w", err)}
				return err
			}
			sent, ok := sentClass.(*tg.AuthSentCode)
			if !ok {
				initErr := fmt.Errorf("收到未预期的验证码响应类型: %T", sentClass)
				initCh <- initResult{err: initErr}
				return initErr
			}

			initCh <- initResult{phoneCodeHash: sent.PhoneCodeHash}
			awaitingPassword := false

			for {
				select {
				case <-c.Done():
					return c.Err()
				case cmd := <-cmdCh:
					if !awaitingPassword {
						code := strings.TrimSpace(cmd.code)
						if code == "" {
							cmd.replyCh <- loginVerifyResp{err: errors.New("请输入收到的登录验证码")}
							continue
						}
						_, signInErr := client.Auth().SignIn(c, phone, code, sent.PhoneCodeHash)
						if errors.Is(signInErr, auth.ErrPasswordAuthNeeded) {
							awaitingPassword = true
							if strings.TrimSpace(cmd.password) == "" {
								cmd.replyCh <- loginVerifyResp{status: "WAIT_PASSWORD"}
								continue
							}
							// 若用户在提交验证码的同时提供了 2FA 密码，直接完成二次校验
							if _, pwErr := client.Auth().Password(c, cmd.password); pwErr != nil {
								cmd.replyCh <- loginVerifyResp{err: fmt.Errorf("两步验证密码校验失败: %w", pwErr)}
								continue
							}
						} else if signInErr != nil {
							cmd.replyCh <- loginVerifyResp{err: fmt.Errorf("验证码校验失败: %w", signInErr)}
							continue
						}
					} else {
						if strings.TrimSpace(cmd.password) == "" {
							cmd.replyCh <- loginVerifyResp{err: errors.New("当前账号已开启两步验证，请输入 2FA 云密码")}
							continue
						}
						if _, pwErr := client.Auth().Password(c, cmd.password); pwErr != nil {
							cmd.replyCh <- loginVerifyResp{err: fmt.Errorf("两步验证密码校验失败: %w", pwErr)}
							continue
						}
					}

					// 登录成功，读取账号信息与单行 Session 字符串后退出临时客户端
					self, selfErr := client.Self(c)
					if selfErr != nil {
						cmd.replyCh <- loginVerifyResp{err: fmt.Errorf("登录成功但读取账号信息失败: %w", selfErr)}
						return selfErr
					}
					cmd.replyCh <- loginVerifyResp{
						status:        "AUTHORIZED",
						sessionString: storage.String(),
						user:          formatUser(self),
					}
					return nil
				}
			}
		})

		select {
		case initCh <- initResult{err: runErr}:
		default:
		}
		if runErr != nil && !errors.Is(runErr, context.Canceled) && !errors.Is(runErr, context.DeadlineExceeded) {
			log.Printf("交互式登录会话 %s 结束: %v", loginID, runErr)
		}
	}()

	select {
	case res := <-initCh:
		if res.err != nil {
			cancel()
			return nil, res.err
		}
		lm.mu.Lock()
		lm.evictExpiredLocked()
		lm.pending[loginID] = &pendingLogin{
			id:        loginID,
			phone:     phone,
			createdAt: time.Now(),
			cmdCh:     cmdCh,
			cancel:    cancel,
		}
		lm.mu.Unlock()

		return map[string]any{
			"loginId":       loginID,
			"status":        "WAIT_CODE",
			"phone":         phone,
			"phoneCodeHash": res.phoneCodeHash,
			"expiresInSec":  int(pendingLoginTimeout.Seconds()),
		}, nil
	case <-ctx.Done():
		cancel()
		return nil, fmt.Errorf("发起 Telegram 验证码登录超时: %w", ctx.Err())
	}
}

// VerifyLogin 提交收到的登录验证码（及可选 2FA 云密码），成功后返回可持久化的 sessionString。
func (lm *LoginManager) VerifyLogin(ctx context.Context, req VerifyLoginRequest) (map[string]any, error) {
	loginID := strings.TrimSpace(req.LoginID)
	if loginID == "" {
		return nil, errors.New("loginId 不能为空")
	}

	lm.mu.Lock()
	p, ok := lm.pending[loginID]
	lm.mu.Unlock()
	if !ok {
		return nil, errors.New("登录会话不存在或已过期，请重新发送验证码")
	}

	replyCh := make(chan loginVerifyResp, 1)
	select {
	case p.cmdCh <- loginVerifyCmd{code: req.Code, password: req.Password, replyCh: replyCh}:
	case <-ctx.Done():
		return nil, fmt.Errorf("提交验证码超时: %w", ctx.Err())
	}

	select {
	case resp := <-replyCh:
		if resp.err != nil {
			return nil, resp.err
		}
		if resp.status == "WAIT_PASSWORD" {
			return map[string]any{
				"loginId": loginID,
				"status":  "WAIT_PASSWORD",
				"message": "账号已开启两步验证，请继续输入 2FA 云密码完成登录",
			}, nil
		}
		return map[string]any{
			"loginId":       loginID,
			"status":        "AUTHORIZED",
			"sessionString": resp.sessionString,
			"user":          resp.user,
		}, nil
	case <-ctx.Done():
		return nil, fmt.Errorf("等待 Telegram 校验响应超时: %w", ctx.Err())
	}
}

func (lm *LoginManager) deletePending(loginID string) {
	lm.mu.Lock()
	delete(lm.pending, loginID)
	lm.mu.Unlock()
}

func (lm *LoginManager) evictExpiredLocked() {
	now := time.Now()
	for k, v := range lm.pending {
		if now.Sub(v.createdAt) > pendingLoginTimeout {
			v.cancel()
			delete(lm.pending, k)
		}
	}
	for len(lm.pending) >= maxPendingLogins {
		var oldestKey string
		var oldestTime time.Time
		for k, v := range lm.pending {
			if oldestKey == "" || v.createdAt.Before(oldestTime) {
				oldestKey = k
				oldestTime = v.createdAt
			}
		}
		if oldestKey == "" {
			break
		}
		lm.pending[oldestKey].cancel()
		delete(lm.pending, oldestKey)
	}
}

func (lm *LoginManager) CloseAll() {
	lm.mu.Lock()
	for k, v := range lm.pending {
		v.cancel()
		delete(lm.pending, k)
	}
	lm.mu.Unlock()
}

func randomHexID(numBytes int) string {
	buf := make([]byte, numBytes)
	_, _ = rand.Read(buf)
	return hex.EncodeToString(buf)
}
