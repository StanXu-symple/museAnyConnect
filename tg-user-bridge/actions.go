package main

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"strings"

	"github.com/gotd/td/telegram/message"
	"github.com/gotd/td/telegram/message/html"
	"github.com/gotd/td/tg"
)

// =========================================================================
// 各 MTProto 业务接口请求结构定义
// =========================================================================

type MeRequest struct {
	ClientCredentials
}

type DialogsRequest struct {
	ClientCredentials
	Limit      int `json:"limit,omitempty"`
	OffsetDate int `json:"offsetDate,omitempty"`
	FolderID   int `json:"folderId,omitempty"`
}

type HistoryRequest struct {
	ClientCredentials
	ChatID   string `json:"chatId"`
	Limit    int    `json:"limit,omitempty"`
	OffsetID int    `json:"offsetId,omitempty"`
	MinID    int    `json:"minId,omitempty"`
	MaxID    int    `json:"maxId,omitempty"`
}

type SearchRequest struct {
	ClientCredentials
	ChatID   string `json:"chatId,omitempty"`
	Query    string `json:"query"`
	Limit    int    `json:"limit,omitempty"`
	OffsetID int    `json:"offsetId,omitempty"`
}

type SendMessageRequest struct {
	ClientCredentials
	ChatID              string `json:"chatId"`
	Text                string `json:"text"`
	ParseMode           string `json:"parseMode,omitempty"`
	DisableNotification bool   `json:"disableNotification,omitempty"`
	ReplyToMessageID    int    `json:"replyToMessageId,omitempty"`
	NoWebpage           bool   `json:"noWebpage,omitempty"`
}

type EditMessageRequest struct {
	ClientCredentials
	ChatID    string `json:"chatId"`
	MessageID int    `json:"messageId"`
	Text      string `json:"text"`
	ParseMode string `json:"parseMode,omitempty"`
}

type DeleteMessagesRequest struct {
	ClientCredentials
	ChatID     string `json:"chatId"`
	MessageIDs []int  `json:"messageIds"`
	Revoke     bool   `json:"revoke,omitempty"`
}

type ForwardMessagesRequest struct {
	ClientCredentials
	FromChatID string `json:"fromChatId"`
	ToChatID   string `json:"toChatId"`
	MessageIDs []int  `json:"messageIds"`
	DropAuthor bool   `json:"dropAuthor,omitempty"`
	Silent     bool   `json:"silent,omitempty"`
}

type MarkReadRequest struct {
	ClientCredentials
	ChatID string `json:"chatId"`
	MaxID  int    `json:"maxId,omitempty"`
}

type ResolveChatRequest struct {
	ClientCredentials
	ChatID string `json:"chatId"`
}

// =========================================================================
// MTProto 核心业务操作实现
// =========================================================================

// handleMe 测试连通性并返回当前已登录个人账号信息。
func (s *BridgeServer) handleMe(ctx context.Context, req MeRequest) (any, error) {
	mc, err := s.sessions.GetOrConnect(ctx, req.ClientCredentials)
	if err != nil {
		return nil, err
	}
	self, err := mc.client.Self(ctx)
	if err != nil {
		return nil, fmt.Errorf("获取当前账号信息失败: %w", err)
	}
	return formatUser(self), nil
}

