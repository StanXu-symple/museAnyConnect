package com.muse.connect.controller;

import com.muse.connect.aidoc.AiApiGroup;
import com.muse.connect.aidoc.AiDocMeta;
import com.muse.connect.aidoc.AiEndpoint;
import com.muse.connect.aidoc.AiField;
import com.muse.connect.constant.RouteConstants;
import com.muse.connect.model.RelayResponse;
import com.muse.connect.service.TelegramRelayService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Telegram 中转控制器：兼容 Bot API 与个人账号 MTProto (gotd/td) 能力接口。
 */
@RestController
@RequestMapping(RouteConstants.API_RELAY_TELEGRAM_BASE)
@AiApiGroup(AiDocMeta.GROUP_TELEGRAM_RELAY)
public class TelegramRelayController {

    private final TelegramRelayService telegramRelayService;

    public TelegramRelayController(TelegramRelayService telegramRelayService) {
        this.telegramRelayService = telegramRelayService;
    }

    public record SendMessageRequest(
            @AiField(AiDocMeta.FIELD_TELEGRAM_CHAT_ID) String chatId,
            @AiField(AiDocMeta.FIELD_TELEGRAM_TEXT) String text,
            @AiField(AiDocMeta.FIELD_TELEGRAM_PARSE_MODE) String parseMode,
            @AiField(AiDocMeta.FIELD_TELEGRAM_DISABLE_NOTIFICATION) Boolean disableNotification,
            @AiField(AiDocMeta.FIELD_TELEGRAM_REPLY_TO_MESSAGE_ID) Long replyToMessageId
    ) {
    }

    public record EditMessageRequest(
            @AiField(AiDocMeta.FIELD_TELEGRAM_USER_CHAT_ID) String chatId,
            @AiField(AiDocMeta.FIELD_TELEGRAM_MESSAGE_ID) Integer messageId,
            @AiField(AiDocMeta.FIELD_TELEGRAM_TEXT) String text,
            @AiField(AiDocMeta.FIELD_TELEGRAM_PARSE_MODE) String parseMode
    ) {
    }

    public record DeleteMessagesRequest(
            @AiField(AiDocMeta.FIELD_TELEGRAM_USER_CHAT_ID) String chatId,
            @AiField(AiDocMeta.FIELD_TELEGRAM_MESSAGE_IDS) List<Integer> messageIds,
            @AiField(AiDocMeta.FIELD_TELEGRAM_REVOKE) Boolean revoke
    ) {
    }

    public record ForwardMessagesRequest(
            @AiField(AiDocMeta.FIELD_TELEGRAM_FROM_CHAT_ID) String fromChatId,
            @AiField(AiDocMeta.FIELD_TELEGRAM_TO_CHAT_ID) String toChatId,
            @AiField(AiDocMeta.FIELD_TELEGRAM_MESSAGE_IDS) List<Integer> messageIds,
            @AiField(AiDocMeta.FIELD_TELEGRAM_DROP_AUTHOR) Boolean dropAuthor,
            @AiField(AiDocMeta.FIELD_TELEGRAM_DISABLE_NOTIFICATION) Boolean silent
    ) {
    }

    public record MarkReadRequest(
            @AiField(AiDocMeta.FIELD_TELEGRAM_USER_CHAT_ID) String chatId,
            @AiField(AiDocMeta.FIELD_TELEGRAM_MAX_ID) Integer maxId
    ) {
    }

    @AiEndpoint(AiDocMeta.EP_TELEGRAM_SEND_MESSAGE)
    @PostMapping(RouteConstants.TELEGRAM_SEND)
    public RelayResponse sendMessage(
            @PathVariable @AiField(AiDocMeta.FIELD_TELEGRAM_PROFILE_ID) String profileId,
            @RequestBody SendMessageRequest request
    ) {
        return telegramRelayService.sendMessage(
                profileId,
                request.chatId(),
                request.text(),
                request.parseMode(),
                request.disableNotification(),
                request.replyToMessageId()
        );
    }

    @AiEndpoint(AiDocMeta.EP_TELEGRAM_GET_UPDATES)
    @GetMapping(RouteConstants.TELEGRAM_UPDATES)
    public RelayResponse getUpdates(
            @PathVariable @AiField(AiDocMeta.FIELD_TELEGRAM_PROFILE_ID) String profileId,
            @RequestParam(required = false) @AiField(AiDocMeta.FIELD_TELEGRAM_OFFSET) Long offset,
            @RequestParam(defaultValue = RouteConstants.DEFAULT_TELEGRAM_LIMIT) @AiField(AiDocMeta.FIELD_TELEGRAM_LIMIT) Integer limit,
            @RequestParam(defaultValue = RouteConstants.DEFAULT_TELEGRAM_TIMEOUT) @AiField(AiDocMeta.FIELD_TELEGRAM_TIMEOUT) Integer timeout
    ) {
        return telegramRelayService.getUpdates(profileId, offset, limit, timeout);
    }

    @AiEndpoint(AiDocMeta.EP_TELEGRAM_CALL_METHOD)
    @PostMapping(RouteConstants.TELEGRAM_METHOD)
    public RelayResponse callMethod(
            @PathVariable @AiField(AiDocMeta.FIELD_TELEGRAM_PROFILE_ID) String profileId,
            @PathVariable @AiField(AiDocMeta.FIELD_TELEGRAM_METHOD) String method,
            @RequestBody(required = false) @AiField(AiDocMeta.FIELD_TELEGRAM_PAYLOAD) Map<String, Object> payload
    ) {
        return telegramRelayService.callMethod(profileId, method, payload);
    }

