package com.muse.connect.controller;

import com.muse.connect.aidoc.AiApiGroup;
import com.muse.connect.aidoc.AiDocMeta;
import com.muse.connect.aidoc.AiEndpoint;
import com.muse.connect.aidoc.AiField;
import com.muse.connect.model.RelayResponse;
import com.muse.connect.service.TelegramRelayService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/relay/telegram/{profileId}")
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

    @AiEndpoint(AiDocMeta.EP_TELEGRAM_SEND_MESSAGE)
    @PostMapping("/send")
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
    @GetMapping("/updates")
    public RelayResponse getUpdates(
            @PathVariable @AiField(AiDocMeta.FIELD_TELEGRAM_PROFILE_ID) String profileId,
            @RequestParam(required = false) @AiField(AiDocMeta.FIELD_TELEGRAM_OFFSET) Long offset,
            @RequestParam(defaultValue = "20") @AiField(AiDocMeta.FIELD_TELEGRAM_LIMIT) Integer limit,
            @RequestParam(defaultValue = "0") @AiField(AiDocMeta.FIELD_TELEGRAM_TIMEOUT) Integer timeout
    ) {
        return telegramRelayService.getUpdates(profileId, offset, limit, timeout);
    }

    @AiEndpoint(AiDocMeta.EP_TELEGRAM_CALL_METHOD)
    @PostMapping("/method/{method}")
    public RelayResponse callMethod(
            @PathVariable @AiField(AiDocMeta.FIELD_TELEGRAM_PROFILE_ID) String profileId,
            @PathVariable @AiField(AiDocMeta.FIELD_TELEGRAM_METHOD) String method,
            @RequestBody(required = false) @AiField(AiDocMeta.FIELD_TELEGRAM_PAYLOAD) Map<String, Object> payload
    ) {
        return telegramRelayService.callMethod(profileId, method, payload);
    }
}