// handleDialogs 获取当前个人账号的对话列表（私聊、群组、超级群、频道），同时预热 Peer 缓存。
func (s *BridgeServer) handleDialogs(ctx context.Context, req DialogsRequest) (any, error) {
	mc, err := s.sessions.GetOrConnect(ctx, req.ClientCredentials)
	if err != nil {
		return nil, err
	}

	limit := clampInt(req.Limit, 1, 100, 30)
	tgReq := &tg.MessagesGetDialogsRequest{
		OffsetDate: req.OffsetDate,
		OffsetID:   0,
		OffsetPeer: &tg.InputPeerEmpty{},
		Limit:      limit,
	}
	if req.FolderID > 0 {
		tgReq.SetFolderID(req.FolderID)
	}

	res, err := mc.client.API().MessagesGetDialogs(ctx, tgReq)
	if err != nil {
		return nil, fmt.Errorf("获取对话列表失败: %w", err)
	}

	var dialogs []tg.DialogClass
	var messages []tg.MessageClass
	var users []tg.UserClass
	var chats []tg.ChatClass
	var totalCount int

	switch d := res.(type) {
	case *tg.MessagesDialogs:
		dialogs, messages, users, chats = d.Dialogs, d.Messages, d.Users, d.Chats
		totalCount = len(d.Dialogs)
	case *tg.MessagesDialogsSlice:
		dialogs, messages, users, chats = d.Dialogs, d.Messages, d.Users, d.Chats
		totalCount = d.Count
	default:
		return map[string]any{"count": 0, "dialogs": []any{}}, nil
	}

	mc.peers.RememberUsers(users)
	mc.peers.RememberChats(chats)
	mc.peers.MarkWarmed()

	userMap := indexUsers(users)
	chatMap, channelMap := indexChats(chats)
	msgMap := indexMessages(messages)

	items := make([]map[string]any, 0, len(dialogs))
	for _, dc := range dialogs {
		dlg, ok := dc.(*tg.Dialog)
		if !ok {
			continue
		}
		item := buildDialogSummary(dlg, userMap, chatMap, channelMap, msgMap)
		items = append(items, item)
	}

	return map[string]any{
		"totalCount": totalCount,
		"returned":   len(items),
		"dialogs":    items,
	}, nil
}

// handleHistory 读取任意已加入群组、频道或私聊的历史消息。
func (s *BridgeServer) handleHistory(ctx context.Context, req HistoryRequest) (any, error) {
	mc, err := s.sessions.GetOrConnect(ctx, req.ClientCredentials)
	if err != nil {
		return nil, err
	}
	peer, err := mc.ResolvePeer(ctx, req.ChatID)
	if err != nil {
		return nil, err
	}

	limit := clampInt(req.Limit, 1, 100, 20)
	res, err := mc.client.API().MessagesGetHistory(ctx, &tg.MessagesGetHistoryRequest{
		Peer:     peer,
		OffsetID: req.OffsetID,
		Limit:    limit,
		MaxID:    req.MaxID,
		MinID:    req.MinID,
	})
	if err != nil {
		return nil, fmt.Errorf("拉取历史消息失败: %w", err)
	}

	return formatMessagesResult(mc, res), nil
}

// handleSearch 在指定群组内搜索消息；若 chatId 为空则执行全账号全局消息搜索。
func (s *BridgeServer) handleSearch(ctx context.Context, req SearchRequest) (any, error) {
	query := strings.TrimSpace(req.Query)
	if query == "" {
		return nil, errors.New("搜索关键词 query 不能为空")
	}

	mc, err := s.sessions.GetOrConnect(ctx, req.ClientCredentials)
	if err != nil {
		return nil, err
	}
	limit := clampInt(req.Limit, 1, 100, 20)

	var res tg.MessagesMessagesClass
	if strings.TrimSpace(req.ChatID) != "" {
		peer, err := mc.ResolvePeer(ctx, req.ChatID)
		if err != nil {
			return nil, err
		}
		res, err = mc.client.API().MessagesSearch(ctx, &tg.MessagesSearchRequest{
			Peer:     peer,
			Q:        query,
			Filter:   &tg.InputMessagesFilterEmpty{},
			OffsetID: req.OffsetID,
			Limit:    limit,
		})
		if err != nil {
			return nil, fmt.Errorf("搜索群组消息失败: %w", err)
		}
	} else {
		res, err = mc.client.API().MessagesSearchGlobal(ctx, &tg.MessagesSearchGlobalRequest{
			Q:          query,
			Filter:     &tg.InputMessagesFilterEmpty{},
			OffsetRate: 0,
			OffsetPeer: &tg.InputPeerEmpty{},
			OffsetID:   req.OffsetID,
			Limit:      limit,
		})
		if err != nil {
			return nil, fmt.Errorf("全局搜索消息失败: %w", err)
		}
	}

	return formatMessagesResult(mc, res), nil
}

