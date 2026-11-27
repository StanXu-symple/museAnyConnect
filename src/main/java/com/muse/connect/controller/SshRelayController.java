package com.muse.connect.controller;

import com.muse.connect.model.RelayResponse;
import com.muse.connect.service.SshRelayService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/relay/ssh/{profileId}")
public class SshRelayController {

    private final SshRelayService sshRelayService;

    public SshRelayController(SshRelayService sshRelayService) {
        this.sshRelayService = sshRelayService;
    }

    public record ExecRequest(String command, Integer timeoutMs) {
    }

    public record SftpReadRequest(String remotePath, Integer maxBytes) {
    }

    public record SftpWriteRequest(String remotePath, String content, boolean append) {
    }

    @PostMapping("/exec")
    public RelayResponse exec(@PathVariable String profileId, @RequestBody ExecRequest request) {
        return sshRelayService.exec(profileId, request.command(), request.timeoutMs());
    }

    @PostMapping("/sftp/read")
    public RelayResponse sftpRead(@PathVariable String profileId, @RequestBody SftpReadRequest request) {
        return sshRelayService.sftpRead(profileId, request.remotePath(), request.maxBytes());
    }

    @PostMapping("/sftp/write")
    public RelayResponse sftpWrite(@PathVariable String profileId, @RequestBody SftpWriteRequest request) {
        return sshRelayService.sftpWrite(profileId, request.remotePath(), request.content(), request.append());
    }
}
