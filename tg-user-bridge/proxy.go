package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/gotd/td/telegram/dcs"
	"golang.org/x/net/proxy"
)

// buildResolver 根据可选代理参数构造 gotd/td 使用的 MTProto 网络解析器。
// 支持直连、HTTP CONNECT 代理（默认）及 SOCKS5 代理（在 proxyHost 中填写 socks5://host）。
func buildResolver(proxyHost string, proxyPort int) (dcs.Resolver, error) {
	host := strings.TrimSpace(proxyHost)
	if host == "" {
		return nil, nil
	}

	// 支持用户在 proxyHost 中填写 socks5://host 或 http://host 完整代理 URL
	if strings.Contains(host, "://") {
		u, err := url.Parse(host)
		if err != nil {
			return nil, fmt.Errorf("代理地址解析失败: %w", err)
		}
		if u.Port() == "" && proxyPort > 0 {
			u.Host = net.JoinHostPort(u.Hostname(), strconv.Itoa(proxyPort))
		}
		return buildResolverFromURL(u)
	}

	if proxyPort <= 0 || proxyPort > 65535 {
		return nil, fmt.Errorf("代理端口非法: %d", proxyPort)
	}

	u := &url.URL{
		Scheme: "http",
		Host:   net.JoinHostPort(host, strconv.Itoa(proxyPort)),
	}
	return buildResolverFromURL(u)
}

func buildResolverFromURL(u *url.URL) (dcs.Resolver, error) {
	scheme := strings.ToLower(strings.TrimSpace(u.Scheme))
	switch scheme {
	case "socks5", "socks5h":
		dialer, err := proxy.FromURL(u, &net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second})
		if err != nil {
			return nil, fmt.Errorf("初始化 SOCKS5 代理失败: %w", err)
		}
		ctxDialer, ok := dialer.(proxy.ContextDialer)
		if !ok {
			return nil, fmt.Errorf("SOCKS5 代理拨号器不支持上下文超时控制")
		}
		return dcs.Plain(dcs.PlainOptions{
			Dial: ctxDialer.DialContext,
		}), nil

	case "http", "https":
		return dcs.Plain(dcs.PlainOptions{
			Dial: newHTTPConnectDialer(u),
		}), nil

	default:
		return nil, fmt.Errorf("不支持的代理协议: %s（仅支持 http / socks5）", scheme)
	}
}

// newHTTPConnectDialer 构造通过 HTTP CONNECT 隧道转发 MTProto TCP 流量的拨号函数。
func newHTTPConnectDialer(proxyURL *url.URL) func(ctx context.Context, network, addr string) (net.Conn, error) {
	baseDialer := &net.Dialer{
		Timeout:   15 * time.Second,
		KeepAlive: 30 * time.Second,
	}
	proxyAddr := proxyURL.Host
	var authHeader string
	if proxyURL.User != nil {
		password, _ := proxyURL.User.Password()
		cred := proxyURL.User.Username() + ":" + password
		authHeader = "Basic " + base64.StdEncoding.EncodeToString([]byte(cred))
	}

	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		conn, err := baseDialer.DialContext(ctx, "tcp", proxyAddr)
		if err != nil {
			return nil, fmt.Errorf("连接 HTTP 代理 %s 失败: %w", proxyAddr, err)
		}

		// 若上下文带截止时间，将其应用到 CONNECT 握手阶段防止永久阻塞
		if deadline, ok := ctx.Deadline(); ok {
			_ = conn.SetDeadline(deadline)
		} else {
			_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
		}

		connectReq := &http.Request{
			Method: http.MethodConnect,
			URL:    &url.URL{Opaque: addr},
			Host:   addr,
			Header: make(http.Header),
		}
		if authHeader != "" {
			connectReq.Header.Set("Proxy-Authorization", authHeader)
		}

		if err := connectReq.Write(conn); err != nil {
			_ = conn.Close()
			return nil, fmt.Errorf("发送代理 CONNECT 请求失败: %w", err)
		}

		br := bufio.NewReader(conn)
		resp, err := http.ReadResponse(br, connectReq)
		if err != nil {
			_ = conn.Close()
			return nil, fmt.Errorf("读取代理 CONNECT 响应失败: %w", err)
		}
		_ = resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			_ = conn.Close()
			return nil, fmt.Errorf("HTTP 代理拒绝 CONNECT 隧道: %s", resp.Status)
		}

		// 握手完成后清除临时截止时间，交由 gotd/td 自行管理连接生命周期
		_ = conn.SetDeadline(time.Time{})
		return conn, nil
	}
}