// handleSendMessage 以个人账号身份发送或回复文本消息（支持纯文本与 HTML 格式）。
func (s *BridgeServer) handleSendMessage(ctx context.Context, req SendMessageRequest) (any, error) {
	if strings.TrimSpace(req.Text) == "" {
		return nil, errors.New("消息正文 text 不能为空")
	}
	mc, err := s.sessions.GetOrConnect(ctx, req.ClientCredentials)
	if err != nil {
		return nil, err
	}
	peer, err := mc.ResolvePeer(ctx, req.ChatID)
	if err != nil {
		return nil, err
	}

	builder := message.NewSender(mc.client.API()).To(peer)
	if req.DisableNotification {
		builder = builder.Silent()
	}
	if req.NoWebpage {
		builder = builder.NoWebpage()
	}
	if req.ReplyToMessageID > 0 {
		builder = builder.Reply(req.ReplyToMessageID)
	}

	var updates tg.UpdatesClass
	if strings.EqualFold(strings.TrimSpace(req.ParseMode), "HTML") {
		updates, err = builder.StyledText(ctx, html.String(nil, req.Text))
	} else {
		updates, err = builder.Text(ctx, req.Text)
	}
	if err != nil {
		return nil, fmt.Errorf("发送消息失败: %w", err)
	}

	msgIDs := extractMessageIDs(updates)
	result := map[string]any{
		"chatId": req.ChatID,
		"sent":   true,
	}
	if len(msgIDs) > 0 {
		result["messageId"] = msgIDs[0]
		result["messageIds"] = msgIDs
	}
	return result, nil
}

// handleEditMessage 编辑已发送的消息内容。
func (s *BridgeServer) handleEditMessage(ctx context.Context, req EditMessageRequest) (any, error) {
	if req.MessageID <= 0 {
		return nil, errors.New("messageId 必须为正整数")
	}
	if strings.TrimSpace(req.Text) == "" {
		return nil, errors.New("新的消息正文 text 不能为空")
	}
	mc, err := s.sessions.GetOrConnect(ctx, req.ClientCredentials)
	if err != nil {
		return nil, err
	}
	peer, err := mc.ResolvePeer(ctx, req.ChatID)
	if err != nil {
		return nil, err
	}

	editBuilder := message.NewSender(mc.client.API()).To(peer).Edit(req.MessageID)
	if strings.EqualFold(strings.TrimSpace(req.ParseMode), "HTML") {
		_, err = editBuilder.StyledText(ctx, html.String(nil, req.Text))
	} else {
		_, err = editBuilder.Text(ctx, req.Text)
	}
	if err != nil {
		return nil, fmt.Errorf("编辑消息失败: %w", err)
	}

	return map[string]any{
		"chatId":    req.ChatID,
		"messageId": req.MessageID,
		"edited":    true,
	}, nil
}

// handleDeleteMessages 删除指定聊天中的一条或多条消息，自动区分频道/超级群与普通聊天接口。
func (s *BridgeServer) handleDeleteMessages(ctx context.Context, req DeleteMessagesRequest) (any, error) {
	if len(req.MessageIDs) == 0 {
		return nil, errors.New("messageIds 不能为空")
	}
	mc, err := s.sessions.GetOrConnect(ctx, req.ClientCredentials)
	if err != nil {
		return nil, err
	}
	peer, err := mc.ResolvePeer(ctx, req.ChatID)
	if err != nil {
		return nil, err
	}

	var ptsCount int
	if ch, ok := peer.(*tg.InputPeerChannel); ok {
		affected, err := mc.client.API().ChannelsDeleteMessages(ctx, &tg.ChannelsDeleteMessagesRequest{
			Channel: &tg.InputChannel{
				ChannelID:  ch.ChannelID,
				AccessHash: ch.AccessHash,
			},
			ID: req.MessageIDs,
		})
		if err != nil {
			return nil, fmt.Errorf("删除频道/超级群消息失败: %w", err)
		}
		ptsCount = affected.PtsCount
	} else {
		affected, err := mc.client.API().MessagesDeleteMessages(ctx, &tg.MessagesDeleteMessagesRequest{
			Revoke: req.Revoke,
			ID:     req.MessageIDs,
		})
		if err != nil {
			return nil, fmt.Errorf("删除消息失败: %w", err)
		}
		ptsCount = affected.PtsCount
	}

	return map[string]any{
		"chatId":     req.ChatID,
		"messageIds": req.MessageIDs,
		"deleted":    true,
		"ptsCount":   ptsCount,
	}, nil
}