    @AiEndpoint(AiDocMeta.EP_TELEGRAM_USER_DIALOGS)
    @GetMapping(RouteConstants.TELEGRAM_DIALOGS)
    public RelayResponse getDialogs(
            @PathVariable @AiField(AiDocMeta.FIELD_TELEGRAM_PROFILE_ID) String profileId,
            @RequestParam(defaultValue = RouteConstants.DEFAULT_TELEGRAM_DIALOGS_LIMIT) @AiField(AiDocMeta.FIELD_TELEGRAM_LIMIT) Integer limit,
            @RequestParam(required = false) @AiField(AiDocMeta.FIELD_TELEGRAM_OFFSET_DATE) Integer offsetDate,
            @RequestParam(required = false) @AiField(AiDocMeta.FIELD_TELEGRAM_FOLDER_ID) Integer folderId
    ) {
        return telegramRelayService.getDialogs(profileId, limit, offsetDate, folderId);
    }

    @AiEndpoint(AiDocMeta.EP_TELEGRAM_USER_HISTORY)
    @GetMapping(RouteConstants.TELEGRAM_HISTORY)
    public RelayResponse getHistory(
            @PathVariable @AiField(AiDocMeta.FIELD_TELEGRAM_PROFILE_ID) String profileId,
            @RequestParam(required = false) @AiField(AiDocMeta.FIELD_TELEGRAM_USER_CHAT_ID) String chatId,
            @RequestParam(defaultValue = RouteConstants.DEFAULT_TELEGRAM_LIMIT) @AiField(AiDocMeta.FIELD_TELEGRAM_LIMIT) Integer limit,
            @RequestParam(required = false) @AiField(AiDocMeta.FIELD_TELEGRAM_OFFSET_ID) Integer offsetId,
            @RequestParam(required = false) @AiField(AiDocMeta.FIELD_TELEGRAM_MIN_ID) Integer minId,
            @RequestParam(required = false) @AiField(AiDocMeta.FIELD_TELEGRAM_MAX_ID) Integer maxId
    ) {
        return telegramRelayService.getHistory(profileId, chatId, limit, offsetId, minId, maxId);
    }

    @AiEndpoint(AiDocMeta.EP_TELEGRAM_USER_SEARCH)
    @GetMapping(RouteConstants.TELEGRAM_SEARCH)
    public RelayResponse searchMessages(
            @PathVariable @AiField(AiDocMeta.FIELD_TELEGRAM_PROFILE_ID) String profileId,
            @RequestParam(required = false) @AiField(AiDocMeta.FIELD_TELEGRAM_USER_CHAT_ID) String chatId,
            @RequestParam @AiField(AiDocMeta.FIELD_TELEGRAM_SEARCH_QUERY) String query,
            @RequestParam(defaultValue = RouteConstants.DEFAULT_TELEGRAM_LIMIT) @AiField(AiDocMeta.FIELD_TELEGRAM_LIMIT) Integer limit,
            @RequestParam(required = false) @AiField(AiDocMeta.FIELD_TELEGRAM_OFFSET_ID) Integer offsetId
    ) {
        return telegramRelayService.searchMessages(profileId, chatId, query, limit, offsetId);
    }

    @AiEndpoint(AiDocMeta.EP_TELEGRAM_USER_EDIT)
    @PostMapping(RouteConstants.TELEGRAM_EDIT)
    public RelayResponse editMessage(
            @PathVariable @AiField(AiDocMeta.FIELD_TELEGRAM_PROFILE_ID) String profileId,
            @RequestBody EditMessageRequest request
    ) {
        return telegramRelayService.editMessage(
                profileId,
                request.chatId(),
                request.messageId(),
                request.text(),
                request.parseMode()
        );
    }

    @AiEndpoint(AiDocMeta.EP_TELEGRAM_USER_DELETE)
    @PostMapping(RouteConstants.TELEGRAM_DELETE)
    public RelayResponse deleteMessages(
            @PathVariable @AiField(AiDocMeta.FIELD_TELEGRAM_PROFILE_ID) String profileId,
            @RequestBody DeleteMessagesRequest request
    ) {
        return telegramRelayService.deleteMessages(
                profileId,
                request.chatId(),
                request.messageIds(),
                request.revoke()
        );
    }

    @AiEndpoint(AiDocMeta.EP_TELEGRAM_USER_FORWARD)
    @PostMapping(RouteConstants.TELEGRAM_FORWARD)
    public RelayResponse forwardMessages(
            @PathVariable @AiField(AiDocMeta.FIELD_TELEGRAM_PROFILE_ID) String profileId,
            @RequestBody ForwardMessagesRequest request
    ) {
        return telegramRelayService.forwardMessages(
                profileId,
                request.fromChatId(),
                request.toChatId(),
                request.messageIds(),
                request.dropAuthor(),
                request.silent()
        );
    }

    @AiEndpoint(AiDocMeta.EP_TELEGRAM_USER_READ)
    @PostMapping(RouteConstants.TELEGRAM_READ)
    public RelayResponse markRead(
            @PathVariable @AiField(AiDocMeta.FIELD_TELEGRAM_PROFILE_ID) String profileId,
            @RequestBody MarkReadRequest request
    ) {
        return telegramRelayService.markRead(profileId, request.chatId(), request.maxId());
    }

    @AiEndpoint(AiDocMeta.EP_TELEGRAM_USER_RESOLVE)
    @GetMapping(RouteConstants.TELEGRAM_RESOLVE)
    public RelayResponse resolveChat(
            @PathVariable @AiField(AiDocMeta.FIELD_TELEGRAM_PROFILE_ID) String profileId,
            @RequestParam(required = false) @AiField(AiDocMeta.FIELD_TELEGRAM_USER_CHAT_ID) String chatId
    ) {
        return telegramRelayService.resolveChat(profileId, chatId);
    }
}
