package com.muse.connect.service;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.muse.connect.config.AppProperties;
import com.muse.connect.model.AuthMode;
import com.muse.connect.model.ConnectorConfig;
import com.muse.connect.model.ConnectorProfile;
import com.muse.connect.model.ConnectorType;
import com.muse.connect.model.RelayResponse;
import com.muse.connect.repository.RelayActivityBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Service
public class SshRelayService {

    private static final int DEFAULT_SSH_PORT = 22;
    private static final int MAX_OUTPUT_BYTES = 512 * 1024;

    private final ConnectorService connectorService;
    private final AppProperties properties;
    private final RelayActivityBuffer activityBuffer;

    public SshRelayService(
            ConnectorService connectorService,
            AppProperties properties,
            RelayActivityBuffer activityBuffer
    ) {
        this.connectorService = connectorService;
        this.properties = properties;
        this.activityBuffer = activityBuffer;
    }

    public RelayResponse exec(String profileId, String command, Integer timeoutMsOverride) {
        if (!StringUtils.hasText(command)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, "Command is required");
        }
        ConnectorProfile profile = connectorService.getEnabledProfile(profileId, ConnectorType.SSH);
        return executeCommand(profile, command.trim(), timeoutMsOverride, true);
    }

    public RelayResponse test(ConnectorProfile profile) {
        return executeCommand(profile, "echo muse-ssh-ok", 8000, false);
    }

    public RelayResponse sftpRead(String profileId, String remotePath, Integer maxBytes) {
        if (!StringUtils.hasText(remotePath)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, "remotePath is required");
        }
        ConnectorProfile profile = connectorService.getEnabledProfile(profileId, ConnectorType.SSH);
        long start = System.currentTimeMillis();
        int limit = (maxBytes != null && maxBytes > 0) ? Math.min(maxBytes, MAX_OUTPUT_BYTES) : 65536;

        Session session = null;
        ChannelSftp sftp = null;
        try {
            int timeoutMs = resolveTimeout(profile.getConfig(), null);
            session = openSession(profile, timeoutMs);
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(timeoutMs);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = sftp.get(remotePath.trim())) {
                byte[] buf = new byte[4096];
                int total = 0;
                int read;
                while (total < limit && (read = in.read(buf, 0, Math.min(buf.length, limit - total))) != -1) {
                    out.write(buf, 0, read);
                    total += read;
                }
            }
            long durationMs = System.currentTimeMillis() - start;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("remotePath", remotePath.trim());
            data.put("bytesRead", out.size());
            data.put("content", out.toString(StandardCharsets.UTF_8));
            activityBuffer.record(profileId, ConnectorType.SSH, "sftp:read " + remotePath.trim(), true, 200, durationMs, "bytes=" + out.size());
            return RelayResponse.ok(profileId, ConnectorType.SSH, 200, durationMs, data);
        } catch (Exception ex) {
            long durationMs = System.currentTimeMillis() - start;
            activityBuffer.record(profileId, ConnectorType.SSH, "sftp:read " + remotePath.trim(), false, 502, durationMs, ex.getMessage());
            return RelayResponse.fail(profileId, ConnectorType.SSH, 502, durationMs, "SFTP read failed: " + ex.getMessage(), null);
        } finally {
            if (sftp != null && sftp.isConnected()) {
                sftp.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    public RelayResponse sftpWrite(String profileId, String remotePath, String content, boolean append) {
        if (!StringUtils.hasText(remotePath)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, "remotePath is required");
        }
        ConnectorProfile profile = connectorService.getEnabledProfile(profileId, ConnectorType.SSH);
        long start = System.currentTimeMillis();
        byte[] bytes = (content != null ? content : "").getBytes(StandardCharsets.UTF_8);

        Session session = null;
        ChannelSftp sftp = null;
        try {
            int timeoutMs = resolveTimeout(profile.getConfig(), null);
            session = openSession(profile, timeoutMs);
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(timeoutMs);
            sftp.put(new ByteArrayInputStream(bytes), remotePath.trim(), append ? ChannelSftp.APPEND : ChannelSftp.OVERWRITE);
            long durationMs = System.currentTimeMillis() - start;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("remotePath", remotePath.trim());
            data.put("bytesWritten", bytes.length);
            data.put("append", append);
            activityBuffer.record(profileId, ConnectorType.SSH, "sftp:write " + remotePath.trim(), true, 200, durationMs, "bytes=" + bytes.length);
            return RelayResponse.ok(profileId, ConnectorType.SSH, 200, durationMs, data);
        } catch (Exception ex) {
            long durationMs = System.currentTimeMillis() - start;
            activityBuffer.record(profileId, ConnectorType.SSH, "sftp:write " + remotePath.trim(), false, 502, durationMs, ex.getMessage());
            return RelayResponse.fail(profileId, ConnectorType.SSH, 502, durationMs, "SFTP write failed: " + ex.getMessage(), null);
        } finally {
            if (sftp != null && sftp.isConnected()) {
                sftp.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    private RelayResponse executeCommand(
            ConnectorProfile profile,
            String command,
            Integer timeoutMsOverride,
            boolean enforceAllowlist
    ) {
        if (enforceAllowlist) {
            enforceCommandPolicy(profile.getConfig(), command);
        }

        long start = System.currentTimeMillis();
        int timeoutMs = resolveTimeout(profile.getConfig(), timeoutMsOverride);
        Session session = null;
        ChannelExec channel = null;

        try {
            session = openSession(profile, timeoutMs);
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            InputStream in = channel.getInputStream();
            InputStream err = channel.getErrStream();

            channel.connect(timeoutMs);

            byte[] buf = new byte[2048];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                drainStream(in, stdout, buf);
                drainStream(err, stderr, buf);
                if (channel.isClosed()) {
                    if (in.available() > 0 || err.available() > 0) {
                        continue;
                    }
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("SSH command timed out after " + timeoutMs + "ms");
                }
                Thread.sleep(25);
            }

            int exitCode = channel.getExitStatus();
            long durationMs = System.currentTimeMillis() - start;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("exitCode", exitCode);
            data.put("stdout", stdout.toString(StandardCharsets.UTF_8));
            data.put("stderr", stderr.toString(StandardCharsets.UTF_8));

            boolean ok = exitCode == 0;
            activityBuffer.record(profile.getId(), ConnectorType.SSH, "exec: " + command, ok, ok ? 200 : 500, durationMs, "exit=" + exitCode);
            return ok
                    ? RelayResponse.ok(profile.getId(), ConnectorType.SSH, 200, durationMs, data)
                    : RelayResponse.fail(profile.getId(), ConnectorType.SSH, 500, durationMs, "SSH command exited with code " + exitCode, data);
        } catch (ConnectorException ex) {
            throw ex;
        } catch (Exception ex) {
            long durationMs = System.currentTimeMillis() - start;
            activityBuffer.record(profile.getId(), ConnectorType.SSH, "exec: " + command, false, 502, durationMs, ex.getMessage());
            return RelayResponse.fail(profile.getId(), ConnectorType.SSH, 502, durationMs, "SSH exec failed: " + ex.getMessage(), null);
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    private Session openSession(ConnectorProfile profile, int timeoutMs) throws Exception {
        ConnectorConfig cfg = profile.getConfig();
        JSch jsch = new JSch();
        if (profile.getAuthMode() == AuthMode.PRIVATE_KEY) {
            if (!StringUtils.hasText(cfg.getPrivateKey())) {
                throw new ConnectorException(HttpStatus.BAD_REQUEST, "SSH privateKey is missing");
            }
            byte[] prvKey = cfg.getPrivateKey().getBytes(StandardCharsets.UTF_8);
            byte[] passphrase = StringUtils.hasText(cfg.getPassphrase())
                    ? cfg.getPassphrase().getBytes(StandardCharsets.UTF_8)
                    : null;
            jsch.addIdentity("muse-key-" + profile.getId(), prvKey, null, passphrase);
        }

        int port = (cfg.getPort() != null && cfg.getPort() > 0) ? cfg.getPort() : DEFAULT_SSH_PORT;
        Session session = jsch.getSession(cfg.getUsername(), cfg.getHost(), port);
        if (profile.getAuthMode() == AuthMode.PASSWORD) {
            session.setPassword(cfg.getPassword());
        }

        Properties sshProps = new Properties();
        boolean strict = Boolean.TRUE.equals(cfg.getStrictHostKeyChecking());
        sshProps.put("StrictHostKeyChecking", strict ? "yes" : "no");
        session.setConfig(sshProps);
        session.setTimeout(timeoutMs);
        session.connect(timeoutMs);
        return session;
    }

    void enforceCommandPolicy(ConnectorConfig cfg, String command) {
        if (!StringUtils.hasText(cfg.getAllowedCommandPrefixes())) {
            return;
        }
        if (command.contains(";") || command.contains("&&") || command.contains("||")
                || command.contains("|") || command.contains("`") || command.contains("$(")
                || command.contains("\n") || command.contains("\r")) {
            throw new ConnectorException(HttpStatus.FORBIDDEN, "Shell chaining operators are blocked when allowedCommandPrefixes is configured");
        }
        List<String> prefixes = Arrays.stream(cfg.getAllowedCommandPrefixes().split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        boolean matched = prefixes.stream().anyMatch(prefix ->
                command.equals(prefix) || command.startsWith(prefix + " ")
        );
        if (!matched) {
            throw new ConnectorException(HttpStatus.FORBIDDEN, "Command not allowed by allowedCommandPrefixes policy");
        }
    }

    private int resolveTimeout(ConnectorConfig cfg, Integer override) {
        if (override != null && override > 0) {
            return Math.min(override, 120_000);
        }
        if (cfg.getTimeoutMs() != null && cfg.getTimeoutMs() > 0) {
            return cfg.getTimeoutMs();
        }
        return properties.getDefaultTimeoutMs();
    }

    private void drainStream(InputStream in, ByteArrayOutputStream out, byte[] buf) throws Exception {
        while (in.available() > 0) {
            int n = in.read(buf, 0, buf.length);
            if (n < 0) {
                break;
            }
            if (out.size() < MAX_OUTPUT_BYTES) {
                int writable = Math.min(n, MAX_OUTPUT_BYTES - out.size());
                out.write(buf, 0, writable);
            }
        }
    }
}