// handleForwardMessages 将指定消息从来源聊天转发到目标聊天。
func (s *BridgeServer) handleForwardMessages(ctx context.Context, req ForwardMessagesRequest) (any, error) {
	if len(req.MessageIDs) == 0 {
		return nil, errors.New("messageIds 不能为空")
	}
	mc, err := s.sessions.GetOrConnect(ctx, req.ClientCredentials)
	if err != nil {
		return nil, err
	}
	fromPeer, err := mc.ResolvePeer(ctx, req.FromChatID)
	if err != nil {
		return nil, fmt.Errorf("解析来源会话失败: %w", err)
	}
	toPeer, err := mc.ResolvePeer(ctx, req.ToChatID)
	if err != nil {
		return nil, fmt.Errorf("解析目标会话失败: %w", err)
	}

	randomIDs := make([]int64, len(req.MessageIDs))
	for i := range randomIDs {
		randomIDs[i] = randomInt64()
	}

	updates, err := mc.client.API().MessagesForwardMessages(ctx, &tg.MessagesForwardMessagesRequest{
		FromPeer:   fromPeer,
		ToPeer:     toPeer,
		ID:         req.MessageIDs,
		RandomID:   randomIDs,
		DropAuthor: req.DropAuthor,
		Silent:     req.Silent,
	})
	if err != nil {
		return nil, fmt.Errorf("转发消息失败: %w", err)
	}

	return map[string]any{
		"fromChatId":          req.FromChatID,
		"toChatId":            req.ToChatID,
		"forwardedMessageIds": extractMessageIDs(updates),
	}, nil
}

// handleMarkRead 将指定聊天或超级群/频道的消息标记为已读。
func (s *BridgeServer) handleMarkRead(ctx context.Context, req MarkReadRequest) (any, error) {
	mc, err := s.sessions.GetOrConnect(ctx, req.ClientCredentials)
	if err != nil {
		return nil, err
	}
	peer, err := mc.ResolvePeer(ctx, req.ChatID)
	if err != nil {
		return nil, err
	}

	if ch, ok := peer.(*tg.InputPeerChannel); ok {
		okRead, err := mc.client.API().ChannelsReadHistory(ctx, &tg.ChannelsReadHistoryRequest{
			Channel: &tg.InputChannel{
				ChannelID:  ch.ChannelID,
				AccessHash: ch.AccessHash,
			},
			MaxID: req.MaxID,
		})
		if err != nil {
			return nil, fmt.Errorf("标记频道/超级群已读失败: %w", err)
		}
		return map[string]any{"chatId": req.ChatID, "read": okRead}, nil
	}

	affected, err := mc.client.API().MessagesReadHistory(ctx, &tg.MessagesReadHistoryRequest{
		Peer:  peer,
		MaxID: req.MaxID,
	})
	if err != nil {
		return nil, fmt.Errorf("标记消息已读失败: %w", err)
	}
	return map[string]any{"chatId": req.ChatID, "read": true, "ptsCount": affected.PtsCount}, nil
}

