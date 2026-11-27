package com.muse.connect.controller;

import com.muse.connect.aidoc.AiApiGroup;
import com.muse.connect.aidoc.AiDocMeta;
import com.muse.connect.aidoc.AiEndpoint;
import com.muse.connect.aidoc.AiField;
import com.muse.connect.model.RelayResponse;
import com.muse.connect.service.SshRelayService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/relay/ssh/{profileId}")
@AiApiGroup(AiDocMeta.GROUP_SSH_RELAY)
public class SshRelayController {

    private final SshRelayService sshRelayService;

    public SshRelayController(SshRelayService sshRelayService) {
        this.sshRelayService = sshRelayService;
    }

    public record ExecRequest(
            @AiField(AiDocMeta.FIELD_SSH_COMMAND) String command,
            @AiField(AiDocMeta.FIELD_SSH_TIMEOUT_MS) Integer timeoutMs
    ) {
    }

    public record SftpReadRequest(
            @AiField(AiDocMeta.FIELD_SSH_REMOTE_PATH_READ) String remotePath,
            @AiField(AiDocMeta.FIELD_SSH_MAX_BYTES) Integer maxBytes
    ) {
    }

    public record SftpWriteRequest(
            @AiField(AiDocMeta.FIELD_SSH_REMOTE_PATH_WRITE) String remotePath,
            @AiField(AiDocMeta.FIELD_SSH_CONTENT) String content,
            @AiField(AiDocMeta.FIELD_SSH_APPEND) boolean append
    ) {
    }

    @AiEndpoint(AiDocMeta.EP_SSH_EXEC)
    @PostMapping("/exec")
    public RelayResponse exec(
            @PathVariable @AiField(AiDocMeta.FIELD_SSH_PROFILE_ID) String profileId,
            @RequestBody ExecRequest request
    ) {
        return sshRelayService.exec(profileId, request.command(), request.timeoutMs());
    }

    @AiEndpoint(AiDocMeta.EP_SSH_SFTP_READ)
    @PostMapping("/sftp/read")
    public RelayResponse sftpRead(
            @PathVariable @AiField(AiDocMeta.FIELD_SSH_PROFILE_ID) String profileId,
            @RequestBody SftpReadRequest request
    ) {
        return sshRelayService.sftpRead(profileId, request.remotePath(), request.maxBytes());
    }

    @AiEndpoint(AiDocMeta.EP_SSH_SFTP_WRITE)
    @PostMapping("/sftp/write")
    public RelayResponse sftpWrite(
            @PathVariable @AiField(AiDocMeta.FIELD_SSH_PROFILE_ID) String profileId,
            @RequestBody SftpWriteRequest request
    ) {
        return sshRelayService.sftpWrite(profileId, request.remotePath(), request.content(), request.append());
    }
}
