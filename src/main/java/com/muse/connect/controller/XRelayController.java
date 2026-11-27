package com.muse.connect.controller;

import com.muse.connect.aidoc.AiApiGroup;
import com.muse.connect.aidoc.AiDocMeta;
import com.muse.connect.aidoc.AiEndpoint;
import com.muse.connect.aidoc.AiField;
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
@AiApiGroup(AiDocMeta.GROUP_X_RELAY)
public class XRelayController {

    private final XRelayService xRelayService;

    public XRelayController(XRelayService xRelayService) {
        this.xRelayService = xRelayService;
    }

    public record GenericXRequest(
            @AiField(AiDocMeta.FIELD_X_METHOD) String method,
            @AiField(AiDocMeta.FIELD_X_PATH) String path,
            @AiField(AiDocMeta.FIELD_X_QUERY_PARAMS) Map<String, String> queryParams,
            @AiField(AiDocMeta.FIELD_X_BODY) Object body
    ) {
    }

    public record PostTweetRequest(
            @AiField(AiDocMeta.FIELD_X_TWEET_TEXT) String text,
            @AiField(AiDocMeta.FIELD_X_REPLY_TO_TWEET_ID) String replyToTweetId
    ) {
    }

    @AiEndpoint(AiDocMeta.EP_X_GENERIC_REQUEST)
    @PostMapping("/request")
    public RelayResponse relayRequest(
            @PathVariable @AiField(AiDocMeta.FIELD_X_PROFILE_ID) String profileId,
            @RequestBody GenericXRequest request
    ) {
        return xRelayService.relay(profileId, request.method(), request.path(), request.queryParams(), request.body());
    }

    @AiEndpoint(AiDocMeta.EP_X_POST_TWEET)
    @PostMapping("/tweet")
    public RelayResponse postTweet(
            @PathVariable @AiField(AiDocMeta.FIELD_X_PROFILE_ID) String profileId,
            @RequestBody PostTweetRequest request
    ) {
        return xRelayService.postTweet(profileId, request.text(), request.replyToTweetId());
    }

    @AiEndpoint(AiDocMeta.EP_X_SEARCH_RECENT)
    @GetMapping("/search")
    public RelayResponse search(
            @PathVariable @AiField(AiDocMeta.FIELD_X_PROFILE_ID) String profileId,
            @RequestParam @AiField(AiDocMeta.FIELD_X_SEARCH_QUERY) String query,
            @RequestParam(defaultValue = "10") @AiField(AiDocMeta.FIELD_X_MAX_RESULTS) int maxResults
    ) {
        return xRelayService.searchRecent(profileId, query, maxResults);
    }

    @AiEndpoint(AiDocMeta.EP_X_USER_BY_USERNAME)
    @GetMapping("/users/by/username/{username}")
    public RelayResponse userByUsername(
            @PathVariable @AiField(AiDocMeta.FIELD_X_PROFILE_ID) String profileId,
            @PathVariable @AiField(AiDocMeta.FIELD_X_USERNAME) String username
    ) {
        return xRelayService.getUserByUsername(profileId, username);
    }
}