// handleResolveChat 解析 @username 或 chatId 并返回标准化实体信息。
func (s *BridgeServer) handleResolveChat(ctx context.Context, req ResolveChatRequest) (any, error) {
	target := strings.TrimSpace(req.ChatID)
	if target == "" {
		return nil, errors.New("chatId 不能为空")
	}
	mc, err := s.sessions.GetOrConnect(ctx, req.ClientCredentials)
	if err != nil {
		return nil, err
	}

	// 如果是用户名，直接通过 ContactsResolveUsername 获取完整实体资料
	if strings.HasPrefix(target, "@") || (!isSignedDigits(target) && !strings.EqualFold(target, "me") && !strings.EqualFold(target, "self")) {
		uname := strings.TrimPrefix(target, "@")
		resolved, err := mc.client.API().ContactsResolveUsername(ctx, &tg.ContactsResolveUsernameRequest{
			Username: uname,
		})
		if err != nil {
			return nil, fmt.Errorf("解析用户名 @%s 失败: %w", uname, err)
		}
		mc.peers.RememberUsers(resolved.Users)
		mc.peers.RememberChats(resolved.Chats)
		return formatResolvedPeer(resolved.Peer, indexUsers(resolved.Users), resolved.Chats), nil
	}

	peer, err := mc.ResolvePeer(ctx, target)
	if err != nil {
		return nil, err
	}
	switch p := peer.(type) {
	case *tg.InputPeerSelf:
		self, err := mc.client.Self(ctx)
		if err != nil {
			return nil, err
		}
		return formatUser(self), nil
	case *tg.InputPeerUser:
		return map[string]any{"chatId": fmt.Sprintf("%d", p.UserID), "type": "user", "id": p.UserID}, nil
	case *tg.InputPeerChat:
		return map[string]any{"chatId": fmt.Sprintf("-%d", p.ChatID), "type": "group", "id": p.ChatID}, nil
	case *tg.InputPeerChannel:
		return map[string]any{"chatId": fmt.Sprintf("-100%d", p.ChannelID), "type": "channel_or_supergroup", "id": p.ChannelID}, nil
	default:
		return map[string]any{"chatId": target, "type": "unknown"}, nil
	}
}

// =========================================================================
// 响应格式化辅助函数
// =========================================================================

func formatUser(u *tg.User) map[string]any {
	if u == nil {
		return nil
	}
	return map[string]any{
		"id":        u.ID,
		"chatId":    fmt.Sprintf("%d", u.ID),
		"type":      "user",
		"username":  u.Username,
		"firstName": u.FirstName,
		"lastName":  u.LastName,
		"phone":     u.Phone,
		"bot":       u.Bot,
		"premium":   u.Premium,
	}
}

func formatResolvedPeer(peer tg.PeerClass, users map[int64]*tg.User, chats []tg.ChatClass) map[string]any {
	chatMap, channelMap := indexChats(chats)
	switch p := peer.(type) {
	case *tg.PeerUser:
		if u, ok := users[p.UserID]; ok {
			return formatUser(u)
		}
		return map[string]any{"chatId": fmt.Sprintf("%d", p.UserID), "type": "user", "id": p.UserID}
	case *tg.PeerChat:
		if ch, ok := chatMap[p.ChatID]; ok {
			return map[string]any{
				"chatId":            fmt.Sprintf("-%d", ch.ID),
				"id":                ch.ID,
				"type":              "group",
				"title":             ch.Title,
				"participantsCount": ch.ParticipantsCount,
			}
		}
		return map[string]any{"chatId": fmt.Sprintf("-%d", p.ChatID), "type": "group", "id": p.ChatID}
	case *tg.PeerChannel:
		if ch, ok := channelMap[p.ChannelID]; ok {
			chatType := "channel"
			if ch.Megagroup {
				chatType = "supergroup"
			}
			return map[string]any{
				"chatId":            fmt.Sprintf("-100%d", ch.ID),
				"id":                ch.ID,
				"type":              chatType,
				"title":             ch.Title,
				"username":          ch.Username,
				"participantsCount": ch.ParticipantsCount,
			}
		}
		return map[string]any{"chatId": fmt.Sprintf("-100%d", p.ChannelID), "type": "channel", "id": p.ChannelID}
	default:
		return map[string]any{"type": "unknown"}
	}
}

