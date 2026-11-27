package com.muse.connect.controller;

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
public class TelegramRelayController {

    private final TelegramRelayService telegramRelayService;

    public TelegramRelayController(TelegramRelayService telegramRelayService) {
        this.telegramRelayService = telegramRelayService;
    }

    public record SendMessageRequest(
            String chatId,
            String text,
            String parseMode,
            Boolean disableNotification,
            Long replyToMessageId
    ) {
    }

    @PostMapping("/send")
    public RelayResponse sendMessage(@PathVariable String profileId, @RequestBody SendMessageRequest request) {
        return telegramRelayService.sendMessage(
                profileId,
                request.chatId(),
                request.text(),
                request.parseMode(),
                request.disableNotification(),
                request.replyToMessageId()
        );
    }

    @GetMapping("/updates")
    public RelayResponse getUpdates(
            @PathVariable String profileId,
            @RequestParam(required = false) Long offset,
            @RequestParam(defaultValue = "20") Integer limit,
            @RequestParam(defaultValue = "0") Integer timeout
    ) {
        return telegramRelayService.getUpdates(profileId, offset, limit, timeout);
    }

    @PostMapping("/method/{method}")
    public RelayResponse callMethod(
            @PathVariable String profileId,
            @PathVariable String method,
            @RequestBody(required = false) Map<String, Object> payload
    ) {
        return telegramRelayService.callMethod(profileId, method, payload);
    }
}
