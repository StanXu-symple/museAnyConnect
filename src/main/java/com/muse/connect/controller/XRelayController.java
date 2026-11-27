package com.muse.connect.controller;

import com.muse.connect.model.RelayResponse;
import com.muse.connect.service.XRelayService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/relay/x/{profileId}")
public class XRelayController {

    private final XRelayService xRelayService;

    public XRelayController(XRelayService xRelayService) {
        this.xRelayService = xRelayService;
    }

    public record GenericXRequest(String method, String path, Map<String, String> queryParams, Object body) {
    }

    public record PostTweetRequest(String text, String replyToTweetId) {
    }

    @PostMapping("/request")
    public RelayResponse relayRequest(@PathVariable String profileId, @RequestBody GenericXRequest request) {
        return xRelayService.relay(profileId, request.method(), request.path(), request.queryParams(), request.body());
    }

    @PostMapping("/tweet")
    public RelayResponse postTweet(@PathVariable String profileId, @RequestBody PostTweetRequest request) {
        return xRelayService.postTweet(profileId, request.text(), request.replyToTweetId());
    }

    @GetMapping("/search")
    public RelayResponse search(
            @PathVariable String profileId,
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int maxResults
    ) {
        return xRelayService.searchRecent(profileId, query, maxResults);
    }

    @GetMapping("/users/by/username/{username}")
    public RelayResponse userByUsername(@PathVariable String profileId, @PathVariable String username) {
        return xRelayService.getUserByUsername(profileId, username);
    }
}