func buildDialogSummary(
	dlg *tg.Dialog,
	users map[int64]*tg.User,
	chats map[int64]*tg.Chat,
	channels map[int64]*tg.Channel,
	msgs map[int]*tg.Message,
) map[string]any {
	item := map[string]any{
		"unreadCount": dlg.UnreadCount,
		"topMessage":  dlg.TopMessage,
		"pinned":      dlg.Pinned,
	}

	switch p := dlg.Peer.(type) {
	case *tg.PeerUser:
		item["chatId"] = fmt.Sprintf("%d", p.UserID)
		item["type"] = "user"
		if u, ok := users[p.UserID]; ok {
			item["title"] = strings.TrimSpace(u.FirstName + " " + u.LastName)
			item["username"] = u.Username
			item["bot"] = u.Bot
		}
	case *tg.PeerChat:
		item["chatId"] = fmt.Sprintf("-%d", p.ChatID)
		item["type"] = "group"
		if ch, ok := chats[p.ChatID]; ok {
			item["title"] = ch.Title
			item["participantsCount"] = ch.ParticipantsCount
		}
	case *tg.PeerChannel:
		item["chatId"] = fmt.Sprintf("-100%d", p.ChannelID)
		if ch, ok := channels[p.ChannelID]; ok {
			if ch.Megagroup {
				item["type"] = "supergroup"
			} else {
				item["type"] = "channel"
			}
			item["title"] = ch.Title
			item["username"] = ch.Username
		} else {
			item["type"] = "channel"
		}
	}

	if topMsg, ok := msgs[dlg.TopMessage]; ok {
		item["lastMessageDate"] = topMsg.Date
		item["lastMessageText"] = topMsg.Message
	}
	return item
}

func formatMessagesResult(mc *ManagedClient, res tg.MessagesMessagesClass) map[string]any {
	var rawMsgs []tg.MessageClass
	var users []tg.UserClass
	var chats []tg.ChatClass
	var totalCount int

	switch m := res.(type) {
	case *tg.MessagesMessages:
		rawMsgs, users, chats = m.Messages, m.Users, m.Chats
		totalCount = len(m.Messages)
	case *tg.MessagesMessagesSlice:
		rawMsgs, users, chats = m.Messages, m.Users, m.Chats
		totalCount = m.Count
	case *tg.MessagesChannelMessages:
		rawMsgs, users, chats = m.Messages, m.Users, m.Chats
		totalCount = m.Count
	default:
		return map[string]any{"totalCount": 0, "returned": 0, "messages": []any{}}
	}

	mc.peers.RememberUsers(users)
	mc.peers.RememberChats(chats)

	userMap := indexUsers(users)
	chatMap, channelMap := indexChats(chats)

	items := make([]map[string]any, 0, len(rawMsgs))
	for _, mcItem := range rawMsgs {
		msg, ok := mcItem.(*tg.Message)
		if !ok {
			continue
		}
		entry := map[string]any{
			"id":   msg.ID,
			"date": msg.Date,
			"out":  msg.Out,
			"text": msg.Message,
		}
		if chatID, chatTitle := describePeer(msg.PeerID, userMap, chatMap, channelMap); chatID != "" {
			entry["chatId"] = chatID
			if chatTitle != "" {
				entry["chatTitle"] = chatTitle
			}
		}
		if senderID, senderName := describePeer(msg.FromID, userMap, chatMap, channelMap); senderID != "" {
			entry["senderId"] = senderID
			if senderName != "" {
				entry["senderName"] = senderName
			}
		}
		if replyHeader, ok := msg.ReplyTo.(*tg.MessageReplyHeader); ok && replyHeader.ReplyToMsgID > 0 {
			entry["replyToMessageId"] = replyHeader.ReplyToMsgID
		}
		if msg.Views > 0 {
			entry["views"] = msg.Views
		}
		if msg.Forwards > 0 {
			entry["forwards"] = msg.Forwards
		}
		if msg.EditDate > 0 {
			entry["editDate"] = msg.EditDate
		}
		if msg.Media != nil {
			entry["mediaType"] = describeMediaType(msg.Media)
		}
		items = append(items, entry)
	}

	return map[string]any{
		"totalCount": totalCount,
		"returned":   len(items),
		"messages":   items,
	}
}

func describePeer(
	peer tg.PeerClass,
	users map[int64]*tg.User,
	chats map[int64]*tg.Chat,
	channels map[int64]*tg.Channel,
) (string, string) {
	if peer == nil {
		return "", ""
	}
	switch p := peer.(type) {
	case *tg.PeerUser:
		id := fmt.Sprintf("%d", p.UserID)
		if u, ok := users[p.UserID]; ok {
			name := strings.TrimSpace(u.FirstName + " " + u.LastName)
			if u.Username != "" {
				if name != "" {
					return id, name + " (@" + u.Username + ")"
				}
				return id, "@" + u.Username
			}
			return id, name
		}
		return id, ""
	case *tg.PeerChat:
		id := fmt.Sprintf("-%d", p.ChatID)
		if ch, ok := chats[p.ChatID]; ok {
			return id, ch.Title
		}
		return id, ""
	case *tg.PeerChannel:
		id := fmt.Sprintf("-100%d", p.ChannelID)
		if ch, ok := channels[p.ChannelID]; ok {
			return id, ch.Title
		}
		return id, ""
	default:
		return "", ""
	}
}

func describeMediaType(media tg.MessageMediaClass) string {
	switch media.(type) {
	case *tg.MessageMediaPhoto:
		return "photo"
	case *tg.MessageMediaDocument:
		return "document"
	case *tg.MessageMediaWebPage:
		return "webpage"
	case *tg.MessageMediaGeo:
		return "location"
	case *tg.MessageMediaContact:
		return "contact"
	case *tg.MessageMediaPoll:
		return "poll"
	default:
		return fmt.Sprintf("%T", media)
	}
}

func indexUsers(users []tg.UserClass) map[int64]*tg.User {
	m := make(map[int64]*tg.User, len(users))
	for _, uc := range users {
		if u, ok := uc.(*tg.User); ok {
			m[u.ID] = u
		}
	}
	return m
}

func indexChats(chats []tg.ChatClass) (map[int64]*tg.Chat, map[int64]*tg.Channel) {
	cm := make(map[int64]*tg.Chat)
	chm := make(map[int64]*tg.Channel)
	for _, cc := range chats {
		switch c := cc.(type) {
		case *tg.Chat:
			cm[c.ID] = c
		case *tg.Channel:
			chm[c.ID] = c
		}
	}
	return cm, chm
}

func indexMessages(messages []tg.MessageClass) map[int]*tg.Message {
	m := make(map[int]*tg.Message, len(messages))
	for _, mc := range messages {
		if msg, ok := mc.(*tg.Message); ok {
			m[msg.ID] = msg
		}
	}
	return m
}

func extractMessageIDs(updates tg.UpdatesClass) []int {
	var ids []int
	switch u := updates.(type) {
	case *tg.UpdateShortSentMessage:
		ids = append(ids, u.ID)
	case *tg.Updates:
		ids = append(ids, collectUpdateMessageIDs(u.Updates)...)
	case *tg.UpdatesCombined:
		ids = append(ids, collectUpdateMessageIDs(u.Updates)...)
	}
	return ids
}

func collectUpdateMessageIDs(list []tg.UpdateClass) []int {
	seen := make(map[int]struct{})
	var ids []int
	add := func(id int) {
		if id <= 0 {
			return
		}
		if _, ok := seen[id]; !ok {
			seen[id] = struct{}{}
			ids = append(ids, id)
		}
	}
	for _, item := range list {
		switch up := item.(type) {
		case *tg.UpdateMessageID:
			add(up.ID)
		case *tg.UpdateNewMessage:
			if m, ok := up.Message.(*tg.Message); ok {
				add(m.ID)
			}
		case *tg.UpdateNewChannelMessage:
			if m, ok := up.Message.(*tg.Message); ok {
				add(m.ID)
			}
		}
	}
	return ids
}

func clampInt(val, minVal, maxVal, fallback int) int {
	if val <= 0 {
		return fallback
	}
	if val < minVal {
		return minVal
	}
	if val > maxVal {
		return maxVal
	}
	return val
}

func randomInt64() int64 {
	var b [8]byte
	_, _ = rand.Read(b[:])
	v := int64(binary.LittleEndian.Uint64(b[:]) & 0x7fffffffffffffff)
	if v == 0 {
		return 1
	}
	return v
}
